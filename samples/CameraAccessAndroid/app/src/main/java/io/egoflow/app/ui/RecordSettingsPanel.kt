package io.egoflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.settings.GlassesVideoQuality
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.stream.rtmp.RtmpAudioSource
import io.egoflow.app.stream.rtmp.RtmpVideoCodec

data class RecordRepositorySummary(
    val displayName: String,
    val visibility: String? = null,
    val role: String? = null,
    val isCached: Boolean = false,
)

@Composable
fun RecordSettingsPanel(
    repository: RecordRepositorySummary?,
    streamingActive: Boolean,
    onOpenRepositories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var transportMode by remember { mutableStateOf(SettingsManager.recordTransportMode()) }
    var audioEnabled by remember { mutableStateOf(SettingsManager.rtmpAudioEnabled) }
    var audioSource by remember { mutableStateOf(SettingsManager.audioSource) }
    var compressVideo by remember { mutableStateOf(SettingsManager.rtmpCompressVideo) }
    var videoCodec by remember { mutableStateOf(SettingsManager.rtmpVideoCodec) }
    var videoQuality by remember { mutableStateOf(SettingsManager.videoQuality) }
    var advancedExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        transportMode = SettingsManager.normalizeRecordTransportMode()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repository?.let {
            ActiveRepositoryCard(
                repository = it,
                onClick = onOpenRepositories,
            )
        }

        RecordSettingsCard {
            SettingsToggleRow(
                label = "Enable Live Streaming",
                description = "On uses RTMP live streaming. Off uses HTTP recording/upload.",
                checked = transportMode == TransportId.RTMP,
                onCheckedChange = { checked ->
                    transportMode = if (checked) TransportId.RTMP else TransportId.HTTP
                    SettingsManager.transportMode = transportMode
                },
                enabled = !streamingActive,
            )
            RecordSettingsDivider()
            SegmentedColumn(
                label = "Video quality",
                description = "Glasses only. The phone camera is fixed at 480x640.",
                enabled = !streamingActive,
            ) {
                SegmentedControl(
                    options = listOf(
                        "Low" to GlassesVideoQuality.LOW,
                        "Medium" to GlassesVideoQuality.MEDIUM,
                        "High" to GlassesVideoQuality.HIGH,
                    ),
                    selected = videoQuality,
                    onSelected = {
                        videoQuality = it
                        SettingsManager.videoQuality = it
                    },
                    subLabel = {
                        when (it) {
                            GlassesVideoQuality.LOW -> "360x640"
                            GlassesVideoQuality.MEDIUM -> "504x896"
                            GlassesVideoQuality.HIGH -> "720x1280"
                        }
                    },
                    enabled = !streamingActive,
                )
            }
        }

        RecordSettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Advanced",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = "Audio source, codec, and on-device compression.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    )
                }
                Icon(
                    imageVector = Lucide.ArrowRight,
                    contentDescription = if (advancedExpanded) "Collapse advanced settings" else "Expand advanced settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (advancedExpanded) 90f else 0f },
                )
            }

            if (advancedExpanded) {
                RecordSettingsDivider()
                SettingsToggleRow(
                    label = "Stream audio",
                    description = "Capture microphone while streaming. (RTMP only.)",
                    checked = audioEnabled,
                    onCheckedChange = {
                        audioEnabled = it
                        SettingsManager.rtmpAudioEnabled = it
                    },
                    enabled = !streamingActive && transportMode == TransportId.RTMP,
                )
                RecordSettingsDivider()
                SegmentedColumn(
                    label = "Audio source",
                    description = "Auto uses the glasses mic when detected, otherwise the phone.",
                    enabled = audioEnabled && !streamingActive && transportMode == TransportId.RTMP,
                ) {
                    SegmentedControl(
                        options = listOf(
                            RtmpAudioSource.AUTO.displayName to RtmpAudioSource.AUTO,
                            RtmpAudioSource.GLASSES.displayName to RtmpAudioSource.GLASSES,
                            RtmpAudioSource.PHONE.displayName to RtmpAudioSource.PHONE,
                        ),
                        selected = audioSource,
                        onSelected = {
                            audioSource = it
                            SettingsManager.audioSource = it
                        },
                        enabled = audioEnabled && !streamingActive && transportMode == TransportId.RTMP,
                    )
                }
                RecordSettingsDivider()
                val codecControlEnabled =
                    !streamingActive &&
                        !(transportMode == TransportId.RTMP && compressVideo)
                SegmentedColumn(
                    label = "Video codec",
                    description = "H.265 reduces bandwidth. Applies to RTMP and HTTP modes.",
                    enabled = codecControlEnabled,
                ) {
                    SegmentedControl(
                        options = listOf(
                            "H.264" to RtmpVideoCodec.H264,
                            "H.265" to RtmpVideoCodec.H265,
                        ),
                        selected = videoCodec,
                        onSelected = {
                            videoCodec = it
                            SettingsManager.rtmpVideoCodec = it
                        },
                        enabled = codecControlEnabled,
                    )
                }
                RecordSettingsDivider()
                SettingsToggleRow(
                    label = "Compress on device",
                    description = "Request HEVC frames directly from the glasses. (RTMP only.)",
                    checked = compressVideo,
                    onCheckedChange = { enabled ->
                        compressVideo = enabled
                        SettingsManager.rtmpCompressVideo = enabled
                        if (enabled) {
                            videoCodec = RtmpVideoCodec.H265
                            SettingsManager.rtmpVideoCodec = RtmpVideoCodec.H265
                        }
                    },
                    enabled = !streamingActive && transportMode == TransportId.RTMP,
                )
            }
        }
    }
}

@Composable
private fun ActiveRepositoryCard(
    repository: RecordRepositorySummary,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), cardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Lucide.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Repository",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = repository.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Icon(
                imageVector = Lucide.ArrowRight,
                contentDescription = "Change repository",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repository.visibility?.takeIf { it.isNotBlank() }?.let { visibility ->
                RepositoryChip(
                    label = visibility.lowercase(),
                    icon = if (visibility.equals("public", ignoreCase = true)) Lucide.Globe else Lucide.Lock,
                )
            }
            repository.role?.takeIf { it.isNotBlank() }?.let { role ->
                RepositoryChip(
                    label = role.lowercase(),
                    icon = if (role.equals("owner", ignoreCase = true)) Lucide.Crown else Lucide.Wrench,
                )
            }
            if (repository.isCached) {
                RepositoryChip(label = "cached")
            }
        }
    }
}

@Composable
private fun RepositoryChip(
    label: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(PaddingValues(horizontal = 10.dp, vertical = 4.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun RecordSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val cardShape = RoundedCornerShape(16.dp)
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
private fun RecordSettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

@Composable
private fun SegmentedColumn(
    label: String,
    description: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        content()
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        )
    }
}

@Composable
private fun <T> SegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
    subLabel: ((T) -> String?)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape),
    ) {
        options.forEachIndexed { index, (label, value) ->
            val isSelected = selected == value
            val sub = subLabel?.invoke(value)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
                    )
                    .clickable(enabled = enabled) { onSelected(value) }
                    .padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                    )
                    if (sub != null) {
                        Text(
                            text = sub,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        )
                    }
                }
            }
            if (index < options.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
    }
}
