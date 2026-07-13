/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.ui

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.stream.CaptureState
import io.egoflow.app.stream.FPS_HISTORY_SECONDS
import io.egoflow.app.stream.StreamUiState
import io.egoflow.app.stream.StreamViewModel
import io.egoflow.app.stream.StreamingMode
import io.egoflow.app.stream.rtmp.RtmpDiagnostics
import io.egoflow.app.ui.theme.LocalEgoFlowColors
import io.egoflow.app.wearables.WearablesViewModel
import kotlinx.coroutines.delay

private val OverlayScrim = Color(0x8C000000) // rgba(0,0,0,0.55)

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val rtmpDiagnostics by RtmpDiagnostics.entries.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lock the Activity to portrait while StreamScreen is composed.
    // Landscape mid-stream would force a config change (Activity
    // recreate) and rotate the camera feed in flight; both
    // destabilize the encoder + RTMP socket. Other tabs keep the
    // manifest's `fullSensor` orientation.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            streamViewModel.startPhoneCamera(lifecycleOwner)
        } else {
            streamViewModel.startStream()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (streamUiState.streamState == CaptureState.STARTING) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                if (streamUiState.streamingMode != StreamingMode.PHONE) {
                    Text(
                        text = "The glasses haven't started streaming yet. " +
                            "If this keeps spinning, stop and restart the stream.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Top overlays. No statusBarsPadding here -- the enclosing
        // MainTabScreen Scaffold now keeps the Record top bar visible and
        // its innerPadding already offsets us below the status bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamHealthCard(state = streamUiState)
                if (SettingsManager.rtmpDebugOverlayEnabled && rtmpDiagnostics.isNotEmpty()) {
                    RtmpDebugOverlay(entries = rtmpDiagnostics)
                }
            }
            SettingsOverlayButton(onClick = { wearablesViewModel.showSettings() })
        }

        // Bottom controls
        ControlsRow(
            onStopStream = {
                // Arm the "stopping" overlay, then tear down. Navigation back to
                // device selection is driven by WearablesViewModel.onStreamStopped
                // once stopSession() completes, so the StreamViewModel stays alive
                // long enough to finish teardown.
                wearablesViewModel.beginStreamStop()
                streamViewModel.stopStream(notifyOnSuccess = true)
            },
            onCapturePhoto = { streamViewModel.capturePhoto() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}

/** Compact card with two status rows:
 *   1. Stream (from Glasses/Phone) -- per-stream lifecycle of the active source
 *   2. Stream (to Server) -- outbound transport (app -> server)
 *  Decoupling them surfaces failure modes where one link is healthy
 *  but another has failed, instead of conflating into a single
 *  "LIVE / CONNECTING" label. Per-frame counters live elsewhere; the
 *  elapsed clock ticks 1Hz. */
@Composable
private fun StreamHealthCard(state: StreamUiState) {
    val nowMs by produceState(
        initialValue = SystemClock.elapsedRealtime(),
        key1 = state.streamingStartedAtMs,
    ) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000L)
        }
    }

    val elapsedSec = state.streamingStartedAtMs
        ?.let { ((nowMs - it) / 1000L).coerceAtLeast(0L) }
        ?: 0L
    val isStreaming = state.streamState == CaptureState.STREAMING &&
        state.streamingStartedAtMs != null
    val streamInfo = streamSessionStatusInfo(state.streamState)
    val transportInfo = transportStatusInfo(state.transportState)
    val codec = state.activeVideoCodec?.displayName
        ?: state.requestedVideoCodec?.displayName
    val hasDetails = codec != null || isStreaming ||
        (state.videoCodecDidFallback && state.activeVideoCodec != null)

    val cardShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), cardShape)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val isPhoneMode = state.streamingMode == StreamingMode.PHONE
        StatusRow(
            label = if (isPhoneMode) "Stream (from Phone)" else "Stream (from Glasses)",
            value = streamInfo.label,
            dotColor = streamInfo.color,
        )
        StatusRow(
            label = "Stream (to Server)",
            value = transportInfo.label,
            dotColor = transportInfo.color,
            trailing = if (isStreaming) formatElapsed(elapsedSec) else null,
        )

        if (hasDetails) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )

            if (codec != null) {
                DetailLine(text = codec)
            }
            if (isStreaming) {
                // Shared vertical scale so the in/out sparklines are
                // directly comparable (out can only ever be <= in).
                val scaleMax = (state.inputFpsHistory + state.outputFpsHistory)
                    .maxOrNull()
                    ?.coerceAtLeast(1f)
                    ?: 1f
                FpsTrack(label = "in", fps = state.inputFps, history = state.inputFpsHistory, scaleMax = scaleMax)
                FpsTrack(label = "out", fps = state.outputFps, history = state.outputFpsHistory, scaleMax = scaleMax)
            }
            if (state.videoCodecDidFallback && state.activeVideoCodec != null) {
                Text(
                    text = "codec fallback → ${state.activeVideoCodec.displayName}",
                    color = LocalEgoFlowColors.current.statusYellow,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

/** A current fps readout with the recent-history sparkline below it. */
@Composable
private fun FpsTrack(label: String, fps: Float, history: List<Float>, scaleMax: Float) {
    DetailLine(text = "%s: %.1f fps".format(label, fps))
    FpsSparkline(history = history, scaleMax = scaleMax)
}

/** Minimal line chart of the last FPS_HISTORY_SECONDS samples. x maps
 *  oldest->newest left->right; y is 0..scaleMax (shared across tracks).
 *  Renders nothing meaningful until at least two samples exist. */
@Composable
private fun FpsSparkline(
    history: List<Float>,
    scaleMax: Float,
    modifier: Modifier = Modifier,
) {
    val lineColor = LocalEgoFlowColors.current.statusGreen
    val baselineColor = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        // Baseline so an empty/flat track still reads as a chart.
        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
        if (history.size < 2) return@Canvas
        val stepX = size.width / (FPS_HISTORY_SECONDS - 1)
        // Right-align newest sample; pad older history toward the left.
        val offsetIndex = FPS_HISTORY_SECONDS - history.size
        val path = Path()
        history.forEachIndexed { i, value ->
            val x = (offsetIndex + i) * stepX
            val y = size.height - (value / scaleMax).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private data class StatusInfo(val label: String, val color: Color)

@Composable
private fun streamSessionStatusInfo(state: CaptureState): StatusInfo {
    val colors = LocalEgoFlowColors.current
    val dimmed = MaterialTheme.colorScheme.outline
    return when (state) {
        CaptureState.STARTING -> StatusInfo("STARTING", colors.statusYellow)
        CaptureState.STREAMING -> StatusInfo("STREAMING", colors.statusGreen)
        CaptureState.STOPPED -> StatusInfo("STOPPED", dimmed)
    }
}

@Composable
private fun transportStatusInfo(state: TransportState): StatusInfo {
    val colors = LocalEgoFlowColors.current
    return when (state) {
        is TransportState.Idle -> StatusInfo("IDLE", MaterialTheme.colorScheme.outline)
        is TransportState.Connecting -> StatusInfo("CONNECTING", colors.statusYellow)
        is TransportState.Streaming -> StatusInfo("LIVE", colors.statusGreen)
        is TransportState.Stopping -> StatusInfo("STOPPING", colors.statusYellow)
        is TransportState.Failed -> StatusInfo("FAILED", colors.statusRed)
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    dotColor: Color,
    trailing: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            ),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        if (trailing != null) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = trailing,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun SettingsOverlayButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(OverlayScrim)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.Settings,
            contentDescription = "Settings",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RtmpDebugOverlay(entries: List<String>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(entries.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(12.dp))
            .background(OverlayScrim)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "RTMP Debug",
            style = MaterialTheme.typography.labelMedium,
            color = LocalEgoFlowColors.current.statusYellow,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .verticalScroll(scrollState),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                entries.forEach { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
