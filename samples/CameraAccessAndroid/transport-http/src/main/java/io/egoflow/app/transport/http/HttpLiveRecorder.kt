/*
 * HttpLiveRecorder -- drives the shared :core VideoEncoder on a single "http-codec"
 * thread and feeds its output into a FragmentedMp4Muxer, emitting append-only fMP4
 * segments (init + moof/mdat fragments) as capture proceeds.
 *
 * Replaces the old HttpMuxRecorder, which wrote a seekable MP4 via MediaMuxer (moov
 * at end + mdat back-patch -- not append-only) and only handed the finished file off
 * after stop(). Here nothing is written to disk: each segment goes straight to the
 * caller's [onSegment] sink (the live uploader), so chunk transfer overlaps capture.
 *
 * PTS bookkeeping mirrors the old recorder (two source variants, strictly increasing
 * timestamps as MediaMuxer/fMP4 both require).
 */
package io.egoflow.app.transport.http

import android.media.MediaCodec
import android.util.Log
import io.egoflow.app.core.encoder.VideoEncoder
import io.egoflow.app.core.transport.api.VideoCodec
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

class HttpLiveRecorder(
    private val codec: VideoCodec,
    // Receives the fMP4 init segment then each media fragment, on the codec thread.
    onSegment: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "HttpLiveRecorder"
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "http-codec") }
    private val encoder = VideoEncoder()
    private val muxer = FragmentedMp4Muxer(codec, onSegment)
    private val frameNormalizer = I420FrameNormalizer(LibWebRtcI420Scaler)

    private var encoderStarted = false

    // Count of real (non codec-config) samples handed to the muxer -- sampled by the
    // UI for the "out" FPS readout. With live upload this also tracks bytes-on-wire.
    private val sampleCount = AtomicLong(0L)

    // Monotonic PTS bookkeeping (single-threaded on the executor, no locking).
    private var streamStartNs = 0L
    private var lastPtsUs = -1L
    private var glassesOriginUs = -1L

    fun videoFramesSent(): Long = sampleCount.get()

    /** Queue a glasses frame. [bytes] must already be a private copy. Uses the source
     *  PTS as the monotonic origin. */
    fun queueGlassesFrame(bytes: ByteArray, width: Int, height: Int, sourcePtsUs: Long) {
        submit {
            val frame = normalizeFrame(bytes, width, height) ?: return@submit
            if (!ensureStarted(frame.width, frame.height)) return@submit
            encoder.queue(frame.bytes, frame.width, frame.height, glassesPtsUs(sourcePtsUs))
            drain(endOfStream = false)
        }
    }

    /** Queue a phone frame ([i420] already a private copy). Uses wall-clock PTS. */
    fun queuePhoneFrame(i420: ByteArray, width: Int, height: Int) {
        submit {
            val frame = normalizeFrame(i420, width, height) ?: return@submit
            if (!ensureStarted(frame.width, frame.height)) return@submit
            encoder.queue(frame.bytes, frame.width, frame.height, phonePtsUs(System.nanoTime()))
            drain(endOfStream = false)
        }
    }

    /** Finalize on the codec thread: flush the encoder tail, emit the last fragment,
     *  release the encoder. Blocks until teardown completes. Safe to call once. */
    fun stop() {
        val task = FutureTask {
            try {
                if (encoderStarted) {
                    encoder.signalEndOfStream(lastPtsUs.coerceAtLeast(0L))
                    drain(endOfStream = true)
                    muxer.finish()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Drain on stop failed", e)
            }
            encoder.stop()
        }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            return
        }
        try {
            task.get()
        } catch (e: Exception) {
            Log.w(TAG, "stop() await failed", e)
        }
        executor.shutdown()
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    // A single bad frame must not kill the codec thread.
                    Log.w(TAG, "Frame processing failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Recorder already stopping; drop the late frame.
        }
    }

    private fun ensureStarted(width: Int, height: Int): Boolean {
        if (encoderStarted) return true
        return try {
            encoder.start(width, height, codec)
            encoderStarted = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            false
        }
    }

    private fun normalizeFrame(bytes: ByteArray, width: Int, height: Int): I420NormalizationResult.Frame? {
        return when (val result = frameNormalizer.normalize(bytes, width, height)) {
            is I420NormalizationResult.Frame -> result
            is I420NormalizationResult.Dropped -> {
                if (result.cause == null) {
                    Log.w(TAG, result.reason)
                } else {
                    Log.w(TAG, result.reason, result.cause)
                }
                null
            }
        }
    }

    private fun drain(endOfStream: Boolean) {
        // While finalizing, the encoder may not have flushed its tail yet, so a null
        // (TRY_AGAIN_LATER) is transient -- retry briefly until the EndOfStream marker
        // rather than dropping the last frames. During normal draining, null just means
        // "nothing ready right now" and we return immediately.
        var emptyPolls = 0
        while (true) {
            val result = encoder.drainNext()
            if (result == null) {
                if (!endOfStream) return
                if (++emptyPolls > 200) return // ~1s safety cap
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            emptyPolls = 0
            when (result) {
                is VideoEncoder.DrainResult.FormatChanged -> muxer.onFormat(result.format)
                is VideoEncoder.DrainResult.Sample -> {
                    // csd (SPS/PPS/VPS) already rode in via the format's csd buffers;
                    // never write the standalone codec-config sample as media.
                    val isConfig = result.info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && result.info.size > 0) {
                        val isKey = result.info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        muxer.onSample(result.data, result.info.presentationTimeUs, isKey)
                        sampleCount.incrementAndGet()
                    }
                }
                VideoEncoder.DrainResult.EndOfStream -> return
            }
        }
    }

    private fun phonePtsUs(captureNs: Long): Long {
        if (streamStartNs == 0L) {
            streamStartNs = captureNs
            lastPtsUs = 0L
            return 0L
        }
        val elapsed = ((captureNs - streamStartNs).coerceAtLeast(0L)) / 1_000L
        val next = maxOf(elapsed, lastPtsUs + 1L)
        lastPtsUs = next
        return next
    }

    private fun glassesPtsUs(sourcePtsUs: Long): Long {
        if (glassesOriginUs < 0L) {
            glassesOriginUs = sourcePtsUs
            lastPtsUs = 0L
            return 0L
        }
        val elapsed = (sourcePtsUs - glassesOriginUs).coerceAtLeast(0L)
        val next = maxOf(elapsed, lastPtsUs + 1L)
        lastPtsUs = next
        return next
    }
}
