package io.egoflow.app.auth

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EgoFlowAuthClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EgoFlowAuthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = EgoFlowAuthClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun login_postsCredentialsAndReturnsToken() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "token": "jwt-token",
                      "user": {
                        "id": "admin",
                        "role": "admin",
                        "displayName": "Administrator"
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = client.login(
            baseUrl = server.url("/").toString(),
            userId = "admin",
            password = "secret123",
        )

        assertTrue(result is EgoFlowLoginResult.Success)
        val success = result as EgoFlowLoginResult.Success
        assertEquals("jwt-token", success.token)
        assertEquals("admin", success.userId)
        assertEquals("admin", success.role)
        assertEquals("Administrator", success.displayName)

        val request = server.takeRequest()
        assertEquals("/api/v1/auth/app/login", request.path)
        assertEquals("POST", request.method)
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"id\":\"admin\""))
        assertTrue(requestBody.contains("\"password\":\"secret123\""))
    }

    @Test
    fun login_returnsServerErrorMessage() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody(
                    """
                    {
                      "error": {
                        "code": "INVALID_CREDENTIALS",
                        "message": "Invalid id or password."
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = client.login(
            baseUrl = server.url("/").toString(),
            userId = "admin",
            password = "wrong-password",
        )

        assertTrue(result is EgoFlowLoginResult.Failure)
        val failure = result as EgoFlowLoginResult.Failure
        assertEquals("Invalid id or password.", failure.message)
    }

    @Test
    fun login_rejectsBlankBaseUrlWithoutNetworkCall() = runBlocking {
        val result = client.login(
            baseUrl = "  ",
            userId = "admin",
            password = "secret123",
        )

        assertTrue(result is EgoFlowLoginResult.Failure)
        val failure = result as EgoFlowLoginResult.Failure
        assertEquals("EgoFlow API base URL is required", failure.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun login_rejectsBaseUrlWithoutScheme() = runBlocking {
        val result = client.login(
            baseUrl = "example.com",
            userId = "admin",
            password = "secret123",
        )

        assertTrue(result is EgoFlowLoginResult.Failure)
        val failure = result as EgoFlowLoginResult.Failure
        assertEquals("EgoFlow API base URL must include http:// or https://", failure.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun login_mapsTlsProtocolMismatchToActionableMessage() = runBlocking {
        val throwingClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Unable to parse TLS packet header")
            }
            .build()
        client = EgoFlowAuthClient(client = throwingClient)

        val result = client.login(
            baseUrl = "https://example.com",
            userId = "admin",
            password = "secret123",
        )

        assertTrue(result is EgoFlowLoginResult.Failure)
        val failure = result as EgoFlowLoginResult.Failure
        assertEquals(
            "TLS handshake failed. The server may be HTTP-only. Check whether the API Base URL should use http:// instead of https://.",
            failure.message,
        )
    }
}
