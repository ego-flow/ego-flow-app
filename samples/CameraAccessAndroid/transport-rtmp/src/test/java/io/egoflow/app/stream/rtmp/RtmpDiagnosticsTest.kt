package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpDiagnosticsTest {
    private val testRtmpBaseUrl = "rtmp://127.0.0.1:1935/live"

    @Test
    fun maskSensitiveUrl_masksSensitiveQueryValues() {
        val masked = RtmpDiagnostics.maskSensitiveUrl(
            "$testRtmpBaseUrl/glass_20260323_235109?ticket=t_opaque&mode=live"
        )

        assertEquals(
            "$testRtmpBaseUrl/glass_20260323_235109?ticket=***&mode=live",
            masked,
        )
    }

    @Test
    fun maskSensitiveUrl_returnsOriginalWhenQueryIsMissing() {
        val url = "$testRtmpBaseUrl/glass_20260323_235109"

        assertEquals(url, RtmpDiagnostics.maskSensitiveUrl(url))
    }

    @Test
    fun logEvent_formatsStructuredPayloadAndMasksUrls() {
        RtmpDiagnostics.clear()

        RtmpDiagnostics.logEvent(
            event = "backend.ticket.succeeded",
            fields =
                linkedMapOf(
                    "recordingSessionId" to "rec_123",
                    "connectionId" to "conn_456",
                    "generation" to 7,
                    "publishUrl" to "$testRtmpBaseUrl/glass_20260323_235109?ticket=t_opaque",
                    "error" to "stale session detected",
                ),
        )

        val entry = RtmpDiagnostics.entries.value.single()
        assertTrue(entry.contains("event=backend.ticket.succeeded"))
        assertTrue(entry.contains("recordingSessionId=rec_123"))
        assertTrue(entry.contains("connectionId=conn_456"))
        assertTrue(entry.contains("generation=7"))
        assertTrue(entry.contains("publishUrl=$testRtmpBaseUrl/glass_20260323_235109?ticket=***"))
        assertTrue(entry.contains("error=stale_session_detected"))
    }
}
