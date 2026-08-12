package io.egoflow.app.stream.rtmp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.egoflow.app.core.encoder.VideoEncoder
import io.egoflow.app.core.transport.api.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RTMP_TIMESTAMP_TICK_US = 1_000L

/**
 * [RTMP Streaming Engine]
 * Encodes video frames as H.264/H.265 via MediaCodec and sends them to an RTMP server through RtmpPublisher.
 *
 * Core flow:
 * 1. start(publishUrl, codec) → enqueue SendItem.Connect on the sender queue → T_sender performs the RTMP handshake
 * 2. sendGlassesFrame(VideoFrame) → YUV encode on T_codec → enqueue the encoded sample as a SendItem
 *    → T_sender drains the queue and calls into the publisher
 * 3. sendPhoneFrame(I420) → feed the encoder directly (camera already delivers I420), then the same path
 * 4. stop() → encoder EOS → drain remaining samples through the queue, then enqueue SendItem.Close
 *
 * **Thread model**
 * - **T_codec** (`codecExecutor`): encoder lifecycle, MediaCodec feed/drain, PTS bookkeeping,
 *   codec fallback policy, session-id minting. Sole owner of VideoEncoder + the audio MediaCodec.
 * - **T_sender** (`senderExecutor`): RtmpPublisher lifecycle plus every publisher.send* call.
 *   The only thread on which socket-write blocking can occur. Failures are posted back to codecExecutor.
 * - **T_reader** (`rtmp-reader`, owned by RtmpPublisher): blocking read loop on the socket. Replies
 *   to server flow-control messages (Window Acknowledgement Size, Set Chunk Size, etc.) independently
 *   of T_sender, so a slow write cannot starve control responses. Shares the socket's write side
 *   with T_sender via RtmpPublisher.writeLock.
 * - **Queue between codec and sender**: a LinkedBlockingDeque<SendItem> with capacity
 *   SEND_QUEUE_CAPACITY. Locally encoded video never evicts a queued reference frame: audio is
 *   evicted first, then incoming video is suppressed through the next IDR. The excluded
 *   pre-encoded HEVC path keeps its legacy eviction policy. Lifecycle items are never dropped.
 *
 * If the H.265 encoder is unavailable, automatically falls back to H.264. Audio streaming (AAC) is also
 * supported via a separate encoder, with audio capture starting only after the first video sample is sent.
 */
/**
 * @param audioEnabledProvider supplies "is audio capture enabled?" at
 *   sample time. Default false. Caller wires this to whichever
 *   settings store it uses (SettingsManager.rtmpAudioEnabled in
 *   this app) so RtmpStreamer stays unaware of :app's settings
 *   layer.
 * @param audioSourceProvider supplies the mic source (phone vs.
 *   glasses) at capture-start time, read the same way as
 *   audioEnabledProvider so a settings change applies to the next
 *   capture without coupling this class to :app's settings.
 */
