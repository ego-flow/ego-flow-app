package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpVideoPacketizerTest {
    @Test
    fun extractNalUnits_splitsAnnexBFrames() {
        val annexB =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67,
                0x64,
                0x00,
                0x1f,
                0x00,
                0x00,
                0x01,
                0x68,
                0x11,
                0x22,
            )

        val nalUnits = RtmpVideoPacketizer.extractNalUnits(annexB)

        assertEquals(2, nalUnits.size)
        assertArrayEquals(byteArrayOf(0x67, 0x64, 0x00, 0x1f), nalUnits[0])
        assertArrayEquals(byteArrayOf(0x68, 0x11, 0x22), nalUnits[1])
    }

    @Test
    fun buildVideoPacket_buildsLegacyAvcPacket() {
        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H264,
                annexBData =
                    byteArrayOf(
                        0x00,
                        0x00,
                        0x00,
                        0x01,
                        0x65,
                        0x55,
                        0x66,
                    ),
                isKeyFrame = true,
                compositionTimeMs = 0,
            )

        assertEquals(0x17.toByte(), packet[0])
        assertEquals(0x01.toByte(), packet[1])
        assertEquals(12, packet.size)
        assertEquals(3, packet[8].toInt() and 0xff)
        assertEquals(0x65.toByte(), packet[9])
    }

    @Test
    fun buildVideoPacket_buildsEnhancedHevcPacket() {
        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H265,
                annexBData =
                    byteArrayOf(
                        0x00,
                        0x00,
                        0x00,
                        0x01,
                        0x26,
                        0x01,
                        0x11,
                        0x22,
                    ),
                isKeyFrame = true,
                compositionTimeMs = 0,
            )

        assertEquals(0x93.toByte(), packet[0])
        assertEquals('h'.code.toByte(), packet[1])
        assertEquals('v'.code.toByte(), packet[2])
        assertEquals('c'.code.toByte(), packet[3])
        assertEquals('1'.code.toByte(), packet[4])
        assertEquals(13, packet.size)
        assertEquals(4, packet[8].toInt() and 0xff)
        assertEquals(0x26.toByte(), packet[9])
    }

    @Test
    fun buildVideoPacket_prependsUpdatedHevcParametersToRestartIdr() {
        val vps = byteArrayOf(0x40, 0x01, 0x11)
        val sps = byteArrayOf(0x42, 0x01, 0x22, 0x33)
        val pps = byteArrayOf(0x44, 0x01, 0x44)
        val idr = byteArrayOf(0x26, 0x01, 0x55, 0x66)

        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H265,
                annexBData = byteArrayOf(0x00, 0x00, 0x00, 0x01) + idr,
                isKeyFrame = true,
                compositionTimeMs = 0,
                prependedNalUnits = listOf(vps, sps, pps),
            )

        assertEquals(0x93.toByte(), packet[0])
        val nalUnits = readLengthPrefixedNalUnits(packet, offset = 5)
        assertEquals(4, nalUnits.size)
        assertArrayEquals(vps, nalUnits[0])
        assertArrayEquals(sps, nalUnits[1])
        assertArrayEquals(pps, nalUnits[2])
        assertArrayEquals(idr, nalUnits[3])
    }

    @Test
    fun buildVideoPacket_insertsUpdatedHevcParametersAfterAccessUnitDelimiter() {
        val aud = byteArrayOf(0x46, 0x01, 0x10)
        val vps = byteArrayOf(0x40, 0x01, 0x11)
        val sps = byteArrayOf(0x42, 0x01, 0x22)
        val pps = byteArrayOf(0x44, 0x01, 0x33)
        val idr = byteArrayOf(0x26, 0x01, 0x44)
        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H265,
                annexBData = annexB(aud, idr),
                isKeyFrame = true,
                prependedNalUnits = listOf(vps, sps, pps),
            )

        val nalUnits = readLengthPrefixedNalUnits(packet, offset = 5)

        assertEquals(listOf(35, 32, 33, 34, 19), nalUnits.map(RtmpVideoPacketizer::hevcNalUnitType))
        assertArrayEquals(aud, nalUnits[0])
    }

    @Test
    fun buildVideoPacket_replacesEncoderInlineHevcParametersInsteadOfDuplicatingThem() {
        val aud = byteArrayOf(0x46, 0x01, 0x10)
        val oldVps = byteArrayOf(0x40, 0x01, 0x01)
        val oldSps = byteArrayOf(0x42, 0x01, 0x02)
        val oldPps = byteArrayOf(0x44, 0x01, 0x03)
        val newVps = byteArrayOf(0x40, 0x01, 0x11)
        val newSps = byteArrayOf(0x42, 0x01, 0x22)
        val newPps = byteArrayOf(0x44, 0x01, 0x33)
        val idr = byteArrayOf(0x26, 0x01, 0x44)
        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H265,
                annexBData = annexB(aud, oldVps, oldSps, oldPps, idr),
                isKeyFrame = true,
                prependedNalUnits = listOf(newVps, newSps, newPps),
            )

        val nalUnits = readLengthPrefixedNalUnits(packet, offset = 5)

        assertEquals(listOf(35, 32, 33, 34, 19), nalUnits.map(RtmpVideoPacketizer::hevcNalUnitType))
        assertArrayEquals(newVps, nalUnits[1])
        assertArrayEquals(newSps, nalUnits[2])
        assertArrayEquals(newPps, nalUnits[3])
    }

    @Test
    fun buildVideoPacket_rejectsHevcParameterInjectionOnNonKeyFrame() {
        try {
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec = RtmpVideoCodec.H265,
                annexBData = annexB(byteArrayOf(0x02, 0x01, 0x44)),
                isKeyFrame = false,
                prependedNalUnits = listOf(byteArrayOf(0x40, 0x01, 0x11)),
            )
            throw AssertionError("non-key HEVC parameter injection must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("key frame"))
        }
    }

    @Test
    fun fromPreferenceValue_defaultsToH264() {
        assertEquals(RtmpVideoCodec.H264, RtmpVideoCodec.fromPreferenceValue("unknown"))
        assertEquals(RtmpVideoCodec.H265, RtmpVideoCodec.fromPreferenceValue("H265"))
    }

    @Test
    fun buildAvcSequenceHeader_embedsSpsPpsWithoutStartCodePrefix() {
        val sps = byteArrayOf(
            0x67.toByte(), // NAL header (SPS)
            0x42.toByte(), // profile_idc (Baseline)
            0xC0.toByte(), // constraint_set + reserved
            0x1F.toByte(), // level_idc
            0x8C.toByte(),
            0x8D.toByte(),
        )
        val pps = byteArrayOf(
            0x68.toByte(), // NAL header (PPS)
            0xCE.toByte(),
            0x3C.toByte(),
            0x80.toByte(),
        )

        val header = RtmpVideoPacketizer.buildAvcSequenceHeader(sps, pps)

        assertEquals(0x17.toByte(), header[0])
        assertEquals(0x00.toByte(), header[1])
        assertEquals(0x01.toByte(), header[5])          // configurationVersion
        assertEquals(0x42.toByte(), header[6])          // AVCProfileIndication
        assertEquals(0xC0.toByte(), header[7])          // profile_compatibility
        assertEquals(0x1F.toByte(), header[8])          // AVCLevelIndication
        assertEquals(0xFF.toByte(), header[9])          // lengthSizeMinusOne
        assertEquals(0xE1.toByte(), header[10])         // numOfSPS (reserved + 1)
        // SPS length + body
        assertEquals(sps.size, ((header[11].toInt() and 0xff) shl 8) or (header[12].toInt() and 0xff))
        val spsBody = header.copyOfRange(13, 13 + sps.size)
        assertArrayEquals(sps, spsBody)
        val ppsOffset = 13 + sps.size
        assertEquals(0x01.toByte(), header[ppsOffset])  // numOfPPS
        assertEquals(
            pps.size,
            ((header[ppsOffset + 1].toInt() and 0xff) shl 8) or (header[ppsOffset + 2].toInt() and 0xff),
        )
        val ppsBody = header.copyOfRange(ppsOffset + 3, ppsOffset + 3 + pps.size)
        assertArrayEquals(pps, ppsBody)
        assertEquals(16 + sps.size + pps.size, header.size)
    }

    private fun readLengthPrefixedNalUnits(packet: ByteArray, offset: Int): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var cursor = offset
        while (cursor < packet.size) {
            val length =
                ((packet[cursor].toInt() and 0xff) shl 24) or
                    ((packet[cursor + 1].toInt() and 0xff) shl 16) or
                    ((packet[cursor + 2].toInt() and 0xff) shl 8) or
                    (packet[cursor + 3].toInt() and 0xff)
            cursor += 4
            nalUnits += packet.copyOfRange(cursor, cursor + length)
            cursor += length
        }
        return nalUnits
    }

    private fun annexB(vararg nalUnits: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        return nalUnits.fold(ByteArray(0)) { bytes, nal -> bytes + startCode + nal }
    }
}
