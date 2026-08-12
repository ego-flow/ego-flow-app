/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

// WearablesViewModel - Core DAT SDK Integration
//
// This ViewModel demonstrates the core DAT API patterns for:
// - Device registration and unregistration using the DAT SDK
// - Permission management for wearable devices
// - Device discovery and state management

package io.egoflow.app.wearables

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import io.egoflow.app.auth.EgoFlowAuthClient
import io.egoflow.app.auth.EgoFlowLoginResult
import io.egoflow.app.settings.AuthPrefs
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "WearablesViewModel"
    // Each transition overlay stays up for at least this long so a fast
    // start/stop doesn't flash a spinner for a single frame.
    private const val TRANSITION_MIN_VISIBLE_MS = 2_000L
  }

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  private val authClient = EgoFlowAuthClient()

  // AutoDeviceSelector automatically selects the first available wearable device.
  // Lazy because AutoDeviceSelector() requires Wearables.initialize() to have run first (0.6.0).
  val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var deviceSelectorJob: Job? = null

  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()
  private val deviceCompatibility = mutableMapOf<DeviceIdentifier, DeviceCompatibility>()

  // ----- Stream transition overlay -----
  //
  // A transition (STARTING/STOPPING) clears only once BOTH conditions hold:
  //  - the underlying work finished (transportWorkDone, set by StreamViewModel)
  //  - the minimum visible time elapsed (transitionMinElapsed, set by the timer)
  // This AND keeps the overlay on screen for at least TRANSITION_MIN_VISIBLE_MS
  // even when the work completes instantly. The timer runs in viewModelScope
  // (app-scoped), so it survives StreamViewModel being cleared mid-stop.
  private var transitionTimerJob: Job? = null
  private var transitionMinElapsed = false
  private var transitionWorkDone = false
  private var notificationPermissionWarningShown = false
  // A terminal failure observed while a transition overlay is up. When set, a
  // STARTING transition resolves by returning to device selection (instead of
  // staying on the stream screen) once the min-visible timer elapses -- so a
  // start that fails instantly still shows the start UI for the full window
  // rather than flashing back home.
  private var transitionFailed = false

  private fun startMonitoring() {
    if (monitoringStarted) {
      return
    }
    monitoringStarted = true

    // Monitor device selector for active device
    deviceSelectorJob =
        viewModelScope.launch {
          deviceSelector.activeDeviceFlow().collect { device ->
            _uiState.update { it.copy(hasActiveDevice = device != null) }
          }
        }

    // This allows the app to react to registration changes (registered, unregistered, etc.)
    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        val previousState = _uiState.value.registrationState
        val showGettingStartedSheet =
            value == RegistrationState.REGISTERED && previousState == RegistrationState.REGISTERING
        _uiState.update {
          it.copy(registrationState = value, isGettingStartedSheetVisible = showGettingStartedSheet)
        }
      }
    }
    // This automatically updates when devices are discovered, connected, or disconnected
    viewModelScope.launch {
      Wearables.devices.collect { value ->
        _uiState.update { it.copy(devices = value.toList().toImmutableList()) }
        // Monitor device metadata for compatibility issues
        monitorDeviceCompatibility(value)
      }
    }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    // Cancel monitoring jobs for devices that are no longer in the list
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { deviceId ->
      deviceMonitoringJobs[deviceId]?.cancel()
      deviceMonitoringJobs.remove(deviceId)
      deviceCompatibility.remove(deviceId)
    }
    updateFirmwareUpdateRequired()

    // Start monitoring jobs only for new devices (not already being monitored)
    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { deviceId ->
      val job =
          viewModelScope.launch {
            Wearables.devicesMetadata[deviceId]?.collect { metadata ->
              deviceCompatibility[deviceId] = metadata.compatibility
              updateFirmwareUpdateRequired()
            }
          }
      deviceMonitoringJobs[deviceId] = job
    }
  }

  private fun updateFirmwareUpdateRequired() {
    val isRequired =
        deviceCompatibility.values.any { it == DeviceCompatibility.DEVICE_UPDATE_REQUIRED }
    _uiState.update { it.copy(isFirmwareUpdateRequired = isRequired) }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  fun openFirmwareUpdate(activity: Activity) {
    Wearables.openFirmwareUpdate(activity).onFailure { error, _ -> setRecentError(error.description) }
  }

  fun openDATGlassesAppUpdate(activity: Activity) {
    Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
      setRecentError(error.description)
    }
  }

  internal fun setDatAppUpdateRequired(required: Boolean) {
    _uiState.update { it.copy(isDatAppUpdateRequired = required) }
  }

  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch {
      val permission = Permission.CAMERA // Camera permission is required for streaming
      val result = Wearables.checkPermissionStatus(permission)

      // Handle the result
      result.onFailure { error, _ ->
        setRecentError("Permission check error: ${error.description}")
        return@launch
      }

      val permissionStatus = result.getOrNull()
      if (permissionStatus == PermissionStatus.Granted) {
        beginStreamStart()
        _uiState.update { it.copy(isStreaming = true) }
        return@launch
      }

      // Request permission
      val requestedPermissionStatus = onRequestWearablesPermission(permission)
      when (requestedPermissionStatus) {
        PermissionStatus.Denied -> {
          setRecentError("Permission denied")
        }
        PermissionStatus.Granted -> {
          beginStreamStart()
          _uiState.update { it.copy(isStreaming = true) }
        }
      }
    }
  }

  fun navigateToPhoneMode() {
    beginStreamStart()
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = true) }
  }

  fun navigateToDeviceSelection() {
    // Also clears any active transition overlay -- this is the single exit used
    // by the normal stop path (after stopSession) and by every start-failure
    // path, so dismissing the overlay here covers both.
    transitionTimerJob?.cancel()
    _uiState.update {
      it.copy(isStreaming = false, isPhoneMode = false, streamTransition = StreamTransition.NONE)
    }
  }

  // [Stream transition overlay lifecycle]
  // beginStreamStart/beginStreamStop arm the overlay + min-visible timer.
  // onStreamStarted/onStreamStopped report the work as done. The overlay
  // clears only once both the timer and the work have completed.

  private fun beginStreamStart() = startTransition(StreamTransition.STARTING)

  /** Call when the user taps Stop. Arms the STOPPING overlay; navigation back
   *  to device selection happens from [onStreamStopped] once teardown finishes. */
  fun beginStreamStop() = startTransition(StreamTransition.STOPPING)

  private fun startTransition(phase: StreamTransition) {
    transitionMinElapsed = false
    transitionWorkDone = false
    transitionFailed = false
    _uiState.update { it.copy(streamTransition = phase) }
    transitionTimerJob?.cancel()
    transitionTimerJob =
        viewModelScope.launch {
          delay(TRANSITION_MIN_VISIBLE_MS)
          transitionMinElapsed = true
          finishTransitionIfReady()
        }
  }

  /** Reported by StreamViewModel when the active transport session reaches
   *  Streaming (the start has succeeded). No-op unless a start is in progress. */
  fun onStreamStarted() {
    if (_uiState.value.streamTransition != StreamTransition.STARTING) return
    transitionWorkDone = true
    finishTransitionIfReady()
  }

  /** Reported by StreamViewModel once stopSession() has finished tearing the
   *  stream down. No-op unless a stop is in progress. */
  fun onStreamStopped() {
    if (_uiState.value.streamTransition != StreamTransition.STOPPING) return
    transitionWorkDone = true
    finishTransitionIfReady()
  }

  /** Reported by StreamViewModel when a start or an active stream fails
   *  terminally. If a transition overlay is showing (e.g. the STARTING start
   *  UI), keep it up until its min-visible window elapses before bouncing back
   *  to device selection, so a fast failure doesn't flash the start UI for a
   *  single frame. With no transition in progress (a mid-stream failure),
   *  return immediately. The user-facing error is surfaced by the caller. */
  fun onStreamFailed() {
    if (_uiState.value.streamTransition == StreamTransition.NONE) {
      navigateToDeviceSelection()
      return
    }
    transitionFailed = true
    transitionWorkDone = true
    finishTransitionIfReady()
  }

  private fun finishTransitionIfReady() {
    if (!transitionMinElapsed || !transitionWorkDone) return
    when (_uiState.value.streamTransition) {
      // Start finished. On success stay on StreamScreen (just drop the
      // overlay); on failure bounce back to device selection.
      StreamTransition.STARTING ->
          if (transitionFailed) {
            navigateToDeviceSelection()
          } else {
            _uiState.update { it.copy(streamTransition = StreamTransition.NONE) }
          }
      // Stop finished -- leave the stream and clear the overlay in one step.
      StreamTransition.STOPPING -> navigateToDeviceSelection()
      StreamTransition.NONE -> Unit
    }
  }

  fun showSettings() {
    _uiState.update { it.copy(isSettingsVisible = true) }
  }

  fun hideSettings() {
    _uiState.update { it.copy(isSettingsVisible = false) }
  }

  fun selectTab(tab: MainTab) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  fun clearRecentError() {
    _uiState.update { it.copy(recentError = null) }
  }

  fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  fun clearRecentSuccess() {
    _uiState.update { it.copy(recentSuccess = null) }
  }

  fun setRecentSuccess(message: String) {
    _uiState.update { it.copy(recentSuccess = message) }
  }

  fun onPermissionsResult(permissionsResult: Map<String, Boolean>, onAllGranted: () -> Unit) {
    val granted = permissionsResult.entries.all { it.value }
    _uiState.update { it.copy(canRegister = granted) }
    if (granted) {
      onAllGranted()
      startMonitoring()
    } else {
      _uiState.update {
        it.copy(
            recentError =
                "Allow camera, microphone, and Bluetooth permissions to continue.",
        )
      }
    }
  }

  fun onNotificationPermissionResult(granted: Boolean) {
    _uiState.update { it.copy(hasNotificationPermission = granted) }
    if (!granted && !notificationPermissionWarningShown) {
      notificationPermissionWarningShown = true
      setRecentError("Allow notifications in system settings to see active streaming status.")
    }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  fun login(
      baseUrl: String,
      userId: String,
      password: String,
      rememberMe: Boolean,
  ) {
    _uiState.update { it.copy(isLoginLoading = true) }
    viewModelScope.launch {
      val result = authClient.login(baseUrl, userId, password)
      when (result) {
        is EgoFlowLoginResult.Success -> {
          AuthPrefs.saveLogin(
              apiBaseUrl = baseUrl.trim(),
              userId = result.userId,
              password = password.trim(),
              rememberMe = rememberMe,
              displayName = result.displayName.orEmpty(),
          )
          _uiState.update { it.copy(isLoggedIn = true, isLoginLoading = false) }
        }
        is EgoFlowLoginResult.Failure -> {
          setRecentError(result.message)
          _uiState.update { it.copy(isLoginLoading = false) }
        }
      }
    }
  }

  fun logout() {
    AuthPrefs.clearLogin()
    _uiState.update { it.copy(isLoggedIn = false) }
  }

  override fun onCleared() {
    super.onCleared()
    // Cancel all device monitoring jobs when ViewModel is cleared
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel()
  }
}
