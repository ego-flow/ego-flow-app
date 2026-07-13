/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.CAMERA
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.egoflow.app.settings.AuthPrefs
import io.egoflow.app.settings.RepoPrefs
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.ui.CameraAccessScaffold
import io.egoflow.app.ui.PermissionIntroItem
import io.egoflow.app.ui.PermissionIntroScreen
import io.egoflow.app.ui.PermissionIntroType
import io.egoflow.app.ui.theme.EgoFlowTheme
import io.egoflow.app.ui.theme.ThemePreference
import io.egoflow.app.wearables.WearablesViewModel

private data class RuntimePermissionDisclosure(
    val permission: String,
    val type: PermissionIntroType,
    val required: Boolean,
)

private data class PermissionGateUiState(
    val showIntro: Boolean = false,
    val introItems: List<PermissionIntroItem> = emptyList(),
    val requestPermissions: List<String> = emptyList(),
    val canContinueWithoutNotifications: Boolean = false,
)

class MainActivity : ComponentActivity() {
  companion object {
    private val REQUIRED_PERMISSION_DISCLOSURES =
        listOf(
            RuntimePermissionDisclosure(CAMERA, PermissionIntroType.CAMERA, required = true),
            RuntimePermissionDisclosure(
                RECORD_AUDIO,
                PermissionIntroType.MICROPHONE,
                required = true,
            ),
            RuntimePermissionDisclosure(
                BLUETOOTH_CONNECT,
                PermissionIntroType.BLUETOOTH,
                required = true,
            ),
            RuntimePermissionDisclosure(
                BLUETOOTH_SCAN,
                PermissionIntroType.BLUETOOTH,
                required = true,
            ),
        )

    private val OPTIONAL_PERMISSION_DISCLOSURES: List<RuntimePermissionDisclosure>
      get() =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                RuntimePermissionDisclosure(
                    POST_NOTIFICATIONS,
                    PermissionIntroType.NOTIFICATIONS,
                    required = false,
                ),
            )
          } else {
            emptyList()
          }

    private val REQUIRED_RUNTIME_PERMISSIONS: List<String>
      get() = REQUIRED_PERMISSION_DISCLOSURES.map { it.permission }

    private val REQUESTABLE_RUNTIME_PERMISSIONS: List<String>
      get() = REQUIRED_RUNTIME_PERMISSIONS + OPTIONAL_PERMISSION_DISCLOSURES.map { it.permission }
  }

  val viewModel: WearablesViewModel by viewModels()

  private val permissionCheckLauncher =
      registerForActivityResult(RequestMultiplePermissions()) {
        if (POST_NOTIFICATIONS in pendingPermissionRequest) {
          val notificationGranted = isPermissionGranted(POST_NOTIFICATIONS)
          if (!notificationGranted) {
            notificationPermissionSkipped = true
          }
          reportNotificationPermission(notificationGranted)
        }
        pendingPermissionRequest = emptyList()
        permissionPromptError =
            if (missingRequiredDisclosures().isEmpty()) {
              null
            } else {
              getString(R.string.permission_intro_required_error)
            }
        refreshPermissionGate()
      }

  private var permissionGateUiState by mutableStateOf(PermissionGateUiState())
  private var permissionPromptError by mutableStateOf<String?>(null)
  private var notificationPermissionSkipped by mutableStateOf(false)
  private var pendingPermissionRequest: List<String> = emptyList()
  private var requiredPermissionsReportedGranted = false
  private var lastReportedNotificationPermission: Boolean? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Initialize settings with app context
    SettingsManager.init(this)
    AuthPrefs.init(this)
    RepoPrefs.init(this)

    // Keep screen on while streaming
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    refreshPermissionGate()
    setContent {
      val themePreference = SettingsManager.themeMode
      val isDark = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
      }
      enableEdgeToEdge(
          statusBarStyle = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
          },
          navigationBarStyle = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
          },
      )
      EgoFlowTheme(themePreference = themePreference) {
        val permissionGate = permissionGateUiState
        if (permissionGate.showIntro) {
          PermissionIntroScreen(
              permissions = permissionGate.introItems,
              onAllowPermissions = ::requestMissingRuntimePermissions,
              onContinueWithoutNotifications =
                  if (permissionGate.canContinueWithoutNotifications) {
                    ::continueWithoutNotifications
                  } else {
                    null
                  },
              errorMessage = permissionPromptError,
          )
        } else {
          CameraAccessScaffold(viewModel = viewModel)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refreshPermissionGate()
  }

  private fun requestMissingRuntimePermissions() {
    val permissionsToRequest =
        permissionGateUiState.requestPermissions.filter { permission ->
          !isPermissionGranted(permission) && permission in REQUESTABLE_RUNTIME_PERMISSIONS
        }
    if (permissionsToRequest.isEmpty()) {
      refreshPermissionGate()
      return
    }

    pendingPermissionRequest = permissionsToRequest
    permissionPromptError = null
    permissionCheckLauncher.launch(permissionsToRequest.toTypedArray())
  }

  private fun continueWithoutNotifications() {
    notificationPermissionSkipped = true
    permissionPromptError = null
    reportNotificationPermission(false)
    refreshPermissionGate()
  }

  private fun refreshPermissionGate() {
    val missingRequired = missingRequiredDisclosures()
    if (missingRequired.isEmpty()) {
      permissionPromptError = null
    }
    val missingOptional =
        if (notificationPermissionSkipped) {
          emptyList()
        } else {
          OPTIONAL_PERMISSION_DISCLOSURES.filter { !isPermissionGranted(it.permission) }
        }
    val introDisclosures = missingRequired + missingOptional

    permissionGateUiState =
        PermissionGateUiState(
            showIntro = introDisclosures.isNotEmpty(),
            introItems =
                introDisclosures.distinctBy { it.type }.map { disclosure ->
                  PermissionIntroItem(
                      type = disclosure.type,
                      required = disclosure.required,
                  )
                },
            requestPermissions = introDisclosures.map { it.permission },
            canContinueWithoutNotifications =
                missingOptional.any { it.permission == POST_NOTIFICATIONS },
        )

    if (missingRequired.isEmpty()) {
      markRequiredPermissionsGranted()
    } else {
      requiredPermissionsReportedGranted = false
    }

    if (OPTIONAL_PERMISSION_DISCLOSURES.all { isPermissionGranted(it.permission) }) {
      reportNotificationPermission(true)
    }
  }

  private fun missingRequiredDisclosures(): List<RuntimePermissionDisclosure> =
      REQUIRED_PERMISSION_DISCLOSURES.filter { !isPermissionGranted(it.permission) }

  private fun isPermissionGranted(permission: String): Boolean =
      ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  private fun markRequiredPermissionsGranted() {
    if (requiredPermissionsReportedGranted) {
      return
    }
    requiredPermissionsReportedGranted = true
    val requiredPermissionsResult = REQUIRED_RUNTIME_PERMISSIONS.associateWith { true }
    viewModel.onPermissionsResult(requiredPermissionsResult)
  }

  private fun reportNotificationPermission(granted: Boolean) {
    if (lastReportedNotificationPermission == granted) {
      return
    }
    lastReportedNotificationPermission = granted
    viewModel.onNotificationPermissionResult(granted)
  }
}
