/*
 * Factory entry point for selecting RtmpTransport from the
 * TransportFactory registry that :app's Application class wires up
 * based on SettingsManager.transportMode. The factory carries the
 * RTMP-specific dependencies (device-type provider, audio-enabled
 * provider) that don't fit in the generic TransportDeps container.
 */
package io.egoflow.app.transport.rtmp

import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportDeps
import io.egoflow.app.core.transport.api.TransportFactory
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.stream.rtmp.RtmpAudioSource

class RtmpTransportFactory(
    private val deviceTypeProvider: () -> String,
    private val audioEnabledProvider: () -> Boolean,
    private val audioSourceProvider: () -> RtmpAudioSource = { RtmpAudioSource.AUTO },
) : TransportFactory {
    override val id: TransportId = TransportId.RTMP

    override fun create(deps: TransportDeps): Transport = RtmpTransport(
        deviceTypeProvider = deviceTypeProvider,
        audioEnabledProvider = audioEnabledProvider,
        context = deps.context,
        audioSourceProvider = audioSourceProvider,
    )
}
