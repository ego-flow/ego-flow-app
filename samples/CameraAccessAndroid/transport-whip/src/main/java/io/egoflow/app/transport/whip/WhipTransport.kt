/*
 * WhipTransport -- Transport-interface adapter over WhipPeerConnection +
 * WhipClient + the shared EgoFlowBackendClient control plane.
 *
 * Lifecycle (see .mermaid/3.whip-publish.mmd, 4.whip-stream.mmd):
 *   startSession → register + publish-ticket (REST, shared with RTMP) → build
 *   WHIP URL → WebRTC offer/ICE → POST SDP offer → apply answer → Connecting,
 *   then Streaming once the connection reaches CONNECTED.
 *   stopSession → for a graceful stop POST close-intent (NORMAL_DISCONNECT)
 *   before tearing down, DELETE the WHIP resource, then dispose the peer; for
 *   errors just dispose and let the server record UNEXPECTED_DISCONNECT.
 *
 * Codec note: WHIP media is whatever the SDP negotiates -- libwebrtc offers
 * H.264, which MediaMTX accepts -- so the requested [VideoCodec] is informational
 * and the reported actualCodec is always H264. On-device HEVC pass-through
 * (compressed glasses frames) has no place in a WebRTC pipeline and is dropped.
 *
 * Structurally mirrors RtmpTransport so the two transports read the same.
 */
package io.egoflow.app.transport.whip

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.egoflow.app.core.transport.api.StopReason
import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportFailureReason
import io.egoflow.app.core.transport.api.TransportStartException
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.core.transport.api.VideoCodec
import io.egoflow.app.egoflow.EgoFlowBackendClient
import io.egoflow.app.egoflow.EgoFlowBackendException
import io.egoflow.app.egoflow.IngestType
import io.egoflow.app.egoflow.PublishTicketGrant
import io.egoflow.app.egoflow.RegisteredStreamSession
import io.egoflow.app.settings.AuthPrefs
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * @param deviceTypeProvider supplies the EgoFlow "device_type" string
 *   ("meta_glasses_android" / "phone_android") at session start. Read each call
 *   so a user toggling streaming mode between sessions picks up the new value.
 * @param context application context for the WebRTC factory / EGL setup.
 * @param backendFactory injectable so unit tests need not hit the network.
 * @param peerConnectionFactory injectable for the same reason.
 */
