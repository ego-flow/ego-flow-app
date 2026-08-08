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

  // ── Link demotion: leaving Active is what stops a capture ──────────────
  //
  // The stop condition is a TRANSITION. A single-state check cannot express
  // it, because Registered and Connecting are also the normal states on the
  // way UP; the difference is entirely whether we arrived from Active.

  private fun active(camera: CameraStatus = CameraStatus.READY) =
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
              camera = camera,
          )
      )

  @Test
  fun `Active to Connecting stops the capture`() {
    assertTrue(shouldStopGlassesCapture(active(), GlassesState.Connecting("device-1")))
  }

  @Test
  fun `Active to Registered stops the capture`() {
    // The demotion that actually fires on Meta hardware: the SDK treats the
    // BLE link state as authoritative and demotes Active the moment it drops.
    assertTrue(shouldStopGlassesCapture(active(), GlassesState.Registered))
  }

  @Test
  fun `Active to Disconnected stops the capture`() {
    assertTrue(
        shouldStopGlassesCapture(
            active(),
            GlassesState.Disconnected(DisconnectCause.DeviceDroppedConnection),
        )
    )
  }

  @Test
  fun `the initial handshake up to Active never stops a capture`() {
    // Registered -> Connecting -> Active. None of these arrive from Active,
    // so none of them are a demotion. A first emission has no previous state.
    assertFalse(shouldStopGlassesCapture(null, GlassesState.Registered))
    assertFalse(shouldStopGlassesCapture(GlassesState.Registered, GlassesState.Connecting("device-1")))
    assertFalse(shouldStopGlassesCapture(GlassesState.Connecting("device-1"), active()))
  }

  @Test
  fun `camera arming inside Active never stops a capture`() {
    // READY -> STARTING -> READY. The SDK refines the camera status rather
    // than regressing the top-level state precisely so this is not a
    // disconnect; both endpoints are still Active, so neither is a demotion.
    assertFalse(shouldStopGlassesCapture(active(CameraStatus.READY), active(CameraStatus.STARTING)))
    assertFalse(shouldStopGlassesCapture(active(CameraStatus.STARTING), active(CameraStatus.READY)))
  }

  @Test
  fun `a broken camera is not a link demotion`() {
    // Broken means a capture failed and needs a reconnect to clear. It is a
    // camera-health signal, not a lost link, and the stream is still up.
    assertFalse(shouldStopGlassesCapture(active(CameraStatus.READY), active(CameraStatus.BROKEN)))
  }
}
