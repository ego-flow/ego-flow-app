/*
 * The Transport seam.
 *
 * One implementation per outbound protocol:
 *   - RTMP (Phase 1): wraps RtmpStreamer + EgoFlowBackendClient.
 *   - Slab-append (Phase 2): wraps MkvFileWriter + Uploader +
 *     EventsClient.
 *
 * StreamViewModel depends on this interface ONLY.
 *
 * Frame-input methods take RAW frames (VideoFrame / Bitmap) on
 * purpose: encoding settings (codec, bitrate, GOP) differ per
 * transport (RTMP wants baseline H.264; slab-append wants HEVC
 * main with larger GOPs), so each implementation owns its own
 * MediaCodec. Pre-encoded glasses pass-through (HEVC NALs already
 * delivered by the device) is exposed as a separate path so the
 * encoder is skipped entirely.
 *
 * If a method here starts to feel transport-specific (publish
 * tickets, byte offsets, HLS URLs, ...) DO NOT add it -- that's the
 * abstraction leaking. Put the concept inside the implementation,
 * or escalate to the split-app discussion (see plan's split
 * triggers).
 */

package io.egoflow.app.core.transport.api

import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.flow.StateFlow

interface Transport {
  /** Coarse lifecycle state for the UI + ViewModel. */
  val state: StateFlow<TransportState>

  /**
   * Begin a new recording session. Resolves any pre-flight handshake
   * (publish tickets, auth, etc.) and returns once the transport is
   * ready to accept frames. Throws [TransportStartException] on
   * unrecoverable startup failure -- its `reason` lets the caller surface
   * a user-facing message without inspecting transport-specific types.
   *
   * @param sessionId stable client-generated id (UUID hex). Server
   *   keys its session row on this so retries don't dup.
   * @param codec which codec the encoder pipeline should use. RTMP
   *   commits to this at publish handshake time; slab-append uses
   *   it to pick MediaMuxer track config.
   */
  suspend fun startSession(sessionId: String, codec: VideoCodec)

  /** Raw YUV glasses frame. Transport encodes via its own MediaCodec
   *  pipeline. Non-blocking: queue + return; back-pressure is the
   *  transport's concern. */
  fun sendGlassesFrame(frame: VideoFrame)

  /** Pre-encoded HEVC NALs delivered by the glasses (compressed
   *  mode). Transport packages and ships without re-encoding. */
  fun sendGlassesFrameCompressed(frame: VideoFrame)

  /** Planar I420 phone-camera frame (already upright, even dimensions).
   *  CameraX delivers YUV420, so the camera layer repacks to I420 directly —
   *  no Bitmap round-trip. Transport encodes via its own MediaCodec pipeline.
   *  Non-blocking: queue + return. */
  fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int)

  /** Stop the current session, flush, release. Safe to call when
   *  already Idle (no-op). */
  suspend fun stopSession(reason: StopReason)

  /** Cumulative count of video samples acknowledged-as-sent on the
   *  wire since this transport was created. Monotonic across sessions;
   *  not reset on stop. Safe to call from any thread. Sampled by the UI
   *  to derive an output FPS readout. */
  fun videoFramesSent(): Long
}
