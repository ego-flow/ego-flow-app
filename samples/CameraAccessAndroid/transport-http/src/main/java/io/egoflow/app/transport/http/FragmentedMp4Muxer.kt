/*
 * FragmentedMp4Muxer -- hand-rolled fragmented-MP4 (fMP4 / CMAF-ish) muxer for
 * the HTTP live-upload path.
 *
 * Unlike Android's MediaMuxer (which writes `moov` at the END and back-patches the
 * `mdat` size on stop -- i.e. NOT append-only), this emits an append-only byte
 * stream so chunks can be shipped to /chunks DURING capture:
 *
 *   [ftyp + moov(init: trak, avcC/hvcC, mvex/trex)]   <- emitted once, on first format
 *   [moof + mdat]   <- one self-contained media fragment per GOP, then just appended
 *   [moof + mdat]
 *   ...
 *
 * Concatenating (init + every fragment) is a valid fMP4 file at every fragment
 * boundary, so the server's finalize ffprobe accepts it.
 *
 * Input is :core VideoEncoder output: one FormatChanged (carries SPS/PPS via csd),
 * then Annex-B samples. We convert Annex-B start-code framing to the 4-byte
 * length-prefixed (AVCC/HVCC) framing fMP4 requires, and strip in-band parameter
 * sets (they live in the avcC/hvcC config record, per the avc1/hvc1 sample entry).
 *
 * No-B-frame assumption: VideoEncoder requests baseline H.264 / max-b-frames=0, so
 * decode order == presentation order (DTS == PTS, composition offset 0). That keeps
 * trun simple -- we never see PTS reordering and don't need a DTS reconstruction.
 */
package io.egoflow.app.transport.http

import android.media.MediaFormat
import io.egoflow.app.core.transport.api.VideoCodec
import java.io.ByteArrayOutputStream

/**
 * @param codec which elementary stream the encoder is producing (selects avc1/avcC
 *   vs hvc1/hvcC).
 * @param onSegment receives the init segment (first call) and each media fragment.
 *   Called on the caller's thread (the recorder's single codec thread).
 */
