package io.egoflow.app.stream.rtmp

import android.media.MediaFormat

internal data class RtmpVideoCodecConfig(
    val sequenceHeader: ByteArray,
    val inBandParameterSets: List<ByteArray> = emptyList(),
)

internal object RtmpVideoPacketizer {
    private const val VIDEO_FRAME_TYPE_KEYFRAME = 1
    private const val VIDEO_FRAME_TYPE_INTERFRAME = 2
    private const val ENHANCED_PACKET_TYPE_SEQUENCE_START = 0
    private const val ENHANCED_PACKET_TYPE_CODED_FRAMES = 1
    private const val ENHANCED_PACKET_TYPE_CODED_FRAMES_X = 3
    private const val AVC_NAL_SPS = 7
    private const val AVC_NAL_PPS = 8
    internal const val HEVC_NAL_VPS = 32
    internal const val HEVC_NAL_SPS = 33
    internal const val HEVC_NAL_PPS = 34
    private const val HEVC_NAL_AUD = 35
    private const val HEVC_NAL_IDR_W_RADL = 19
    private const val HEVC_NAL_IDR_N_LP = 20

    fun buildCodecConfig(videoCodec: RtmpVideoCodec, format: MediaFormat): RtmpVideoCodecConfig {
        return when (videoCodec) {
            RtmpVideoCodec.H264 -> {
                val csd0 = format.getByteBuffer("csd-0") ?: error("AVC csd-0 is missing")
                val csd0Copy = csd0.duplicate()
                val csd0Bytes = ByteArray(csd0Copy.remaining()).also { csd0Copy.get(it) }
                val csd1Bytes = format.getByteBuffer("csd-1")?.let { buffer ->
                    val copy = buffer.duplicate()
                    ByteArray(copy.remaining()).also { copy.get(it) }
                } ?: ByteArray(0)
                // csd-0/csd-1 are in Annex-B (start-code-prefixed) format, so extract NAL units before using them
                val nalUnits = extractNalUnits(csd0Bytes) + extractNalUnits(csd1Bytes)
                val sps = nalUnits.firstOrNull { avcNalUnitType(it) == AVC_NAL_SPS }
                    ?: error("AVC SPS NAL unit is missing from csd buffers")
                val pps = nalUnits.firstOrNull { avcNalUnitType(it) == AVC_NAL_PPS }
                    ?: error("AVC PPS NAL unit is missing from csd buffers")
                RtmpVideoCodecConfig(buildAvcSequenceHeader(sps = sps, pps = pps))
            }

            RtmpVideoCodec.H265 -> {
                val csd = format.getByteBuffer("csd-0") ?: error("HEVC csd-0 is missing")
                val csdCopy = csd.duplicate()
                val nalUnits = extractNalUnits(ByteArray(csdCopy.remaining()).also { csdCopy.get(it) })
                val vpsUnits = nalUnits.filter { hevcNalUnitType(it) == HEVC_NAL_VPS }
                val spsUnits = nalUnits.filter { hevcNalUnitType(it) == HEVC_NAL_SPS }
                val ppsUnits = nalUnits.filter { hevcNalUnitType(it) == HEVC_NAL_PPS }
                require(vpsUnits.isNotEmpty()) { "HEVC VPS is missing from csd-0" }
                require(spsUnits.isNotEmpty()) { "HEVC SPS is missing from csd-0" }
                require(ppsUnits.isNotEmpty()) { "HEVC PPS is missing from csd-0" }
                RtmpVideoCodecConfig(
                    sequenceHeader = buildHevcSequenceHeader(vpsUnits, spsUnits, ppsUnits),
                    // gortmplib discovers the initial H.265 track from SequenceStart, but its
                    // runtime H.265 callback ignores later SequenceStart messages. Repeating new
                    // parameters once in the following IDR AU lets MediaMTX update the same track.
                    inBandParameterSets = vpsUnits + spsUnits + ppsUnits,
                )
            }
        }
    }

    fun buildVideoPacket(
        videoCodec: RtmpVideoCodec,
        annexBData: ByteArray,
        isKeyFrame: Boolean,
        compositionTimeMs: Int = 0,
        prependedNalUnits: List<ByteArray> = emptyList(),
    ): ByteArray {
        return when (videoCodec) {
            RtmpVideoCodec.H264 -> buildAvcVideoPacket(annexBData, isKeyFrame, compositionTimeMs)
            RtmpVideoCodec.H265 ->
                buildHevcVideoPacket(
                    annexBData,
                    isKeyFrame,
                    compositionTimeMs,
                    prependedNalUnits,
                )
        }
    }

