/*
 * HttpTransport -- Transport adapter for the HTTP chunk-upload ingest path.
 *
 * Unlike the old store-and-forward design (record a full MP4, upload after stop),
 * this OVERLAPS capture and upload: it encodes into append-only fMP4 segments
 * (HttpLiveRecorder + FragmentedMp4Muxer) and ships them to the /http-streams
 * control plane DURING the session (HttpLiveUploader). So:
 *   startSession -> register → publish-ticket → /start (network now, ticket TTL ok)
 *   each fragment -> /chunks (live)
 *   stopSession(graceful) -> flush last fragment → /finish
 *
 * The upload runs on the uploader's own process-lifetime scope; the foreground
 * StreamingService keeps the process alive for the session and the final drain.
 *
 * Structurally mirrors WhipTransport so the transports read the same.
 */
package io.egoflow.app.transport.http

import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.egoflow.app.core.transport.api.StopReason
import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportFailureReason
import io.egoflow.app.core.transport.api.TransportStartException
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.core.transport.api.VideoCodec
import io.egoflow.app.egoflow.EgoFlowBackendException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * @param deviceTypeProvider supplies the EgoFlow "device_type" string at session
 *   start; captured then so the upload registers with the type active during capture.
 */
class HttpTransport(
    private val deviceTypeProvider: () -> String,
) : Transport {

    companion object {
        private const val TAG = "HttpTransport"
    }

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Bits carried across startSession → stopSession.
    private var recorder: HttpLiveRecorder? = null
    private var uploader: HttpLiveUploader? = null
    private var currentSessionId: String? = null

    override suspend fun startSession(sessionId: String, codec: VideoCodec) {
        if (_state.value !is TransportState.Idle) {
            throw TransportStartException(
                reason = TransportFailureReason.ALREADY_ACTIVE,
                message = "startSession called while in state ${_state.value}; stopSession first",
            )
        }
        currentSessionId = sessionId
        val deviceType = deviceTypeProvider()

        val up = HttpLiveUploader()
        // Start recording immediately so frames captured during the /start handshake
        // are buffered (the uploader's queue holds segments until the send loop runs).
        val rec = HttpLiveRecorder(codec) { segment -> up.enqueue(segment) }
        recorder = rec

        try {
            up.start(deviceType) // register → publish-ticket → /start
        } catch (e: Exception) {
            Log.w(TAG, "HTTP stream start failed", e)
            rec.stop()
            up.cancel()
            resetSessionState()
            throw TransportStartException(reason = startFailureReason(e), message = e.message ?: "start failed", cause = e)
        }

        uploader = up
        _state.value = TransportState.Streaming(sessionId, codec)
    }

    override fun sendGlassesFrame(frame: VideoFrame) {
        val source = frame.buffer.duplicate()
        val bytes = ByteArray(source.remaining())
        source.get(bytes)
        recorder?.queueGlassesFrame(bytes, frame.width, frame.height, frame.presentationTimeUs)
    }

    override fun sendGlassesFrameCompressed(frame: VideoFrame) {
        // HTTP re-encodes from raw YUV via the on-device encoder; pre-encoded HEVC
        // pass-through has nowhere to go. The ViewModel forces compression off for
        // HTTP; this guards the edge case.
        logCompressedDropOnce()
    }

    override fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int) {
        // Copy: the recorder consumes the frame asynchronously on the codec thread.
        recorder?.queuePhoneFrame(i420.copyOf(), width, height)
    }

    override fun videoFramesSent(): Long = recorder?.videoFramesSent() ?: 0L

    override suspend fun stopSession(reason: StopReason) {
        if (_state.value is TransportState.Idle) return
        val sid = currentSessionId.orEmpty()
        _state.value = TransportState.Stopping(sid, reason)

        // Flush the encoder tail + the last fragment into the uploader queue. Off the
        // caller's (Main) dispatcher: it blocks on the codec thread and can briefly
        // block on the upload backpressure queue under a slow uplink.
        try {
            withContext(Dispatchers.IO) { recorder?.stop() }
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop failed", e)
        }

        val up = uploader
        if (up != null) {
            if (reasonIsGraceful(reason)) {
                // Drain remaining chunks + /finish. Survives caller-scope cancellation
                // on the uploader's own scope.
                try {
                    up.finishAndAwait()
                } catch (e: Exception) {
                    Log.w(TAG, "Live upload finish failed", e)
                }
            } else {
                // Aborted / errored: stop sending; the server times out and recovers a
                // partial recording.
                up.cancel()
            }
        }

        resetSessionState()
        _state.value = TransportState.Idle
    }

    private fun resetSessionState() {
        recorder = null
        uploader = null
        currentSessionId = null
    }

    private fun startFailureReason(e: Exception): TransportFailureReason = when {
        e is EgoFlowBackendException && e.statusCode == 401 -> TransportFailureReason.AUTH
        e is EgoFlowBackendException && e.statusCode == 403 -> TransportFailureReason.AUTH
        e is EgoFlowBackendException -> TransportFailureReason.SERVER
        else -> TransportFailureReason.NETWORK
    }

    @Volatile private var compressedDropLogged = false
    private fun logCompressedDropOnce() {
        if (compressedDropLogged) return
        compressedDropLogged = true
        Log.w(TAG, "Dropping compressed (on-device HEVC) frames: HTTP path re-encodes from raw YUV")
    }
}

// A graceful stop is a deliberate, normal end whose recording is worth finishing.
// Network loss / fatal errors abort (server times out into a partial recovery),
// consistent with RTMP/WHIP not recording NORMAL_DISCONNECT in those cases.
private fun reasonIsGraceful(reason: StopReason): Boolean = when (reason) {
    StopReason.USER_STOP, StopReason.GLASSES_STOP -> true
    StopReason.NETWORK_LOST, StopReason.FATAL_ERROR -> false
}