class FragmentedMp4Muxer(
    private val codec: VideoCodec,
    private val onSegment: (ByteArray) -> Unit,
) {
    private companion object {
        // PTS from the encoder are microseconds; use that directly as the media
        // timescale so durations/decode-times are exact (no rounding).
        const val TIMESCALE = 1_000_000L
        const val NOMINAL_FRAME_US = TIMESCALE / 24 // fallback duration for a fragment's last sample
    }

    // One buffered sample awaiting flush: AVCC-framed payload + its PTS + sync flag.
    private class Sample(val data: ByteArray, val ptsUs: Long, val sync: Boolean)

    private var initWritten = false
    private var width = 0
    private var height = 0
    private val pending = ArrayList<Sample>()
    private var sawFirstSync = false
    private var fragmentSeq = 1L

    /** Build + emit the init segment from the encoder's output format (carries csd
     *  = SPS/PPS[/VPS]). Idempotent: only the first call emits. */
    fun onFormat(format: MediaFormat) {
        if (initWritten) return
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        val csd = ArrayList<ByteArray>()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val buf = if (format.containsKey(key)) format.getByteBuffer(key) else null
            if (buf != null) {
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                splitAnnexB(bytes).forEach { csd.add(it) }
            }
        }
        emitInit(w, h, csd)
    }

    /** Build + emit the init segment from raw (start-code-stripped) parameter-set NAL
     *  units. Split out from [onFormat] so it's testable without an Android MediaFormat. */
    internal fun emitInit(w: Int, h: Int, csdNals: List<ByteArray>) {
        if (initWritten) return
        width = w
        height = h
        onSegment(buildInitSegment(classifyParameterSets(csdNals)))
        initWritten = true
    }

    /** Buffer one encoded access unit ([data] is Annex-B framed, [ptsUs] its PTS,
     *  [isKeyFrame] whether it's a sync sample). Emits the *previous* GOP as a fragment
     *  when a new keyframe arrives (so each sample's duration is known from the next PTS). */
    fun onSample(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean) {
        if (!initWritten) return
        if (!sawFirstSync) {
            if (!isKeyFrame) return // skip anything before the first IDR
            sawFirstSync = true
        }
        val avcc = annexBToLengthPrefixed(data) ?: return
        if (isKeyFrame && pending.isNotEmpty()) {
            // GOP boundary: the new keyframe's PTS closes the last buffered sample.
            flush(boundaryPtsUs = ptsUs)
        }
        pending.add(Sample(avcc, ptsUs, isKeyFrame))
    }

    /** Flush the final buffered GOP. Call once after EOS. */
    fun finish() {
        if (pending.isEmpty()) return
        val last = pending.last().ptsUs
        flush(boundaryPtsUs = last + NOMINAL_FRAME_US)
    }

    // ---- fragment assembly ----

    private fun flush(boundaryPtsUs: Long) {
        if (pending.isEmpty()) return
        val samples = pending.toList()
        pending.clear()
        val n = samples.size
        val durations = LongArray(n) { i ->
            val next = if (i < n - 1) samples[i + 1].ptsUs else boundaryPtsUs
            (next - samples[i].ptsUs).coerceAtLeast(1L)
        }
        val baseDecodeTime = samples[0].ptsUs.coerceAtLeast(0L)
        val moof = buildMoof(samples, durations, baseDecodeTime, fragmentSeq)
        val mdat = buildMdat(samples)
        fragmentSeq += 1
        onSegment(moof + mdat)
    }

    private fun buildMdat(samples: List<Sample>): ByteArray {
        val body = ByteArrayOutputStream()
        for (s in samples) body.bytes(s.data)
        return boxOf("mdat", body.toByteArray())
    }

    private fun buildMoof(
        samples: List<Sample>,
        durations: LongArray,
        baseDecodeTime: Long,
        seq: Long,
    ): ByteArray {
        val mfhd = boxOf("mfhd", ByteArrayOutputStream().apply {
            u32(0)            // version + flags
            u32(seq)          // sequence_number
        }.toByteArray())

        // tfhd: default-base-is-moof (0x020000) + default-sample-flags-present (0x000008).
        // default flags mark non-keyframe samples as non-sync; the keyframe (always the
        // first sample of a fragment) overrides via trun first_sample_flags.
        val tfhd = boxOf("tfhd", ByteArrayOutputStream().apply {
            u8(0); u24(0x020008)          // version + flags
            u32(1)                        // track_ID
            u32(SAMPLE_FLAGS_NON_SYNC)    // default_sample_flags
        }.toByteArray())

        val tfdt = boxOf("tfdt", ByteArrayOutputStream().apply {
            u8(1); u24(0)                 // version 1 (64-bit) + flags
            u64(baseDecodeTime)           // baseMediaDecodeTime
        }.toByteArray())

        // trun flags: data-offset(0x1) + first-sample-flags(0x4) + sample-duration(0x100) + sample-size(0x200)
        val trunBody = ByteArrayOutputStream().apply {
            u8(0); u24(0x000305)
            u32(samples.size)             // sample_count
            u32(0)                        // data_offset (patched below)
            u32(SAMPLE_FLAGS_SYNC)        // first_sample_flags (keyframe)
            for (i in samples.indices) {
                u32(durations[i])         // sample_duration
                u32(samples[i].data.size.toLong()) // sample_size
            }
        }.toByteArray()
        val trun = boxOf("trun", trunBody)

        val traf = boxOf("traf", tfhd + tfdt + trun)
        val moof = boxOf("moof", mfhd + traf)

        // data_offset points at the first mdat payload byte, relative to the moof start
        // (default-base-is-moof). mdat data begins at moof.size + 8 (mdat box header).
        // Locate the data_offset field: moof hdr(8) + mfhd + traf hdr(8) + tfhd + tfdt +
        // trun box hdr(8) + version/flags(4) + sample_count(4).
        val dataOffsetIndex = 8 + mfhd.size + 8 + tfhd.size + tfdt.size + 8 + 8
        writeU32(moof, dataOffsetIndex, (moof.size + 8).toLong())
        return moof
    }

    // ---- init segment (ftyp + moov) ----

    private fun buildInitSegment(paramSets: ParameterSets): ByteArray {
        val ftyp = boxOf("ftyp", ByteArrayOutputStream().apply {
            str("isom"); u32(0x200)
            str("isom"); str("iso6"); str("mp41")
        }.toByteArray())

        val mvhd = boxOf("mvhd", ByteArrayOutputStream().apply {
            u8(0); u24(0)                 // version + flags
            u32(0); u32(0)                // creation / modification time
            u32(TIMESCALE)                // timescale
            u32(0)                        // duration (0 = unknown for fragmented)
            u32(0x00010000)               // rate 1.0
            u16(0x0100); u16(0)           // volume 1.0 + reserved
            u32(0); u32(0)                // reserved
            identityMatrix(this)
            repeat(6) { u32(0) }          // pre_defined
            u32(2)                        // next_track_ID
        }.toByteArray())

        val tkhd = boxOf("tkhd", ByteArrayOutputStream().apply {
            u8(0); u24(0x000007)          // flags: enabled | in movie | in preview
            u32(0); u32(0)                // creation / modification
            u32(1)                        // track_ID
            u32(0)                        // reserved
            u32(0)                        // duration
            u32(0); u32(0)                // reserved[2]
            u16(0); u16(0)                // layer + alternate_group
            u16(0); u16(0)                // volume (0 for video) + reserved
            identityMatrix(this)
            u32(width shl 16)             // width 16.16
            u32(height shl 16)            // height 16.16
        }.toByteArray())

        val mdhd = boxOf("mdhd", ByteArrayOutputStream().apply {
            u8(0); u24(0)
            u32(0); u32(0)
            u32(TIMESCALE)
            u32(0)                        // duration
            u16(0x55C4)                   // language 'und'
            u16(0)                        // pre_defined
        }.toByteArray())

        val hdlr = boxOf("hdlr", ByteArrayOutputStream().apply {
            u8(0); u24(0)
            u32(0)                        // pre_defined
            str("vide")                   // handler_type
            u32(0); u32(0); u32(0)        // reserved[3]
            str("VideoHandler"); u8(0)    // name (null-terminated)
        }.toByteArray())

        val vmhd = boxOf("vmhd", ByteArrayOutputStream().apply {
            u8(0); u24(1)                 // flags = 1
            u16(0); u16(0); u16(0); u16(0) // graphicsmode + opcolor[3]
        }.toByteArray())

        val dref = boxOf("dref", ByteArrayOutputStream().apply {
            u8(0); u24(0)
            u32(1)                        // entry_count
            bytes(boxOf("url ", ByteArrayOutputStream().apply { u8(0); u24(1) }.toByteArray()))
        }.toByteArray())
        val dinf = boxOf("dinf", dref)

        val stsd = boxOf("stsd", ByteArrayOutputStream().apply {
            u8(0); u24(0)
            u32(1)                        // entry_count
            bytes(buildSampleEntry(paramSets))
        }.toByteArray())

        val emptyFull = { type: String -> boxOf(type, ByteArrayOutputStream().apply { u8(0); u24(0); u32(0) }.toByteArray()) }
        val stts = emptyFull("stts")
        val stsc = emptyFull("stsc")
        val stco = emptyFull("stco")
        val stsz = boxOf("stsz", ByteArrayOutputStream().apply { u8(0); u24(0); u32(0); u32(0) }.toByteArray())

        val stbl = boxOf("stbl", stsd + stts + stsc + stsz + stco)
        val minf = boxOf("minf", vmhd + dinf + stbl)
        val mdia = boxOf("mdia", mdhd + hdlr + minf)
        val trak = boxOf("trak", tkhd + mdia)

        val trex = boxOf("trex", ByteArrayOutputStream().apply {
            u8(0); u24(0)
            u32(1)                        // track_ID
            u32(1)                        // default_sample_description_index
            u32(0)                        // default_sample_duration
            u32(0)                        // default_sample_size
            u32(0)                        // default_sample_flags
        }.toByteArray())
        val mvex = boxOf("mvex", trex)

        val moov = boxOf("moov", mvhd + trak + mvex)
        return ftyp + moov
    }

    private fun buildSampleEntry(paramSets: ParameterSets): ByteArray {
        val cfg = when (codec) {
            VideoCodec.H264 -> boxOf("avcC", buildAvcC(paramSets))
            VideoCodec.HEVC -> boxOf("hvcC", buildHvcC(paramSets))
        }
        val type = if (codec == VideoCodec.H264) "avc1" else "hvc1"
        val body = ByteArrayOutputStream().apply {
            repeat(6) { u8(0) }           // reserved
            u16(1)                        // data_reference_index
            u16(0); u16(0)                // pre_defined + reserved
            repeat(3) { u32(0) }          // pre_defined[3]
            u16(width); u16(height)
            u32(0x00480000)               // horizresolution 72dpi
            u32(0x00480000)               // vertresolution 72dpi
            u32(0)                        // reserved
            u16(1)                        // frame_count
            repeat(32) { u8(0) }          // compressorname
            u16(0x0018)                   // depth
            u16(0xFFFF)                   // pre_defined
            bytes(cfg)
        }.toByteArray()
        return boxOf(type, body)
    }

    // AVCDecoderConfigurationRecord (ISO/IEC 14496-15)
    private fun buildAvcC(p: ParameterSets): ByteArray {
        val sps = p.sps.first()
        return ByteArrayOutputStream().apply {
            u8(1)                         // configurationVersion
            u8(sps[1].toInt() and 0xFF)   // AVCProfileIndication
            u8(sps[2].toInt() and 0xFF)   // profile_compatibility
            u8(sps[3].toInt() and 0xFF)   // AVCLevelIndication
            u8(0xFF)                      // 6 bits reserved + lengthSizeMinusOne = 3
            u8(0xE0 or p.sps.size)        // 3 bits reserved + numSPS
            for (s in p.sps) { u16(s.size); bytes(s) }
            u8(p.pps.size)                // numPPS
            for (s in p.pps) { u16(s.size); bytes(s) }
        }.toByteArray()
    }

    // HEVCDecoderConfigurationRecord (ISO/IEC 14496-15). general_* fields are read
    // from the SPS profile_tier_level (12 bytes after the 1-byte sps header fields);
    // chroma/bit-depth are fixed to 4:2:0 8-bit (what the encoder emits).
    private fun buildHvcC(p: ParameterSets): ByteArray {
        val spsRbsp = removeEmulationPrevention(p.sps.first())
        // NAL header (2) + sps_vps_id/max_sub_layers/nesting (1) -> PTL starts at index 3.
        val ptl = spsRbsp.copyOfRange(3, 3 + 12)
        val maxSubLayersMinus1 = (spsRbsp[2].toInt() ushr 1) and 0x07
        return ByteArrayOutputStream().apply {
            u8(1)                         // configurationVersion
            u8(ptl[0].toInt() and 0xFF)   // general_profile_space/tier/profile_idc
            bytes(ptl.copyOfRange(1, 5))  // general_profile_compatibility_flags
            bytes(ptl.copyOfRange(5, 11)) // general_constraint_indicator_flags (48 bits)
            u8(ptl[11].toInt() and 0xFF)  // general_level_idc
            u16(0xF000)                   // reserved(4=1) + min_spatial_segmentation_idc
            u8(0xFC)                      // reserved(6=1) + parallelismType
            u8(0xFC or 1)                 // reserved(6=1) + chromaFormat (1 = 4:2:0)
            u8(0xF8)                      // reserved(5=1) + bitDepthLumaMinus8 (0)
            u8(0xF8)                      // reserved(5=1) + bitDepthChromaMinus8 (0)
            u16(0)                        // avgFrameRate
            // constantFrameRate(2)=0 | numTemporalLayers(3) | temporalIdNested(1)=0 | lengthSizeMinusOne(2)=3
            u8(((maxSubLayersMinus1 + 1) shl 3) or 0x03)
            // arrays: VPS, SPS, PPS (only those present)
            val arrays = buildList {
                if (p.vps.isNotEmpty()) add(32 to p.vps)
                if (p.sps.isNotEmpty()) add(33 to p.sps)
                if (p.pps.isNotEmpty()) add(34 to p.pps)
            }
            u8(arrays.size)               // numOfArrays
            for ((nalType, units) in arrays) {
                u8(0x80 or nalType)       // array_completeness(1) + reserved(1) + NAL_unit_type
                u16(units.size)           // numNalus
                for (nal in units) { u16(nal.size); bytes(nal) }
            }
        }.toByteArray()
    }

    // ---- NAL helpers ----

    private class ParameterSets(
        val vps: List<ByteArray>,
        val sps: List<ByteArray>,
        val pps: List<ByteArray>,
    )

    // Classify start-code-stripped csd NAL units into SPS/PPS[/VPS].
    private fun classifyParameterSets(nals: List<ByteArray>): ParameterSets {
        val vps = ArrayList<ByteArray>()
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        for (nal in nals) when (nalType(nal)) {
            NalKind.VPS -> vps.add(nal)
            NalKind.SPS -> sps.add(nal)
            NalKind.PPS -> pps.add(nal)
            else -> {}
        }
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "csd missing SPS/PPS for $codec" }
        return ParameterSets(vps, sps, pps)
    }

    // Convert an Annex-B access unit to 4-byte length-prefixed NALs, dropping
    // parameter sets (in avcC/hvcC) and access-unit delimiters. Returns null if
    // nothing usable remains.
    private fun annexBToLengthPrefixed(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        for (nal in splitAnnexB(data)) {
            when (nalType(nal)) {
                NalKind.VPS, NalKind.SPS, NalKind.PPS, NalKind.AUD -> continue
                else -> {
                    out.u32(nal.size.toLong())
                    out.bytes(nal)
                }
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private enum class NalKind { VPS, SPS, PPS, AUD, OTHER }

    private fun nalType(nal: ByteArray): NalKind {
        if (nal.isEmpty()) return NalKind.OTHER
        return when (codec) {
            VideoCodec.H264 -> when (nal[0].toInt() and 0x1F) {
                7 -> NalKind.SPS
                8 -> NalKind.PPS
                9 -> NalKind.AUD
                else -> NalKind.OTHER
            }
            VideoCodec.HEVC -> when ((nal[0].toInt() ushr 1) and 0x3F) {
                32 -> NalKind.VPS
                33 -> NalKind.SPS
                34 -> NalKind.PPS
                35 -> NalKind.AUD
                else -> NalKind.OTHER
            }
        }
    }

    // Split an Annex-B buffer into NAL units (without start codes). Handles both
    // 3- and 4-byte start codes.
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var i = 0
        val n = data.size
        while (i + 3 <= n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        for (s in starts.indices) {
            val payloadStart = starts[s]
            // Next start code begins at most 4 bytes before the next recorded start.
            var end = if (s + 1 < starts.size) starts[s + 1] - 3 else n
            // Trim a leading extra 0x00 (4-byte start code) that the 3-byte scan left in.
            if (end > payloadStart && end - 1 >= payloadStart && s + 1 < starts.size &&
                data[end - 1].toInt() == 0
            ) {
                end -= 1
            }
            if (end > payloadStart) result.add(data.copyOfRange(payloadStart, end))
        }
        return result
    }

    // Strip H.265/H.264 emulation-prevention bytes (00 00 03 -> 00 00) so raw fields
    // (e.g. the HEVC profile_tier_level) can be read positionally.
    private fun removeEmulationPrevention(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        var i = 0
        while (i < nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03 && i + 1 < nal.size && (nal[i + 1].toInt() and 0xFF) <= 0x03) {
                zeros = 0 // drop the emulation_prevention_three_byte
            } else {
                out.write(b)
                zeros = if (b == 0x00) zeros + 1 else 0
            }
            i++
        }
        return out.toByteArray()
    }
}

// ---- box / big-endian writing primitives ----

private const val SAMPLE_FLAGS_SYNC = 0x02000000L     // sample_depends_on=2, is_non_sync=0
private const val SAMPLE_FLAGS_NON_SYNC = 0x01010000L // sample_depends_on=1, is_non_sync=1

private fun boxOf(type: String, body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(8 + body.size)
    out.u32((8 + body.size).toLong())
    out.str(type)
    out.bytes(body)
    return out.toByteArray()
}

private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
private fun ByteArrayOutputStream.u16(v: Int) { write((v ushr 8) and 0xFF); write(v and 0xFF) }
private fun ByteArrayOutputStream.u24(v: Int) { write((v ushr 16) and 0xFF); write((v ushr 8) and 0xFF); write(v and 0xFF) }
private fun ByteArrayOutputStream.u32(v: Long) {
    write(((v ushr 24) and 0xFF).toInt()); write(((v ushr 16) and 0xFF).toInt())
    write(((v ushr 8) and 0xFF).toInt()); write((v and 0xFF).toInt())
}
private fun ByteArrayOutputStream.u32(v: Int) = u32(v.toLong())
private fun ByteArrayOutputStream.u64(v: Long) { u32((v ushr 32) and 0xFFFFFFFFL); u32(v and 0xFFFFFFFFL) }
private fun ByteArrayOutputStream.str(s: String) = write(s.toByteArray(Charsets.US_ASCII))
private fun ByteArrayOutputStream.bytes(b: ByteArray) = write(b)

private fun identityMatrix(out: ByteArrayOutputStream) {
    // 3x3 transformation matrix, identity (16.16 fixed for a,b,c,d; 2.30 for u,v,w)
    intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { out.u32(it.toLong() and 0xFFFFFFFFL) }
}

private fun writeU32(buf: ByteArray, index: Int, v: Long) {
    buf[index] = ((v ushr 24) and 0xFF).toByte()
    buf[index + 1] = ((v ushr 16) and 0xFF).toByte()
    buf[index + 2] = ((v ushr 8) and 0xFF).toByte()
    buf[index + 3] = (v and 0xFF).toByte()
}
