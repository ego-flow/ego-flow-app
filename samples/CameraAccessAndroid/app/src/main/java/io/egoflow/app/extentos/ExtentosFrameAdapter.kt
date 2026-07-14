package io.egoflow.app.extentos

import android.graphics.BitmapFactory
import com.extentos.glasses.core.VideoFrame
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
    val jpegSizeBytes: Int,
    val decodeDurationUs: Long,
    val conversionDurationUs: Long,
)

/** Converts Extentos' public JPEG frame payload into EgoFlow's transport-owned I420 frame. */
internal class ExtentosFrameAdapter(
    private val jpegDecoder: JpegFrameDecoder = AndroidJpegFrameDecoder,
    private val nanoTime: () -> Long = System::nanoTime,
) {
  private var lastPresentationTimeUs: Long? = null

  fun reset() {
    lastPresentationTimeUs = null
  }

  fun adapt(source: VideoFrame): AdaptedExtentosFrame {
    require(isCompleteJpeg(source.data)) { "Extentos frame is not a complete JPEG payload" }

    val sourcePresentationTimeUs = toPresentationTimeUs(source.timestampMs)
    val previousTimestampUs = lastPresentationTimeUs
    val presentationTimeUs =
        if (previousTimestampUs == null || sourcePresentationTimeUs > previousTimestampUs) {
          sourcePresentationTimeUs
        } else {
          previousTimestampUs + 1L
        }

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
    val frame =
        GlassesVideoFrame(
            i420 = i420,
            width = decoded.width,
            height = decoded.height,
            presentationTimeUs = presentationTimeUs,
        )

    lastPresentationTimeUs = presentationTimeUs
    return AdaptedExtentosFrame(
        frame = frame,
        jpegSizeBytes = source.data.size,
        decodeDurationUs = elapsedUs(decodeStartedNs, decodeFinishedNs),
        conversionDurationUs = elapsedUs(conversionStartedNs, conversionFinishedNs),
    )
  }

  private fun toPresentationTimeUs(timestampMs: Long): Long {
    require(timestampMs >= 0L) { "Extentos frame timestamp must be non-negative" }
    require(timestampMs <= Long.MAX_VALUE / MICROSECONDS_PER_MILLISECOND) {
      "Extentos frame timestamp is too large to convert to microseconds"
    }
    return timestampMs * MICROSECONDS_PER_MILLISECOND
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
    const val MICROSECONDS_PER_MILLISECOND = 1_000L
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
