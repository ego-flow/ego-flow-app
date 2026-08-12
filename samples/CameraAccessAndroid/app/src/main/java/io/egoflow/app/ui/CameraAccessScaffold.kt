/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

// CameraAccessScaffold - DAT Application Navigation Orchestrator
//
// This scaffold demonstrates a typical DAT application navigation pattern based on device
// registration and streaming states from the DAT API.
//
// DAT State-Based Navigation:
// - HomeScreen: When NOT registered (uiState.isRegistered = false) Shows initial registration UI
//   calling Wearables.startRegistration()
// - NonStreamScreen: When registered (uiState.isRegistered = true) but not streaming Shows device
//   selection, permission checking, and pre-streaming setup
// - StreamScreen: When actively streaming (uiState.isStreaming = true) Shows live video from
//   the camera Stream.videoStream and photo capture UI

package io.egoflow.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import io.egoflow.app.settings.AuthPrefs
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.ui.theme.EgoFlowTheme
import io.egoflow.app.ui.theme.ThemePreference
import io.egoflow.app.wearables.WearablesViewModel

private enum class SnackbarTone {
  ERROR,
  SUCCESS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAccessScaffold(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  var snackbarTone by remember { mutableStateOf(SnackbarTone.ERROR) }
  var themeMode by remember { mutableStateOf(SettingsManager.themeMode) }
  var showMicDiagnostics by remember { mutableStateOf(false) }

  // Observe camera permission errors and show snackbar
  LaunchedEffect(uiState.recentError) {
    uiState.recentError?.let { errorMessage ->
      snackbarTone = SnackbarTone.ERROR
      snackbarHostState.showSnackbar(errorMessage)
      viewModel.clearRecentError()
    }
  }

  LaunchedEffect(uiState.recentSuccess) {
    uiState.recentSuccess?.let { successMessage ->
      snackbarTone = SnackbarTone.SUCCESS
      snackbarHostState.showSnackbar(successMessage)
      viewModel.clearRecentSuccess()
    }
  }

  // Auto-login: if rememberMe was set and credentials are stored, attempt silent login.
  LaunchedEffect(Unit) {
    if (AuthPrefs.rememberMe && AuthPrefs.hasEgoFlowBackendConfig()) {
      viewModel.login(
          baseUrl = AuthPrefs.egoFlowApiBaseUrl,
          userId = AuthPrefs.egoFlowUserId,
          password = AuthPrefs.egoFlowPassword,
          rememberMe = true,
      )
    }
  }

  EgoFlowTheme(themePreference = themeMode) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(modifier = Modifier.fillMaxSize()) {
        when {
          !uiState.isLoggedIn ->
              LoginScreen(
                  onLogin = { baseUrl, userId, password, rememberMe ->
                    viewModel.login(baseUrl, userId, password, rememberMe)
                  },
                  isLoading = uiState.isLoginLoading,
              )
          uiState.isSettingsVisible && showMicDiagnostics ->
              MicDiagnosticsScreen(onBack = { showMicDiagnostics = false })
          uiState.isSettingsVisible ->
              SettingsScreen(
                  onBack = { viewModel.hideSettings() },
                  onLogout = { viewModel.logout() },
                  onThemeChanged = { themeMode = it },
                  onOpenMicDiagnostics = { showMicDiagnostics = true },
                  streamingActive = uiState.isStreaming,
              )
          else ->
              MainTabScreen(
                  viewModel = viewModel,
                  onRequestWearablesPermission = onRequestWearablesPermission,
                  onThemeChanged = { themeMode = it },
              )
        }

        // Above the screens (covers tab bar / top bar during a transition) but
        // below the snackbar so a "stopped" confirmation stays visible.
        StreamTransitionOverlay(transition = uiState.streamTransition)

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
            snackbar = { data ->
              val isError = snackbarTone == SnackbarTone.ERROR
              Snackbar(
                  shape = RoundedCornerShape(24.dp),
                  containerColor =
                      if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                      } else {
                        MaterialTheme.colorScheme.primaryContainer
                      },
                  contentColor =
                      if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                      } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                      },
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                      imageVector = if (isError) Lucide.CircleAlert else Lucide.CircleCheck,
                      contentDescription =
                          if (isError) "Camera Access error" else "Camera Access success",
                      tint =
                          if (isError) {
                            MaterialTheme.colorScheme.error
                          } else {
                            MaterialTheme.colorScheme.primary
                          },
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(data.visuals.message)
                }
              }
            },
        )

      }
    }
  }
}
