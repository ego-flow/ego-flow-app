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
    val jpegSizeBytes: Int,
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
 *  - **JPEG (fallback).** Unchanged from before, and deliberately kept.
 *
 * The fallback is not defensive paranoia. `GlassesVideoFrame` requires the buffer
 * to be EXACTLY `width * height * 3 / 2`, and the SDK passes the platform's raw
 * buffer through untouched — so whether it is tightly packed or stride-padded is
 * a property of the underlying camera stack, not of Extentos. It is tightly
 * packed on the hardware and resolutions we could check, but that is not a
 * guarantee across future SDK versions, other resolutions, or other devices.
 * Rather than let a padded buffer throw inside `GlassesVideoFrame.init` and take
 * the stream down, an unexpected size falls back to the JPEG path for that frame.
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

  fun adapt(source: VideoFrame): AdaptedExtentosFrame {
    if (source.format == VideoFrameFormat.RAW_YUV && isWellFormedI420(source)) {
      return AdaptedExtentosFrame(
          frame =
              GlassesVideoFrame(
                  i420 = source.data,
                  width = source.width,
                  height = source.height,
                  presentationTimeUs = source.timestampUs,
              ),
          jpegSizeBytes = source.data.size,
          decodeDurationUs = 0,
          conversionDurationUs = 0,
          usedRawYuv = true,
      )
    }
    return adaptJpeg(source)
  }

  /** Exactly what [GlassesVideoFrame] will accept — checked before, not after. */
  private fun isWellFormedI420(source: VideoFrame): Boolean {
    if (source.width <= 0 || source.height <= 0) return false
    if (source.width % 2 != 0 || source.height % 2 != 0) return false
    val expected = source.width.toLong() * source.height.toLong() * 3L / 2L
    return expected <= Int.MAX_VALUE && source.data.size.toLong() == expected
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
        jpegSizeBytes = source.data.size,
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
