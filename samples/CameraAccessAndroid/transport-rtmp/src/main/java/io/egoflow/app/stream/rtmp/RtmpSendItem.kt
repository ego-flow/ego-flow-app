package io.egoflow.app.stream.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

private const val DEFAULT_CONFIG_OFFER_TIMEOUT_MS = 500L
private const val NANOS_PER_MILLISECOND = 1_000_000L

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
        val refreshH265ParameterSetsInBand: Boolean = false,
    ) : SendItem()

    data class VideoSample(
        override val sessionId: Long,
        val codec: RtmpVideoCodec,
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val isKeyFrame: Boolean,
        // The glasses' pre-encoded HEVC pass-through path is explicitly outside the raw encoder
        // pacing contract and keeps its existing send behavior.
        val pacingEnabled: Boolean = true,
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

    data class Close(
        override val sessionId: Long,
        val completion: RtmpCloseCompletion = RtmpCloseCompletion(),
    ) : SendItem()

    object Poison : SendItem() {
        override val sessionId: Long = 0L
    }
}

/** Sender acknowledgement used to make normal RTMP shutdown observable without polling queue size. */
internal class RtmpCloseCompletion {
    private val latch = CountDownLatch(1)

    fun complete() {
        latch.countDown()
    }

    fun await(timeoutMs: Long): Boolean =
        try {
            latch.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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
    /** A prior re-encoded frame was dropped; dependent frames are suppressed until the next IDR. */
    DroppedUntilKeyFrame,
}

/**
 * Enqueue a data item ([SendItem.VideoSample]/[SendItem.AudioSample]/[SendItem.VideoConfig]/
 * [SendItem.AudioConfig]) onto [queue], applying drop policy when full:
 *
 * - Re-encoded VideoSample: evict oldest AudioSample; never evict a queued reference frame; else
 *   drop self. [RtmpReencodedVideoGate] then suppresses dependent frames through the next IDR.
 * - Pre-encoded pass-through VideoSample: retains the legacy non-keyframe/audio eviction policy.
 * - AudioSample: evict oldest AudioSample; else drop self.
 * - VideoConfig/AudioConfig: wait for the sender to free one slot for a bounded interval. Config
 *   insertion must preserve every sample already ordered before it, especially across an encoder
 *   resolution change; timeout is reported as [EnqueueOutcome.DroppedSelf] for the caller to fail
 *   the session instead of silently publishing undecodable samples.
 *
 * Pure function -- no logging or counter side effects. The caller wires those up.
 */
internal fun tryEnqueueData(
    queue: LinkedBlockingDeque<SendItem>,
    item: SendItem,
    configOfferTimeoutMs: Long = DEFAULT_CONFIG_OFFER_TIMEOUT_MS,
): EnqueueOutcome {
    if (queue.offerLast(item)) return EnqueueOutcome.Accepted
    return when (item) {
        is SendItem.VideoSample -> {
            if (!item.pacingEnabled && evictNonKeyframeVideo(queue) && queue.offerLast(item)) {
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
            try {
                if (queue.offerLast(item, configOfferTimeoutMs, TimeUnit.MILLISECONDS)) {
                    EnqueueOutcome.Accepted
                } else {
                    EnqueueOutcome.DroppedSelf
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                EnqueueOutcome.DroppedSelf
            }
        }
    }
}

/**
 * Prevents a bounded-queue overflow from publishing an undecodable tail of a locally encoded GOP.
 * Once one encoded sample cannot be queued, all following dependent samples are discarded until a
 * keyframe itself is successfully queued. The pre-encoded HEVC path (`pacingEnabled == false`) is
 * deliberately delegated to the legacy policy unchanged.
 *
 * Owned by T_codec; no synchronization is required.
 */
internal class RtmpReencodedVideoGate {
    var waitingForKeyFrame: Boolean = false
        private set

    fun tryEnqueue(
        queue: LinkedBlockingDeque<SendItem>,
        item: SendItem.VideoSample,
    ): EnqueueOutcome {
        if (!item.pacingEnabled) return tryEnqueueData(queue, item)
        if (waitingForKeyFrame && !item.isKeyFrame) {
            return EnqueueOutcome.DroppedUntilKeyFrame
        }

        val outcome = tryEnqueueData(queue, item)
        when (outcome) {
            EnqueueOutcome.DroppedSelf -> waitingForKeyFrame = true
            EnqueueOutcome.Accepted,
            EnqueueOutcome.EvictedNonKeyVideo,
            EnqueueOutcome.EvictedOldestAudio,
            -> if (item.isKeyFrame) waitingForKeyFrame = false
            EnqueueOutcome.DroppedUntilKeyFrame -> Unit
        }
        return outcome
    }

    fun reset() {
        waitingForKeyFrame = false
    }
}

/**
 * Appends a normal [SendItem.Close] behind every queued media item without removing anything.
 * A false result lets the caller enforce its stop deadline by aborting the publisher; destructive
 * queue eviction remains restricted to fatal teardown through [tryEnqueueLifecycle].
 */
internal fun tryEnqueueGracefulClose(
    queue: LinkedBlockingDeque<SendItem>,
    item: SendItem.Close,
    offerTimeoutMs: Long,
): Boolean =
    try {
        if (offerTimeoutMs > 0L) {
            queue.offerLast(item, offerTimeoutMs, TimeUnit.MILLISECONDS)
        } else {
            queue.offerLast(item)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

internal enum class RtmpGracefulCloseResult {
    Completed,
    AbortedAndCompleted,
    TimedOut,
}

internal fun RtmpGracefulCloseResult.requireCompletion() {
    when (this) {
        RtmpGracefulCloseResult.Completed -> Unit
        RtmpGracefulCloseResult.AbortedAndCompleted ->
            throw RtmpTimeoutException(
                "RTMP stop aborted before all queued media could be published.",
            )
        RtmpGracefulCloseResult.TimedOut ->
            throw RtmpTimeoutException(
                "Timed out draining queued media and waiting for RTMP sender Close acknowledgement.",
            )
    }
}

/**
 * Uses one deadline for graceful enqueue + sender acknowledgement. When that budget expires, the
 * publisher is aborted once so a pacing wait or blocking socket write can unblock, then a short
 * forced-cleanup budget is allowed to append/acknowledge the same Close marker. This coordinator
 * never evicts queued media itself; any result that required abort is surfaced as a failed stop.
 */
internal fun awaitBoundedGracefulClose(
    queue: LinkedBlockingDeque<SendItem>,
    close: SendItem.Close,
    gracefulTimeoutMs: Long,
    forcedCleanupTimeoutMs: Long,
    abortPublisher: () -> Unit,
    nowNs: () -> Long = System::nanoTime,
): RtmpGracefulCloseResult {
    val gracefulDeadlineNs = deadlineNs(nowNs(), gracefulTimeoutMs)
    var enqueued =
        tryEnqueueGracefulClose(
            queue,
            close,
            offerTimeoutMs = remainingTimeoutMs(gracefulDeadlineNs, nowNs()),
        )
    if (
        enqueued &&
        close.completion.await(remainingTimeoutMs(gracefulDeadlineNs, nowNs()))
    ) {
        return RtmpGracefulCloseResult.Completed
    }

    abortPublisher()

    val forcedDeadlineNs = deadlineNs(nowNs(), forcedCleanupTimeoutMs)
    if (!enqueued) {
        enqueued =
            tryEnqueueGracefulClose(
                queue,
                close,
                offerTimeoutMs = remainingTimeoutMs(forcedDeadlineNs, nowNs()),
            )
    }
    if (
        enqueued &&
        close.completion.await(remainingTimeoutMs(forcedDeadlineNs, nowNs()))
    ) {
        return RtmpGracefulCloseResult.AbortedAndCompleted
    }
    return RtmpGracefulCloseResult.TimedOut
}

private fun deadlineNs(nowNs: Long, timeoutMs: Long): Long {
    val timeoutNs = timeoutMs.coerceAtLeast(0L).let { millis ->
        if (millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE else millis * NANOS_PER_MILLISECOND
    }
    return if (timeoutNs == Long.MAX_VALUE || nowNs > Long.MAX_VALUE - timeoutNs) {
        Long.MAX_VALUE
    } else {
        nowNs + timeoutNs
    }
}

private fun remainingTimeoutMs(deadlineNs: Long, nowNs: Long): Long {
    val remainingNs = deadlineNs - nowNs
    if (remainingNs <= 0L) return 0L
    return (remainingNs / NANOS_PER_MILLISECOND) +
        if (remainingNs % NANOS_PER_MILLISECOND == 0L) 0L else 1L
}

/**
 * Enqueue an urgent lifecycle item ([SendItem.Connect], fatal [SendItem.Close], or [SendItem.Poison]).
 * These are never dropped: wait briefly, then evict pending data items to make room, then
 * blocking-put. Normal Close must instead use [awaitBoundedGracefulClose].
 * Codec configs must use [tryEnqueueData] so their ordering relative to queued samples is preserved.
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
