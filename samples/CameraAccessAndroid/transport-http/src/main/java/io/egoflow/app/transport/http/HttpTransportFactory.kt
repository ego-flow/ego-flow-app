/*
 * Factory entry point for selecting HttpTransport from the TransportFactory
 * registry that :app wires up based on SettingsManager.transportMode. Like
 * WhipTransportFactory it carries only the device-type provider: the HTTP path now
 * uploads fMP4 chunks live during capture (HttpLiveUploader), so there's no
 * post-capture file handoff and no :app dependency.
 */
package io.egoflow.app.transport.http

import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportDeps
import io.egoflow.app.core.transport.api.TransportFactory
import io.egoflow.app.core.transport.api.TransportId

class HttpTransportFactory(
    private val deviceTypeProvider: () -> String,
) : TransportFactory {
    override val id: TransportId = TransportId.HTTP

    override fun create(deps: TransportDeps): Transport = HttpTransport(
        deviceTypeProvider = deviceTypeProvider,
    )
}
