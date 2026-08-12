package io.egoflow.app.stream.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the T_codec → T_sender queue drop policy in [RtmpSendItem].
 *
 * The policy is the load-bearing piece of the S-3 producer-consumer refactor:
 * when the bounded queue fills up because the socket is slow, we evict
 * non-keyframe video first, then audio, before dropping incoming items.
 * Config and normal Close preserve queued media; urgent/fatal lifecycle items remain loss-tolerant.
 */
class RtmpSendQueueTest {
    private val sessionId = 42L

    private fun videoSample(
        isKey: Boolean,
        pacingEnabled: Boolean = true,
    ): SendItem.VideoSample =
        SendItem.VideoSample(
            sessionId = sessionId,
            codec = RtmpVideoCodec.H264,
            data = ByteArray(8),
            info = MediaCodec.BufferInfo(),
            isKeyFrame = isKey,
            pacingEnabled = pacingEnabled,
        )

    private fun audioSample(): SendItem.AudioSample =
        SendItem.AudioSample(
            sessionId = sessionId,
            data = ByteArray(8),
            info = MediaCodec.BufferInfo(),
        )

    private fun newQueue(capacity: Int): LinkedBlockingDeque<SendItem> =
        LinkedBlockingDeque(capacity)

    @Test
    fun tryEnqueueData_acceptsUntilCapacity() {
        val queue = newQueue(capacity = 3)

        assertEquals(EnqueueOutcome.Accepted, tryEnqueueData(queue, videoSample(isKey = true)))
        assertEquals(EnqueueOutcome.Accepted, tryEnqueueData(queue, videoSample(isKey = false)))
        assertEquals(EnqueueOutcome.Accepted, tryEnqueueData(queue, audioSample()))
        assertEquals(3, queue.size)
    }

    @Test
    fun tryEnqueueData_passThroughVideoKeepsExistingNonKeyEvictionPolicy() {
        val queue = newQueue(capacity = 3)
        val key = videoSample(isKey = true, pacingEnabled = false)
        val nonKey = videoSample(isKey = false, pacingEnabled = false)
        val incoming = videoSample(isKey = true, pacingEnabled = false)

        tryEnqueueData(queue, key)
        tryEnqueueData(queue, nonKey)
        tryEnqueueData(queue, key)  // second keyframe; queue now full

        val outcome = tryEnqueueData(queue, incoming)

        assertEquals(EnqueueOutcome.EvictedNonKeyVideo, outcome)
        assertEquals(3, queue.size)
        assertTrue("non-key video should have been removed", nonKey !in queue)
        assertTrue("incoming keyframe should be present", incoming in queue)
    }

    @Test
    fun tryEnqueueData_reencodedVideoNeverEvictsQueuedReferenceFrames() {
        val queue = newQueue(capacity = 3)
        val key = videoSample(isKey = true)
        val firstReference = videoSample(isKey = false)
        val secondReference = videoSample(isKey = false)
        val incoming = videoSample(isKey = false)
        tryEnqueueData(queue, key)
        tryEnqueueData(queue, firstReference)
        tryEnqueueData(queue, secondReference)

        val outcome = tryEnqueueData(queue, incoming)

        assertEquals(EnqueueOutcome.DroppedSelf, outcome)
        assertEquals(listOf(key, firstReference, secondReference), queue.toList())
    }

    @Test
    fun reencodedVideoGate_dropsDependentFramesUntilAKeyFrameCanBeQueued() {
        val queue = newQueue(capacity = 2)
        val gate = RtmpReencodedVideoGate()
        val key = videoSample(isKey = true)
        val reference = videoSample(isKey = false)
        tryEnqueueData(queue, key)
        tryEnqueueData(queue, reference)

        assertEquals(
            EnqueueOutcome.DroppedSelf,
            gate.tryEnqueue(queue, videoSample(isKey = false)),
        )
        assertTrue(gate.waitingForKeyFrame)

        queue.takeFirst()
        assertEquals(
            EnqueueOutcome.DroppedUntilKeyFrame,
            gate.tryEnqueue(queue, videoSample(isKey = false)),
        )

        val recoveryKey = videoSample(isKey = true)
        assertEquals(EnqueueOutcome.Accepted, gate.tryEnqueue(queue, recoveryKey))
        assertEquals(listOf(reference, recoveryKey), queue.toList())
        assertEquals(false, gate.waitingForKeyFrame)
    }

