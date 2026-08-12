/*
 * Construction-time injection for Transport implementations.
 *
 * Application class builds a `TransportDeps`, picks a factory
 * based on `SettingsManager.transportMode`, and hands the
 * resulting `Transport` to whichever ViewModel needs it. Per-mode
 * config (publish base URL, API keys, etc.) is each factory's own
 * problem -- it reads from SharedPreferences via the supplied
 * Context. Keeping mode-specific knobs out of TransportDeps stops
 * this contract from accreting fields every time a new transport
 * lands.
 */

package io.egoflow.app.core.transport.api

import android.content.Context

/** Carries dependencies that every transport needs regardless of
 *  protocol. Add to this only when something is truly universal. */
data class TransportDeps(
    val context: Context,
)

interface TransportFactory {
  val id: TransportId

  fun create(deps: TransportDeps): Transport
}
