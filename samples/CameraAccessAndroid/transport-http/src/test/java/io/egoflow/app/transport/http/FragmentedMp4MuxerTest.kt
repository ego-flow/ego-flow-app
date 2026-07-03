package io.egoflow.app.transport.http

import io.egoflow.app.core.transport.api.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the hand-rolled fMP4 output. These don't validate the
 * elementary stream (ffprobe on a real device recording does that), but they catch
 * the box-assembly bugs that are easy to get wrong: inconsistent box sizes, broken
 * nesting, and the trun data_offset patch.
 */
class FragmentedMp4MuxerTest {

    // Minimal H.264 parameter sets (start-code-stripped). avcC reads sps[1..3].
    private val sps = byteArrayOf(0x67, 0x42.toByte(), 0x00, 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0x01.toByte(), 0x02)

    private fun annexB(nalHeader: Int, payloadLen: Int): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf(0, 0, 0, 1).map { it.toByte() }) // 4-byte start code
        out.add(nalHeader.toByte())
        repeat(payloadLen) { out.add(0x10.toByte()) }
        return out.toByteArray()
    }

    @Test
    fun `init segment and fragments have consistent box structure`() {
        val segments = ArrayList<ByteArray>()
        val muxer = FragmentedMp4Muxer(VideoCodec.H264) { segments.add(it) }

        muxer.emitInit(1280, 720, listOf(sps, pps))

        // GOP 1: IDR (type 5) + two P-slices (type 1), then GOP 2's IDR triggers flush.
        muxer.onSample(annexB(0x65, 20), ptsUs = 0, isKeyFrame = true)
        muxer.onSample(annexB(0x41, 8), ptsUs = 41_666, isKeyFrame = false)
        muxer.onSample(annexB(0x41, 8), ptsUs = 83_333, isKeyFrame = false)
        muxer.onSample(annexB(0x65, 20), ptsUs = 125_000, isKeyFrame = true) // flushes GOP 1
        muxer.finish() // flushes GOP 2

        assertEquals("init + 2 fragments", 3, segments.size)

        // Every emitted segment must be a sequence of well-formed boxes whose sizes
        // exactly consume the buffer (the strongest cheap structural invariant).
        val initBoxes = topLevelBoxes(segments[0])
        assertEquals(listOf("ftyp", "moov"), initBoxes)

        for (i in 1..2) {
            val boxes = topLevelBoxes(segments[i])
            assertEquals("fragment $i", listOf("moof", "mdat"), boxes)
            assertTrunDataOffset(segments[i])
        }
    }

    @Test
    fun `start codes split and in-band parameter sets are stripped from samples`() {
        val segments = ArrayList<ByteArray>()
        val muxer = FragmentedMp4Muxer(VideoCodec.H264) { segments.add(it) }
        muxer.emitInit(640, 480, listOf(sps, pps))

        // A keyframe access unit carrying in-band SPS+PPS+IDR (encoders sometimes do).
        // SPS/PPS must be stripped (they live in avcC); only the IDR slice survives.
        val au = ArrayList<Byte>().apply {
            addAll(listOf(0, 0, 0, 1).map { it.toByte() }); add(0x67); addAll(listOf(0x42, 0, 0x1F).map { it.toByte() })
            addAll(listOf(0, 0, 1).map { it.toByte() }); add(0x68); add(0x01) // 3-byte start code
            addAll(listOf(0, 0, 0, 1).map { it.toByte() }); add(0x65); repeat(16) { add(0x10) }
        }.toByteArray()
        muxer.onSample(au, ptsUs = 0, isKeyFrame = true)
        muxer.finish()

        assertEquals(2, segments.size)
        // mdat payload = one length-prefixed NAL: 4-byte length + 17-byte IDR (header+16).
        // SPS/PPS are stripped (they live in avcC). mdat size = 8 + 4 + 17 = 29.
        val mdatBox = topLevelBoxesWithSizes(segments[1]).first { it.first == "mdat" }
        assertEquals(29, mdatBox.second)
    }

    // ---- tiny ISO box parser ----

    private fun topLevelBoxes(buf: ByteArray): List<String> = topLevelBoxesWithSizes(buf).map { it.first }

    private fun topLevelBoxesWithSizes(buf: ByteArray): List<Pair<String, Int>> {
        val boxes = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < buf.size) {
            assertTrue("box header overruns buffer", i + 8 <= buf.size)
            val size = readU32(buf, i)
            val type = String(buf, i + 4, 4, Charsets.US_ASCII)
            assertTrue("box '$type' size $size overruns buffer at $i", size in 8..(buf.size - i))
            boxes.add(type to size)
            i += size
        }
        assertEquals("boxes must consume the whole buffer", buf.size, i)
        return boxes
    }

    // Verify trun.data_offset points exactly at the first mdat payload byte (relative
    // to the moof start), i.e. moofSize + 8.
    private fun assertTrunDataOffset(fragment: ByteArray) {
        val moofSize = readU32(fragment, 0)
        val trunStart = findBox(fragment, 0, moofSize, "trun")
        val dataOffset = readU32(fragment, trunStart + 16) // hdr(8) + version/flags(4) + sample_count(4)
        assertEquals(moofSize + 8, dataOffset)
    }

    // Depth-first search for a box type within [start, start+limit).
    private fun findBox(buf: ByteArray, start: Int, limit: Int, type: String): Int {
        var i = start + 8 // descend into the container's payload
        val end = start + limit
        while (i < end) {
            val size = readU32(buf, i)
            val t = String(buf, i + 4, 4, Charsets.US_ASCII)
            if (t == type) return i
            // recurse into known container boxes
            if (t in setOf("moof", "traf")) {
                val found = runCatching { findBox(buf, i, size, type) }.getOrNull()
                if (found != null && found >= 0) return found
            }
            i += size
        }
        return -1
    }

    private fun readU32(buf: ByteArray, i: Int): Int =
        ((buf[i].toInt() and 0xFF) shl 24) or
            ((buf[i + 1].toInt() and 0xFF) shl 16) or
            ((buf[i + 2].toInt() and 0xFF) shl 8) or
            (buf[i + 3].toInt() and 0xFF)
}
