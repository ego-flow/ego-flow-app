package io.egoflow.app.transport.whip

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhipClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WhipClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WhipClient(client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postOffer_postsSdpAndReturnsAnswerWithAbsoluteResourceUrl() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Location", "/live/repo/sess/whip/abc")
                .setBody("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n")
        )

        val whipUrl = server.url("/live/repo/sess/whip?ticket=t_xyz").toString()
        val answer = client.postOffer(whipUrl, "v=0\r\no=offer\r\n")

        assertEquals("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n", answer.sdpAnswer)
        // The relative Location is resolved against the request origin.
        assertEquals(server.url("/live/repo/sess/whip/abc").toString(), answer.resourceUrl)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/live/repo/sess/whip?ticket=t_xyz", request.path)
        // okhttp appends "; charset=utf-8" to a string body's media type.
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/sdp"))
        assertEquals("v=0\r\no=offer\r\n", request.body.readUtf8())
    }

    @Test
    fun postOffer_throwsWhipExceptionWithStatusCodeOnNon2xx() {
        server.enqueue(MockResponse().setResponseCode(401))

        val whipUrl = server.url("/live/repo/sess/whip?ticket=t_xyz").toString()
        val error = assertThrows(WhipException::class.java) {
            client.postOffer(whipUrl, "v=0\r\no=offer\r\n")
        }
        assertEquals(401, error.statusCode)
    }

    @Test
    fun postOffer_throwsWhipExceptionOnEmptyAnswer() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(""))

        val whipUrl = server.url("/live/repo/sess/whip?ticket=t_xyz").toString()
        val error = assertThrows(WhipException::class.java) {
            client.postOffer(whipUrl, "v=0\r\no=offer\r\n")
        }
        assertEquals(201, error.statusCode)
        assertTrue(error.message!!.contains("empty SDP answer"))
    }

    @Test
    fun deleteSession_issuesDeleteToResourceUrl() {
        server.enqueue(MockResponse().setResponseCode(200))

        client.deleteSession(server.url("/live/repo/sess/whip/abc").toString())

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/live/repo/sess/whip/abc", request.path)
    }
}
