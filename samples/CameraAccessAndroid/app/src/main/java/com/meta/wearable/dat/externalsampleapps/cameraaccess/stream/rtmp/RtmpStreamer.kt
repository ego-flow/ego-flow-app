package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.rtmp

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RtmpStreamer {
    companion object {
        private const val TAG = "RtmpStreamer"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TARGET_FPS = 24
        private const val IFRAME_INTERVAL_SECONDS = 2
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 64_000
    }

    private enum class InputColorFormat {
        I420,
        NV12,
    }

    private data class EncoderConfig(
        val codecName: String,
        val colorFormat: Int,
        val inputColorFormat: InputColorFormat,
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var publisher: RtmpPublisher? = null
    private var encoder: MediaCodec? = null
    private var encoderConfig: EncoderConfig? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var streamStartTimeNs = 0L
    private var lastPresentationTimeUs = -1L
    private var sentSamples = 0L
    private var audioEncoder: MediaCodec? = null
    private var audioStartTimeNs = 0L
    private var lastAudioPresentationTimeUs = -1L
    private var sentAudioSamples = 0L
    private val audioRecorder = RtmpAudioRecorder(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
        onPcmData = { pcm, captureTimeNs ->
            executor.execute {
                try {
                    ensureAudioStarted()
                    queueAudio(pcm, captureTimeNs)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to send audio sample", error)
                    RtmpDiagnostics.log("Audio sample failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
                    stopInternal()
                }
            }
        }
    }

    fun start() {
        if (!isEnabled()) return
        executor.execute {
            if (publisher != null) return@execute
            try {
                RtmpDiagnostics.clear()
                RtmpDiagnostics.log("RTMP streamer start requested")
                publisher = RtmpPublisher(SettingsManager.rtmpPublishUrl).also { it.connect() }
                audioRecorder.start()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to connect RTMP publisher", error)
                RtmpDiagnostics.log("Connect failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
                stopInternal()
            }
        }
    }

    fun stop() {
        executor.execute { stopInternal() }
    }

    fun sendGlassesFrame(videoFrame: VideoFrame) {
        if (!isEnabled()) return
        val sourceBuffer = videoFrame.buffer.duplicate()
        val frameData = ByteArray(sourceBuffer.remaining())
        sourceBuffer.get(frameData)
        executor.execute {
            try {
                ensureStarted(videoFrame.width, videoFrame.height)
                queueFrame(frameData, videoFrame.width, videoFrame.height, System.nanoTime())
            } catch (error: Exception) {
                Log.e(TAG, "Failed to send glasses frame", error)
                RtmpDiagnostics.log("Glasses frame failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
                stopInternal()
            }
        }
    }

    fun sendBitmapFrame(bitmap: Bitmap) {
        if (!isEnabled()) return
        val safeBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = safeBitmap.width and 0xFFFE
        val height = safeBitmap.height and 0xFFFE
        val evenBitmap =
            if (width != safeBitmap.width || height != safeBitmap.height) {
                Bitmap.createScaledBitmap(safeBitmap, width, height, true)
            } else {
                safeBitmap
            }
        val frameData = RtmpFrameConverter.bitmapToI420(evenBitmap)
        executor.execute {
            try {
                ensureStarted(width, height)
                queueFrame(frameData, width, height, System.nanoTime())
            } catch (error: Exception) {
                Log.e(TAG, "Failed to send bitmap frame", error)
                RtmpDiagnostics.log("Phone frame failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
                stopInternal()
            }
        }
    }

    private fun isEnabled(): Boolean {
        return SettingsManager.rtmpEnabled && SettingsManager.rtmpPublishUrl.isNotBlank()
    }

    private fun ensureStarted(width: Int, height: Int) {
        if (publisher == null) {
            publisher = RtmpPublisher(SettingsManager.rtmpPublishUrl).also { it.connect() }
            audioRecorder.start()
        }
        if (encoder == null || width != configuredWidth || height != configuredHeight) {
            restartEncoder(width, height)
        }
    }

    private fun ensureAudioStarted() {
        if (publisher == null) {
            publisher = RtmpPublisher(SettingsManager.rtmpPublishUrl).also { it.connect() }
        }
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
        sentAudioSamples = 0L
        RtmpDiagnostics.log("Audio encoder started ${AUDIO_SAMPLE_RATE}Hz/${AUDIO_CHANNEL_COUNT}ch")
        drainAudioEncoder(endOfStream = false)
    }

    private fun restartEncoder(width: Int, height: Int) {
        encoder?.stop()
        encoder?.release()
        encoder = null

        val config = selectEncoderConfig()
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, config.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        encoder = MediaCodec.createByCodecName(config.codecName).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        encoderConfig = config
        configuredWidth = width
        configuredHeight = height
        streamStartTimeNs = 0L
        lastPresentationTimeUs = -1L
        sentSamples = 0L
        RtmpDiagnostics.log("Encoder restarted ${width}x${height} codec=${config.codecName}")
        drainEncoder(endOfStream = false)
    }

    private fun queueFrame(i420Frame: ByteArray, width: Int, height: Int, captureTimeNs: Long) {
        val encoder = requireNotNull(encoder)
        val inputIndex = encoder.dequeueInputBuffer(5_000)
        if (inputIndex < 0) return
        val targetInput = encoder.getInputBuffer(inputIndex) ?: return
        targetInput.clear()
        val converted = convertForEncoder(i420Frame, width, height)
        val presentationTimeUs = framePresentationTimeUs(captureTimeNs)
        if (converted.size > targetInput.remaining()) {
            Log.w(TAG, "Dropping frame because encoder input buffer is too small")
            RtmpDiagnostics.log("Dropped frame: input buffer too small (${converted.size} > ${targetInput.remaining()})")
            encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            return
        }
        targetInput.put(converted)
        encoder.queueInputBuffer(inputIndex, 0, converted.size, presentationTimeUs, 0)
        drainEncoder(endOfStream = false)
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

    private fun convertForEncoder(i420Frame: ByteArray, width: Int, height: Int): ByteArray {
        return when (encoderConfig?.inputColorFormat) {
            InputColorFormat.NV12 -> RtmpFrameConverter.i420ToNv12(i420Frame, width, height)
            else -> i420Frame
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = encoder ?: return
        val publisher = publisher ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            val inputIndex = encoder.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    framePresentationTimeUs(System.nanoTime()),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        while (true) {
            when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    publisher.sendVideoConfig(encoder.outputFormat)
                    RtmpDiagnostics.log("Encoder output format changed")
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
                    publisher.sendVideoSample(encoded, bufferInfo)
                    sentSamples++
                    if (sentSamples == 1L || sentSamples % 120L == 0L) {
                        val ptsMs = bufferInfo.presentationTimeUs / 1000L
                        val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        RtmpDiagnostics.log("Sent sample #$sentSamples pts=${ptsMs}ms size=${bufferInfo.size} key=$isKeyFrame")
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        RtmpDiagnostics.log("Encoder reached end of stream")
                        return
                    }
                }
            }
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val encoder = audioEncoder ?: return
        val publisher = publisher ?: return
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
                    publisher.sendAudioConfig(encoder.outputFormat)
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
                    publisher.sendAudioSample(encoded, bufferInfo)
                    sentAudioSamples++
                    if (sentAudioSamples == 1L || sentAudioSamples % 120L == 0L) {
                        val ptsMs = bufferInfo.presentationTimeUs / 1000L
                        RtmpDiagnostics.log("Sent audio #$sentAudioSamples pts=${ptsMs}ms size=${bufferInfo.size}")
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        RtmpDiagnostics.log("Audio encoder reached end of stream")
                        return
                    }
                }
            }
        }
    }

    private fun framePresentationTimeUs(captureTimeNs: Long): Long {
        if (streamStartTimeNs == 0L) {
            streamStartTimeNs = captureTimeNs
            lastPresentationTimeUs = 0L
            RtmpDiagnostics.log("PTS origin initialized")
            return 0L
        }

        val elapsedUs = ((captureTimeNs - streamStartTimeNs).coerceAtLeast(0L)) / 1_000L
        val nextPtsUs = maxOf(elapsedUs, lastPresentationTimeUs + 1L)
        val gapUs = nextPtsUs - lastPresentationTimeUs
        if (lastPresentationTimeUs >= 0L && gapUs > 1_500_000L) {
            RtmpDiagnostics.log("Large PTS gap ${(gapUs / 1000L)}ms")
        }
        lastPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }

    private fun audioPresentationTimeUs(captureTimeNs: Long): Long {
        if (audioStartTimeNs == 0L) {
            audioStartTimeNs = captureTimeNs
            lastAudioPresentationTimeUs = 0L
            return 0L
        }

        val elapsedUs = ((captureTimeNs - audioStartTimeNs).coerceAtLeast(0L)) / 1_000L
        val nextPtsUs = maxOf(elapsedUs, lastAudioPresentationTimeUs + 1L)
        lastAudioPresentationTimeUs = nextPtsUs
        return nextPtsUs
    }

    private fun stopInternal() {
        audioRecorder.stop()
        try {
            drainAudioEncoder(endOfStream = true)
        } catch (_: Exception) {
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
        sentAudioSamples = 0L
        try {
            drainEncoder(endOfStream = true)
        } catch (_: Exception) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        encoderConfig = null
        configuredWidth = 0
        configuredHeight = 0
        streamStartTimeNs = 0L
        lastPresentationTimeUs = -1L
        sentSamples = 0L
        publisher?.close()
        publisher = null
        RtmpDiagnostics.log("RTMP streamer stopped")
    }

    private fun selectEncoderConfig(): EncoderConfig {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        codecInfos.forEach { codecInfo ->
            if (!codecInfo.isEncoder) return@forEach
            if (MIME_TYPE !in codecInfo.supportedTypes) return@forEach
            val capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE)
            val formats = capabilities.colorFormats.toSet()
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats) {
                return EncoderConfig(
                    codecName = codecInfo.name,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    inputColorFormat = InputColorFormat.I420,
                )
            }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats) {
                return EncoderConfig(
                    codecName = codecInfo.name,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    inputColorFormat = InputColorFormat.NV12,
                )
            }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats) {
                return EncoderConfig(
                    codecName = codecInfo.name,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    inputColorFormat = InputColorFormat.I420,
                )
            }
        }
        error("No H.264 encoder with YUV420 input support was found on this device")
    }

    private fun ByteBuffer.toByteArray(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }
}
