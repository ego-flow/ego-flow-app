package io.egoflow.app.stream.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * One unit of work flowing from the encoder thread (T_codec) to the
 * sender thread (T_sender) inside [RtmpStreamer].
 *
 * Every item carries the [sessionId] of the session that produced it
 * so T_sender can silently skip stale items belonging to a session
 * that's already been torn down -- without consulting shared state.
 *
 * Lifecycle items ([Connect], [Close], [Poison]) are never dropped;
 * data items ([VideoSample], [AudioSample]) may be dropped at the
 * queue boundary when the queue is full (see [tryEnqueueData]).
 */
internal sealed class SendItem {
    abstract val sessionId: Long

    data class Connect(
        override val sessionId: Long,
        val url: String,
        val codec: RtmpVideoCodec,
        val diagnosticsContext: RtmpPublishDiagnosticsContext?,
    ) : SendItem()

    data class VideoConfig(
        override val sessionId: Long,
        val codec: RtmpVideoCodec,
        val format: MediaFormat,
    ) : SendItem()

    data class VideoSample(
        override val sessionId: Long,
        val codec: RtmpVideoCodec,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val isKeyFrame: Boolean,
    ) : SendItem()

    data class AudioConfig(
        override val sessionId: Long,
        val format: MediaFormat,
    ) : SendItem()

    data class AudioSample(
        override val sessionId: Long,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
    ) : SendItem()

    data class Close(override val sessionId: Long) : SendItem()

    object Poison : SendItem() {
        override val sessionId: Long = 0L
    }
}

/** Outcome of [tryEnqueueData]. The caller maps these to counters + diagnostics. */
internal enum class EnqueueOutcome {
    /** Item was placed on the queue with no eviction. */
    Accepted,
    /** Queue was full; a non-keyframe video sample was evicted to make room for this item. */
    EvictedNonKeyVideo,
    /** Queue was full; an oldest audio sample was evicted to make room for this item. */
    EvictedOldestAudio,
    /** Queue was full and no eviction candidate matched -- the incoming item itself was dropped. */
    DroppedSelf,
}

/**
 * Enqueue a data item ([SendItem.VideoSample]/[SendItem.AudioSample]/[SendItem.VideoConfig]/
 * [SendItem.AudioConfig]) onto [queue], applying drop policy when full:
 *
 * - VideoSample: evict oldest non-keyframe VideoSample; else evict oldest AudioSample; else drop self.
 * - AudioSample: evict oldest AudioSample; else drop self.
 * - VideoConfig/AudioConfig: caller should route through [tryEnqueueLifecycle] instead -- they're
 *   handled here only as a safety net (treated like a never-drop lifecycle item via blocking put).
 *
 * Pure function -- no logging or counter side effects. The caller wires those up.
 */
internal fun tryEnqueueData(queue: LinkedBlockingDeque<SendItem>, item: SendItem): EnqueueOutcome {
    if (queue.offerLast(item)) return EnqueueOutcome.Accepted
    return when (item) {
        is SendItem.VideoSample -> {
            if (evictNonKeyframeVideo(queue) && queue.offerLast(item)) {
                EnqueueOutcome.EvictedNonKeyVideo
            } else if (evictOldestAudio(queue) && queue.offerLast(item)) {
                EnqueueOutcome.EvictedOldestAudio
            } else {
                EnqueueOutcome.DroppedSelf
            }
        }
        is SendItem.AudioSample -> {
            if (evictOldestAudio(queue) && queue.offerLast(item)) {
                EnqueueOutcome.EvictedOldestAudio
            } else {
                EnqueueOutcome.DroppedSelf
            }
        }
        else -> {
            // Configs land here only if the caller bypassed tryEnqueueLifecycle.
            // Force-add to preserve correctness.
            tryEnqueueLifecycle(queue, item, lifecycleOfferTimeoutMs = 0L)
            EnqueueOutcome.Accepted
        }
    }
}

/**
 * Enqueue a lifecycle item ([SendItem.Connect]/[SendItem.Close]/[SendItem.VideoConfig]/
 * [SendItem.AudioConfig]/[SendItem.Poison]). Lifecycle items are never dropped: wait briefly, then
 * evict pending data items to make room, then blocking-put.
 *
 * Returns `true` if any data items were evicted to make room.
 */
internal fun tryEnqueueLifecycle(
    queue: LinkedBlockingDeque<SendItem>,
    item: SendItem,
    lifecycleOfferTimeoutMs: Long,
): Boolean {
    if (lifecycleOfferTimeoutMs > 0L && queue.offerLast(item, lifecycleOfferTimeoutMs, TimeUnit.MILLISECONDS)) {
        return false
    }
    if (lifecycleOfferTimeoutMs == 0L && queue.offerLast(item)) {
        return false
    }
    val evictedV = queue.removeIf { it is SendItem.VideoSample }
    val evictedA = queue.removeIf { it is SendItem.AudioSample }
    try {
        queue.putLast(item)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    return evictedV || evictedA
}

private fun evictNonKeyframeVideo(queue: LinkedBlockingDeque<SendItem>): Boolean {
    val iterator = queue.iterator()
    while (iterator.hasNext()) {
        val candidate = iterator.next()
        if (candidate is SendItem.VideoSample && !candidate.isKeyFrame) {
            iterator.remove()
            return true
        }
    }
    return false
}

private fun evictOldestAudio(queue: LinkedBlockingDeque<SendItem>): Boolean {
    val iterator = queue.iterator()
    while (iterator.hasNext()) {
        if (iterator.next() is SendItem.AudioSample) {
            iterator.remove()
            return true
        }
    }
    return false
}
