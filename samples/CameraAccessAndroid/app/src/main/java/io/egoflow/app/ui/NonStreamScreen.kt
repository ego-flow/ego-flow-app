/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Unplug
import com.composables.icons.lucide.Video
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import io.egoflow.app.R
import io.egoflow.app.ui.theme.LocalEgoFlowColors
import io.egoflow.app.wearables.WearablesViewModel
import kotlinx.coroutines.launch

// Amber pair for the "update required" banner -- fixed so it stays legible in either theme,
// mirroring the reference CameraAccess sample.
private val UpdateRequiredBackground = Color(0xFFFFF4D6)
private val UpdateRequiredForeground = Color(0xFF8A4B00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStreamScreen(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gettingStartedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showDisconnectDialog by remember { mutableStateOf(false) }
    val isDisconnectEnabled = uiState.registrationState == RegistrationState.REGISTERED
    val isUpdateRequired = uiState.isFirmwareUpdateRequired || uiState.isDatAppUpdateRequired
    val activity = LocalActivity.current
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        // Disconnect button -- top-right
        DisconnectButton(
            onClick = { showDisconnectDialog = true },
            enabled = isDisconnectEnabled,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
        )

        // Centered hero icon
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            LiveHero(isReady = uiState.hasActiveDevice)
            Spacer(modifier = Modifier.weight(1f))

            // Bottom CTA
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isUpdateRequired) {
                    UpdateRequiredBanner(
                        showFirmware = uiState.isFirmwareUpdateRequired,
                        showDatApp = uiState.isDatAppUpdateRequired,
                    )
                }
                if (uiState.isFirmwareUpdateRequired) {
                    PillButton(
                        label = stringResource(R.string.update_firmware_button_title),
                        onClick = {
                            activity?.let { viewModel.openFirmwareUpdate(it) }
                                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                if (uiState.isDatAppUpdateRequired) {
                    PillButton(
                        label = stringResource(R.string.update_dat_app_button_title),
                        onClick = {
                            activity?.let { viewModel.openDATGlassesAppUpdate(it) }
                                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                if (uiState.hasActiveDevice) {
                    PillButton(
                        label = "Start streaming",
                        onClick = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
                        enabled = !isUpdateRequired,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.waiting_for_active_device),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // Phone mode never touches the glasses, so update-required must not gate it.
                PillButton(
                    label = "Start on Phone",
                    onClick = { viewModel.navigateToPhoneMode() },
                    style = PillButtonStyle.Outlined,
                )
            }
        }

        if (uiState.isGettingStartedSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.hideGettingStartedSheet() },
                sheetState = gettingStartedSheetState,
            ) {
                GettingStartedSheetContent(
                    onContinue = {
                        scope.launch {
                            gettingStartedSheetState.hide()
                            viewModel.hideGettingStartedSheet()
                        }
                    },
                )
            }
        }

        if (showDisconnectDialog) {
            AlertDialog(
                onDismissRequest = { showDisconnectDialog = false },
                title = { Text("Disconnect glasses?") },
                text = {
                    Text("This unregisters the glasses from the app. You'll need to reconnect before you can stream again.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        activity?.let { viewModel.startUnregistration(it) }
                            ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                        showDisconnectDialog = false
                    }) {
                        Text(stringResource(R.string.unregister_button_title), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun UpdateRequiredBanner(
    showFirmware: Boolean,
    showDatApp: Boolean,
    modifier: Modifier = Modifier,
) {
    val message = when {
        showFirmware && showDatApp -> stringResource(R.string.update_required_both_message)
        showFirmware -> stringResource(R.string.update_required_firmware_message)
        else -> stringResource(R.string.update_required_dat_app_message)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(UpdateRequiredBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Lucide.CircleAlert,
            contentDescription = null,
            tint = UpdateRequiredForeground,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.update_required_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = UpdateRequiredForeground,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = UpdateRequiredForeground,
            )
        }
    }
}

@Composable
private fun LiveHero(
    isReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "live-hero")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Box(
            modifier = Modifier.size(212.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Breathing outer halo
            Box(
                modifier = Modifier
                    .size(212.dp)
                    .graphicsLayer {
                        val s = 0.9f + pulse * 0.1f
                        scaleX = s
                        scaleY = s
                        alpha = 0.3f + pulse * 0.4f
                    }
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.07f)),
            )
            // Static mid ring
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.10f)),
            )
            // Inner gradient badge
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    .border(1.dp, primary.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Video,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(52.dp),
                )
            }
        }

        // Minimal status chip
        val statusColors = LocalEgoFlowColors.current
        val dotColor = if (isReady) statusColors.statusGreen else statusColors.statusYellow
        val statusText = if (isReady) "Ready to go live" else "Waiting for glasses"
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = 0.5f + pulse * 0.5f }
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DisconnectButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, contentColor.copy(alpha = 0.5f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Lucide.Unplug,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.unregister_button_title),
            color = contentColor,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun GettingStartedSheetContent(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.getting_started_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.getting_started_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepItem(
                number = 1,
                title = stringResource(R.string.getting_started_step_repo_title),
                description = stringResource(R.string.getting_started_step_repo_desc),
            )
            StepItem(
                number = 2,
                title = stringResource(R.string.getting_started_step_live_title),
                description = stringResource(R.string.getting_started_step_live_desc),
            )
            StepItem(
                number = 3,
                title = stringResource(R.string.getting_started_step_photo_title),
                description = stringResource(R.string.getting_started_step_photo_desc),
            )
        }

        PillButton(
            label = stringResource(R.string.getting_started_continue),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        )
    }
}

@Composable
private fun StepItem(
    number: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
