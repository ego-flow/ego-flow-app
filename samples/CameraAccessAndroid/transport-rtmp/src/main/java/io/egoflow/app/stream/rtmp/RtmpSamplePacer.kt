package io.egoflow.app.stream.rtmp

import kotlin.math.min

private const val NANOS_PER_MICROSECOND = 1_000L
private const val DEFAULT_MAX_SLEEP_SLICE_NS = 50_000_000L

/**
 * Prevents buffered media from being published faster than its presentation timeline.
 *
 * The anchor is the first successful send in the session. Every later sample is released against
 * that absolute wall-clock/media-time mapping. Socket-write and scheduler overhead therefore do
 * not accumulate into permanent sender lag, while a sample whose media timestamp is ahead of wall
 * time still waits. If delivery was temporarily late, queued samples may catch up only as far as
 * the original timeline; they can never run ahead of it.
 *
 * [maxSleepSliceNs] bounds each individual sleep so even a large media-time gap remains promptly
 * cancellable without compressing that gap. This class is owned by T_sender and is intentionally
 * not synchronized.
 */
internal class RtmpSamplePacer(
    private val nowNs: () -> Long = System::nanoTime,
    private val sleepNs: (Long) -> Unit = ::sleepPrecisely,
    private val maxSleepSliceNs: Long = DEFAULT_MAX_SLEEP_SLICE_NS,
) {
    private var initialized = false
    private var originPresentationTimeUs = 0L
    private var originSentAtNs = 0L

    init {
        require(maxSleepSliceNs > 0L)
    }

    /** Returns false when the session becomes inactive or the wait is interrupted. */
    fun awaitPresentationTime(
        presentationTimeUs: Long,
        isActive: () -> Boolean,
    ): Boolean {
        if (!isActive()) return false

        var currentTimeNs = nowNs()
        if (!initialized) {
            return true
        }

        val presentationDeltaUs =
            if (presentationTimeUs > originPresentationTimeUs) {
                presentationTimeUs - originPresentationTimeUs
            } else {
                0L
            }
        val targetIntervalNs =
            if (presentationDeltaUs > Long.MAX_VALUE / NANOS_PER_MICROSECOND) {
                Long.MAX_VALUE
            } else {
                presentationDeltaUs * NANOS_PER_MICROSECOND
            }
        var remainingNs = targetIntervalNs - (currentTimeNs - originSentAtNs).coerceAtLeast(0L)
        if (remainingNs <= 0L) return true

        while (remainingNs > 0L) {
            if (!isActive()) return false
            val sleepDurationNs = min(remainingNs, maxSleepSliceNs)
            try {
                sleepNs(sleepDurationNs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            currentTimeNs = nowNs()
            remainingNs = targetIntervalNs - (currentTimeNs - originSentAtNs).coerceAtLeast(0L)
        }

        if (!isActive()) return false
        return true
    }

    /** Records the completion time of a successful media write. */
    fun markSent(presentationTimeUs: Long) {
        if (!initialized) {
            anchor(presentationTimeUs, nowNs())
        }
    }

    fun reset() {
        initialized = false
        originPresentationTimeUs = 0L
        originSentAtNs = 0L
    }

    private fun anchor(presentationTimeUs: Long, sentAtNs: Long) {
        initialized = true
        originPresentationTimeUs = presentationTimeUs
        originSentAtNs = sentAtNs
    }
}

private fun sleepPrecisely(durationNs: Long) {
    val millis = durationNs / 1_000_000L
    val nanos = (durationNs % 1_000_000L).toInt()
    Thread.sleep(millis, nanos)
}
