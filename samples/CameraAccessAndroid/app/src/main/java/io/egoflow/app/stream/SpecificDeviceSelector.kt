package io.egoflow.app.stream

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceIdentifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SpecificDeviceSelector(private val targetDeviceId: DeviceIdentifier) : DeviceSelector {
  override fun activeDevice(): DeviceIdentifier? {
    return targetDeviceId
  }

  override fun activeDeviceFlow(): Flow<DeviceIdentifier?> {
    return Wearables.devices.map { devices ->
      if (targetDeviceId in devices) {
        targetDeviceId
      } else {
        null
      }
    }
  }
}
