package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertEquals
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
}
