/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

// App-level state derived from Extentos connection state plus EgoFlow UI state.

package io.egoflow.app.wearables

data class WearablesUiState(
    val connectionStatus: ExtentosConnectionStatus = ExtentosConnectionStatus.NOT_REGISTERED,
    val recentError: String? = null,
    val recentSuccess: String? = null,
    val isStreaming: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isLoginLoading: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val selectedTab: MainTab = MainTab.RECORD,
    // Full-screen transition overlay shown while a stream is spinning up or
    // tearing down. Driven by WearablesViewModel; cleared once the underlying
    // work completes (and a minimum visible time has elapsed). See
    // StreamTransitionOverlay.
    val streamTransition: StreamTransition = StreamTransition.NONE,
) {
  val hasActiveDevice: Boolean
    get() = connectionStatus.canStream
}

enum class MainTab {
  REPO,
  RECORD,
  SETTINGS,
}

// Phase of the full-screen stream transition overlay.
//  STARTING -- shown from "Start streaming"/"Start on Phone" until the active
//              transport session reaches Streaming (or the start fails).
//  STOPPING -- shown from "Stop" until stopSession() has safely torn down.
//  NONE     -- no overlay.
enum class StreamTransition {
  NONE,
  STARTING,
  STOPPING,
}
