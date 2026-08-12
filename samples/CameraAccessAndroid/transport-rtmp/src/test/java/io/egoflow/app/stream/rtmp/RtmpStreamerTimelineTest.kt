package io.egoflow.app.stream.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpStreamerTimelineTest {
    private val bufferDelayUs = 100_000L

    @Test
    fun glassesTimeline_continuesAcrossEncoderRestartWithoutCompressingSourceGap() {
        val timeline = RtmpVideoSessionState(bufferDelayUs)

        val beforeRestart =
            timeline.presentationTimeForGlasses(
                sourcePtsUs = 212_600_000L,
                arrivalTimeNs = 1_000_000_000L,
            )
        val afterRestart =
            timeline.presentationTimeForGlasses(
                sourcePtsUs = 222_285_000L,
                arrivalTimeNs = 2_000_000_000L,
            )

        assertEquals(bufferDelayUs, beforeRestart)
        assertEquals(bufferDelayUs + 9_685_000L, afterRestart)
    }

    @Test
    fun glassesTimeline_clampsOnlyDuplicateAndBackwardTimestamps() {
        val timeline = RtmpVideoSessionState(bufferDelayUs)

        assertEquals(
            bufferDelayUs,
            timeline.presentationTimeForGlasses(1_000_000L, 1_000_000_000L),
        )
        assertEquals(
            bufferDelayUs + 8_000_000L,
            timeline.presentationTimeForGlasses(9_000_000L, 2_000_000_000L),
        )
        assertEquals(
            bufferDelayUs + 8_001_000L,
            timeline.presentationTimeForGlasses(8_000_000L, 3_000_000_000L),
        )
        assertEquals(
            bufferDelayUs + 20_000_000L,
            timeline.presentationTimeForGlasses(21_000_000L, 4_000_000_000L),
        )
    }

    @Test
    fun glassesTimeline_keepsWireMillisecondTimestampsStrictlyIncreasing() {
        val timeline = RtmpVideoSessionState(bufferDelayUs)

        timeline.presentationTimeForGlasses(1_000_000L, 1_000_000_000L)
        val beforeBackwardSourcePts =
            timeline.presentationTimeForGlasses(9_000_000L, 2_000_000_000L)
        val afterBackwardSourcePts =
            timeline.presentationTimeForGlasses(8_000_000L, 3_000_000_000L)

        assertTrue(afterBackwardSourcePts / 1_000L > beforeBackwardSourcePts / 1_000L)
    }

    @Test
    fun glassesTimeline_preservesDeltasAfterSourceEpochReset() {
        val timeline = RtmpVideoSessionState(bufferDelayUs)

        val beforeReset =
            timeline.presentationTimeForGlasses(328_000_000L, 1_000_000_000L)
        val resetSample =
            timeline.presentationTimeForGlasses(0L, 2_000_000_000L)
        val afterReset =
            timeline.presentationTimeForGlasses(33_000L, 2_033_000_000L)
        val nextAfterReset =
            timeline.presentationTimeForGlasses(66_000L, 2_066_000_000L)

        assertEquals(bufferDelayUs, beforeReset)
        assertEquals(beforeReset + 1_000L, resetSample)
        assertEquals(resetSample + 33_000L, afterReset)
        assertEquals(afterReset + 33_000L, nextAfterReset)
    }

    @Test
    fun reset_startsANewSessionTimeline() {
        val timeline = RtmpVideoSessionState(bufferDelayUs)
        timeline.presentationTimeForGlasses(1_000_000L, 1_000_000_000L)
        timeline.presentationTimeForGlasses(9_000_000L, 2_000_000_000L)
        timeline.markFirstVideoSampleSent()

        timeline.reset()

        assertEquals(
            bufferDelayUs,
            timeline.presentationTimeForGlasses(50_000_000L, 5_000_000_000L),
        )
        assertFalse(timeline.firstVideoSampleSent)
    }

    @Test
    fun activeSession_deactivationRejectsLateCompletion() {
        val activeSession = RtmpActiveSession()
        activeSession.activate(41L)

        assertTrue(activeSession.isActive(41L))
        activeSession.deactivate(41L)

        assertFalse(activeSession.isActive(41L))
        assertEquals(0L, activeSession.id)
    }

    @Test
    fun activeSession_staleDeactivationDoesNotCancelNewGeneration() {
        val activeSession = RtmpActiveSession()
        activeSession.activate(41L)
        activeSession.activate(42L)

        activeSession.deactivate(41L)

        assertTrue(activeSession.isActive(42L))
        assertEquals(42L, activeSession.id)
    }

    @Test
    fun selectVideoCodecForEncoderRestart_keepsActiveFallbackCodec() {
        assertEquals(
            RtmpVideoCodec.H264,
            selectVideoCodecForEncoderRestart(
                forceVideoCodec = null,
                activeVideoCodec = RtmpVideoCodec.H264,
                requestedVideoCodec = RtmpVideoCodec.H265,
            ),
        )
    }

    @Test
    fun selectVideoCodecForEncoderRestart_honorsExplicitFallback() {
        assertEquals(
            RtmpVideoCodec.H264,
            selectVideoCodecForEncoderRestart(
                forceVideoCodec = RtmpVideoCodec.H264,
                activeVideoCodec = RtmpVideoCodec.H265,
                requestedVideoCodec = RtmpVideoCodec.H265,
            ),
        )
    }

    @Test
    fun h265Fallback_isAllowedOnlyForTheInitialEncoderStart() {
        assertTrue(
            shouldFallbackH265EncoderStart(
                desiredCodec = RtmpVideoCodec.H265,
                hadStartedEncoder = false,
            ),
        )
        assertFalse(
            shouldFallbackH265EncoderStart(
                desiredCodec = RtmpVideoCodec.H265,
                hadStartedEncoder = true,
            ),
        )
        assertFalse(
            shouldFallbackH265EncoderStart(
                desiredCodec = RtmpVideoCodec.H264,
                hadStartedEncoder = false,
            ),
        )
    }

    @Test
    fun encoderRestartBoundary_preservesSessionStateAndQueuedOutput() {
        val sessionState = RtmpVideoSessionState(bufferDelayUs)
        sessionState.presentationTimeForGlasses(1_000_000L, 1_000_000_000L)
        val ptsBeforeRestart =
            sessionState.presentationTimeForGlasses(9_000_000L, 2_000_000_000L)
        sessionState.markFirstVideoSampleSent()
        val pendingItems = mutableListOf("old-config", "old-sample")
        var encoderStopped = false

        drainAndStopEncoderForRestart(
            isEncoderStarted = true,
            drainImmediatelyAvailable = { pendingItems += "immediately-drainable-sample" },
            stopEncoder = { encoderStopped = true },
        )

        assertEquals(
            listOf("old-config", "old-sample", "immediately-drainable-sample"),
            pendingItems,
        )
        assertEquals(ptsBeforeRestart, sessionState.lastPresentationTimeUs)
        assertTrue(sessionState.firstVideoSampleSent)
        assertTrue(encoderStopped)
    }
}
