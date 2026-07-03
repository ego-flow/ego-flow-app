/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

// WearablesUiState - DAT API State Management
//
// This data class aggregates DAT API state for the UI layer

package io.egoflow.app.wearables

import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: ImmutableList<DeviceIdentifier> = persistentListOf(),
    val recentError: String? = null,
    val recentSuccess: String? = null,
    val isStreaming: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val isFirmwareUpdateRequired: Boolean = false,
    val isDatAppUpdateRequired: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val canRegister: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isLoginLoading: Boolean = false,
    val selectedTab: MainTab = MainTab.LIVE,
    // Full-screen transition overlay shown while a stream is spinning up or
    // tearing down. Driven by WearablesViewModel; cleared once the underlying
    // work completes (and a minimum visible time has elapsed). See
    // StreamTransitionOverlay.
    val streamTransition: StreamTransition = StreamTransition.NONE,
) {
  val isRegistered: Boolean =
      registrationState == RegistrationState.REGISTERED ||
          registrationState == RegistrationState.UNREGISTERING
}

enum class MainTab {
  REPO,
  LIVE,
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
