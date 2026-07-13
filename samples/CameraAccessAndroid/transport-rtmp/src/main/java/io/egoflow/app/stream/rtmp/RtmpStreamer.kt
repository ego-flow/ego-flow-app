package io.egoflow.app.stream.rtmp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import io.egoflow.app.core.encoder.VideoEncoder
import io.egoflow.app.core.transport.api.GlassesVideoFrame
import io.egoflow.app.core.transport.api.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * [RTMP Streaming Engine]
 * Encodes video frames as H.264/H.265 via MediaCodec and sends them to an RTMP server through RtmpPublisher.
 *
 * Core flow:
 * 1. start(publishUrl, codec) → enqueue SendItem.Connect on the sender queue → T_sender performs the RTMP handshake
 * 2. sendGlassesFrame(GlassesVideoFrame) → YUV encode on T_codec → enqueue the encoded sample as a SendItem
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
 *   SEND_QUEUE_CAPACITY. When it fills, a drop policy applies at enqueue time (video drops
 *   non-keyframes first, audio drops the oldest sample, lifecycle items are never dropped).
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
        // How long codec thread waits for sender to drain on normal stop.
        private const val SENDER_DRAIN_TIMEOUT_MS = 1000L

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
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var streamStartTimeNs = 0L
    private var lastPresentationTimeUs = -1L
    // Glasses-side PTS of the first frame this session; subsequent glasses frames produce output PTS
    // as (sourcePts - glassesPtsOriginUs) + STREAM_BUFFER_DELAY_US. Sentinel -1L means not yet set.
    private var glassesPtsOriginUs = -1L
    private var audioEncoder: MediaCodec? = null
    private var audioStartTimeNs = 0L
    private var lastAudioPresentationTimeUs = -1L
    private var audioCaptureStarted = false
    private var firstVideoSampleSent = false
    private var compressedVideoConfigSent = false

    // -- Shared atomic state ------------------------------------------------
    private val sessionId = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val droppedAudioChunks = AtomicLong(0L)

    // -- T_sender-owned state (mutate only on senderExecutor) ---------------
    private var publisher: RtmpPublisher? = null
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
            if (publishUrl == nextPublishUrl) return@execute
            if (publishUrl != null) {
                stopInternal(fatal = false)
            }
            RtmpDiagnostics.clear()
            RtmpDiagnostics.log("RTMP streamer start requested")
            publishUrl = nextPublishUrl
            this.diagnosticsContext = diagnosticsContext
            requestedVideoCodec = videoCodec
            audioCaptureStarted = false
            firstVideoSampleSent = false
            enqueueLifecycle(SendItem.Connect(nextSessionId, nextPublishUrl, videoCodec, diagnosticsContext))
        }
        return nextSessionId
    }

    // [Stop RTMP streaming]
    // Run stopInternal on codecExecutor: drain encoder EOS → enqueue remaining samples → enqueue SendItem.Close.
    // T_sender drains the queue, sends the final samples, then calls publisher.close().
    fun stop() {
        codecExecutor.execute { stopInternal(fatal = false) }
    }

    // [Send glasses video frame]
    // Hand the YUV buffer to codecExecutor, which feeds the encoder, drains it, and enqueues a
    // SendItem.VideoSample on the queue. When the queue is full, enqueueData drops non-keyframes first.
    //
    // PTS is derived from the glasses-side capture timestamp (videoFrame.presentationTimeUs), not
    // from arrival time on T_codec. Burst arrivals over BT/Wi-Fi previously compressed PTS gaps and
    // showed up as >30fps on the receiver; sourcing PTS from the device clock preserves the actual
    // 30fps cadence regardless of transport jitter. Mirrors the reference PresentationQueue's use
    // of videoFrame.presentationTimeUs as its base.
    fun sendGlassesFrame(videoFrame: GlassesVideoFrame) {
        sendI420GlassesFrame(
            frameData = videoFrame.copyI420(),
            width = videoFrame.width,
            height = videoFrame.height,
            sourcePtsUs = videoFrame.presentationTimeUs,
        )
    }

    // Temporary compatibility overload for the raw DAT path. Removed once
    // StreamViewModel collects Extentos frames directly.
    fun sendGlassesFrame(videoFrame: VideoFrame) {
        val sourceBuffer = videoFrame.buffer.duplicate()
        val frameData = ByteArray(sourceBuffer.remaining())
        sourceBuffer.get(frameData)
        sendI420GlassesFrame(
            frameData = frameData,
            width = videoFrame.width,
            height = videoFrame.height,
            sourcePtsUs = videoFrame.presentationTimeUs,
        )
    }

    private fun sendI420GlassesFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        sourcePtsUs: Long,
    ) {
        if (!isEnabled()) return
        val expectedSessionId = sessionId.get()
        codecExecutor.execute {
            if (!isActiveSession(expectedSessionId)) return@execute
            try {
                ensureStarted(width, height)
                queueFrame(frameData, width, height, glassesFramePresentationTimeUs(sourcePtsUs))
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
        // 0.7.0 surfaces HEVC codec config (VPS/SPS/PPS) as its own frame flagged
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
                enqueueData(SendItem.VideoSample(expectedSessionId, RtmpVideoCodec.H265, bytes, bufferInfo, isKeyFrame))
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
        return expectedSessionId != 0L && sessionId.get() == expectedSessionId && !publishUrl.isNullOrBlank()
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
    // The encoder's own lifecycle is owned by VideoEncoder; RtmpStreamer only handles (a) codec
    // fallback policy and (b) the PTS-counter reset that accompanies a restart.
    // Any prior-encoder VideoSample/VideoConfig items still in the queue are discarded to avoid PTS jumps.
    private fun restartEncoder(width: Int, height: Int, forceVideoCodec: RtmpVideoCodec? = null) {
        videoEncoder.stop()
        sendQueue.removeIf { it is SendItem.VideoSample || it is SendItem.VideoConfig }

        val desiredRtmpCodec = forceVideoCodec
            ?: videoEncoder.activeCodec?.toRtmp()
            ?: requestedVideoCodec
        try {
            videoEncoder.start(width, height, desiredRtmpCodec.toCore())
        } catch (error: Exception) {
            if (desiredRtmpCodec == RtmpVideoCodec.H265 && !firstVideoSampleSent) {
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
        streamStartTimeNs = 0L
        lastPresentationTimeUs = -1L
        glassesPtsOriginUs = -1L
        firstVideoSampleSent = false
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
    // When the queue is full, enqueueData applies the drop policy (non-keyframes first).
    // FormatChanged → VideoConfig, Sample → VideoSample. The actual send happens on T_sender.
    private fun drainVideoEncoder(endOfStream: Boolean) {
        val currentSessionId = sessionId.get()
        if (endOfStream) {
            videoEncoder.signalEndOfStream(framePresentationTimeUs(System.nanoTime()))
        }
        while (true) {
            val result = videoEncoder.drainNext() ?: return
            val active = videoEncoder.activeCodec?.toRtmp() ?: return
            when (result) {
                is VideoEncoder.DrainResult.FormatChanged -> {
                    enqueueData(SendItem.VideoConfig(currentSessionId, active, result.format))
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
        val currentSessionId = sessionId.get()
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
        if (streamStartTimeNs == 0L) {
            streamStartTimeNs = captureTimeNs
            lastPresentationTimeUs = STREAM_BUFFER_DELAY_US
            RtmpDiagnostics.log("PTS origin initialized (wall-clock, buffer=${STREAM_BUFFER_DELAY_MS}ms)")
            return STREAM_BUFFER_DELAY_US
        }

        val elapsedUs = ((captureTimeNs - streamStartTimeNs).coerceAtLeast(0L)) / 1_000L
        val nextPtsUs = maxOf(STREAM_BUFFER_DELAY_US + elapsedUs, lastPresentationTimeUs + 1L)
        val gapUs = nextPtsUs - lastPresentationTimeUs
        if (lastPresentationTimeUs >= 0L && gapUs > 1_500_000L) {
            RtmpDiagnostics.log("Large PTS gap ${(gapUs / 1000L)}ms")
        }
        lastPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }

    // Glasses video, where the source-side capture timestamp (videoFrame.presentationTimeUs) is the
    // ground truth for inter-frame cadence. The first frame anchors glassesPtsOriginUs; subsequent
    // frames preserve the device-side gaps exactly. Also seeds streamStartTimeNs to the wall-clock
    // moment of first arrival so audioPresentationTimeUs has a reference for AV alignment.
    private fun glassesFramePresentationTimeUs(sourcePtsUs: Long): Long {
        if (glassesPtsOriginUs < 0L) {
            glassesPtsOriginUs = sourcePtsUs
            if (streamStartTimeNs == 0L) {
                streamStartTimeNs = System.nanoTime()
            }
            lastPresentationTimeUs = STREAM_BUFFER_DELAY_US
            RtmpDiagnostics.log("PTS origin initialized (glasses pts=${sourcePtsUs / 1000L}ms, buffer=${STREAM_BUFFER_DELAY_MS}ms)")
            return STREAM_BUFFER_DELAY_US
        }

        val elapsedUs = (sourcePtsUs - glassesPtsOriginUs).coerceAtLeast(0L)
        val nextPtsUs = maxOf(STREAM_BUFFER_DELAY_US + elapsedUs, lastPresentationTimeUs + 1L)
        val gapUs = nextPtsUs - lastPresentationTimeUs
        if (lastPresentationTimeUs >= 0L && gapUs > 1_500_000L) {
            RtmpDiagnostics.log("Large PTS gap ${(gapUs / 1000L)}ms")
        }
        lastPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }

    private fun audioPresentationTimeUs(captureTimeNs: Long): Long {
        if (audioStartTimeNs == 0L) {
            audioStartTimeNs = if (streamStartTimeNs != 0L) streamStartTimeNs else captureTimeNs
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

    private fun stopInternal(fatal: Boolean) {
        val closingSessionId = sessionId.get()
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
        configuredWidth = 0
        configuredHeight = 0
        streamStartTimeNs = 0L
        lastPresentationTimeUs = -1L
        glassesPtsOriginUs = -1L
        firstVideoSampleSent = false
        compressedVideoConfigSent = false
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
        enqueueLifecycle(SendItem.Close(closingSessionId))

        if (!fatal) {
            // Block T_codec until the sender drains everything queued so far +
            // the Close item -- gives callers an observable "stream is fully closed".
            waitForQueueDrain(SENDER_DRAIN_TIMEOUT_MS)
        }
        RtmpDiagnostics.log("RTMP streamer stopped")
    }

    private fun waitForQueueDrain(timeoutMs: Long) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (!sendQueue.isEmpty() && System.nanoTime() < deadlineNs) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun ensureAudioCaptureStarted() {
        if (audioCaptureStarted) return
        if (!isAudioStreamingEnabled()) return
        audioRecorder.start()
        audioCaptureStarted = true
        RtmpDiagnostics.log("Audio capture started after first video sample")
    }

    private fun isAudioStreamingEnabled(): Boolean = audioEnabledProvider()

    private fun handleVideoCodecFailure(stage: String, error: Exception): Boolean {
        if (videoEncoder.activeCodec != VideoCodec.HEVC || firstVideoSampleSent) return false
        val width = configuredWidth
        val height = configuredHeight
        if (width <= 0 || height <= 0) return false
        Log.w(TAG, "HEVC $stage failed, retrying with AVC", error)
        RtmpDiagnostics.log("HEVC $stage failed, retrying with H.264")
        restartEncoder(width, height, RtmpVideoCodec.H264)
        return true
    }

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
        when (tryEnqueueData(sendQueue, item)) {
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
                    RtmpDiagnostics.log("Dropped video sample (queue full, no eviction candidate)")
                }
                is SendItem.AudioSample -> {
                    droppedAudioChunks.incrementAndGet()
                    RtmpDiagnostics.log("Dropped audio sample (queue full)")
                }
                else -> Unit
            }
        }
    }

    /** Push a lifecycle item ([SendItem.Connect]/[SendItem.VideoConfig]/[SendItem.AudioConfig]/
     *  [SendItem.Close]) onto the queue. Lifecycle items are never dropped; we wait briefly,
     *  then evict data items to make room. */
    private fun enqueueLifecycle(item: SendItem) {
        val evicted = tryEnqueueLifecycle(sendQueue, item, LIFECYCLE_OFFER_TIMEOUT_MS)
        if (evicted) {
            RtmpDiagnostics.log("Evicted samples to make room for lifecycle item ${item::class.simpleName}")
        }
    }

    // -- Callbacks posted from T_sender → T_codec ---------------------------

    private fun onSendFailure(failedSessionId: Long, stage: String, error: Exception) {
        if (!isActiveSession(failedSessionId)) return
        if (handleVideoCodecFailure(stage, error)) return
        failActiveSession(failedSessionId, "Failed during $stage", stage, error)
    }

    private fun onFirstVideoSampleSentFromSender(id: Long) {
        if (!isActiveSession(id)) return
        if (firstVideoSampleSent) return
        firstVideoSampleSent = true
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
                }
                publisher = null
                senderSessionId = 0L
                senderVideoSamples = 0L
                senderAudioSamples = 0L
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
                is SendItem.VideoConfig -> p.sendVideoConfig(item.codec, item.format)
                is SendItem.VideoSample -> {
                    if (p.sendVideoSample(item.codec, item.data, item.info)) {
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
            try {
                RtmpPublisher(item.url, item.codec, item.diagnosticsContext).also { it.connect() }
            } catch (e: Exception) {
                codecExecutor.execute { onSendFailure(item.sessionId, "Connect", e) }
                return
            }

        // Discard if a newer session has already superseded this one.
        if (sessionId.get() != item.sessionId) {
            try { newPublisher.close() } catch (_: Exception) {}
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
