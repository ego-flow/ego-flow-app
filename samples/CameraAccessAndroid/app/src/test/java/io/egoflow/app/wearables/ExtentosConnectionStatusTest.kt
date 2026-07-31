package io.egoflow.app.wearables

import com.extentos.glasses.core.ActiveState
import com.extentos.glasses.core.CameraStatus
import com.extentos.glasses.core.DeviceInfo
import com.extentos.glasses.core.DeviceType
import com.extentos.glasses.core.DisconnectCause
import com.extentos.glasses.core.GlassesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtentosConnectionStatusTest {
  @Test
  fun `maps all non-active connection phases`() {
    assertEquals(
        ExtentosConnectionStatus.NOT_REGISTERED,
        GlassesState.NotRegistered.toConnectionStatus(),
    )
    assertEquals(
        ExtentosConnectionStatus.REGISTERED,
        GlassesState.Registered.toConnectionStatus(),
    )
    assertEquals(
        ExtentosConnectionStatus.DEVICE_DISCOVERED,
        GlassesState.DeviceDiscovered("device-1").toConnectionStatus(),
    )
    assertEquals(
        ExtentosConnectionStatus.CONNECTING,
        GlassesState.Connecting("device-1").toConnectionStatus(),
    )
    assertEquals(
        ExtentosConnectionStatus.DISCONNECTED,
        GlassesState.Disconnected(DisconnectCause.UserRequested).toConnectionStatus(),
    )
  }

  @Test
  fun `only active Extentos state enables glasses streaming`() {
    val active =
        GlassesState.Active(
            ActiveState.Connected(
                device =
                    DeviceInfo(
                        id = "device-1",
                        modelName = "Ray-Ban Meta",
                        firmwareVersion = "test",
                        deviceType = DeviceType.META_RAYBAN,
                        vendor = "Meta",
                        modelId = "test-model",
                    ),
                // 2.0.x replaced the captureReady boolean with a three-state
                // CameraStatus (Ready / Starting / Broken). The old boolean
                // conflated "starting" with "broken", which is why it changed;
                // only Broken should ever surface an amber warning to a user.
                camera = CameraStatus.READY,
            )
        )

    assertEquals(ExtentosConnectionStatus.ACTIVE, active.toConnectionStatus())
    assertTrue(active.toConnectionStatus().canStream)
    assertFalse(GlassesState.Registered.toConnectionStatus().canStream)
  }

  @Test
  fun `connecting during capture is transient and does not stop the stream`() {
    assertFalse(GlassesState.Connecting("device-1").shouldStopGlassesCapture())
  }

  @Test
  fun `explicit disconnect during capture stops the stream`() {
    assertTrue(
        GlassesState.Disconnected(DisconnectCause.DeviceDroppedConnection)
            .shouldStopGlassesCapture()
    )
  }
}
