/*
 * Surface-agnostic types used by every :transport-* implementation.
 *
 * Kept intentionally minimal -- anything that needs to live here must
 * be meaningful to every transport (RTMP, slab-append, anything
 * future). Transport-specific concepts (publish tickets, byte
 * offsets, owner leases, HLS URLs, etc.) stay inside their own
 * module and never leak through this surface. See the "Split
 * trigger" list in the plan for what to do if they start leaking.
 */

package io.egoflow.app.core.transport.api

import android.media.MediaCodec
import android.media.MediaFormat

/** Stable identifier for each transport implementation. Used by the
 *  factory registry in `:app` to pick which one to instantiate. */
enum class TransportId { RTMP, SLAB, WHIP, HTTP }

/** Video codec carried in an `EncodedFrame`. Mirrors what the
 *  on-device encoder emits -- kept abstract so we don't drag
 *  RtmpVideoCodec (or any other transport's enum) into the core.
 *  `mimeType` is colocated so a shared `:core` encoder can build
 *  a MediaFormat without round-tripping through a transport-
 *  specific enum. */
enum class VideoCodec(val mimeType: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC),
}

/**
 * One packed planar I420 frame captured by the glasses.
 *
 * The constructor and [copyI420] both copy the byte array so a vendor SDK or an
 * asynchronous transport cannot mutate a frame that another layer still owns.
 */
class GlassesVideoFrame(
    i420: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
) {
  private val i420Bytes: ByteArray

  init {
    require(width > 0 && height > 0) { "Frame dimensions must be positive" }
    require(width % 2 == 0 && height % 2 == 0) { "I420 frame dimensions must be even" }
    require(presentationTimeUs >= 0L) { "Frame presentation timestamp must not be negative" }

    val expectedSize = width.toLong() * height.toLong() * 3L / 2L
    require(expectedSize <= Int.MAX_VALUE && i420.size.toLong() == expectedSize) {
      "I420 buffer length ${i420.size} does not match ${width}x${height} frame size $expectedSize"
    }
    i420Bytes = i420.copyOf()
  }

  fun copyI420(): ByteArray = i420Bytes.copyOf()
}

/** Why a session stopped. Each value implies a different remediation
 *  posture (retry vs surface error vs treat as user intent). */
enum class StopReason {
  /** User explicitly stopped (Stop button, etc.). */
  USER_STOP,

  /** Glasses ended the stream from their side (battery, disconnect,
   *  user took them off). */
  GLASSES_STOP,

  /** Network unreachable / unrecoverable transport error. */
  NETWORK_LOST,

  /** Anything else -- bug, unexpected exception. */
  FATAL_ERROR,
}

/**
 * One encoded video frame ready to be shipped over the wire.
 *
 * We hold a COPY of the bytes (`ByteArray`, not the MediaCodec
 * output `ByteBuffer`) on purpose: MediaCodec owns its output
 * buffers and reclaims them as soon as `releaseOutputBuffer` is
 * called, so anything that wants to hand the bytes to a slow sink
 * (RTMP socket, slab uploader, MKV muxer running on a different
 * thread) has to copy first or risk holding the codec hostage.
 */
class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    /** MediaCodec.BUFFER_FLAG_* bitmask. */
    val flags: Int,
    val codec: VideoCodec,
) {
  val isKeyFrame: Boolean
    get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

  /** SPS/PPS/VPS bundle (no actual picture data). Always the first
   *  frame out of the encoder for AVC/HEVC. */
  val isCodecConfig: Boolean
    get() = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
}

/**
 * Coarse state machine surfaced through `Transport.state`. The UI
 * consumes this directly (e.g. the Start/Stop button label, the
 * "Connecting..." toast). Per-transport diagnostics (e.g. slab
 * `received_to` byte counters) stay inside the implementation and
 * out of this enum.
 */
sealed class TransportState {
  object Idle : TransportState()
  data class Connecting(val sessionId: String) : TransportState()
  /** `actualCodec` may differ from the codec the caller requested
   *  in `startSession` (e.g. RTMP HEVC→H.264 fallback). UI uses the
   *  diff to flag a degraded-quality warning. */
  data class Streaming(val sessionId: String, val actualCodec: VideoCodec) : TransportState()
  data class Stopping(val sessionId: String, val reason: StopReason) : TransportState()
  /** `message` is the raw technical detail (exception text, server
   *  category) -- log it, don't show it. `reason` is the coarse,
   *  transport-agnostic bucket the UI maps to a user-facing string. */
  data class Failed(
      val sessionId: String?,
      val message: String,
      val reason: TransportFailureReason = TransportFailureReason.INTERNAL,
  ) : TransportState()
}

/**
 * Coarse, transport-agnostic failure buckets. Every transport maps its
 * own error taxonomy onto these; the UI maps these onto user-facing
 * strings (see StreamViewModel.friendlyFailureMessage). Intentionally
 * small -- add a case only when the UI would genuinely word it
 * differently.
 */
enum class TransportFailureReason {
  /** Tried to start while a session was already active (caller bug / race). */
  ALREADY_ACTIVE,
  /** Auth/authorization rejected (bad token, expired sign-in, 401/403). */
  AUTH,
  /** Server reached but refused the request (non-auth 4xx/5xx). */
  SERVER,
  /** Connection couldn't be established or was lost. */
  NETWORK,
  /** Operation exceeded its time budget. */
  TIMEOUT,
  /** Secure-connection (TLS) negotiation failed. */
  TLS,
  /** Anything not covered above. */
  INTERNAL,
}

/**
 * Thrown by [Transport.startSession] when the pre-flight handshake fails.
 * Carries a [reason] so the caller can surface a user-facing message
 * without inspecting transport-specific exception types. `message` stays
 * technical (for logs).
 */
class TransportStartException(
    val reason: TransportFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
