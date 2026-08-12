/*
 * Factory entry point for selecting WhipTransport from the TransportFactory
 * registry that :app wires up based on SettingsManager.transportMode. WHIP is
 * video-only for now, so unlike RtmpTransportFactory it carries no audio
 * providers -- only the device-type provider that doesn't fit in TransportDeps.
 */
package io.egoflow.app.transport.whip

import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportDeps
import io.egoflow.app.core.transport.api.TransportFactory
import io.egoflow.app.core.transport.api.TransportId

class WhipTransportFactory(
    private val deviceTypeProvider: () -> String,
) : TransportFactory {
    override val id: TransportId = TransportId.WHIP

    override fun create(deps: TransportDeps): Transport = WhipTransport(
        deviceTypeProvider = deviceTypeProvider,
        context = deps.context,
    )
}
