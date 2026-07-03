package io.egoflow.app.stream.rtmp

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpTransportFailuresTest {
    @Test
    fun classifyRtmpTransportFailure_marksTimeoutsRetryable() {
        val failure = classifyRtmpTransportFailure(SocketTimeoutException("read timed out"))

        assertEquals(RtmpFailureCategory.TIMEOUT, failure.category)
        assertTrue(failure.retryable)
    }

    @Test
    fun classifyRtmpTransportFailure_marksTlsHandshakeNonRetryable() {
        val failure = classifyRtmpTransportFailure(SSLHandshakeException("certificate verify failed"))

        assertEquals(RtmpFailureCategory.TLS, failure.category)
        assertFalse(failure.retryable)
    }

    @Test
    fun classifyRtmpTransportFailure_marksAuthFailuresNonRetryable() {
        val failure = classifyRtmpTransportFailure(RtmpAuthException("publish denied"))

        assertEquals(RtmpFailureCategory.AUTH, failure.category)
        assertFalse(failure.retryable)
    }

    @Test
    fun classifyRtmpTransportFailure_keepsIoFailuresRetryable() {
        val failure = classifyRtmpTransportFailure(IOException("connection reset"))

        assertEquals(RtmpFailureCategory.NETWORK, failure.category)
        assertTrue(failure.retryable)
    }
}
