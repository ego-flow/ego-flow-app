/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.wearables

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.extentos.glasses.core.DisconnectCause
import com.extentos.glasses.core.ExtentosGlasses
import com.extentos.glasses.core.GlassesState
import io.egoflow.app.auth.EgoFlowAuthClient
import io.egoflow.app.auth.EgoFlowLoginResult
import io.egoflow.app.extentos.ExtentosBootstrap
import io.egoflow.app.settings.AuthPrefs
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
    private const val TRANSITION_MIN_VISIBLE_MS = 2_000L
  }

  val glasses: ExtentosGlasses = (application as ExtentosBootstrap).glasses

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  private val authClient = EgoFlowAuthClient()
  private var transitionTimerJob: Job? = null
  private var transitionMinElapsed = false
  private var transitionWorkDone = false
  private var notificationPermissionWarningShown = false
  private var transitionFailed = false

  init {
    observeExtentosConnection()
  }

  private fun observeExtentosConnection() {
    viewModelScope.launch {
      glasses.connection.state.collect { state ->
        val previousStatus = _uiState.value.connectionStatus
        val status = state.toConnectionStatus()
        Log.i(TAG, "Extentos connection state: $previousStatus -> $status")
        _uiState.update { it.copy(connectionStatus = status) }

        if (state is GlassesState.Disconnected && status != previousStatus) {
          disconnectedMessage(state.cause)?.let(::setRecentError)
        }
      }
    }
  }

  fun disconnectGlasses() {
    viewModelScope.launch {
      glasses.connection.disconnect()
    }
  }

  fun navigateToStreaming() {
    if (!_uiState.value.connectionStatus.canStream) {
      setRecentError("Connect your glasses before starting a glasses stream.")
      return
    }
    beginStreamStart()
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = false) }
  }

  fun navigateToPhoneMode() {
    beginStreamStart()
    _uiState.update { it.copy(isStreaming = true, isPhoneMode = true) }
  }

  fun navigateToDeviceSelection() {
    transitionTimerJob?.cancel()
    _uiState.update {
      it.copy(isStreaming = false, isPhoneMode = false, streamTransition = StreamTransition.NONE)
    }
  }

  private fun beginStreamStart() = startTransition(StreamTransition.STARTING)

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

  fun onStreamStarted() {
    if (_uiState.value.streamTransition != StreamTransition.STARTING) return
    transitionWorkDone = true
    finishTransitionIfReady()
  }

  fun onStreamStopped() {
    if (_uiState.value.streamTransition != StreamTransition.STOPPING) return
    transitionWorkDone = true
    finishTransitionIfReady()
  }

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
      StreamTransition.STARTING ->
          if (transitionFailed) {
            navigateToDeviceSelection()
          } else {
            _uiState.update { it.copy(streamTransition = StreamTransition.NONE) }
          }
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

  fun onPermissionsResult(permissionsResult: Map<String, Boolean>) {
    if (permissionsResult.values.all { it }) return
    setRecentError("Allow camera and Bluetooth permissions to connect and stream from glasses.")
  }

  fun onNotificationPermissionResult(granted: Boolean) {
    _uiState.update { it.copy(hasNotificationPermission = granted) }
    if (!granted && !notificationPermissionWarningShown) {
      notificationPermissionWarningShown = true
      setRecentError("Allow notifications in system settings to see active streaming status.")
    }
  }

  fun login(
      baseUrl: String,
      userId: String,
      password: String,
      rememberMe: Boolean,
  ) {
    _uiState.update { it.copy(isLoginLoading = true) }
    viewModelScope.launch {
      when (val result = authClient.login(baseUrl, userId, password)) {
        is EgoFlowLoginResult.Success -> {
          AuthPrefs.egoFlowApiBaseUrl = baseUrl.trim()
          AuthPrefs.egoFlowUserId = result.userId
          AuthPrefs.egoFlowPassword = password.trim()
          AuthPrefs.rememberMe = rememberMe
          AuthPrefs.authDisplayName = result.displayName.orEmpty()
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
    AuthPrefs.rememberMe = false
    AuthPrefs.authDisplayName = ""
    _uiState.update { it.copy(isLoggedIn = false) }
  }

  private fun disconnectedMessage(cause: DisconnectCause): String? =
      when (cause) {
        DisconnectCause.UserRequested -> null
        DisconnectCause.HingesClosed ->
            "Glasses disconnected because they were folded. Open them and reconnect."
        DisconnectCause.ThermalCritical ->
            "Glasses disconnected to cool down. Wait a moment before reconnecting."
        DisconnectCause.DeviceDroppedConnection ->
            "Connection to the glasses was lost. Keep them nearby and reconnect."
        is DisconnectCause.TransportFailure ->
            "The glasses connection failed. Check Bluetooth and try again."
        is DisconnectCause.SimulatorBrowserClosed,
        is DisconnectCause.SimulatorMeterExhausted,
        is DisconnectCause.SimulatorSessionExpired ->
            "The glasses session ended. Reconnect before starting a stream."
      }
}
