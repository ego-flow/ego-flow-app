package io.egoflow.app.stream.rtmp

internal data class RtmpPreparedVideoSample(
    val sequenceHeader: ByteArray?,
    val inBandParameterSets: List<ByteArray>,
)

/**
 * T_sender-owned codec-config state for [RtmpPublisher].
 *
 * A later Enhanced RTMP H.265 SequenceStart is ignored by the MediaMTX/gortmplib runtime reader.
 * Raw MediaCodec restarts therefore repeat the updated parameter sets once in the following IDR.
 * Initial configs do not need that workaround, and the glasses' pre-encoded HEVC path explicitly
 * opts out so its access units retain their existing bytes.
 */
internal class RtmpVideoPublishState {
    private var pendingSequenceHeader: ByteArray? = null
    private var pendingInBandParameterSets: List<ByteArray> = emptyList()
    private var waitingForVideoKeyFrame = true
    private var hasPublishedAnyVideoSample = false

    var hasPublishedVideoSample: Boolean = false
        private set

    fun queueConfig(
        videoCodec: RtmpVideoCodec,
        config: RtmpVideoCodecConfig,
        refreshH265ParameterSetsInBand: Boolean,
    ) {
        pendingSequenceHeader = config.sequenceHeader
        pendingInBandParameterSets =
            if (
                videoCodec == RtmpVideoCodec.H265 &&
                refreshH265ParameterSetsInBand &&
                hasPublishedAnyVideoSample
            ) {
                config.inBandParameterSets
            } else {
                emptyList()
            }
        hasPublishedVideoSample = false
        waitingForVideoKeyFrame = true
    }

    fun canSendSample(isKeyFrame: Boolean): Boolean = !waitingForVideoKeyFrame || isKeyFrame

    fun prepareSample(isKeyFrame: Boolean): RtmpPreparedVideoSample? {
        if (!canSendSample(isKeyFrame)) return null
        val prepared =
            RtmpPreparedVideoSample(
                sequenceHeader = pendingSequenceHeader,
                inBandParameterSets = pendingInBandParameterSets,
            )
        pendingSequenceHeader = null
        pendingInBandParameterSets = emptyList()
        waitingForVideoKeyFrame = false
        hasPublishedVideoSample = true
        hasPublishedAnyVideoSample = true
        return prepared
    }

    fun reset() {
        pendingSequenceHeader = null
        pendingInBandParameterSets = emptyList()
        waitingForVideoKeyFrame = true
        hasPublishedVideoSample = false
        hasPublishedAnyVideoSample = false
    }
}
