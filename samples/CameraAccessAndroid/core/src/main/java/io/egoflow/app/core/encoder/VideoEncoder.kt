/*
 * Mechanical MediaCodec wrapper shared by every transport.
 *
 * Knows how to negotiate color format, queue I420 frames, and drain
 * encoded NALs out of MediaCodec. Does NOT know about RTMP, ego-flow
 * auth, slab-append, codec-fallback policy, or PTS bookkeeping
 * (callers control PTS so the same monotonic timeline drives both
 * the encoder path and the compressed-glasses pass-through).
 *
 * Lives in :core so :transport-rtmp and :transport-slab can both
 * use the same encoder pipeline -- no per-transport duplication.
 */
package io.egoflow.app.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import io.egoflow.app.core.transport.api.VideoCodec
import java.nio.ByteBuffer

class VideoEncoder {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val TARGET_FPS = 24
        private const val IFRAME_INTERVAL_SECONDS = 2
    }

    private enum class InputColorFormat {
        I420,
        NV12,
    }

    private data class Config(
        val codecName: String,
        val videoCodec: VideoCodec,
        val colorFormat: Int,
        val inputColorFormat: InputColorFormat,
    )

    /** Result of one `drainNext()` call. Mirrors what MediaCodec's
     *  `dequeueOutputBuffer` would surface, but cooked so the caller
     *  never touches the codec's internal buffer pool -- bytes are
     *  copied + buffer released before we return. */
    sealed class DrainResult {
        data class FormatChanged(val format: MediaFormat) : DrainResult()
        data class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo) : DrainResult()
        object EndOfStream : DrainResult()
    }

    private var encoder: MediaCodec? = null
    private var config: Config? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    // Reused per-frame NV12 buffer for semi-planar encoders; sized once at start().
    private var nv12Scratch: ByteArray? = null

    val activeCodec: VideoCodec?
        get() = config?.videoCodec
    val isStarted: Boolean
        get() = encoder != null

    /** Configure + start the encoder. Throws if the device has no
     *  encoder that can take YUV420 input for the requested codec --
     *  caller decides whether to retry with a different codec. */
    fun start(width: Int, height: Int, codec: VideoCodec) {
        check(encoder == null) { "Encoder already started; call stop() first" }
        val (mc, cfg) = create(width, height, codec)
        encoder = mc
        config = cfg
        configuredWidth = width
        configuredHeight = height
        nv12Scratch =
            if (cfg.inputColorFormat == InputColorFormat.NV12) {
                ByteArray(width * height * 3 / 2)
            } else {
                null
            }
    }

    /** Push one I420 frame into the encoder's input queue. PTS is
     *  caller-supplied so a single monotonic clock can drive both
     *  the encoder-path and the compressed-glasses pass-through. */
    fun queue(i420Frame: ByteArray, width: Int, height: Int, presentationTimeUs: Long) {
        val enc = encoder ?: return
        check(width == configuredWidth && height == configuredHeight) {
            "Frame dimensions changed (${width}x${height}) without restart -- caller must stop()+start()"
        }
        val inputIndex = enc.dequeueInputBuffer(5_000)
        if (inputIndex < 0) return
        val targetInput = enc.getInputBuffer(inputIndex) ?: return
        targetInput.clear()
        val converted = convertForEncoder(i420Frame, width, height)
        if (converted.size > targetInput.remaining()) {
            Log.w(TAG, "Dropping frame because encoder input buffer is too small")
            enc.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            return
        }
        targetInput.put(converted)
        enc.queueInputBuffer(inputIndex, 0, converted.size, presentationTimeUs, 0)
    }

    /** Queue the EOS sentinel so the encoder flushes its pipeline.
     *  Call this once before the final `drainNext()` loop on stop. */
    fun signalEndOfStream(presentationTimeUs: Long) {
        val enc = encoder ?: return
        val inputIndex = enc.dequeueInputBuffer(0)
        if (inputIndex < 0) return
        enc.queueInputBuffer(
            inputIndex,
            0,
            0,
            presentationTimeUs,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    /** Pull at most one output entry. Returns null when the encoder
     *  has nothing right now (`INFO_TRY_AGAIN_LATER`) or isn't
     *  running. Caller loops until null or EndOfStream. */
    fun drainNext(): DrainResult? {
        val enc = encoder ?: return null
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = enc.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return DrainResult.FormatChanged(enc.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated path on modern Android; keep looping.
                }
                else -> {
                    if (outputIndex < 0) continue
                    val outputBuffer = enc.getOutputBuffer(outputIndex)
                    if (outputBuffer == null) {
                        enc.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    // Copy bytes + release the codec's buffer immediately so
                    // we never hold the pool hostage across a slow consumer
                    // (RTMP socket, MKV writer, slab uploader, ...).
                    val info = MediaCodec.BufferInfo().apply {
                        set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    val data = outputBuffer.toByteArray(bufferInfo)
                    enc.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Emit any payload before the EOS marker (codec may
                        // send a final sample with EOS flag set), but the
                        // caller's loop terminates on EndOfStream.
                        return if (data.isNotEmpty()) DrainResult.Sample(data, info) else DrainResult.EndOfStream
                    }
                    return DrainResult.Sample(data, info)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    /** Stop the encoder. Swallows all teardown exceptions -- at this
     *  point we're tearing down regardless and partial failure of
     *  one stop step shouldn't block the others. */
    fun stop() {
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        config = null
        configuredWidth = 0
        configuredHeight = 0
        nv12Scratch = null
    }

    private fun create(width: Int, height: Int, codec: VideoCodec): Pair<MediaCodec, Config> {
        val cfg = select(codec)
        val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, cfg.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SECONDS)
            // No B-frames: keeps decode order == presentation order (DTS == PTS), which
            // the HTTP fMP4 muxer relies on (it has only PTS, no DTS) and which lowers
            // latency for the realtime transports. Baseline H.264 already implies this;
            // set it explicitly so HEVC (and any non-baseline profile) honors it too.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (codec == VideoCodec.H264) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
        }
        val mc = MediaCodec.createByCodecName(cfg.codecName).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
            try {
                setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {
            }
        }
        return mc to cfg
    }

    private fun select(videoCodec: VideoCodec): Config {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        codecInfos.forEach { codecInfo ->
            if (!codecInfo.isEncoder) return@forEach
            if (videoCodec.mimeType !in codecInfo.supportedTypes) return@forEach
            val capabilities = codecInfo.getCapabilitiesForType(videoCodec.mimeType)
            val formats = capabilities.colorFormats.toSet()
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats) {
                return Config(
                    codecName = codecInfo.name,
                    videoCodec = videoCodec,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    inputColorFormat = InputColorFormat.I420,
                )
            }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats) {
                return Config(
                    codecName = codecInfo.name,
                    videoCodec = videoCodec,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    inputColorFormat = InputColorFormat.NV12,
                )
            }
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats) {
                return Config(
                    codecName = codecInfo.name,
                    videoCodec = videoCodec,
                    colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    inputColorFormat = InputColorFormat.I420,
                )
            }
        }
        error("No ${videoCodec.name} encoder with YUV420 input support was found on this device")
    }

    private fun convertForEncoder(i420Frame: ByteArray, width: Int, height: Int): ByteArray {
        return when (config?.inputColorFormat) {
            InputColorFormat.NV12 -> {
                // Reuse the scratch buffer; it's copied straight into the codec input
                // buffer in queue(), so it's safe to overwrite next frame.
                val scratch = nv12Scratch ?: ByteArray(width * height * 3 / 2).also { nv12Scratch = it }
                YuvFrameConverter.i420ToNv12(i420Frame, width, height, scratch)
                scratch
            }
            else -> i420Frame
        }
    }

    private fun ByteBuffer.toByteArray(info: MediaCodec.BufferInfo): ByteArray {
        position(info.offset)
        limit(info.offset + info.size)
        return ByteArray(info.size).also { get(it) }
    }
}
