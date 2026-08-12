package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RtmpPublisherTest {
    private val testHost = "127.0.0.1"
    private val testRtmpBaseUrl = "rtmp://$testHost:1935/live"

    @Test
    fun parsePublishUrl_keepsQueryParametersOnStreamKey() {
        val parsed = RtmpPublisher.parsePublishUrl(
            "$testRtmpBaseUrl/glass_20260323_235109?ticket=t_opaque"
        )

        assertEquals(testHost, parsed.host)
        assertEquals(1935, parsed.port)
        assertEquals("live", parsed.app)
        assertEquals("glass_20260323_235109?ticket=t_opaque", parsed.streamKey)
        assertEquals(testRtmpBaseUrl, parsed.tcUrl)
    }

    @Test
    fun parsePublishUrl_preservesNestedStreamPathBeforeQuery() {
        val parsed = RtmpPublisher.parsePublishUrl(
            "$testRtmpBaseUrl/device/glass_01?ticket=t_nested"
        )

        assertEquals("live", parsed.app)
        assertEquals("device/glass_01?ticket=t_nested", parsed.streamKey)
    }

    @Test
    fun parsePublishUrl_supportsRtmpsWithDefaultPort() {
        val parsed = RtmpPublisher.parsePublishUrl(
            "rtmps://secure.example.com/live/repo_name?ticket=t_tls"
        )

        assertEquals("rtmps", parsed.scheme)
        assertEquals("secure.example.com", parsed.host)
        assertEquals(1936, parsed.port)
        assertEquals("live", parsed.app)
        assertEquals("repo_name?ticket=t_tls", parsed.streamKey)
        assertEquals("rtmps://secure.example.com:1936/live", parsed.tcUrl)
    }

    @Test
    fun connect_rejectsInactiveGenerationBeforeOpeningSocket() {
        val publisher =
            RtmpPublisher(
                publishUrl = "rtmp://127.0.0.1:1/live/cancelled",
                publishAllowed = { false },
            )

        try {
            publisher.connect()
            fail("cancelled generation must not attempt an RTMP connection")
        } catch (error: RtmpTransportException) {
            assertTrue(error.message.orEmpty().contains("cancelled"))
        }
    }

    @Test
    fun abort_remainsEffectiveWhenCalledBeforeSocketCreation() {
        val publisher = RtmpPublisher("rtmp://127.0.0.1:1/live/aborted")
        publisher.abort()

        try {
            publisher.connect()
            fail("aborted publisher must not attempt an RTMP connection")
        } catch (error: RtmpTransportException) {
            assertTrue(error.message.orEmpty().contains("cancelled"))
        }
    }

    @Test
    fun videoPublishState_inBandParametersAreOneShotAndOnlyForRawRuntimeRefresh() {
        val state = RtmpVideoPublishState()
        val sequenceHeader = byteArrayOf(0x01)
        val parameterSets = listOf(byteArrayOf(0x40, 0x01), byteArrayOf(0x42, 0x01), byteArrayOf(0x44, 0x01))
        val config = RtmpVideoCodecConfig(sequenceHeader, parameterSets)

        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = true)
        val initial = state.prepareSample(isKeyFrame = true)
        assertArrayEquals(sequenceHeader, initial?.sequenceHeader)
        assertTrue(initial?.inBandParameterSets.orEmpty().isEmpty())

        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = true)
        assertFalse(state.canSendSample(isKeyFrame = false))
        assertNull(state.prepareSample(isKeyFrame = false))

        val refresh = state.prepareSample(isKeyFrame = true)
        assertArrayEquals(sequenceHeader, refresh?.sequenceHeader)
        assertEquals(parameterSets, refresh?.inBandParameterSets)

        val followingKeyFrame = state.prepareSample(isKeyFrame = true)
        assertNull(followingKeyFrame?.sequenceHeader)
        assertTrue(followingKeyFrame?.inBandParameterSets.orEmpty().isEmpty())
    }

    @Test
    fun videoPublishState_passThroughRefreshNeverInjectsParameterSets() {
        val state = RtmpVideoPublishState()
        val config =
            RtmpVideoCodecConfig(
                sequenceHeader = byteArrayOf(0x01),
                inBandParameterSets = listOf(byteArrayOf(0x40, 0x01)),
            )
        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = false)
        state.prepareSample(isKeyFrame = true)

        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = false)
        val refresh = state.prepareSample(isKeyFrame = true)

        assertTrue(refresh?.inBandParameterSets.orEmpty().isEmpty())
    }

    @Test
    fun videoPublishState_resetClearsPendingRefreshAndPublishedState() {
        val state = RtmpVideoPublishState()
        val config =
            RtmpVideoCodecConfig(
                sequenceHeader = byteArrayOf(0x01),
                inBandParameterSets = listOf(byteArrayOf(0x40, 0x01)),
            )
        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = true)
        state.prepareSample(isKeyFrame = true)
        state.queueConfig(RtmpVideoCodec.H265, config, refreshH265ParameterSetsInBand = true)

        state.reset()

        assertFalse(state.hasPublishedVideoSample)
        assertTrue(state.canSendSample(isKeyFrame = true))
        val sample = state.prepareSample(isKeyFrame = true)
        assertNull(sample?.sequenceHeader)
        assertTrue(sample?.inBandParameterSets.orEmpty().isEmpty())
    }
}
