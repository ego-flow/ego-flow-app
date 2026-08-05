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

/**
 * A genuine link demotion: the connection LEFT [GlassesState.Active].
 *
 * Transition-aware rather than a single-state check, because "not Active" on
 * its own is ambiguous. [GlassesState.Registered] and [GlassesState.Connecting]
 * are also the NORMAL states on the way UP, so stopping whenever the state is
 * not Active would tear the stream down during the initial handshake.
 * Arriving at them FROM Active is the opposite: the link is gone.
 *
 * That works because the SDK keeps its internal camera work inside Active.
 * Session arming, auto-recovery and stream re-arms refine
 * `ActiveState.Connected.camera` (Ready / Starting / Broken) instead of
 * regressing the top-level state, so leaving Active at all means the link
 * itself went away rather than the camera doing housekeeping.
 *
 * On Meta hardware the demotion that actually fires is `Active -> Registered`:
 * the SDK treats the BLE link state as authoritative and demotes any Active
 * state the moment the link drops. `Active -> Connecting` is covered here
 * defensively — the SDK deliberately suppresses that blip on this transport,
 * but the browser simulator can still produce it, and defending against it
 * costs nothing.
 *
 * Deliberately no grace period, reconnecting state, or timeout: stop on the
 * first demotion, let the user restart. Add debouncing only if a real soak
 * shows the link flapping.
 */
internal fun shouldStopGlassesCapture(
    previous: GlassesState?,
    current: GlassesState,
): Boolean = previous is GlassesState.Active && current !is GlassesState.Active
