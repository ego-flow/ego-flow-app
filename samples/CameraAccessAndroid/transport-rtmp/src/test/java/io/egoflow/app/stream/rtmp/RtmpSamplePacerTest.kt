package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpSamplePacerTest {
    @Test
    fun arrivalStall_doesNotAccumulateAdditionalDelayWhileTimelineCatchesUp() {
        val time = FakeTime()
        val pacer = time.newPacer()

        assertTrue(send(pacer, 100_000L))
        time.advance(2_000_000_000L)
        assertTrue(send(pacer, 133_000L))
        assertTrue(send(pacer, 166_000L))

        assertTrue(time.sleeps.isEmpty())
    }

    @Test
    fun mediaGapWithoutArrivalGap_isReleasedAtMediaRate() {
        val time = FakeTime()
        val pacer = time.newPacer(maxSleepSliceNs = 5_000_000_000L)

        assertTrue(send(pacer, 100_000L))
        assertTrue(send(pacer, 4_100_000L))

        assertEquals(listOf(4_000_000_000L), time.sleeps)
    }

    @Test
    fun pacingWait_isSliceBoundedAndCancellable() {
        val time = FakeTime()
        val pacer = time.newPacer(maxSleepSliceNs = 25_000_000L)
        var active = true

        assertTrue(send(pacer, 100_000L) { active })
        time.onSleep = { active = false }

        assertFalse(pacer.awaitPresentationTime(1_100_000L) { active })
        assertEquals(listOf(25_000_000L), time.sleeps)
    }

    @Test
    fun largeMediaGap_isPreservedInsteadOfBeingSentAsABurst() {
        val time = FakeTime()
        val pacer = time.newPacer(maxSleepSliceNs = 60_000_000_000L)

        assertTrue(send(pacer, 100_000L))
        assertTrue(send(pacer, 10_100_000L))

        assertEquals(listOf(10_000_000_000L), time.sleeps)
    }

    @Test
    fun socketWriteTime_isIncludedBeforeTheNextSampleIsPaced() {
        val time = FakeTime()
        val pacer = time.newPacer()

        assertTrue(pacer.awaitPresentationTime(100_000L) { true })
        time.advance(2_000_000_000L) // the first socket write blocks
        pacer.markSent(100_000L)
        assertTrue(send(pacer, 133_000L))

        assertEquals(listOf(33_000_000L), time.sleeps)
    }

    @Test
    fun perSampleWriteOverhead_doesNotAccumulateIntoSenderLag() {
        val time = FakeTime()
        val pacer = time.newPacer()

        assertTrue(send(pacer, 100_000L))
        time.advance(5_000_000L)
        assertTrue(send(pacer, 133_000L))
        time.advance(5_000_000L)
        assertTrue(send(pacer, 166_000L))

        assertEquals(listOf(28_000_000L, 28_000_000L), time.sleeps)
    }

    private fun send(
        pacer: RtmpSamplePacer,
        presentationTimeUs: Long,
        isActive: () -> Boolean = { true },
    ): Boolean {
        if (!pacer.awaitPresentationTime(presentationTimeUs, isActive)) return false
        pacer.markSent(presentationTimeUs)
        return true
    }

    private class FakeTime {
        var nowNs: Long = 0L
        val sleeps = mutableListOf<Long>()
        var onSleep: () -> Unit = {}

        fun advance(deltaNs: Long) {
            nowNs += deltaNs
        }

        fun newPacer(
            maxSleepSliceNs: Long = 50_000_000L,
        ): RtmpSamplePacer =
            RtmpSamplePacer(
                nowNs = { nowNs },
                sleepNs = { durationNs ->
                    sleeps += durationNs
                    nowNs += durationNs
                    onSleep()
                },
                maxSleepSliceNs = maxSleepSliceNs,
            )
    }
}