    internal fun buildAvcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "AVC SPS NAL unit is too short" }
        require(pps.isNotEmpty()) { "AVC PPS NAL unit is empty" }
        val payload = ByteArray(16 + sps.size + pps.size)
        var index = 0
        payload[index++] = 0x17
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x01
        payload[index++] = sps[1]
        payload[index++] = sps[2]
        payload[index++] = sps[3]
        payload[index++] = 0xff.toByte()
        payload[index++] = 0xe1.toByte()
        writeUInt16(payload, index, sps.size)
        index += 2
        System.arraycopy(sps, 0, payload, index, sps.size)
        index += sps.size
        payload[index++] = 0x01
        writeUInt16(payload, index, pps.size)
        index += 2
        System.arraycopy(pps, 0, payload, index, pps.size)
        return payload
    }

    private fun buildAvcVideoPacket(
        annexBData: ByteArray,
        isKeyFrame: Boolean,
        compositionTimeMs: Int,
    ): ByteArray {
        val nalUnits = extractNalUnits(annexBData)
        val payloadSize = 5 + nalUnits.sumOf { 4 + it.size }
        return ByteArray(payloadSize).apply {
            var index = 0
            this[index++] = if (isKeyFrame) 0x17 else 0x27
            this[index++] = 0x01
            this[index++] = ((compositionTimeMs shr 16) and 0xff).toByte()
            this[index++] = ((compositionTimeMs shr 8) and 0xff).toByte()
            this[index++] = (compositionTimeMs and 0xff).toByte()
            nalUnits.forEach { nalUnit ->
                writeInt32(this, index, nalUnit.size)
                index += 4
                System.arraycopy(nalUnit, 0, this, index, nalUnit.size)
                index += nalUnit.size
            }
        }
    }

    private fun buildHevcSequenceHeader(
        vpsUnits: List<ByteArray>,
        spsUnits: List<ByteArray>,
        ppsUnits: List<ByteArray>,
    ): ByteArray {
        val configurationRecord = buildHevcDecoderConfigurationRecord(vpsUnits, spsUnits, ppsUnits)
        return buildEnhancedVideoPacket(
            frameType = VIDEO_FRAME_TYPE_KEYFRAME,
            packetType = ENHANCED_PACKET_TYPE_SEQUENCE_START,
            fourCc = RtmpVideoCodec.H265.fourCc,
            payload = configurationRecord,
        )
    }

    private fun buildHevcVideoPacket(
        annexBData: ByteArray,
        isKeyFrame: Boolean,
        compositionTimeMs: Int,
        prependedNalUnits: List<ByteArray>,
    ): ByteArray {
        require(prependedNalUnits.isEmpty() || isKeyFrame) {
            "HEVC parameter sets can only be prepended to a key frame"
        }
        val sourceNalUnits = extractNalUnits(annexBData)
        val nalUnits =
            if (prependedNalUnits.isEmpty()) {
                sourceNalUnits
            } else {
                val replacedTypes = prependedNalUnits.map(::hevcNalUnitType).toSet()
                val sourceWithoutReplacedParameters =
                    sourceNalUnits.filterNot { hevcNalUnitType(it) in replacedTypes }
                val insertionIndex =
                    sourceWithoutReplacedParameters.indexOfFirst {
                        hevcNalUnitType(it) != HEVC_NAL_AUD
                    }.let { if (it < 0) sourceWithoutReplacedParameters.size else it }
                sourceWithoutReplacedParameters.take(insertionIndex) +
                    prependedNalUnits +
                    sourceWithoutReplacedParameters.drop(insertionIndex)
            }
        val packetType = if (compositionTimeMs == 0) ENHANCED_PACKET_TYPE_CODED_FRAMES_X else ENHANCED_PACKET_TYPE_CODED_FRAMES
        val headerSize = 5 + if (packetType == ENHANCED_PACKET_TYPE_CODED_FRAMES) 3 else 0
        val payload = ByteArray(headerSize + nalUnits.sumOf { 4 + it.size })
        var index = 0
        payload[index++] = buildEnhancedVideoHeaderByte(
            frameType = if (isKeyFrame) VIDEO_FRAME_TYPE_KEYFRAME else VIDEO_FRAME_TYPE_INTERFRAME,
            packetType = packetType,
        )
        writeFourCc(payload, index, RtmpVideoCodec.H265.fourCc)
        index += 4
        if (packetType == ENHANCED_PACKET_TYPE_CODED_FRAMES) {
            payload[index++] = ((compositionTimeMs shr 16) and 0xff).toByte()
            payload[index++] = ((compositionTimeMs shr 8) and 0xff).toByte()
            payload[index++] = (compositionTimeMs and 0xff).toByte()
        }
        nalUnits.forEach { nalUnit ->
            writeInt32(payload, index, nalUnit.size)
            index += 4
            System.arraycopy(nalUnit, 0, payload, index, nalUnit.size)
            index += nalUnit.size
        }
        return payload
    }

    private fun buildEnhancedVideoPacket(
        frameType: Int,
        packetType: Int,
        fourCc: String,
        payload: ByteArray,
    ): ByteArray {
        return ByteArray(5 + payload.size).apply {
            this[0] = buildEnhancedVideoHeaderByte(frameType, packetType)
            writeFourCc(this, 1, fourCc)
            System.arraycopy(payload, 0, this, 5, payload.size)
        }
    }

    private fun buildEnhancedVideoHeaderByte(frameType: Int, packetType: Int): Byte {
        return (0x80 or ((frameType and 0x07) shl 4) or (packetType and 0x0f)).toByte()
    }

    private fun buildHevcDecoderConfigurationRecord(
        vpsUnits: List<ByteArray>,
        spsUnits: List<ByteArray>,
        ppsUnits: List<ByteArray>,
    ): ByteArray {
        val spsProfile = parseHevcSps(spsUnits.first())
        val arrays =
            listOf(
                HEVC_NAL_VPS to vpsUnits,
                HEVC_NAL_SPS to spsUnits,
                HEVC_NAL_PPS to ppsUnits,
            )
        val size =
            23 + arrays.sumOf { (_, nalUnits) ->
                3 + nalUnits.sumOf { nalUnit -> 2 + nalUnit.size }
            }
        return ByteArray(size).apply {
            var index = 0
            this[index++] = 0x01
            this[index++] =
                (
                    ((spsProfile.generalProfileSpace and 0x03) shl 6) or
                        ((if (spsProfile.generalTierFlag) 1 else 0) shl 5) or
                        (spsProfile.generalProfileIdc and 0x1f)
                ).toByte()
            writeInt32(this, index, spsProfile.generalProfileCompatibilityFlags)
            index += 4
            for (shift in 40 downTo 0 step 8) {
                this[index++] = ((spsProfile.generalConstraintIndicatorFlags shr shift) and 0xff).toByte()
            }
            this[index++] = (spsProfile.generalLevelIdc and 0xff).toByte()
            this[index++] = 0xF0.toByte()
            this[index++] = 0x00
            this[index++] = 0xFC.toByte()
            this[index++] = (0xFC or (spsProfile.chromaFormatIdc and 0x03)).toByte()
            this[index++] = (0xF8 or (spsProfile.bitDepthLumaMinus8 and 0x07)).toByte()
            this[index++] = (0xF8 or (spsProfile.bitDepthChromaMinus8 and 0x07)).toByte()
            this[index++] = 0x00
            this[index++] = 0x00
            this[index++] =
                (
                    ((0 and 0x03) shl 6) or
                        ((spsProfile.numTemporalLayers and 0x07) shl 3) or
                        ((if (spsProfile.temporalIdNested) 1 else 0) shl 2) or
                        0x03
                ).toByte()
            this[index++] = arrays.size.toByte()
            arrays.forEach { (nalUnitType, nalUnits) ->
                this[index++] = (0x80 or nalUnitType).toByte()
                writeUInt16(this, index, nalUnits.size)
                index += 2
                nalUnits.forEach { nalUnit ->
                    writeUInt16(this, index, nalUnit.size)
                    index += 2
                    System.arraycopy(nalUnit, 0, this, index, nalUnit.size)
                    index += nalUnit.size
                }
            }
        }
    }

    // Detect an HEVC keyframe (IDR) without materializing every NAL unit. Scans for
    // start codes and reads only the NAL-type bits of each unit's header byte — no
    // copyOfRange / list allocation — so keyframe detection on the compressed-glasses
    // path no longer duplicates the full parse the packetizer does later.
    internal fun containsHevcKeyFrame(annexBData: ByteArray): Boolean {
        var offset = 0
        while (offset < annexBData.size) {
            val startCodeLength = startCodeLengthAt(annexBData, offset)
            if (startCodeLength == 0) {
                offset++
                continue
            }
            val nalStart = offset + startCodeLength
            if (nalStart < annexBData.size) {
                val type = (annexBData[nalStart].toInt() and 0x7e) shr 1
                if (type == HEVC_NAL_IDR_W_RADL || type == HEVC_NAL_IDR_N_LP) return true
            }
            offset = nalStart
        }
        return false
    }

    internal fun extractNalUnits(annexBData: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < annexBData.size) {
            val startCodeLength = startCodeLengthAt(annexBData, offset)
            if (startCodeLength == 0) {
                offset++
                continue
            }
            val nalStart = offset + startCodeLength
            var nalEnd = annexBData.size
            var scan = nalStart
            while (scan < annexBData.size) {
                val nextStartCodeLength = startCodeLengthAt(annexBData, scan)
                if (nextStartCodeLength != 0) {
                    nalEnd = scan
                    break
                }
                scan++
            }
            if (nalEnd > nalStart) {
                nalUnits += annexBData.copyOfRange(nalStart, nalEnd)
            }
            offset = nalEnd
        }
        return nalUnits
    }

    private fun startCodeLengthAt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte()) {
            if (data[offset + 2] == 1.toByte()) return 3
            if (offset + 4 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) return 4
        }
        return 0
    }

    internal fun hevcNalUnitType(nalUnit: ByteArray): Int {
        require(nalUnit.size >= 2) { "HEVC NAL unit is too short" }
        return (nalUnit[0].toInt() and 0x7e) shr 1
    }

    private fun avcNalUnitType(nalUnit: ByteArray): Int {
        require(nalUnit.isNotEmpty()) { "AVC NAL unit is empty" }
        return nalUnit[0].toInt() and 0x1f
    }

    private fun writeFourCc(buffer: ByteArray, offset: Int, fourCc: String) {
        require(fourCc.length == 4) { "FOURCC must be 4 characters" }
        fourCc.forEachIndexed { index, char -> buffer[offset + index] = char.code.toByte() }
    }

    private fun writeUInt16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 1] = (value and 0xff).toByte()
    }

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 24) and 0xff).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 3] = (value and 0xff).toByte()
    }

    private fun parseHevcSps(spsNalUnit: ByteArray): HevcSpsProfile {
        require(spsNalUnit.size > 2) { "HEVC SPS is too short" }
        val rbsp = hevcRbsp(spsNalUnit)
        val bitReader = BitReader(rbsp)
        bitReader.readBits(4)
        val maxSubLayersMinus1 = bitReader.readBits(3)
        val temporalIdNested = bitReader.readBit() == 1
        val profileTierLevel = bitReader.readProfileTierLevel(maxSubLayersMinus1)
        bitReader.readUnsignedExpGolomb()
        val chromaFormatIdc = bitReader.readUnsignedExpGolomb()
        if (chromaFormatIdc == 3) {
            bitReader.readBit()
        }
        bitReader.readUnsignedExpGolomb()
        bitReader.readUnsignedExpGolomb()
        if (bitReader.readBit() == 1) {
            repeat(4) { bitReader.readUnsignedExpGolomb() }
        }
        val bitDepthLumaMinus8 = bitReader.readUnsignedExpGolomb()
        val bitDepthChromaMinus8 = bitReader.readUnsignedExpGolomb()
        return HevcSpsProfile(
            generalProfileSpace = profileTierLevel.generalProfileSpace,
            generalTierFlag = profileTierLevel.generalTierFlag,
            generalProfileIdc = profileTierLevel.generalProfileIdc,
            generalProfileCompatibilityFlags = profileTierLevel.generalProfileCompatibilityFlags,
            generalConstraintIndicatorFlags = profileTierLevel.generalConstraintIndicatorFlags,
            generalLevelIdc = profileTierLevel.generalLevelIdc,
            chromaFormatIdc = chromaFormatIdc,
            bitDepthLumaMinus8 = bitDepthLumaMinus8,
            bitDepthChromaMinus8 = bitDepthChromaMinus8,
            numTemporalLayers = maxSubLayersMinus1 + 1,
            temporalIdNested = temporalIdNested,
        )
    }

    private fun hevcRbsp(nalUnit: ByteArray): ByteArray {
        val payload = nalUnit.copyOfRange(2, nalUnit.size)
        val rbsp = ArrayList<Byte>(payload.size)
        var zeroCount = 0
        payload.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0
                return@forEach
            }
            rbsp += byte
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return ByteArray(rbsp.size) { index -> rbsp[index] }
    }

    private data class HevcSpsProfile(
        val generalProfileSpace: Int,
        val generalTierFlag: Boolean,
        val generalProfileIdc: Int,
        val generalProfileCompatibilityFlags: Int,
        val generalConstraintIndicatorFlags: Long,
        val generalLevelIdc: Int,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val numTemporalLayers: Int,
        val temporalIdNested: Boolean,
    )

    private data class HevcProfileTierLevel(
        val generalProfileSpace: Int,
        val generalTierFlag: Boolean,
        val generalProfileIdc: Int,
        val generalProfileCompatibilityFlags: Int,
        val generalConstraintIndicatorFlags: Long,
        val generalLevelIdc: Int,
    )

    private class BitReader(private val data: ByteArray) {
        private var bitOffset = 0

        fun readBit(): Int = readBits(1)

        fun readBits(bitCount: Int): Int {
            require(bitCount in 1..32) { "bitCount must be between 1 and 32" }
            var value = 0
            repeat(bitCount) {
                val byteIndex = bitOffset / 8
                val bitIndex = 7 - (bitOffset % 8)
                require(byteIndex < data.size) { "Bitstream ended unexpectedly" }
                value = (value shl 1) or ((data[byteIndex].toInt() shr bitIndex) and 0x01)
                bitOffset++
            }
            return value
        }

        fun readBitsLong(bitCount: Int): Long {
            require(bitCount in 1..64) { "bitCount must be between 1 and 64" }
            var value = 0L
            repeat(bitCount) {
                value = (value shl 1) or readBit().toLong()
            }
            return value
        }

        fun readUnsignedExpGolomb(): Int {
            var leadingZeroBits = 0
            while (readBit() == 0) {
                leadingZeroBits++
            }
            if (leadingZeroBits == 0) {
                return 0
            }
            val suffix = readBits(leadingZeroBits)
            return ((1 shl leadingZeroBits) - 1) + suffix
        }

        fun readProfileTierLevel(maxSubLayersMinus1: Int): HevcProfileTierLevel {
            val generalProfileSpace = readBits(2)
            val generalTierFlag = readBit() == 1
            val generalProfileIdc = readBits(5)
            val generalProfileCompatibilityFlags = readBitsLong(32).toInt()
            val generalConstraintIndicatorFlags = readBitsLong(48)
            val generalLevelIdc = readBits(8)
            val subLayerProfilePresentFlags = BooleanArray(maxSubLayersMinus1)
            val subLayerLevelPresentFlags = BooleanArray(maxSubLayersMinus1)
            repeat(maxSubLayersMinus1) { index ->
                subLayerProfilePresentFlags[index] = readBit() == 1
                subLayerLevelPresentFlags[index] = readBit() == 1
            }
            if (maxSubLayersMinus1 > 0) {
                repeat(8 - maxSubLayersMinus1) {
                    readBits(2)
                }
            }
            repeat(maxSubLayersMinus1) { index ->
                if (subLayerProfilePresentFlags[index]) {
                    readBits(2)
                    readBit()
                    readBits(5)
                    readBitsLong(32)
                    readBitsLong(48)
                }
                if (subLayerLevelPresentFlags[index]) {
                    readBits(8)
                }
            }
            return HevcProfileTierLevel(
                generalProfileSpace = generalProfileSpace,
                generalTierFlag = generalTierFlag,
                generalProfileIdc = generalProfileIdc,
                generalProfileCompatibilityFlags = generalProfileCompatibilityFlags,
                generalConstraintIndicatorFlags = generalConstraintIndicatorFlags,
                generalLevelIdc = generalLevelIdc,
            )
        }
    }
}
