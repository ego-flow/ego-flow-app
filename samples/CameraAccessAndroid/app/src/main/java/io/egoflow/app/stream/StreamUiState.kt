/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

// StreamUiState - EgoFlow capture and transport state.

package io.egoflow.app.stream

import android.graphics.Bitmap
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.stream.rtmp.RtmpVideoCodec

enum class StreamingMode { GLASSES, PHONE }

enum class CaptureState {
  STARTING,
  STREAMING,
  STOPPED,
}

data class StreamUiState(
    val streamState: CaptureState = CaptureState.STOPPED,
    val transportState: TransportState = TransportState.Idle,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val streamingMode: StreamingMode = StreamingMode.GLASSES,
    val activeRepositoryId: String? = null,
    val activeRepositoryName: String? = null,
    val publishTarget: String? = null,
    val usesBackendSession: Boolean = false,
    val requestedVideoCodec: RtmpVideoCodec? = null,
    val activeVideoCodec: RtmpVideoCodec? = null,
    val videoCodecDidFallback: Boolean = false,
    // Wall-clock millis (SystemClock.elapsedRealtime). Set once when the
    // session transitions to STREAMING; not touched on every frame, so
    // it's safe to keep in UI state.
    val streamingStartedAtMs: Long? = null,
    // Per-second FPS readouts sampled at 1Hz by StreamViewModel. The
    // raw frame counters are AtomicLongs out-of-band; only the derived
    // FPS value lands in UI state, so we get one StateFlow emit per
    // second instead of one per frame.
    val inputFps: Float = 0f,
    val outputFps: Float = 0f,
    // Rolling history of the 1Hz fps samples (oldest first), capped at
    // FPS_HISTORY_SECONDS, for the health-card sparklines. Bounded size
    // keeps copy()/equality cheap; reset to empty on each stream start.
    val inputFpsHistory: List<Float> = emptyList(),
    val outputFpsHistory: List<Float> = emptyList(),
)

// Window of the fps sparklines in the health card (one sample/second).
const val FPS_HISTORY_SECONDS = 30
