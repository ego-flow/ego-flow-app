/*
 * RtmpTransport -- Transport-interface adapter over RtmpStreamer +
 * EgoFlowBackendClient.
 *
 * Lifecycle: startSession → (register + publish-ticket +
 * RtmpStreamer.start) → state Connecting/Streaming. sendXxxFrame
 * delegates to RtmpStreamer's executor. stopSession → for a deliberate
 * stop, POST close-intent (NORMAL_DISCONNECT) before closing the RTMP
 * socket; for errors/network loss, just close the socket (server records
 * UNEXPECTED_DISCONNECT) → state Idle.
 *
 * Per the streaming contract there is no owner-lease heartbeat: a dead
 * session mid-stream is detected by MediaMTX read/write timeout + hooks,
 * not by the App.
 */
package io.egoflow.app.transport.rtmp

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.egoflow.app.core.transport.api.GlassesVideoFrame
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
import io.egoflow.app.stream.rtmp.RtmpAudioSource
import io.egoflow.app.stream.rtmp.RtmpFailureCategory
import io.egoflow.app.stream.rtmp.RtmpStreamer
import io.egoflow.app.stream.rtmp.RtmpVideoCodec
import io.egoflow.app.stream.rtmp.classifyRtmpTransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * @param deviceTypeProvider supplies the EgoFlow "device_type" string
 *   ("meta_glasses_android" / "phone_android") at session start.
 *   Read each call so a user toggling streaming mode between
 *   sessions picks up the new value.
 * @param audioEnabledProvider forwarded to the wrapped RtmpStreamer.
 *   Read at sample time, not at construction.
 * @param context application context the streamer uses for AudioManager
 *   routing (glasses mic capture via the Bluetooth comm device).
 * @param audioSourceProvider forwarded to the wrapped RtmpStreamer.
 *   Read at capture-start time, not at construction.
 * @param backendFactory injectable for unit tests so the real
 *   OkHttp-based client isn't required.
 * @param streamerFactory same idea for RtmpStreamer.
 */