class RtmpStreamer(
    context: Context,
    private val audioEnabledProvider: () -> Boolean = { false },
    audioSourceProvider: () -> RtmpAudioSource = { RtmpAudioSource.AUTO },
) {
    companion object {
        private const val TAG = "RtmpStreamer"
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val IFRAME_INTERVAL_SECONDS = 2
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 64_000

        // ~3.75s @ 24fps of video, plus headroom for audio + config items. Sized for one full
        // GOP (IFRAME_INTERVAL_SECONDS = 2) plus enough slack to absorb a brief sender stall
        // — Wi-Fi blip, TLS turn, I-frame burst — without overflowing into eviction.
        private const val SEND_QUEUE_CAPACITY = 90
        // Brief wait before lifecycle items eject data items to fit.
        private const val LIFECYCLE_OFFER_TIMEOUT_MS = 50L
        // One shared budget for appending Close behind the queued tail and receiving the sender's
        // acknowledgement. The forced budget begins only after publisher.abort() cancels pacing.
        private const val GRACEFUL_CLOSE_TIMEOUT_MS = 1000L
        // RtmpPublisher.close() may spend up to 500ms joining its reader, so forced cleanup must
        // leave additional scheduling headroom beyond that join.
        private const val FORCED_CLOSE_TIMEOUT_MS = 750L
        // Includes codec-executor scheduling, AudioRecorder's bounded 1s join, encoder drain, and
        // both sender-side close phases. All sub-budgets are carved from this one absolute deadline.
        private const val STOP_TOTAL_TIMEOUT_MS = 3000L

        // Mirrors PresentationQueue.bufferDelayMs in meta-wearables-dat-android/samples/CameraAccess.
        // Every output PTS is shifted forward by this amount so the first sample lands at
        // STREAM_BUFFER_DELAY_US instead of 0, giving the receiving player a fixed head buffer to
        // absorb network jitter before it has to display anything. The reference uses 100ms; same
        // value here keeps glasses-side video, phone-camera video, and audio on a consistent
        // pre-roll budget.
        private const val STREAM_BUFFER_DELAY_MS = 100L
        private const val STREAM_BUFFER_DELAY_US = STREAM_BUFFER_DELAY_MS * 1_000L
    }

    var onVideoCodecChanged: ((requestedCodec: RtmpVideoCodec, actualCodec: RtmpVideoCodec) -> Unit)? = null
    var onPublishStarted: ((sessionToken: Long) -> Unit)? = null
    var onFatalError: ((sessionToken: Long, failure: RtmpTransportFailure) -> Unit)? = null

    // -- Threads & queue ----------------------------------------------------
    private val codecExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rtmp-codec").apply { isDaemon = true }
    }
    private val senderExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rtmp-sender").apply { isDaemon = true }
    }
    private val sendQueue = LinkedBlockingDeque<SendItem>(SEND_QUEUE_CAPACITY)

    // -- T_codec-owned state (mutate only on codecExecutor) -----------------
    private val videoEncoder = VideoEncoder()
    private var publishUrl: String? = null
    private var diagnosticsContext: RtmpPublishDiagnosticsContext? = null
    private var requestedVideoCodec = RtmpVideoCodec.H264
    private var codecSessionId = 0L
    private var configuredWidth = 0
    private var configuredHeight = 0
    private val videoSessionState = RtmpVideoSessionState(STREAM_BUFFER_DELAY_US)
    private var audioEncoder: MediaCodec? = null
    private var audioStartTimeNs = 0L
    private var lastAudioPresentationTimeUs = -1L
    private var audioCaptureStarted = false
    private var compressedVideoConfigSent = false
    private val reencodedVideoGate = RtmpReencodedVideoGate()

    // -- Shared atomic state ------------------------------------------------
    private val sessionId = AtomicLong(0L)
    private val activeSession = RtmpActiveSession()
    private val droppedFrames = AtomicLong(0L)
    private val droppedAudioChunks = AtomicLong(0L)

    // -- T_sender-owned state (mutate only on senderExecutor) ---------------
    // T_sender owns normal publisher operations; T_codec may only call abort() on fatal teardown.
    @Volatile private var publisher: RtmpPublisher? = null
    private var senderSessionId: Long = 0L
    private var senderVideoSamples = 0L
    private var senderAudioSamples = 0L

    // Separate cumulative counter, not reset on session boundaries, so
    // the UI can poll from any thread to derive output FPS. T_sender
    // increments alongside senderVideoSamples.
    private val totalVideoSamplesSent = AtomicLong(0L)

    /** Cumulative video samples handed to the publisher successfully.
     *  Monotonic across sessions; not reset on stop/start. */
    fun videoFramesSent(): Long = totalVideoSamplesSent.get()

    // Capture thread (separate native thread inside RtmpAudioRecorder) hands PCM
    // off to codecExecutor -- same shape as before, but the eventual send happens
    // on T_sender after the AAC encoder drains.
    private val audioRecorder = RtmpAudioRecorder(
        context = context,
        sampleRate = AUDIO_SAMPLE_RATE,
        channelCount = AUDIO_CHANNEL_COUNT,
        audioSourceProvider = audioSourceProvider,
    ).apply {
        onPcmData = { pcm, captureTimeNs ->
            val expectedSessionId = sessionId.get()
            codecExecutor.execute {
                if (!isActiveSession(expectedSessionId)) return@execute
                if (!isAudioStreamingEnabled()) return@execute
                try {
                    ensureAudioStarted()
                    queueAudio(pcm, captureTimeNs)
                } catch (error: Exception) {
                    failActiveSession(expectedSessionId, "Failed to send audio sample", "Audio sample failed", error)
                }
            }
        }
    }

    init {
        senderExecutor.submit { runSenderLoop() }
    }

    // -- Public API ---------------------------------------------------------

    // [Start RTMP streaming]
    // 1) incrementAndGet sessionId → return the token to the caller
    // 2) On T_codec, set up publishUrl/codec and other codec-side state, then enqueue SendItem.Connect
    // 3) T_sender drains the queue, hits Connect, and calls RtmpPublisher.connect()
    //    → onPublishStarted callback fires on success
    fun start(
        nextPublishUrl: String,
        videoCodec: RtmpVideoCodec,
        diagnosticsContext: RtmpPublishDiagnosticsContext? = null,
    ): Long {
        if (nextPublishUrl.isBlank()) return 0L
        val nextSessionId = sessionId.incrementAndGet()
        codecExecutor.execute {
            if (publishUrl != null) {
                stopInternal(fatal = false)
            }
            RtmpDiagnostics.clear()
            RtmpDiagnostics.log("RTMP streamer start requested")
            publishUrl = nextPublishUrl
            this.diagnosticsContext = diagnosticsContext
            requestedVideoCodec = videoCodec
            codecSessionId = nextSessionId
            audioCaptureStarted = false
            videoSessionState.reset()
            reencodedVideoGate.reset()
            activeSession.activate(nextSessionId)
            enqueueLifecycle(SendItem.Connect(nextSessionId, nextPublishUrl, videoCodec, diagnosticsContext))
        }
        return nextSessionId
    }

    // [Stop RTMP streaming]
    // Run stopInternal on codecExecutor: drain encoder EOS → enqueue remaining samples → enqueue SendItem.Close.
    // T_sender drains the queue, sends the final samples, then calls publisher.close().
    suspend fun stop() {
        // Reject newly submitted frame work immediately; codec-owned teardown remains serialized
        // behind work already accepted by T_codec.
        activeSession.deactivate(sessionId.get())
        val stopDeadlineNs = newStopDeadlineNs()
        withContext(Dispatchers.IO) {
            val stopCompletion = RtmpCloseCompletion()
            val closeResult = AtomicReference<RtmpGracefulCloseResult?>()
            val stopFailure = AtomicReference<Exception?>()
            codecExecutor.execute {
                try {
                    closeResult.set(stopInternal(fatal = false, stopDeadlineNs = stopDeadlineNs))
                } catch (error: Exception) {
                    stopFailure.set(error)
                } finally {
                    stopCompletion.complete()
                }
            }
            if (!stopCompletion.await(remainingStopTimeoutMs(stopDeadlineNs))) {
                // Covers an unexpectedly stalled codec task. The inner close coordinator reserves
                // its own forced phase inside the same absolute deadline when T_codec is progressing.
                publisher?.abort()
                RtmpDiagnostics.log("RTMP teardown exceeded ${STOP_TOTAL_TIMEOUT_MS}ms; publisher aborted")
                throw RtmpTimeoutException("RTMP teardown exceeded ${STOP_TOTAL_TIMEOUT_MS}ms.")
            }
            stopFailure.get()?.let { throw it }
            requireNotNull(closeResult.get()) { "Normal RTMP stop completed without a Close result." }
                .requireCompletion()
        }
    }

    // [Send glasses video frame]
    // Hand the YUV buffer to codecExecutor, which feeds the encoder, drains it, and enqueues a
    // SendItem.VideoSample on the queue. A queue overflow preserves the queued GOP and suppresses
    // newly encoded dependent frames until the next keyframe can be queued.
    //
    // PTS is derived from the glasses-side capture timestamp (videoFrame.presentationTimeUs), not
    // from arrival time on T_codec. Burst arrivals over BT/Wi-Fi previously compressed PTS gaps and
    // showed up as >30fps on the receiver; sourcing PTS from the device clock preserves the actual
    // 30fps cadence regardless of transport jitter. Mirrors the reference PresentationQueue's use
    // of videoFrame.presentationTimeUs as its base.
    fun sendGlassesFrame(videoFrame: VideoFrame) {
        if (!isEnabled()) return
        val sourceBuffer = videoFrame.buffer.duplicate()
        val frameData = ByteArray(sourceBuffer.remaining())
        sourceBuffer.get(frameData)
        val sourcePtsUs = videoFrame.presentationTimeUs
        val expectedSessionId = sessionId.get()
        codecExecutor.execute {
            if (!isActiveSession(expectedSessionId)) return@execute
            try {
                ensureStarted(videoFrame.width, videoFrame.height)
                queueFrame(frameData, videoFrame.width, videoFrame.height, glassesFramePresentationTimeUs(sourcePtsUs))
            } catch (error: Exception) {
                failActiveSession(expectedSessionId, "Failed to send glasses frame", "Glasses frame failed", error)
            }
        }
    }

    fun sendCompressedGlassesFrame(videoFrame: VideoFrame) {
        if (!isEnabled()) return

        val sourceBuffer = videoFrame.buffer.duplicate()
        val bytes = ByteArray(sourceBuffer.remaining())
        sourceBuffer.get(bytes)

        val sourcePtsUs = videoFrame.presentationTimeUs
        // The current SDK surfaces HEVC codec config (VPS/SPS/PPS) as its own frame flagged
        // isCodecConfig, separate from the payload slices. Such a frame carries only
        // parameter sets, so it becomes a VideoConfig and must NOT be enqueued as a sample.
        val isCodecConfig = videoFrame.isCodecConfig
        val expectedSessionId = sessionId.get()

        codecExecutor.execute {
            if (!isActiveSession(expectedSessionId)) return@execute
            try {
                if (isCodecConfig) {
                    sendCompressedVideoConfig(expectedSessionId, bytes)
                    return@execute
                }

                // Fallback for SDKs/streams that still inline parameter sets in the first
                // (key)frame rather than emitting a dedicated isCodecConfig frame.
                if (!compressedVideoConfigSent) {
                    sendCompressedVideoConfig(expectedSessionId, bytes)
                }

                val isKeyFrame = RtmpVideoPacketizer.containsHevcKeyFrame(bytes)
                val bufferInfo = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = bytes.size
                    presentationTimeUs = glassesFramePresentationTimeUs(sourcePtsUs)
                    flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                enqueueData(
                    SendItem.VideoSample(
                        sessionId = expectedSessionId,
                        codec = RtmpVideoCodec.H265,
                        data = bytes,
                        info = bufferInfo,
                        isKeyFrame = isKeyFrame,
                        pacingEnabled = false,
                    ),
                )
            } catch (error: Exception) {
                failActiveSession(expectedSessionId, "Failed to send compressed glasses frame", "Compressed frame failed", error)
            }
        }
    }

    // Extracts VPS+SPS+PPS from an HEVC bitstream and enqueues a single VideoConfig.
    // Idempotent across a session via compressedVideoConfigSent. Runs on T_codec.
    private fun sendCompressedVideoConfig(expectedSessionId: Long, bytes: ByteArray) {
        if (compressedVideoConfigSent) return
        val nalUnits = RtmpVideoPacketizer.extractNalUnits(bytes)
        val vps = nalUnits.filter { RtmpVideoPacketizer.hevcNalUnitType(it) == RtmpVideoPacketizer.HEVC_NAL_VPS }
        val sps = nalUnits.filter { RtmpVideoPacketizer.hevcNalUnitType(it) == RtmpVideoPacketizer.HEVC_NAL_SPS }
        val pps = nalUnits.filter { RtmpVideoPacketizer.hevcNalUnitType(it) == RtmpVideoPacketizer.HEVC_NAL_PPS }
        if (vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()) {
            enqueueData(SendItem.VideoConfig(expectedSessionId, RtmpVideoCodec.H265, buildHevcMediaFormat(vps, sps, pps)))
            compressedVideoConfigSent = true
            RtmpDiagnostics.log("Sent compressed HEVC config (VPS+SPS+PPS extracted from frame)")
        }
    }

    // [Send phone-camera video frame]
    // The phone camera already delivers upright planar I420, so there's no Bitmap,
    // no scaling, and no per-pixel transcode here -- just feed the encoder. The buffer
    // is freshly allocated per frame upstream, so it's safe to hand to codecExecutor.
    // H.264/H.265 require even dimensions; CameraX analysis frames are even, but guard
    // defensively rather than feed the encoder a mis-sized buffer.
    fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int) {
        if (!isEnabled()) return
        if (width and 1 != 0 || height and 1 != 0) {
            RtmpDiagnostics.log("Dropped phone frame with odd dimensions ${width}x${height}")
            return
        }
        val expectedSessionId = sessionId.get()
        codecExecutor.execute {
            if (!isActiveSession(expectedSessionId)) return@execute
            try {
                ensureStarted(width, height)
                queueFrame(i420, width, height, framePresentationTimeUs(System.nanoTime()))
            } catch (error: Exception) {
                failActiveSession(expectedSessionId, "Failed to send phone frame", "Phone frame failed", error)
            }
        }
    }

    // -- T_codec internals --------------------------------------------------

    private fun isEnabled(): Boolean = !publishUrl.isNullOrBlank()

    private fun isActiveSession(expectedSessionId: Long): Boolean {
        return activeSession.isActive(expectedSessionId) && !publishUrl.isNullOrBlank()
    }

    private fun ensureStarted(width: Int, height: Int) {
        if (!videoEncoder.isStarted || width != configuredWidth || height != configuredHeight) {
            restartEncoder(width, height)
        }
    }

    private fun ensureAudioStarted() {
        if (!isAudioStreamingEnabled()) return
        if (audioEncoder != null) return

        val format =
            MediaFormat.createAudioFormat(
                AUDIO_MIME_TYPE,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }

        audioEncoder =
            MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        audioStartTimeNs = 0L
        lastAudioPresentationTimeUs = -1L
        RtmpDiagnostics.log("Audio encoder started ${AUDIO_SAMPLE_RATE}Hz/${AUDIO_CHANNEL_COUNT}ch")
        drainAudioEncoder(endOfStream = false)
    }

    // [Restart video encoder]
    // Called on a resolution change or a codec fallback.
    // If H.265 creation fails, automatically falls back to H.264 and invokes onVideoCodecChanged.
    // The encoder's own lifecycle is owned by VideoEncoder; RtmpStreamer only handles codec fallback
    // policy. The session timeline and already-enqueued items deliberately survive a resolution restart.
    private fun restartEncoder(width: Int, height: Int, forceVideoCodec: RtmpVideoCodec? = null) {
        val hadStartedEncoder = videoEncoder.isStarted
        val desiredRtmpCodec =
            selectVideoCodecForEncoderRestart(
                forceVideoCodec = forceVideoCodec,
                activeVideoCodec = videoEncoder.activeCodec?.toRtmp(),
                requestedVideoCodec = requestedVideoCodec,
            )

        // dequeueOutputBuffer() is non-blocking in VideoEncoder.drainNext(), so this preserves every
        // output currently available without delaying the resolution switch for future output.
        drainAndStopEncoderForRestart(
            isEncoderStarted = hadStartedEncoder,
            drainImmediatelyAvailable = { drainVideoEncoder(endOfStream = false) },
            stopEncoder = videoEncoder::stop,
        )
        try {
            videoEncoder.start(width, height, desiredRtmpCodec.toCore())
        } catch (error: Exception) {
            if (shouldFallbackH265EncoderStart(desiredRtmpCodec, hadStartedEncoder)) {
                RtmpDiagnostics.log("Falling back to H.264: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
                videoEncoder.start(width, height, VideoCodec.H264)
            } else {
                throw error
            }
        }
        val active = requireNotNull(videoEncoder.activeCodec) { "VideoEncoder.start succeeded without an activeCodec" }.toRtmp()
        onVideoCodecChanged?.invoke(requestedVideoCodec, active)
        configuredWidth = width
        configuredHeight = height
        RtmpDiagnostics.log("Encoder restarted ${width}x${height} video=${active.displayName}")
        drainVideoEncoder(endOfStream = false)
    }

    private fun queueFrame(i420Frame: ByteArray, width: Int, height: Int, presentationTimeUs: Long) {
        videoEncoder.queue(i420Frame, width, height, presentationTimeUs)
        drainVideoEncoder(endOfStream = false)
    }

    private fun queueAudio(pcm: ByteArray, captureTimeNs: Long) {
        val encoder = requireNotNull(audioEncoder)
        val inputIndex = encoder.dequeueInputBuffer(5_000)
        if (inputIndex < 0) return
        val targetInput = encoder.getInputBuffer(inputIndex) ?: return
        targetInput.clear()
        if (pcm.size > targetInput.remaining()) {
            Log.w(TAG, "Dropping audio chunk because encoder input buffer is too small")
            RtmpDiagnostics.log("Dropped audio chunk: input buffer too small (${pcm.size} > ${targetInput.remaining()})")
            encoder.queueInputBuffer(inputIndex, 0, 0, audioPresentationTimeUs(captureTimeNs), 0)
            return
        }
        targetInput.put(pcm)
        encoder.queueInputBuffer(inputIndex, 0, pcm.size, audioPresentationTimeUs(captureTimeNs), 0)
        drainAudioEncoder(endOfStream = false)
    }

    // [Handle video encoder output]
    // Pull H.264/H.265 packets from VideoEncoder.drainNext(), wrap them in SendItems, and enqueue.
    // When the queue is full, enqueueData preserves queued reference frames and recovers at an IDR.
    // FormatChanged → VideoConfig, Sample → VideoSample. The actual send happens on T_sender.
    private fun drainVideoEncoder(endOfStream: Boolean) {
        val currentSessionId = codecSessionId
        if (endOfStream) {
            videoEncoder.signalEndOfStream(framePresentationTimeUs(System.nanoTime()))
        }
        while (true) {
            val result = videoEncoder.drainNext() ?: return
            val active = videoEncoder.activeCodec?.toRtmp() ?: return
            when (result) {
                is VideoEncoder.DrainResult.FormatChanged -> {
                    enqueueData(
                        SendItem.VideoConfig(
                            sessionId = currentSessionId,
                            codec = active,
                            format = result.format,
                            refreshH265ParameterSetsInBand = true,
                        ),
                    )
                    RtmpDiagnostics.log("Encoder output format changed")
                }
                is VideoEncoder.DrainResult.Sample -> {
                    val isKey = result.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    enqueueData(SendItem.VideoSample(currentSessionId, active, result.data, result.info, isKey))
                }
                VideoEncoder.DrainResult.EndOfStream -> {
                    RtmpDiagnostics.log("Encoder reached end of stream")
                    return
                }
            }
        }
    }

    // [Handle audio encoder output]
    // Wrap AAC-encoded packets as SendItem.AudioSample/AudioConfig and enqueue them.
    // RtmpPublisher's "video first" constraint is handled by T_sender inside the publisher's own logic,
    // so we don't worry about it here.
    private fun drainAudioEncoder(endOfStream: Boolean) {
        val encoder = audioEncoder ?: return
        val currentSessionId = codecSessionId
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            val inputIndex = encoder.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    audioPresentationTimeUs(System.nanoTime()),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        while (true) {
            when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    enqueueData(SendItem.AudioConfig(currentSessionId, encoder.outputFormat))
                    RtmpDiagnostics.log("Audio output format changed")
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outputIndex < 0) continue
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer == null) {
                        encoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    val encoded = outputBuffer.toByteArray(bufferInfo)
                    val infoCopy = MediaCodec.BufferInfo().apply {
                        set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    enqueueData(SendItem.AudioSample(currentSessionId, encoded, infoCopy))
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        RtmpDiagnostics.log("Audio encoder reached end of stream")
                        return
                    }
                }
            }
        }
    }

    // Phone-camera (and any other locally-clocked source). The first frame's PTS lands at
    // STREAM_BUFFER_DELAY_US, mirroring the buffer pre-roll the reference PresentationQueue applies
    // via `baseWallTimeMs = now + bufferDelayMs`.
    private fun framePresentationTimeUs(captureTimeNs: Long): Long {
        val wasInitialized = videoSessionState.hasWallClockOrigin
        val previousPtsUs = videoSessionState.lastPresentationTimeUs
        val nextPtsUs = videoSessionState.presentationTimeForWallClock(captureTimeNs)
        if (!wasInitialized) {
            RtmpDiagnostics.log("PTS origin initialized (wall-clock, buffer=${STREAM_BUFFER_DELAY_MS}ms)")
        }
        val gapUs = nextPtsUs - previousPtsUs
        if (previousPtsUs >= 0L && gapUs > 1_500_000L) {
            RtmpDiagnostics.log("Large PTS gap ${(gapUs / 1000L)}ms")
        }
        return nextPtsUs
    }

    // Glasses video, where the source-side capture timestamp (videoFrame.presentationTimeUs) is the
    // ground truth for inter-frame cadence. The first frame anchors glassesPtsOriginUs; subsequent
    // frames preserve the device-side gaps exactly. Also seeds streamStartTimeNs to the wall-clock
    // moment of first arrival so audioPresentationTimeUs has a reference for AV alignment.
    private fun glassesFramePresentationTimeUs(sourcePtsUs: Long): Long {
        val wasInitialized = videoSessionState.hasGlassesOrigin
        val previousPtsUs = videoSessionState.lastPresentationTimeUs
        val nextPtsUs = videoSessionState.presentationTimeForGlasses(sourcePtsUs, System.nanoTime())
        if (!wasInitialized) {
            RtmpDiagnostics.log("PTS origin initialized (glasses pts=${sourcePtsUs / 1000L}ms, buffer=${STREAM_BUFFER_DELAY_MS}ms)")
        }
        val gapUs = nextPtsUs - previousPtsUs
        if (previousPtsUs >= 0L && gapUs > 1_500_000L) {
            RtmpDiagnostics.log("Large PTS gap ${(gapUs / 1000L)}ms")
        }
        return nextPtsUs
    }

    private fun audioPresentationTimeUs(captureTimeNs: Long): Long {
        if (audioStartTimeNs == 0L) {
            audioStartTimeNs = videoSessionState.streamStartTimeNs.takeIf { it != 0L } ?: captureTimeNs
            val elapsedUs = ((captureTimeNs - audioStartTimeNs).coerceAtLeast(0L)) / 1_000L
            val initialPtsUs = STREAM_BUFFER_DELAY_US + elapsedUs
            lastAudioPresentationTimeUs = initialPtsUs
            if (elapsedUs > 0L) {
                RtmpDiagnostics.log("Audio PTS aligned to stream origin at ${elapsedUs / 1000L}ms after video")
            }
            return initialPtsUs
        }

        val elapsedUs = ((captureTimeNs - audioStartTimeNs).coerceAtLeast(0L)) / 1_000L
        val nextPtsUs = maxOf(STREAM_BUFFER_DELAY_US + elapsedUs, lastAudioPresentationTimeUs + 1L)
        lastAudioPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }

    private fun failActiveSession(expectedSessionId: Long, logMessage: String, diagPrefix: String, error: Exception) {
        if (!isActiveSession(expectedSessionId)) return
        Log.e(TAG, logMessage, error)
        val classifiedFailure = classifyRtmpTransportFailure(error)
        val failureMessage =
            "$diagPrefix: ${classifiedFailure.category} retryable=${classifiedFailure.retryable}: ${classifiedFailure.message}"
        RtmpDiagnostics.log(failureMessage)
        stopInternal(fatal = true)
        onFatalError?.invoke(expectedSessionId, classifiedFailure)
    }

    private fun stopInternal(
        fatal: Boolean,
        stopDeadlineNs: Long = newStopDeadlineNs(),
    ): RtmpGracefulCloseResult? {
        val closingSessionId = codecSessionId.takeIf { it != 0L } ?: activeSession.id
        activeSession.deactivate(closingSessionId)
        if (fatal) {
            publisher?.abort()
        }
        audioRecorder.stop()
        audioCaptureStarted = false

        if (!fatal) {
            try {
                drainAudioEncoder(endOfStream = true)
            } catch (_: Exception) {
            }
        }
        try {
            audioEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        }
        audioEncoder = null
        audioStartTimeNs = 0L
        lastAudioPresentationTimeUs = -1L

        if (!fatal) {
            try {
                drainVideoEncoder(endOfStream = true)
            } catch (_: Exception) {
            }
        }
        videoEncoder.stop()
        codecSessionId = 0L
        configuredWidth = 0
        configuredHeight = 0
        videoSessionState.reset()
        compressedVideoConfigSent = false
        reencodedVideoGate.reset()
        publishUrl = null
        diagnosticsContext = null

        if (fatal) {
            // Drop any pending data -- we want the socket closed ASAP.
            sendQueue.removeIf {
                it is SendItem.VideoSample ||
                    it is SendItem.AudioSample ||
                    it is SendItem.VideoConfig ||
                    it is SendItem.AudioConfig
            }
        }
        val closeResult = if (fatal) {
            enqueueLifecycle(SendItem.Close(closingSessionId))
            null
        } else {
            val remainingMs = remainingStopTimeoutMs(stopDeadlineNs)
            val forcedCleanupMs = minOf(FORCED_CLOSE_TIMEOUT_MS, remainingMs)
            val gracefulMs =
                minOf(
                    GRACEFUL_CLOSE_TIMEOUT_MS,
                    (remainingMs - forcedCleanupMs).coerceAtLeast(0L),
                )
            val close = SendItem.Close(closingSessionId)
            val result =
                awaitBoundedGracefulClose(
                    queue = sendQueue,
                    close = close,
                    gracefulTimeoutMs = gracefulMs,
                    forcedCleanupTimeoutMs = forcedCleanupMs,
                    abortPublisher = { publisher?.abort() },
                )
            when (result) {
                RtmpGracefulCloseResult.Completed -> Unit
                RtmpGracefulCloseResult.AbortedAndCompleted ->
                    RtmpDiagnostics.log("RTMP graceful close exceeded deadline; completed after publisher abort")
                RtmpGracefulCloseResult.TimedOut ->
                    RtmpDiagnostics.log("RTMP close acknowledgement timed out after publisher abort")
            }
            result
        }
        RtmpDiagnostics.log("RTMP streamer stopped")
        return closeResult
    }

    private fun newStopDeadlineNs(): Long {
        val nowNs = System.nanoTime()
        val timeoutNs = STOP_TOTAL_TIMEOUT_MS * 1_000_000L
        return if (nowNs > Long.MAX_VALUE - timeoutNs) Long.MAX_VALUE else nowNs + timeoutNs
    }

    private fun remainingStopTimeoutMs(deadlineNs: Long): Long {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) return 0L
        return (remainingNs / 1_000_000L) + if (remainingNs % 1_000_000L == 0L) 0L else 1L
    }

    private fun ensureAudioCaptureStarted() {
        if (audioCaptureStarted) return
        if (!isAudioStreamingEnabled()) return
        audioRecorder.start()
        audioCaptureStarted = true
        RtmpDiagnostics.log("Audio capture started after first video sample")
    }

    private fun isAudioStreamingEnabled(): Boolean = audioEnabledProvider()

    private fun buildHevcMediaFormat(
        vpsUnits: List<ByteArray>,
        spsUnits: List<ByteArray>,
        ppsUnits: List<ByteArray>,
    ): MediaFormat {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val allUnits = vpsUnits + spsUnits + ppsUnits
        val csd0 = ByteArray(allUnits.sumOf { startCode.size + it.size })
        var offset = 0
        for (nal in allUnits) {
            System.arraycopy(startCode, 0, csd0, offset, startCode.size); offset += startCode.size
            System.arraycopy(nal, 0, csd0, offset, nal.size); offset += nal.size
        }
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 0, 0).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd0))
        }
    }

    // -- Send-queue helpers (run on T_codec) --------------------------------

    /** Push a data item onto the sender queue, applying drop policy when full.
     *  Lifecycle items must use [enqueueLifecycle] instead. */
    private fun enqueueData(item: SendItem) {
        val outcome =
            if (item is SendItem.VideoSample) {
                reencodedVideoGate.tryEnqueue(sendQueue, item)
            } else {
                tryEnqueueData(sendQueue, item)
            }
        when (outcome) {
            EnqueueOutcome.Accepted -> Unit
            EnqueueOutcome.EvictedNonKeyVideo -> {
                droppedFrames.incrementAndGet()
                RtmpDiagnostics.log("Dropped non-keyframe video (queue full)")
            }
            EnqueueOutcome.EvictedOldestAudio -> {
                droppedAudioChunks.incrementAndGet()
                val tag = if (item is SendItem.VideoSample) "to make room for video" else "(queue full)"
                RtmpDiagnostics.log("Dropped oldest audio $tag")
            }
            EnqueueOutcome.DroppedSelf -> when (item) {
                is SendItem.VideoSample -> {
                    droppedFrames.incrementAndGet()
                    val suffix =
                        if (item.pacingEnabled) {
                            "; suppressing dependent frames until keyframe"
                        } else {
                            ", no eviction candidate"
                        }
                    RtmpDiagnostics.log("Dropped video sample (queue full$suffix)")
                }
                is SendItem.AudioSample -> {
                    droppedAudioChunks.incrementAndGet()
                    RtmpDiagnostics.log("Dropped audio sample (queue full)")
                }
                is SendItem.VideoConfig,
                is SendItem.AudioConfig,
                -> {
                    failActiveSession(
                        expectedSessionId = item.sessionId,
                        logMessage = "Timed out while preserving queued media before codec config",
                        diagPrefix = "Sender queue stalled",
                        error = RtmpTimeoutException("RTMP sender queue did not free capacity for codec config."),
                    )
                }
                else -> Unit
            }
            EnqueueOutcome.DroppedUntilKeyFrame -> {
                droppedFrames.incrementAndGet()
                RtmpDiagnostics.log("Dropped dependent video sample while waiting for keyframe")
            }
        }
    }

    /** Push Connect or fatal Close onto the queue. Normal Close uses the non-evicting graceful path. */
    private fun enqueueLifecycle(item: SendItem) {
        val evicted = tryEnqueueLifecycle(sendQueue, item, LIFECYCLE_OFFER_TIMEOUT_MS)
        if (evicted) {
            RtmpDiagnostics.log("Evicted samples to make room for lifecycle item ${item::class.simpleName}")
        }
    }

    // -- Callbacks posted from T_sender → T_codec ---------------------------

    private fun onSendFailure(failedSessionId: Long, stage: String, error: Exception) {
        if (!isActiveSession(failedSessionId)) return
        // Network/publisher failures cannot be recovered by swapping the local encoder. H.265 to
        // H.264 fallback remains limited to encoder startup inside restartEncoder().
        failActiveSession(failedSessionId, "Failed during $stage", stage, error)
    }

    private fun onFirstVideoSampleSentFromSender(id: Long) {
        if (!isActiveSession(id)) return
        if (videoSessionState.firstVideoSampleSent) return
        videoSessionState.markFirstVideoSampleSent()
        ensureAudioCaptureStarted()
    }

    // -- T_sender loop ------------------------------------------------------

    private fun runSenderLoop() {
        // Reads (and control-message responses) live on RtmpPublisher's dedicated reader thread,
        // so this loop can block on the queue indefinitely; only items move us forward.
        while (true) {
            val item =
                try {
                    sendQueue.takeFirst()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            try {
                handleSenderItem(item)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected sender-loop exception", e)
                RtmpDiagnostics.log("Sender loop swallowed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
            }
            if (item is SendItem.Poison) return
        }
    }

    private fun handleSenderItem(item: SendItem) {
        // Lifecycle items are always processed regardless of senderSessionId.
        when (item) {
            is SendItem.Connect -> {
                handleSenderConnect(item)
                return
            }
            is SendItem.Close -> {
                try {
                    publisher?.close()
                } catch (_: Exception) {
                } finally {
                    publisher = null
                    senderSessionId = 0L
                    senderVideoSamples = 0L
                    senderAudioSamples = 0L
                    item.completion.complete()
                }
                return
            }
            is SendItem.Poison -> return
            else -> {
                // Data + config items: skip if stale.
                if (item.sessionId != senderSessionId || publisher == null) return
            }
        }

        val p = publisher ?: return
        try {
            when (item) {
                is SendItem.VideoConfig ->
                    p.sendVideoConfig(
                        item.codec,
                        item.format,
                        item.refreshH265ParameterSetsInBand,
                    )
                is SendItem.VideoSample -> {
                    if (p.sendVideoSample(item.codec, item.data, item.info, item.pacingEnabled)) {
                        senderVideoSamples++
                        totalVideoSamplesSent.incrementAndGet()
                        if (senderVideoSamples == 1L) {
                            codecExecutor.execute { onFirstVideoSampleSentFromSender(item.sessionId) }
                        }
                        if (senderVideoSamples == 1L || senderVideoSamples % 120L == 0L) {
                            val ptsMs = item.info.presentationTimeUs / 1000L
                            RtmpDiagnostics.log("Sent sample #$senderVideoSamples pts=${ptsMs}ms size=${item.info.size} key=${item.isKeyFrame}")
                        }
                    }
                }
                is SendItem.AudioConfig -> p.sendAudioConfig(item.format)
                is SendItem.AudioSample -> {
                    if (p.sendAudioSample(item.data, item.info)) {
                        senderAudioSamples++
                        if (senderAudioSamples == 1L || senderAudioSamples % 120L == 0L) {
                            val ptsMs = item.info.presentationTimeUs / 1000L
                            RtmpDiagnostics.log("Sent audio #$senderAudioSamples pts=${ptsMs}ms size=${item.info.size}")
                        }
                    }
                }
                else -> Unit  // Connect/Close/Poison handled above
            }
        } catch (e: Exception) {
            val stage = describeStage(item)
            val failedId = item.sessionId
            try {
                publisher?.close()
            } catch (_: Exception) {
            }
            publisher = null
            senderSessionId = 0L
            codecExecutor.execute { onSendFailure(failedId, stage, e) }
        }
    }

    private fun handleSenderConnect(item: SendItem.Connect) {
        // A stale queued Connect must never reach RtmpPublisher.sendPublish().
        if (!activeSession.isActive(item.sessionId)) return

        // A prior session left a publisher behind -- defensively close it.
        try {
            publisher?.close()
        } catch (_: Exception) {
        }
        publisher = null
        senderSessionId = 0L
        senderVideoSamples = 0L
        senderAudioSamples = 0L

        val newPublisher =
            RtmpPublisher(
                publishUrl = item.url,
                preferredVideoCodec = item.codec,
                diagnosticsContext = item.diagnosticsContext,
                publishAllowed = { activeSession.isActive(item.sessionId) },
            )
        // Publish the reference before the blocking handshake so fatal teardown can abort it.
        publisher = newPublisher
        try {
            newPublisher.connect()
        } catch (e: Exception) {
            try {
                newPublisher.close()
            } catch (_: Exception) {
            }
            if (publisher === newPublisher) {
                publisher = null
            }
            codecExecutor.execute { onSendFailure(item.sessionId, "Connect", e) }
            return
        }

        // Discard if a newer session has already superseded this one.
        if (!activeSession.isActive(item.sessionId)) {
            try { newPublisher.close() } catch (_: Exception) {}
            if (publisher === newPublisher) {
                publisher = null
            }
            return
        }
        publisher = newPublisher
        senderSessionId = item.sessionId
        onPublishStarted?.invoke(item.sessionId)
    }

    private fun describeStage(item: SendItem): String = when (item) {
        is SendItem.VideoConfig -> "video config"
        is SendItem.VideoSample -> "video packet"
        is SendItem.AudioConfig -> "audio config"
        is SendItem.AudioSample -> "audio sample"
        is SendItem.Connect -> "connect"
        is SendItem.Close -> "close"
        is SendItem.Poison -> "shutdown"
    }

    // Used by the audio encoder's drain loop (still inline here). The
    // video-encoder counterpart lives inside VideoEncoder.
    private fun ByteBuffer.toByteArray(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }
}

// RtmpVideoCodec carries RTMP-specific metadata (mimeType /
// displayName / preferenceValue). core.VideoCodec is the generic
// flavor that the shared VideoEncoder speaks. Map at the boundary.
private fun RtmpVideoCodec.toCore(): VideoCodec = when (this) {
    RtmpVideoCodec.H264 -> VideoCodec.H264
    RtmpVideoCodec.H265 -> VideoCodec.HEVC
}

private fun VideoCodec.toRtmp(): RtmpVideoCodec = when (this) {
    VideoCodec.H264 -> RtmpVideoCodec.H264
    VideoCodec.HEVC -> RtmpVideoCodec.H265
}

/** Atomically identifies the only session allowed to complete asynchronous sender work. */
internal class RtmpActiveSession {
    private val activeId = AtomicLong(0L)

    val id: Long
        get() = activeId.get()

    fun activate(sessionId: Long) {
        require(sessionId > 0L)
        activeId.set(sessionId)
    }

    fun deactivate(sessionId: Long) {
        if (sessionId > 0L) {
            activeId.compareAndSet(sessionId, 0L)
        }
    }

    fun isActive(sessionId: Long): Boolean = sessionId > 0L && activeId.get() == sessionId
}

/** Video state whose lifetime is the RTMP session, not an individual encoder configuration. */
internal class RtmpVideoSessionState(
    private val bufferDelayUs: Long,
) {
    var streamStartTimeNs: Long = 0L
        private set
    var lastPresentationTimeUs: Long = -1L
        private set
    private var glassesPtsOriginUs: Long = -1L
    private var glassesOutputOriginUs: Long = bufferDelayUs
    private var glassesSourceHighWaterUs: Long = -1L
    private var lastGlassesSourcePtsUs: Long = -1L
    private var pendingEpochSourcePtsUs: Long = -1L
    private var pendingEpochOutputPtsUs: Long = -1L
    var firstVideoSampleSent: Boolean = false
        private set

    val hasWallClockOrigin: Boolean
        get() = streamStartTimeNs != 0L

    val hasGlassesOrigin: Boolean
        get() = glassesPtsOriginUs >= 0L

    fun presentationTimeForWallClock(captureTimeNs: Long): Long {
        if (!hasWallClockOrigin) {
            streamStartTimeNs = captureTimeNs
            return advance(bufferDelayUs)
        }

        val elapsedUs = ((captureTimeNs - streamStartTimeNs).coerceAtLeast(0L)) / 1_000L
        return advance(bufferDelayUs + elapsedUs)
    }

    fun presentationTimeForGlasses(sourcePtsUs: Long, arrivalTimeNs: Long): Long {
        if (!hasGlassesOrigin) {
            glassesPtsOriginUs = sourcePtsUs
            glassesOutputOriginUs = bufferDelayUs
            glassesSourceHighWaterUs = sourcePtsUs
            lastGlassesSourcePtsUs = sourcePtsUs
            if (!hasWallClockOrigin) {
                streamStartTimeNs = arrivalTimeNs
            }
            return advance(bufferDelayUs)
        }

        val confirmsNewEpoch =
            pendingEpochSourcePtsUs >= 0L &&
                sourcePtsUs > lastGlassesSourcePtsUs &&
                sourcePtsUs < glassesSourceHighWaterUs

        if (confirmsNewEpoch) {
            glassesPtsOriginUs = pendingEpochSourcePtsUs
            glassesOutputOriginUs = pendingEpochOutputPtsUs
            glassesSourceHighWaterUs = sourcePtsUs
            pendingEpochSourcePtsUs = -1L
            pendingEpochOutputPtsUs = -1L
        } else if (sourcePtsUs >= glassesSourceHighWaterUs) {
            glassesSourceHighWaterUs = sourcePtsUs
            pendingEpochSourcePtsUs = -1L
            pendingEpochOutputPtsUs = -1L
        }

        val elapsedUs = (sourcePtsUs - glassesPtsOriginUs).coerceAtLeast(0L)
        val outputPtsUs = advance(glassesOutputOriginUs + elapsedUs)

        if (!confirmsNewEpoch && sourcePtsUs < glassesSourceHighWaterUs) {
            // One backward value remains an outlier until the following value proves that a new,
            // increasing source epoch has started. Keep the emitted +1ms clamp as that epoch's
            // continuity anchor so confirmed frames retain their real source deltas.
            if (pendingEpochSourcePtsUs < 0L || sourcePtsUs <= lastGlassesSourcePtsUs) {
                pendingEpochSourcePtsUs = sourcePtsUs
                pendingEpochOutputPtsUs = outputPtsUs
            }
        }
        lastGlassesSourcePtsUs = sourcePtsUs
        return outputPtsUs
    }

    fun reset() {
        streamStartTimeNs = 0L
        lastPresentationTimeUs = -1L
        glassesPtsOriginUs = -1L
        glassesOutputOriginUs = bufferDelayUs
        glassesSourceHighWaterUs = -1L
        lastGlassesSourcePtsUs = -1L
        pendingEpochSourcePtsUs = -1L
        pendingEpochOutputPtsUs = -1L
        firstVideoSampleSent = false
    }

    fun markFirstVideoSampleSent() {
        firstVideoSampleSent = true
    }

    private fun advance(candidatePtsUs: Long): Long {
        val nextPtsUs =
            if (lastPresentationTimeUs < 0L) {
                candidatePtsUs
            } else {
                // RtmpPublisher serializes timestamps in whole milliseconds. Advancing by one
                // complete wire tick prevents duplicate RTMP DTS values after a backward source PTS.
                maxOf(candidatePtsUs, lastPresentationTimeUs + RTMP_TIMESTAMP_TICK_US)
            }
        lastPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }
}

internal fun selectVideoCodecForEncoderRestart(
    forceVideoCodec: RtmpVideoCodec?,
    activeVideoCodec: RtmpVideoCodec?,
    requestedVideoCodec: RtmpVideoCodec,
): RtmpVideoCodec = forceVideoCodec ?: activeVideoCodec ?: requestedVideoCodec

/** H.265 → H.264 fallback is a startup policy, never a mid-session resolution-change policy. */
internal fun shouldFallbackH265EncoderStart(
    desiredCodec: RtmpVideoCodec,
    hadStartedEncoder: Boolean,
): Boolean = desiredCodec == RtmpVideoCodec.H265 && !hadStartedEncoder

/**
 * Crosses the old-encoder boundary without owning session state or the sender queue. This keeps
 * already-enqueued output intact and gives the caller one final non-blocking drain before teardown.
 */
internal fun drainAndStopEncoderForRestart(
    isEncoderStarted: Boolean,
    drainImmediatelyAvailable: () -> Unit,
    stopEncoder: () -> Unit,
) {
    if (isEncoderStarted) {
        drainImmediatelyAvailable()
    }
    stopEncoder()
}
