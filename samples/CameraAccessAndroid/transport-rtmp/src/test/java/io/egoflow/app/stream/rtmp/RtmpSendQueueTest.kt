package io.egoflow.app.stream.rtmp

import android.media.MediaCodec
import java.util.concurrent.LinkedBlockingDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the T_codec → T_sender queue drop policy in [RtmpSendItem].
 *
 * The policy is the load-bearing piece of the S-3 producer-consumer refactor:
 * when the bounded queue fills up because the socket is slow, we evict
 * non-keyframe video first, then audio, before dropping incoming items.
 * Lifecycle items (Connect/Close/Config) must never drop.
 */
class RtmpSendQueueTest {
    private val sessionId = 42L

    private fun videoSample(isKey: Boolean): SendItem.VideoSample =
        SendItem.VideoSample(
            sessionId = sessionId,
            codec = RtmpVideoCodec.H264,
            data = ByteArray(8),
            info = MediaCodec.BufferInfo(),
            isKeyFrame = isKey,
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
    fun tryEnqueueData_evictsNonKeyVideoBeforeIncoming() {
        val queue = newQueue(capacity = 3)
        val key = videoSample(isKey = true)
        val nonKey = videoSample(isKey = false)
        val incoming = videoSample(isKey = true)

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
    fun tryEnqueueLifecycle_evictsSamplesToMakeRoom() {
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
