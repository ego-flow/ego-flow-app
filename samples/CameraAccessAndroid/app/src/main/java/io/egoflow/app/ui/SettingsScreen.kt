package io.egoflow.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Lucide
import com.meta.wearable.dat.camera.types.VideoQuality
import io.egoflow.app.BuildConfig
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.settings.AuthPrefs
import io.egoflow.app.settings.RepoPrefs
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.stream.rtmp.RtmpAudioSource
import io.egoflow.app.stream.rtmp.RtmpVideoCodec
import io.egoflow.app.ui.theme.ThemePreference

private const val DISABLED_ALPHA = 0.4f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {},
    onThemeChanged: (ThemePreference) -> Unit = {},
    onOpenMicDiagnostics: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    streamingActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var themeMode by remember { mutableStateOf(SettingsManager.themeMode) }
    var transportMode by remember { mutableStateOf(SettingsManager.transportMode) }
    var rtmpEnabled by remember { mutableStateOf(SettingsManager.rtmpEnabled) }
    var rtmpAudioEnabled by remember { mutableStateOf(SettingsManager.rtmpAudioEnabled) }
    var audioSource by remember { mutableStateOf(SettingsManager.audioSource) }
    var rtmpCompressVideo by remember { mutableStateOf(SettingsManager.rtmpCompressVideo) }
    var rtmpVideoCodec by remember { mutableStateOf(SettingsManager.rtmpVideoCodec) }
    var rtmpDebugOverlayEnabled by remember { mutableStateOf(SettingsManager.rtmpDebugOverlayEnabled) }
    var videoQuality by remember { mutableStateOf(SettingsManager.videoQuality) }
    var showResetDialog by remember { mutableStateOf(false) }
    var authDisplayName by remember { mutableStateOf(AuthPrefs.authDisplayName) }
    var serverUrl by remember { mutableStateOf(AuthPrefs.egoFlowApiBaseUrl) }
    var repoName by remember { mutableStateOf(RepoPrefs.egoFlowRepositoryName) }

    fun reload() {
        themeMode = SettingsManager.themeMode
        transportMode = SettingsManager.transportMode
        rtmpEnabled = SettingsManager.rtmpEnabled
        rtmpAudioEnabled = SettingsManager.rtmpAudioEnabled
        audioSource = SettingsManager.audioSource
        rtmpCompressVideo = SettingsManager.rtmpCompressVideo
        rtmpVideoCodec = SettingsManager.rtmpVideoCodec
        rtmpDebugOverlayEnabled = SettingsManager.rtmpDebugOverlayEnabled
        videoQuality = SettingsManager.videoQuality
        authDisplayName = AuthPrefs.authDisplayName
        serverUrl = AuthPrefs.egoFlowApiBaseUrl
        repoName = RepoPrefs.egoFlowRepositoryName
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            if (onBack != null) {
                EgoFlowTopAppBar(
                    title = "Settings",
                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(Lucide.ArrowLeft, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountInfoCard(
                displayName = authDisplayName.ifBlank { "Not signed in" },
                onSignOut = onLogout,
            )

            SettingsSection(label = "CONNECTION") {
                SettingsCard {
                    ConnectionRow(label = "SERVER", value = serverUrl.ifBlank { "Not configured" })
                    SettingsDivider()
                    ConnectionRow(label = "REPOSITORY", value = repoName.ifBlank { "None selected" })
                }
            }

            SettingsSection(label = "APPEARANCE") {
                SettingsCard {
                    Text(
                        text = "Theme",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    SegmentedControl(
                        options = listOf(
                            "Light" to ThemePreference.LIGHT,
                            "Dark" to ThemePreference.DARK,
                            "System" to ThemePreference.SYSTEM,
                        ),
                        selected = themeMode,
                        onSelected = {
                            themeMode = it
                            SettingsManager.themeMode = it
                            onThemeChanged(it)
                        },
                    )
                }
            }

            SettingsSection(label = "STREAMING") {
                SettingsCard {
                    SegmentedColumn(
                        label = "Video quality",
                        description = "Glasses only — higher quality uses more battery. The phone camera is fixed at 480x640.",
                        enabled = !streamingActive,
                    ) {
                        SegmentedControl(
                            options = listOf(
                                "Low" to VideoQuality.LOW,
                                "Medium" to VideoQuality.MEDIUM,
                                "High" to VideoQuality.HIGH,
                            ),
                            selected = videoQuality,
                            onSelected = {
                                videoQuality = it
                                SettingsManager.videoQuality = it
                            },
                            subLabel = {
                                when (it) {
                                    VideoQuality.LOW -> "360x640"
                                    VideoQuality.MEDIUM -> "504x896"
                                    VideoQuality.HIGH -> "720x1280"
                                }
                            },
                            enabled = !streamingActive,
                        )
                    }
                    SettingsDivider()
                    SettingsToggleRow(
                        label = "Enable publishing",
                        description = "Send live video to the configured server.",
                        checked = rtmpEnabled,
                        onCheckedChange = {
                            rtmpEnabled = it
                            SettingsManager.rtmpEnabled = it
                        },
                        enabled = !streamingActive,
                    )
                    SettingsDivider()
                    SegmentedColumn(
                        label = "Publishing protocol",
                        description = "RTMP streams over TCP. WHIP publishes over WebRTC (video only). " +
                            "HTTP records on device and uploads the file in chunks after capture (no live playback).",
                        enabled = !streamingActive && rtmpEnabled,
                    ) {
                        SegmentedControl(
                            options = listOf(
                                "RTMP" to TransportId.RTMP,
                                "WHIP" to TransportId.WHIP,
                                "HTTP" to TransportId.HTTP,
                            ),
                            selected = transportMode,
                            onSelected = {
                                transportMode = it
                                SettingsManager.transportMode = it
                            },
                            enabled = !streamingActive && rtmpEnabled,
                        )
                    }
                    SettingsDivider()
                    SettingsToggleRow(
                        label = "Stream audio",
                        description = "Capture microphone while streaming. (RTMP only.)",
                        checked = rtmpAudioEnabled,
                        onCheckedChange = {
                            rtmpAudioEnabled = it
                            SettingsManager.rtmpAudioEnabled = it
                        },
                        enabled = !streamingActive && transportMode == TransportId.RTMP,
                    )
                    SettingsDivider()
                    SegmentedColumn(
                        label = "Audio source",
                        description = "Auto uses the glasses mic when detected, otherwise the phone.",
                        enabled = rtmpAudioEnabled && !streamingActive && transportMode == TransportId.RTMP,
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
                            enabled = rtmpAudioEnabled && !streamingActive && transportMode == TransportId.RTMP,
                        )
                    }
                }
            }

            SettingsSection(label = "ADVANCED") {
                SettingsCard {
                    // HTTP runs the on-device encoder, so codec choice applies there too;
                    // WHIP negotiates H.264 over SDP, so it stays hidden. The compress lock
                    // only matters for RTMP (HTTP forces compression off).
                    val codecControlEnabled =
                        !streamingActive &&
                            transportMode != TransportId.WHIP &&
                            !(transportMode == TransportId.RTMP && rtmpCompressVideo)
                    SegmentedColumn(
                        label = "Video codec",
                        description = "H.265 reduces bandwidth. WHIP always negotiates H.264.",
                        enabled = codecControlEnabled,
                    ) {
                        SegmentedControl(
                            options = listOf(
                                "H.264" to RtmpVideoCodec.H264,
                                "H.265" to RtmpVideoCodec.H265,
                            ),
                            selected = rtmpVideoCodec,
                            onSelected = {
                                rtmpVideoCodec = it
                                SettingsManager.rtmpVideoCodec = it
                            },
                            enabled = codecControlEnabled,
                        )
                    }
                    SettingsDivider()
                    SettingsToggleRow(
                        label = "Compress on device",
                        description = "Request HEVC frames directly from the glasses. (RTMP only.)",
                        checked = rtmpCompressVideo,
                        onCheckedChange = { enabled ->
                            rtmpCompressVideo = enabled
                            SettingsManager.rtmpCompressVideo = enabled
                            if (enabled) {
                                rtmpVideoCodec = RtmpVideoCodec.H265
                                SettingsManager.rtmpVideoCodec = RtmpVideoCodec.H265
                            }
                        },
                        enabled = !streamingActive && transportMode == TransportId.RTMP,
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        label = "Show RTMP debug overlay",
                        description = "For internal diagnostics only.",
                        checked = rtmpDebugOverlayEnabled,
                        onCheckedChange = {
                            rtmpDebugOverlayEnabled = it
                            SettingsManager.rtmpDebugOverlayEnabled = it
                        },
                    )
                }
            }

            SettingsSection(label = "DIAGNOSTICS") {
                SettingsCard {
                    NavigationRow(
                        label = "Microphone diagnostics",
                        description = "List communication devices and visualize live mic input.",
                        onClick = onOpenMicDiagnostics,
                        enabled = !streamingActive,
                    )
                }
            }

            SettingsSection(label = "ABOUT") {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Version",
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        )
                    }
                    SettingsDivider()
                    Text(
                        text = "Reset to defaults",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showResetDialog = true },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Settings") },
                text = {
                    Text("This will reset all settings to the values built into the app. Your login info will be kept.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        SettingsManager.resetAll()
                        reload()
                        showResetDialog = false
                    }) {
                        Text("Reset", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun AccountInfoCard(displayName: String, onSignOut: () -> Unit) {
    val cardShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), cardShape)
            .padding(16.dp),
    ) {
        Text(
            text = "ACCOUNT INFO",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.88.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = displayName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(16.dp))
        SettingsDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sign out",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSignOut)
                .padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun ConnectionRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                letterSpacing = 0.44.sp,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
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
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
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
private fun NavigationRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick),
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
        Icon(
            imageVector = Lucide.ArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
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
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
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
