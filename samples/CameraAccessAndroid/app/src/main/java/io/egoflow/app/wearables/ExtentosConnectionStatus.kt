package io.egoflow.app.wearables

import com.extentos.glasses.core.GlassesState

enum class ExtentosConnectionStatus(val canStream: Boolean) {
  NOT_REGISTERED(false),
  REGISTERED(false),
  DEVICE_DISCOVERED(false),
  CONNECTING(false),
  ACTIVE(true),
  DISCONNECTED(false),
}

internal fun GlassesState.toConnectionStatus(): ExtentosConnectionStatus =
    when (this) {
      GlassesState.NotRegistered -> ExtentosConnectionStatus.NOT_REGISTERED
      GlassesState.Registered -> ExtentosConnectionStatus.REGISTERED
      is GlassesState.DeviceDiscovered -> ExtentosConnectionStatus.DEVICE_DISCOVERED
      is GlassesState.Connecting -> ExtentosConnectionStatus.CONNECTING
      is GlassesState.Active -> ExtentosConnectionStatus.ACTIVE
      is GlassesState.Disconnected -> ExtentosConnectionStatus.DISCONNECTED
    }
