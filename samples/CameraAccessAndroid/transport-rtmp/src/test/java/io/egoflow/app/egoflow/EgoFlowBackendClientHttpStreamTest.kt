package io.egoflow.app.egoflow

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the /http-streams REST surface on EgoFlowBackendClient. Uses the
 * injectable client + base-URL seam so we can drive a MockWebServer without
 * EncryptedSharedPreferences. 401-replay/relogin paths route through login() (which
 * reads AuthPrefs) and are covered by the end-to-end verification, not here.
 */
class EgoFlowBackendClientHttpStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EgoFlowBackendClient

    private val session = RegisteredStreamSession(
        authToken = "jwt-token",
        userId = "user-1",
        recordingSessionId = "rec-1",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EgoFlowBackendClient(
            httpClientOverride = OkHttpClient(),
            apiBaseUrlOverride = server.url("/api/v1"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun httpStreamStart_postsPublishTicketAndParsesState() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "recording_session_id": "rec-1",
                      "status": "STREAMING",
                      "bytes_received": 0,
                      "last_sequence": null
                    }
                    """.trimIndent()
                )
        )

        val state = client.httpStreamStart(session, "t_abc123")

        assertEquals(0L, state.bytesReceived)
        assertNull(state.lastSequence)
        assertEquals("jwt-token", state.authToken)

        val request = server.takeRequest()
        assertEquals("/api/v1/http-streams/rec-1/start", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("\"publish_ticket\":\"t_abc123\""))
    }

    @Test
    fun httpStreamStart_usesRefreshedTokenWhenProvided() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Refreshed-Token", "new-token")
                .setBody(
                    """{"recording_session_id":"rec-1","status":"STREAMING","bytes_received":0,"last_sequence":null}"""
                )
        )

        val state = client.httpStreamStart(session, "t_abc")

        assertEquals("new-token", state.authToken)
    }

    @Test
    fun httpStreamChunk_sendsOctetStreamWithSequenceAndOffsetHeaders() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"recording_session_id":"rec-1","bytes_received":1024,"last_sequence":0}"""
                )
        )

        val chunk = ByteArray(1024) { (it % 256).toByte() }
        val state = client.httpStreamChunk("jwt-token", "rec-1", sequence = 0, offset = 0, chunk = chunk, length = 1024)

        assertEquals(1024L, state.bytesReceived)
        assertEquals(0L, state.lastSequence)

        val request = server.takeRequest()
        assertEquals("/api/v1/http-streams/rec-1/chunks", request.path)
        assertEquals("POST", request.method)
        assertEquals("0", request.getHeader("X-Chunk-Sequence"))
        assertEquals("0", request.getHeader("X-Chunk-Offset"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/octet-stream"))
        assertEquals(1024L, request.body.size)
    }

    @Test
    fun httpStreamChunk_sendsOnlyTheRequestedLengthFromTheBuffer() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"recording_session_id":"rec-1","bytes_received":5000,"last_sequence":3}"""
                )
        )

        // Reusable scratch buffer larger than the actual (final) chunk.
        val buffer = ByteArray(4096) { 1 }
        val state = client.httpStreamChunk("jwt-token", "rec-1", sequence = 3, offset = 4000, chunk = buffer, length = 1000)

        assertEquals(5000L, state.bytesReceived)
        assertEquals(3L, state.lastSequence)

        val request = server.takeRequest()
        assertEquals("3", request.getHeader("X-Chunk-Sequence"))
        assertEquals("4000", request.getHeader("X-Chunk-Offset"))
        assertEquals(1000L, request.body.size)
    }

    @Test
    fun httpStreamFinish_postsTotalBytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"recording_session_id":"rec-1","status":"CLOSED","segment_status":"WRITE_DONE","bytes_received":2048}"""
                )
        )

        val state = client.httpStreamFinish("jwt-token", "rec-1", totalBytes = 2048)

        assertEquals(2048L, state.bytesReceived)

        val request = server.takeRequest()
        assertEquals("/api/v1/http-streams/rec-1/finish", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"total_bytes\":2048"))
    }

    @Test
    fun httpStreamStart_mapsServerErrorToBackendException() {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"CONFLICT","message":"already started"}}""")
        )

        try {
            client.httpStreamStart(session, "t_abc")
            fail("Expected EgoFlowBackendException")
        } catch (e: EgoFlowBackendException) {
            assertEquals(409, e.statusCode)
            assertEquals("CONFLICT", e.code)
        }
    }
}
