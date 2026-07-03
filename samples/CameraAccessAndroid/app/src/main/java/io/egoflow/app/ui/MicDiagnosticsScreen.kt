package io.egoflow.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import io.egoflow.app.diagnostics.MicDiagnostics
import io.egoflow.app.ui.theme.LocalEgoFlowColors
import kotlinx.coroutines.delay

/**
 * Debug screen for verifying which microphone feeds the input — phone vs. Meta glasses — and for
 * inspecting the communication devices Android exposes while the glasses are connected.
 *
 * Lists [android.media.AudioManager.getAvailableCommunicationDevices], lets you route input to one
 * of them (pick the glasses' Bluetooth entry to capture from the glasses mic), and draws a live
 * waveform of the captured audio. See [MicDiagnostics].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicDiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mic = remember { MicDiagnostics(context) }

    // Route the phone's hardware back button to the Settings screen instead of letting it exit the app.
    BackHandler(onBack = onBack)

    val isCapturing by mic.isCapturing.collectAsStateWithLifecycle()
    val waveform by mic.waveform.collectAsStateWithLifecycle()
    val level by mic.level.collectAsStateWithLifecycle()
    val devices by mic.devices.collectAsStateWithLifecycle()
    val selectedDeviceId by mic.selectedDeviceId.collectAsStateWithLifecycle()
    val error by mic.error.collectAsStateWithLifecycle()

    // Auto-start capture on entry, and keep the device list fresh while the screen is open so a
    // glasses connection shows up without a manual refresh.
    LaunchedEffect(Unit) {
        if (mic.hasRecordPermission()) mic.start()
        while (true) {
            mic.refreshDevices()
            delay(2_000)
        }
    }
    DisposableEffect(Unit) {
        onDispose { mic.stop() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            EgoFlowTopAppBar(
                title = "Mic diagnostics",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { mic.refreshDevices() }) {
                        Icon(Lucide.RotateCw, contentDescription = "Refresh devices")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DiagSection(label = "LIVE INPUT") {
                DiagCard {
                    Waveform(waveform = waveform)
                    LevelMeter(level = level)
                    Text(
                        text = "Source: VOICE_COMMUNICATION · 16 kHz mono",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                    CaptureToggle(
                        isCapturing = isCapturing,
                        onToggle = { if (isCapturing) mic.stop() else mic.start() },
                    )
                }
            }

            DiagSection(label = "COMMUNICATION DEVICES") {
                DiagCard {
                    if (devices.isEmpty()) {
                        Text(
                            text = "No communication devices found. Connect the glasses, then refresh.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        )
                    } else {
                        devices.forEachIndexed { index, device ->
                            DeviceRow(
                                device = device,
                                selected = device.id == selectedDeviceId,
                                onClick = { mic.selectDevice(device.id) },
                            )
                            if (index < devices.lastIndex) DiagDivider()
                        }
                    }
                    DiagDivider()
                    Text(
                        text = "Reset to phone default",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { mic.clearSelection() },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }

            Text(
                text = "The glasses' microphone is shared through the system Bluetooth stack, so it " +
                    "appears here as a \"Bluetooth SCO\" or \"BLE headset\" device — not as a Meta " +
                    "SDK audio API. Select that device, speak, and watch the waveform to confirm " +
                    "capture is coming from the glasses. If it never appears, the HFP route isn't " +
                    "exposing the glasses mic.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun Waveform(waveform: FloatArray) {
    val lineColor = LocalEgoFlowColors.current.statusGreen
    val baselineColor = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val mid = size.height / 2f
        drawLine(
            color = baselineColor,
            start = Offset(0f, mid),
            end = Offset(size.width, mid),
            strokeWidth = 1f,
        )
        if (waveform.isEmpty()) return@Canvas
        val slot = size.width / waveform.size
        waveform.forEachIndexed { i, value ->
            val x = i * slot + slot / 2f
            val half = (value.coerceIn(0f, 1f)) * mid
            drawLine(
                color = lineColor,
                start = Offset(x, mid - half),
                end = Offset(x, mid + half),
                strokeWidth = (slot * 0.6f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    val fillColor = LocalEgoFlowColors.current.statusGreen
    // Speech RMS sits low; scale up so the meter is readable.
    val display = (level * 4f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(display)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fillColor),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${(level * 100f).toInt()}%",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        )
    }
}

@Composable
private fun CaptureToggle(isCapturing: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isCapturing) "Stop capture" else "Start capture",
            color = if (isCapturing) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun DeviceRow(
    device: MicDiagnostics.CommDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (device.isBluetooth) "${device.typeLabel} · likely glasses" else device.typeLabel,
                color = if (device.isBluetooth) {
                    LocalEgoFlowColors.current.statusGreen
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Lucide.Check,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DiagSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.88.sp,
            ),
        )
        content()
    }
}

@Composable
private fun DiagCard(content: @Composable ColumnScope.() -> Unit) {
    val cardShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), cardShape)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun DiagDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}