    @Test
    fun reencodedVideoGate_doesNotChangePassThroughBehavior() {
        val queue = newQueue(capacity = 2)
        val gate = RtmpReencodedVideoGate()
        val nonKey = videoSample(isKey = false, pacingEnabled = false)
        tryEnqueueData(queue, nonKey)
        tryEnqueueData(queue, videoSample(isKey = true, pacingEnabled = false))

        val outcome =
            gate.tryEnqueue(
                queue,
                videoSample(isKey = true, pacingEnabled = false),
            )

        assertEquals(EnqueueOutcome.EvictedNonKeyVideo, outcome)
        assertEquals(false, gate.waitingForKeyFrame)
    }

    @Test
    fun reencodedVideoGate_staysInRecoveryWhenKeyFrameCannotBeQueued() {
        val queue = newQueue(capacity = 1)
        val gate = RtmpReencodedVideoGate()
        tryEnqueueData(queue, videoSample(isKey = true))
        assertEquals(
            EnqueueOutcome.DroppedSelf,
            gate.tryEnqueue(queue, videoSample(isKey = false)),
        )

        assertEquals(
            EnqueueOutcome.DroppedSelf,
            gate.tryEnqueue(queue, videoSample(isKey = true)),
        )
        assertTrue(gate.waitingForKeyFrame)
        assertEquals(1, queue.size)
    }

    @Test
    fun tryEnqueueData_evictsAudioWhenAllVideoIsKey() {
        val queue = newQueue(capacity = 3)
        val key1 = videoSample(isKey = true)
        val key2 = videoSample(isKey = true)
        val audio = audioSample()
        tryEnqueueData(queue, key1)
        tryEnqueueData(queue, key2)
        tryEnqueueData(queue, audio)

        val incomingVideo = videoSample(isKey = true)
        val outcome = tryEnqueueData(queue, incomingVideo)

        assertEquals(EnqueueOutcome.EvictedOldestAudio, outcome)
        assertTrue("audio should have been evicted", audio !in queue)
        assertTrue("incoming video should be present", incomingVideo in queue)
        // Existing keyframes survive.
        assertTrue(key1 in queue)
        assertTrue(key2 in queue)
    }

    @Test
    fun tryEnqueueData_dropsIncomingAudioWhenNoAudioToEvict() {
        val queue = newQueue(capacity = 2)
        tryEnqueueData(queue, videoSample(isKey = true))
        tryEnqueueData(queue, videoSample(isKey = true))

        val incomingAudio = audioSample()
        val outcome = tryEnqueueData(queue, incomingAudio)

        assertEquals(EnqueueOutcome.DroppedSelf, outcome)
        assertTrue("queue must not contain the dropped audio", incomingAudio !in queue)
        assertEquals(2, queue.size)
    }

    @Test
    fun tryEnqueueData_dropsIncomingVideoWhenAllVideoIsKeyAndNoAudio() {
        val queue = newQueue(capacity = 2)
        tryEnqueueData(queue, videoSample(isKey = true))
        tryEnqueueData(queue, videoSample(isKey = true))

        val incoming = videoSample(isKey = true)
        val outcome = tryEnqueueData(queue, incoming)

        assertEquals(EnqueueOutcome.DroppedSelf, outcome)
        assertTrue(incoming !in queue)
        assertEquals(2, queue.size)
    }

