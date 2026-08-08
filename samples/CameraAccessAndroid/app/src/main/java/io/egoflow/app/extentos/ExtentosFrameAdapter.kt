package io.egoflow.app.extentos

import android.graphics.BitmapFactory
import com.extentos.glasses.core.VideoFrame
import com.extentos.glasses.core.VideoFrameFormat
import io.egoflow.app.core.encoder.YuvFrameConverter
import io.egoflow.app.core.transport.api.GlassesVideoFrame

internal data class DecodedArgbFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

internal fun interface JpegFrameDecoder {
  fun decode(jpeg: ByteArray): DecodedArgbFrame
}

internal data class AdaptedExtentosFrame(
    val frame: GlassesVideoFrame,
    /** Payload size of the source frame, whatever its format. */
    val sourceSizeBytes: Int,
    val decodeDurationUs: Long,
    val conversionDurationUs: Long,
    /** True when the frame arrived as planar I420 and needed no decode. */
    val usedRawYuv: Boolean = false,
)

/**
 * Turns an Extentos [VideoFrame] into EgoFlow's transport-owned I420 frame.
 *
 * Two paths now:
 *
 *  - **RAW_YUV (preferred).** The SDK hands over planar I420 directly, so there is
 *    nothing to decode or convert — `decodeDurationUs` and `conversionDurationUs`
 *    are both 0. This is the whole point of the change: the JPEG decode and the
 *    software ARGB->I420 conversion used to run on EVERY frame of a continuous
 *    stream, on a phone that is simultaneously encoding and publishing.
 *
 *  - **JPEG.** Unchanged from before, and still the default the SDK serves.
 *
 * The branch is on [VideoFrame.format] alone, because the SDK guarantees the label
 * matches the payload. On hardware the raw branch has no encode fallback at all
 * (`MetaHardwareBridge.videoFrames`: `if (rawRequested) bytes`), and in the browser
 * simulator a frame that cannot be converted to I420 is DROPPED rather than emitted
 * as JPEG (`BrowserSimTransport.videoFrames`: `jpegToI420(data) ?: return`). So a
 * frame labelled `RAW_YUV` is always planar I420, on both substrates.
 *
 * That is why a size mismatch is a hard error here rather than a per-frame fall
 * through to [adaptJpeg]. `GlassesVideoFrame` requires exactly
 * `width * height * 3 / 2`, and whether the platform buffer is tightly packed or
 * stride-padded is a property of the underlying camera stack. But a stride-padded
 * raw buffer is still raw: JPEG-decoding it cannot succeed, so routing it to the
 * JPEG path would only convert a precise layout error into a confusing
 * "not a complete JPEG payload" one. Recovering from that condition means ending
 * the collection and re-requesting the stream as
 * `VideoFrameConfig(format = JPEG)`, which is a stream-level decision and not
 * something this adapter can do per frame.
 *
 * Timestamps come straight from [VideoFrame.timestampUs]. The SDK guarantees they
 * are strictly increasing per stream (it clamps a non-advancing source timestamp
 * to previous + 1), which is what the old `lastPresentationTimeUs` clamp here was
 * compensating for before that guarantee existed.
 */
internal class ExtentosFrameAdapter(
    private val jpegDecoder: JpegFrameDecoder = AndroidJpegFrameDecoder,
    private val nanoTime: () -> Long = System::nanoTime,
) {

  fun adapt(source: VideoFrame): AdaptedExtentosFrame =
      when (source.format) {
        VideoFrameFormat.RAW_YUV -> adaptRawYuv(source)
        else -> adaptJpeg(source)
      }

  /**
   * Planar I420 straight through. Validates the layout [GlassesVideoFrame] requires
   * BEFORE constructing it, so a bad buffer produces an error naming the actual
   * problem instead of an opaque failure inside the frame's init.
   */
  private fun adaptRawYuv(source: VideoFrame): AdaptedExtentosFrame {
    require(source.width > 0 && source.height > 0) {
      "Extentos RAW_YUV frame has non-positive dimensions ${source.width}x${source.height}"
    }
    require(source.width % 2 == 0 && source.height % 2 == 0) {
      "Extentos RAW_YUV frame ${source.width}x${source.height} has odd dimensions; " +
          "I420 subsamples chroma by two in each axis"
    }
    val expected = source.width.toLong() * source.height.toLong() * 3L / 2L
    require(source.data.size.toLong() == expected) {
      "Extentos RAW_YUV frame is not packed I420: ${source.width}x${source.height} " +
          "requires $expected bytes, got ${source.data.size}. A stride-padded or " +
          "otherwise non-packed layout cannot be reinterpreted as I420. Recover by " +
          "restarting the stream as VideoFrameConfig(format = JPEG); it cannot be " +
          "salvaged per frame."
    }

    return AdaptedExtentosFrame(
        frame =
            GlassesVideoFrame(
                i420 = source.data,
                width = source.width,
                height = source.height,
                presentationTimeUs = source.timestampUs,
            ),
        sourceSizeBytes = source.data.size,
        decodeDurationUs = 0,
        conversionDurationUs = 0,
        usedRawYuv = true,
    )
  }

  private fun adaptJpeg(source: VideoFrame): AdaptedExtentosFrame {
    require(isCompleteJpeg(source.data)) { "Extentos frame is not a complete JPEG payload" }

    val decodeStartedNs = nanoTime()
    val decoded = jpegDecoder.decode(source.data)
    val decodeFinishedNs = nanoTime()
    require(decoded.width == source.width && decoded.height == source.height) {
      "Extentos JPEG dimensions ${decoded.width}x${decoded.height} " +
          "do not match frame metadata ${source.width}x${source.height}"
    }

    val conversionStartedNs = nanoTime()
    val i420 = YuvFrameConverter.argbToI420(decoded.pixels, decoded.width, decoded.height)
    val conversionFinishedNs = nanoTime()

    return AdaptedExtentosFrame(
        frame =
            GlassesVideoFrame(
                i420 = i420,
                width = decoded.width,
                height = decoded.height,
                presentationTimeUs = source.timestampUs,
            ),
        sourceSizeBytes = source.data.size,
        decodeDurationUs = elapsedUs(decodeStartedNs, decodeFinishedNs),
        conversionDurationUs = elapsedUs(conversionStartedNs, conversionFinishedNs),
        usedRawYuv = false,
    )
  }

  private fun isCompleteJpeg(data: ByteArray): Boolean =
      data.size >= 4 &&
          data[0] == JPEG_MARKER_PREFIX &&
          data[1] == JPEG_START_OF_IMAGE &&
          data[data.lastIndex - 1] == JPEG_MARKER_PREFIX &&
          data[data.lastIndex] == JPEG_END_OF_IMAGE

  private fun elapsedUs(startNs: Long, endNs: Long): Long =
      ((endNs - startNs).coerceAtLeast(0L)) / NANOSECONDS_PER_MICROSECOND

  private companion object {
    const val NANOSECONDS_PER_MICROSECOND = 1_000L
    const val JPEG_MARKER_PREFIX: Byte = 0xff.toByte()
    const val JPEG_START_OF_IMAGE: Byte = 0xd8.toByte()
    const val JPEG_END_OF_IMAGE: Byte = 0xd9.toByte()
  }
}

private object AndroidJpegFrameDecoder : JpegFrameDecoder {
  override fun decode(jpeg: ByteArray): DecodedArgbFrame {
    val bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) {
          "Extentos JPEG payload could not be decoded"
        }
    return try {
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      DecodedArgbFrame(pixels = pixels, width = bitmap.width, height = bitmap.height)
    } finally {
      bitmap.recycle()
    }
  }
}