class WhipTransport(
    private val deviceTypeProvider: () -> String,
    private val context: Context,
    backendFactory: () -> EgoFlowBackendClient = { EgoFlowBackendClient() },
    private val whipClient: WhipClient = WhipClient(),
    private val peerConnectionFactory: (Context) -> WhipPeerConnection = { WhipPeerConnection(it) },
) : Transport {

  companion object {
    private const val TAG = "WhipTransport"
  }

  private val backend = backendFactory()

  private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
  override val state: StateFlow<TransportState> = _state.asStateFlow()

  // Bits carried across startSession → stopSession.
  private var registeredSession: RegisteredStreamSession? = null
  private var peer: WhipPeerConnection? = null
  private var resourceUrl: String? = null
  private var currentSessionId: String? = null

  // Monotonic across sessions (not reset on stop), so the UI's FPS sampler can
  // poll it the same way it polls RtmpStreamer.videoFramesSent.
  private val totalVideoSamplesSent = AtomicLong(0L)

  override suspend fun startSession(sessionId: String, codec: VideoCodec) {
    if (_state.value !is TransportState.Idle) {
      throw TransportStartException(
          reason = TransportFailureReason.ALREADY_ACTIVE,
          message = "startSession called while in state ${_state.value}; stopSession first",
      )
    }
    _state.value = TransportState.Connecting(sessionId)
    currentSessionId = sessionId

    val deviceType = deviceTypeProvider()
    val session: RegisteredStreamSession = try {
      withContext(Dispatchers.IO) { backend.registerStreamSession(deviceType, IngestType.MEDIAMTX) }
    } catch (error: Exception) {
      failStart("register failed", error)
    }
    registeredSession = session

    val grant: PublishTicketGrant = try {
      withContext(Dispatchers.IO) { backend.requestPublishTicket(session) }
    } catch (error: Exception) {
      failStart("publish-ticket failed", error)
    }
    // The grant may rotate the auth token; keep our copy in sync for close-intent.
    registeredSession = session.copy(authToken = grant.authToken)

    val whipUrl = try {
      buildWhipUrl(AuthPrefs.egoFlowApiBaseUrl, grant.streamPath, grant.publishTicket)
    } catch (error: Exception) {
      failStart("invalid WHIP URL", error)
    }

    val pc = peerConnectionFactory(context)
    peer = pc
    pc.onConnected = {
      val sid = currentSessionId
      if (sid != null && _state.value is TransportState.Connecting) {
        // WHIP media is the SDP-negotiated codec; libwebrtc publishes H.264.
        _state.value = TransportState.Streaming(sid, VideoCodec.H264)
      }
    }
    pc.onFailed = { detail ->
      // A connection drop after we reached Streaming (or while still Connecting)
      // is terminal -- surface it as a network loss so the ViewModel tears down.
      val current = _state.value
      if (current is TransportState.Streaming || current is TransportState.Connecting) {
        Log.w(TAG, "WHIP connection failed: $detail")
        _state.value = TransportState.Failed(
            sessionId = currentSessionId,
            message = "connection: $detail",
            reason = TransportFailureReason.NETWORK,
        )
      }
    }

    try {
      withContext(Dispatchers.Default) { pc.start() }
      val offer = pc.createOfferAndGather()
      val answer = withContext(Dispatchers.IO) { whipClient.postOffer(whipUrl, offer) }
      resourceUrl = answer.resourceUrl
      pc.setRemoteAnswer(answer.sdpAnswer)
    } catch (error: Exception) {
      failStart("WHIP publish failed", error)
    }
    // State transitions to Streaming asynchronously via pc.onConnected.
  }

  override fun sendGlassesFrame(frame: VideoFrame) {
    val source = frame.buffer.duplicate()
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    // Wearables PTS is microseconds; WebRTC frame timestamps are nanoseconds.
    if (peer?.feedI420(bytes, frame.width, frame.height, frame.presentationTimeUs * 1_000L) == true) {
      totalVideoSamplesSent.incrementAndGet()
    }
  }

  override fun sendGlassesFrameCompressed(frame: VideoFrame) {
    // WebRTC re-encodes from raw frames, so pre-encoded HEVC has nowhere to go.
    // The ViewModel forces compression off for WHIP; this guards the edge case.
    logCompressedDropOnce()
  }

  override fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int) {
    if (peer?.feedI420(i420, width, height, System.nanoTime()) == true) {
      totalVideoSamplesSent.incrementAndGet()
    }
  }

  override fun videoFramesSent(): Long = totalVideoSamplesSent.get()

  override suspend fun stopSession(reason: StopReason) {
    val current = _state.value
    if (current is TransportState.Idle) return
    val wasStreaming = current is TransportState.Streaming
    val sid = currentSessionId.orEmpty()
    _state.value = TransportState.Stopping(sid, reason)

    // Deliberate stop: record NORMAL_DISCONNECT before tearing down so the
    // backend's stream-not-ready hook preserves it (same contract as RTMP).
    if (wasStreaming && reasonSendsCloseIntent(reason)) {
      registeredSession?.let { session ->
        withContext(Dispatchers.IO) {
          try {
            backend.closeIntent(session)
          } catch (e: Exception) {
            Log.w(TAG, "close-intent failed (best effort); will be recorded as UNEXPECTED_DISCONNECT", e)
          }
        }
      }
    }

    resourceUrl?.let { url ->
      withContext(Dispatchers.IO) {
        try {
          whipClient.deleteSession(url)
        } catch (e: Exception) {
          Log.w(TAG, "WHIP DELETE failed (best effort)", e)
        }
      }
    }

    resetSessionState()
    _state.value = TransportState.Idle
  }

  /** Tears down a half-built session and throws a typed start failure. Returns
   *  [Nothing] so callers can `failStart(...)` from a `catch` without a trailing
   *  throw -- mirrors RtmpTransport.failStart. */
  private fun failStart(stage: String, error: Exception): Nothing {
    if (error is CancellationException) throw error
    Log.w(TAG, "WhipTransport startSession $stage", error)
    val reason = reasonFor(error)
    val technical = "$stage: ${error.message ?: error.javaClass.simpleName}"
    _state.value = TransportState.Failed(
        sessionId = currentSessionId,
        message = technical,
        reason = reason,
    )
    resetSessionState()
    throw TransportStartException(reason = reason, message = technical, cause = error)
  }

  /** Maps a thrown error onto a coarse, transport-agnostic failure reason.
   *  HTTP-status-bearing errors take precedence over socket classification. */
  private fun reasonFor(error: Throwable): TransportFailureReason = when {
    error is TransportStartException -> error.reason
    error is EgoFlowBackendException ->
        if (error.statusCode == 401 || error.statusCode == 403) TransportFailureReason.AUTH
        else TransportFailureReason.SERVER
    error is WhipException -> when {
      error.statusCode == 401 || error.statusCode == 403 -> TransportFailureReason.AUTH
      (error.statusCode ?: 0) >= 400 -> TransportFailureReason.SERVER
      else -> TransportFailureReason.NETWORK
    }
    error is SSLException -> TransportFailureReason.TLS
    error is SocketTimeoutException -> TransportFailureReason.TIMEOUT
    error is IOException -> TransportFailureReason.NETWORK
    else -> TransportFailureReason.INTERNAL
  }

  private fun resetSessionState() {
    peer?.dispose()
    peer = null
    registeredSession = null
    resourceUrl = null
    currentSessionId = null
  }

  // WHIP publish URL per .mermaid/3.whip-publish.mmd:
  //   {origin}/{stream_path}/whip?ticket={publish_ticket}
  // origin is the backend base URL reduced to scheme://host[:port] (the stored
  // value may carry an /api/v1 suffix, which is dropped). stream_path's slashes
  // are real path segments; the opaque ticket is URL-encoded.
  private fun buildWhipUrl(rawBaseUrl: String, streamPath: String, publishTicket: String): String {
    val origin = originOf(rawBaseUrl)
    val cleanPath = streamPath.trim().trim('/')
    if (cleanPath.isEmpty()) throw IllegalArgumentException("stream_path is blank")
    val encodedTicket = URLEncoder.encode(publishTicket, Charsets.UTF_8.name())
    return "$origin/$cleanPath/whip?ticket=$encodedTicket"
  }

  private fun originOf(raw: String): String {
    val withScheme = when {
      raw.startsWith("http://") || raw.startsWith("https://") -> raw
      raw.isBlank() -> throw IllegalArgumentException("EgoFlow API base URL is blank.")
      // Default a scheme-less host to HTTPS, matching EgoFlowBackendClient.
      else -> "https://$raw"
    }
    val url = withScheme.toHttpUrl()
    // Reduce to the bare origin: drop any path (/api/v1), query, fragment.
    // HttpUrl.toString() omits the default port for the scheme.
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        .toString().trimEnd('/')
  }

  @Volatile private var compressedDropLogged = false
  private fun logCompressedDropOnce() {
    if (compressedDropLogged) return
    compressedDropLogged = true
    Log.w(TAG, "Dropping compressed (on-device HEVC) frames: unsupported on the WHIP/WebRTC path")
  }
}

// Whether a stop is a deliberate, graceful end that should record
// NORMAL_DISCONNECT via close-intent. Same policy as RtmpTransport.
private fun reasonSendsCloseIntent(reason: StopReason): Boolean = when (reason) {
  StopReason.USER_STOP, StopReason.GLASSES_STOP -> true
  StopReason.NETWORK_LOST, StopReason.FATAL_ERROR -> false
}