    @Test
    fun tryEnqueueData_evictsAudioForIncomingAudio() {
        val queue = newQueue(capacity = 2)
        val oldAudio = audioSample()
        tryEnqueueData(queue, oldAudio)
        tryEnqueueData(queue, audioSample())

        val newAudio = audioSample()
        val outcome = tryEnqueueData(queue, newAudio)

        assertEquals(EnqueueOutcome.EvictedOldestAudio, outcome)
        assertTrue("oldest audio should have been removed", oldAudio !in queue)
        assertTrue(newAudio in queue)
    }

    @Test
    fun tryEnqueueData_configWaitsForSenderCapacityWithoutEvictingQueuedSamples() {
        val queue = newQueue(capacity = 2)
        val firstSample = videoSample(isKey = false)
        val secondSample = audioSample()
        tryEnqueueData(queue, firstSample)
        tryEnqueueData(queue, secondSample)
        val config =
            SendItem.VideoConfig(
                sessionId = sessionId,
                codec = RtmpVideoCodec.H264,
                format = MediaFormat(),
            )
        val executor = Executors.newSingleThreadExecutor()
        val enqueueResult =
            executor.submit<EnqueueOutcome> {
                tryEnqueueData(queue, config, configOfferTimeoutMs = 500L)
            }

        try {
            try {
                enqueueResult.get(100L, TimeUnit.MILLISECONDS)
                fail("config enqueue should wait for the sender instead of evicting queued samples")
            } catch (_: TimeoutException) {
                // Expected: the queue is still full and no sample was evicted.
            }
            assertTrue(firstSample in queue)
            assertTrue(secondSample in queue)

            assertSame(firstSample, queue.takeFirst())
            assertEquals(EnqueueOutcome.Accepted, enqueueResult.get(1L, TimeUnit.SECONDS))
            assertTrue(secondSample in queue)
            assertSame(config, queue.peekLast())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tryEnqueueData_configTimesOutWithoutEvictingQueuedSamples() {
        val queue = newQueue(capacity = 2)
        val firstSample = videoSample(isKey = false)
        val secondSample = audioSample()
        tryEnqueueData(queue, firstSample)
        tryEnqueueData(queue, secondSample)
        val config =
            SendItem.VideoConfig(
                sessionId = sessionId,
                codec = RtmpVideoCodec.H264,
                format = MediaFormat(),
            )

        val outcome = tryEnqueueData(queue, config, configOfferTimeoutMs = 1L)

        assertEquals(EnqueueOutcome.DroppedSelf, outcome)
        assertEquals(listOf(firstSample, secondSample), queue.toList())
    }

    @Test
    fun tryEnqueueGracefulClose_timesOutWithoutEvictingQueuedMedia() {
        val queue = newQueue(capacity = 2)
        val firstSample = videoSample(isKey = true)
        val secondSample = audioSample()
        tryEnqueueData(queue, firstSample)
        tryEnqueueData(queue, secondSample)
        val close = SendItem.Close(sessionId)

        val accepted = tryEnqueueGracefulClose(queue, close, offerTimeoutMs = 0L)

        assertFalse(accepted)
        assertEquals(listOf(firstSample, secondSample), queue.toList())
        assertFalse(close in queue)
    }

    @Test
    fun closeCompletion_isObservableOnlyAfterSenderAcknowledgesIt() {
        val close = SendItem.Close(sessionId)

        assertFalse(close.completion.await(timeoutMs = 0L))
        close.completion.complete()

        assertTrue(close.completion.await(timeoutMs = 0L))
    }

    @Test
    fun boundedGracefulClose_completesWithoutAbortAfterSenderAcknowledgement() {
        val queue = newQueue(capacity = 1)
        val close = SendItem.Close(sessionId)
        close.completion.complete()
        var abortCount = 0

        val result =
            awaitBoundedGracefulClose(
                queue = queue,
                close = close,
                gracefulTimeoutMs = 0L,
                forcedCleanupTimeoutMs = 0L,
                abortPublisher = { abortCount++ },
            )

        assertEquals(RtmpGracefulCloseResult.Completed, result)
        assertEquals(0, abortCount)
        assertSame(close, queue.peekLast())
    }

    @Test
    fun boundedGracefulClose_deadlineAbortsWithoutEvictingQueuedMedia() {
        val queue = newQueue(capacity = 2)
        val firstSample = videoSample(isKey = true)
        val secondSample = audioSample()
        tryEnqueueData(queue, firstSample)
        tryEnqueueData(queue, secondSample)
        val close = SendItem.Close(sessionId)
        var abortCount = 0

        val result =
            awaitBoundedGracefulClose(
                queue = queue,
                close = close,
                gracefulTimeoutMs = 0L,
                forcedCleanupTimeoutMs = 0L,
                abortPublisher = { abortCount++ },
            )

        assertEquals(RtmpGracefulCloseResult.TimedOut, result)
        assertEquals(1, abortCount)
        assertEquals(listOf(firstSample, secondSample), queue.toList())
        assertFalse(close in queue)
    }

    @Test
    fun gracefulCloseAbortOrTimeout_isSurfacedAsATransportFailure() {
        for (
            result in
                listOf(
                    RtmpGracefulCloseResult.AbortedAndCompleted,
                    RtmpGracefulCloseResult.TimedOut,
                )
        ) {
            try {
                result.requireCompletion()
                fail("a stop that aborted before draining queued media must not be reported as success")
            } catch (error: RtmpTimeoutException) {
                assertTrue(error.message.orEmpty().contains("queued media"))
            }
        }

        RtmpGracefulCloseResult.Completed.requireCompletion()
    }

    @Test
    fun boundedGracefulClose_afterAbortLetsSenderDrainAndAcknowledgeClose() {
        val queue = newQueue(capacity = 1)
        val sample = videoSample(isKey = true)
        tryEnqueueData(queue, sample)
        val close = SendItem.Close(sessionId)
        val abortStarted = CountDownLatch(1)
        var abortCount = 0
        val sender =
            Executors.newSingleThreadExecutor().apply {
                submit {
                    abortStarted.await()
                    assertSame(sample, queue.takeFirst())
                    val queuedClose = queue.takeFirst() as SendItem.Close
                    queuedClose.completion.complete()
                }
            }

        try {
            val result =
                awaitBoundedGracefulClose(
                    queue = queue,
                    close = close,
                    gracefulTimeoutMs = 0L,
                    forcedCleanupTimeoutMs = 500L,
                    abortPublisher = {
                        abortCount++
                        abortStarted.countDown()
                    },
                )

            assertEquals(RtmpGracefulCloseResult.AbortedAndCompleted, result)
            assertEquals(1, abortCount)
            assertTrue(queue.isEmpty())
        } finally {
            sender.shutdownNow()
        }
    }

    @Test
    fun tryEnqueueLifecycle_fatalCloseEvictsSamplesToMakeRoom() {
        val queue = newQueue(capacity = 2)
        tryEnqueueData(queue, videoSample(isKey = false))
        tryEnqueueData(queue, audioSample())

        val close = SendItem.Close(sessionId)
        val evicted = tryEnqueueLifecycle(queue, close, lifecycleOfferTimeoutMs = 0L)

        assertTrue("lifecycle item should have triggered eviction", evicted)
        assertTrue("Close must be the only thing left (samples evicted)", close in queue)
        assertEquals(1, queue.size)
    }

    @Test
    fun tryEnqueueLifecycle_acceptsImmediatelyWhenQueueHasRoom() {
        val queue = newQueue(capacity = 3)
        tryEnqueueData(queue, videoSample(isKey = true))

        val close = SendItem.Close(sessionId)
        val evicted = tryEnqueueLifecycle(queue, close, lifecycleOfferTimeoutMs = 0L)

        assertEquals(false, evicted)
        // FIFO: existing sample stays at head, close follows.
        assertEquals(2, queue.size)
        assertSame(close, queue.peekLast())
    }
}
