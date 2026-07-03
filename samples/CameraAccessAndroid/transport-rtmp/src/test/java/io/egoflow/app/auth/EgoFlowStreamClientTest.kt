package io.egoflow.app.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EgoFlowStreamClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EgoFlowStreamClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EgoFlowStreamClient(client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun registerStream_postsExpectedPayloadAndReturnsRecordingSessionId() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "recordingSessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """.trimIndent()
                )
        )

        val result = client.registerStream(
            baseUrl = server.url("/").toString(),
            authToken = "jwt-token",
            repositoryId = "repo-123",
            deviceType = "meta_glasses_android",
        )

        assertTrue(result is EgoFlowRegisterStreamResult.Success)
        val success = result as EgoFlowRegisterStreamResult.Success
        assertEquals("550e8400-e29b-41d4-a716-446655440000", success.recordingSessionId)
        assertNull(success.refreshedToken)

        val request = server.takeRequest()
        assertEquals("/api/v1/streams/register", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer jwt-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"repositoryId\":\"repo-123\""))
        assertTrue(body.contains("\"deviceType\":\"meta_glasses_android\""))
        assertTrue(body.contains("\"ingestType\":\"MEDIAMTX\""))
    }

    @Test
    fun registerStream_returnsRefreshedTokenWhenServerProvidesIt() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Refreshed-Token", "new-token")
                .setBody(
                    """
                    {
                      "recordingSessionId": "550e8400-e29b-41d4-a716-446655440001"
                    }
                    """.trimIndent()
                )
        )

        val result = client.registerStream(
            baseUrl = server.url("/").toString(),
            authToken = "jwt-token",
            repositoryId = "repo-456",
            deviceType = "meta_glasses_android",
        )

        assertTrue(result is EgoFlowRegisterStreamResult.Success)
        val success = result as EgoFlowRegisterStreamResult.Success
        assertEquals("550e8400-e29b-41d4-a716-446655440001", success.recordingSessionId)
        assertEquals("new-token", success.refreshedToken)
    }

    @Test
    fun registerStream_failsWhenRecordingSessionIdIsMissing() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "repository_id": "repo-789"
                    }
                    """.trimIndent()
                )
        )

        val result = client.registerStream(
            baseUrl = server.url("/").toString(),
            authToken = "jwt-token",
            repositoryId = "repo-789",
            deviceType = "meta_glasses_android",
        )

        assertTrue(result is EgoFlowRegisterStreamResult.Failure)
    }
}