class RtmpTransport(
    private val deviceTypeProvider: () -> String,
    audioEnabledProvider: () -> Boolean,
    context: Context,
    audioSourceProvider: () -> RtmpAudioSource = { RtmpAudioSource.AUTO },
    backendFactory: () -> EgoFlowBackendClient = { EgoFlowBackendClient() },
    streamerFactory: (audioEnabled: () -> Boolean, audioSource: () -> RtmpAudioSource) -> RtmpStreamer =
        { ae, asrc -> RtmpStreamer(context = context, audioEnabledProvider = ae, audioSourceProvider = asrc) },
) : Transport {

  companion object {
    private const val TAG = "RtmpTransport"
  }

  private val backend = backendFactory()
  private val streamer = streamerFactory(audioEnabledProvider, audioSourceProvider)

  private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
  override val state: StateFlow<TransportState> = _state.asStateFlow()

  // Holds the bits we need across startSession → stopSession.
  private var registeredSession: RegisteredStreamSession? = null
  private var publishGrant: PublishTicketGrant? = null
  private var currentSessionId: String? = null
  private var requestedCodec: VideoCodec? = null
  private var rtmpSessionToken: Long = 0L

  init {
    streamer.onPublishStarted = { token ->
      if (token == rtmpSessionToken) {
        // `actualCodec` starts at the requested value; the encoder
        // hasn't drained its first frame yet at publish-start time
        // so we can't observe what the device actually picked. The
        // onVideoCodecChanged callback (below) corrects this if
        // RtmpStreamer falls back HEVC→H.264 mid-session.
        val sid = currentSessionId
        if (sid != null) {
          _state.value = TransportState.Streaming(sid, requestedCodec ?: VideoCodec.H264)
        }
      }
    }
    streamer.onVideoCodecChanged = { _, actual ->
      _state.update { current ->
        if (current is TransportState.Streaming) current.copy(actualCodec = fromRtmpVideoCodec(actual))
        else current
      }
    }
    streamer.onFatalError = { token, failure ->
      if (token == rtmpSessionToken) {
        Log.w(TAG, "RTMP fatal: ${failure.category} ${failure.message}")
        _state.value = TransportState.Failed(
            sessionId = currentSessionId,
            message = "${failure.category}: ${failure.message}",
            reason = reasonForCategory(failure.category),
        )
      }
    }
  }

  override suspend fun startSession(sessionId: String, codec: VideoCodec) {
    if (_state.value !is TransportState.Idle) {
      // Programming-error / race guard: a session is already in flight. Surface
      // it as a typed start failure so the caller words it for the user.
      throw TransportStartException(
          reason = TransportFailureReason.ALREADY_ACTIVE,
          message = "startSession called while in state ${_state.value}; stopSession first",
      )
    }
    _state.value = TransportState.Connecting(sessionId)
    currentSessionId = sessionId
    requestedCodec = codec

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
    publishGrant = grant
    // The grant may rotate the auth token; keep our copy in sync.
    registeredSession = session.copy(authToken = grant.authToken)

    rtmpSessionToken = streamer.start(
        nextPublishUrl = grant.publishUrl,
        videoCodec = toRtmpVideoCodec(codec),
        diagnosticsContext = null,
    )
    // State transitions to Streaming asynchronously via the
    // streamer.onPublishStarted callback wired in init {}.
  }

  override fun sendGlassesFrame(frame: VideoFrame) {
    streamer.sendGlassesFrame(frame)
  }

  override fun sendGlassesFrame(frame: GlassesVideoFrame) {
    streamer.sendGlassesFrame(frame)
  }

  override fun sendGlassesFrameCompressed(frame: VideoFrame) {
    streamer.sendCompressedGlassesFrame(frame)
  }

  override fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int) {
    streamer.sendPhoneFrame(i420, width, height)
  }

  override fun videoFramesSent(): Long = streamer.videoFramesSent()

  override suspend fun stopSession(reason: StopReason) {
    val current = _state.value
    if (current is TransportState.Idle) return
    // close-intent is only valid for the owner of a STREAMING session, so it only
    // matters when we actually reached Streaming. A stop while still Connecting (publish
    // not yet accepted) just drops the socket.
    val wasStreaming = current is TransportState.Streaming
    val sid = currentSessionId.orEmpty()
    _state.value = TransportState.Stopping(sid, reason)

    // Deliberate stop: record NORMAL_DISCONNECT before closing the publisher socket so the
    // backend's stream-not-ready hook preserves it. Errors / network loss skip close-intent
    // and let the server record UNEXPECTED_DISCONNECT.
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

    streamer.stop()
    resetSessionState()
    _state.value = TransportState.Idle
  }

  /** Tears down a half-built session and throws a typed start failure.
   *  Returns [Nothing] so callers can `failStart(...)` from a `catch`
   *  block without a trailing `throw`. */
  private fun failStart(stage: String, error: Exception): Nothing {
    // A cancelled bootstrap (e.g. user hit stop mid-register) must stay a
    // cancellation, not become a user-visible failure. Re-throw as-is.
    if (error is CancellationException) throw error
    Log.w(TAG, "RtmpTransport startSession $stage", error)
    // A half-built session that never reached STREAMING is left as-is: close-intent can't
    // abort a PENDING session, and the contract has the server reuse the same PENDING row on
    // the next register (same user/repository/deviceType), so there is no orphan to clean up.
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

  /** Maps an arbitrary thrown error onto a coarse, transport-agnostic
   *  failure reason. Backend HTTP errors carry their own status, so they
   *  take precedence over the socket-level classification (a 401 is AUTH,
   *  not NETWORK). */
  private fun reasonFor(error: Throwable): TransportFailureReason = when {
    error is TransportStartException -> error.reason
    error is EgoFlowBackendException ->
        if (error.statusCode == 401 || error.statusCode == 403) TransportFailureReason.AUTH
        else TransportFailureReason.SERVER
    else -> reasonForCategory(classifyRtmpTransportFailure(error).category)
  }

  private fun resetSessionState() {
    registeredSession = null
    publishGrant = null
    currentSessionId = null
    requestedCodec = null
    rtmpSessionToken = 0L
  }
}

private fun reasonForCategory(category: RtmpFailureCategory): TransportFailureReason = when (category) {
  RtmpFailureCategory.NETWORK -> TransportFailureReason.NETWORK
  RtmpFailureCategory.TIMEOUT -> TransportFailureReason.TIMEOUT
  RtmpFailureCategory.AUTH -> TransportFailureReason.AUTH
  RtmpFailureCategory.TLS -> TransportFailureReason.TLS
  RtmpFailureCategory.INTERNAL -> TransportFailureReason.INTERNAL
}

private fun toRtmpVideoCodec(codec: VideoCodec): RtmpVideoCodec = when (codec) {
  VideoCodec.H264 -> RtmpVideoCodec.H264
  VideoCodec.HEVC -> RtmpVideoCodec.H265
}

private fun fromRtmpVideoCodec(codec: RtmpVideoCodec): VideoCodec = when (codec) {
  RtmpVideoCodec.H264 -> VideoCodec.H264
  RtmpVideoCodec.H265 -> VideoCodec.HEVC
}

// Whether a stop is a deliberate, graceful end that should record NORMAL_DISCONNECT via
// close-intent. A user pressing stop and the glasses ending a session on-device are both
// intentional; network loss and fatal errors are not and are left to the server to record
// as UNEXPECTED_DISCONNECT.
private fun reasonSendsCloseIntent(reason: StopReason): Boolean = when (reason) {
  StopReason.USER_STOP, StopReason.GLASSES_STOP -> true
  StopReason.NETWORK_LOST, StopReason.FATAL_ERROR -> false
}
