package io.egoflow.app.stream.rtmp

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class RtmpFailureCategory {
    NETWORK,
    TIMEOUT,
    AUTH,
    TLS,
    INTERNAL,
}

data class RtmpTransportFailure(
    val category: RtmpFailureCategory,
    val retryable: Boolean,
    val message: String,
)

open class RtmpTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class RtmpTimeoutException(
    message: String,
    cause: Throwable? = null,
) : RtmpTransportException(message, cause)

class RtmpAuthException(
    message: String,
    cause: Throwable? = null,
) : RtmpTransportException(message, cause)

class RtmpTlsHandshakeException(
    message: String,
    cause: Throwable? = null,
) : RtmpTransportException(message, cause)

class RtmpTlsHostnameException(
    message: String,
    cause: Throwable? = null,
) : RtmpTransportException(message, cause)

fun classifyRtmpTransportFailure(error: Throwable): RtmpTransportFailure =
    when (error) {
        is RtmpAuthException -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.AUTH,
                retryable = false,
                message = error.message ?: "RTMP publish authorization failed.",
            )
        }
        is RtmpTlsHostnameException,
        is RtmpTlsHandshakeException,
        is SSLPeerUnverifiedException,
        is SSLHandshakeException,
        is SSLException,
        -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.TLS,
                retryable = false,
                message = error.message ?: "RTMPS TLS validation failed.",
            )
        }
        is RtmpTimeoutException,
        is SocketTimeoutException,
        -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.TIMEOUT,
                retryable = true,
                message = error.message ?: "RTMP transport timed out.",
            )
        }
        is EOFException,
        is ConnectException,
        is NoRouteToHostException,
        is UnknownHostException,
        is SocketException,
        -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.NETWORK,
                retryable = true,
                message = error.message ?: "RTMP transport network failure.",
            )
        }
        is IOException -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.NETWORK,
                retryable = true,
                message = error.message ?: "RTMP transport I/O failure.",
            )
        }
        else -> {
            RtmpTransportFailure(
                category = RtmpFailureCategory.INTERNAL,
                retryable = false,
                message = error.message ?: "Unknown RTMP transport failure.",
            )
        }
    }
