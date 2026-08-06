package io.egoflow.app.extentos

import com.extentos.glasses.core.VideoFrame
import com.extentos.glasses.core.VideoFrameFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtentosFrameAdapterTest {
  @Test
  fun `converts decoded JPEG pixels to I420 and carries the SDK timestamp through`() {
    val decoder =
        JpegFrameDecoder {
          DecodedArgbFrame(
              pixels = IntArray(4) { 0xff000000.toInt() },
              width = 2,
              height = 2,
          )
        }
    val adapter = ExtentosFrameAdapter(jpegDecoder = decoder)

    val adapted = adapter.adapt(extentosFrame(timestampMs = 42L))

    // VideoFrame.timestampUs defaults to timestampMs * 1000, and the adapter
    // now passes it straight through rather than deriving it.
    assertEquals(42_000L, adapted.frame.presentationTimeUs)
    assertEquals(false, adapted.usedRawYuv)
    assertEquals(2, adapted.frame.width)
    assertEquals(2, adapted.frame.height)
    assertArrayEquals(
        byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()),
        adapted.frame.copyI420(),
    )
    assertEquals(6, adapted.jpegSizeBytes)
  }

  @Test
  fun `rejects a payload without JPEG start and end markers`() {
    var decoderCalled = false
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = JpegFrameDecoder {
              decoderCalled = true
              DecodedArgbFrame(IntArray(4), 2, 2)
            }
        )

    val exception =
        assertThrows(IllegalArgumentException::class.java) {
          adapter.adapt(VideoFrame(1L, 2, 2, byteArrayOf(1, 2, 3, 4)))
        }

    assertEquals("Extentos frame is not a complete JPEG payload", exception.message)
    assertEquals(false, decoderCalled)
  }

  @Test
  fun `rejects decoded dimensions that differ from Extentos metadata`() {
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = JpegFrameDecoder { DecodedArgbFrame(IntArray(16), 4, 4) }
        )

    val exception =
        assertThrows(IllegalArgumentException::class.java) {
          adapter.adapt(extentosFrame(timestampMs = 1L, width = 2, height = 2))
        }

    assertEquals(
        "Extentos JPEG dimensions 4x4 do not match frame metadata 2x2",
        exception.message,
    )
  }

  @Test
  fun `raw I420 passes straight through with no decode or conversion`() {
    var decoderCalled = false
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = {
              decoderCalled = true
              DecodedArgbFrame(IntArray(4), 2, 2)
            })

    // 2x2 I420 is exactly 6 bytes: 4 luma + 1 Cb + 1 Cr.
    val planar = ByteArray(6) { (it + 1).toByte() }
    // Deliberately NOT on a millisecond boundary: the helper derives
    // timestampMs = timestampUs / 1000, so a regression to timestampMs * 1000
    // would yield 7_000 and fail this assertion. That is the point: it proves
    // the adapter passes timestampUs through rather than reconstructing it.
    val adapted = adapter.adapt(rawFrame(timestampUs = 7_123L, data = planar))

    assertEquals(true, adapted.usedRawYuv)
    assertEquals(false, decoderCalled)
    assertArrayEquals(planar, adapted.frame.copyI420())
    assertEquals(7_123L, adapted.frame.presentationTimeUs)
    // The point of the change: neither cost is paid on the raw path.
    assertEquals(0L, adapted.decodeDurationUs)
    assertEquals(0L, adapted.conversionDurationUs)
  }

  @Test
  fun `a raw buffer of the wrong size falls back to JPEG instead of throwing`() {
    // GlassesVideoFrame REQUIRES exactly width*height*3/2 and throws otherwise.
    // The SDK hands over the platform's buffer untouched, so a stride-padded one
    // is possible on hardware we have not measured. Falling back beats taking
    // the whole stream down inside a require().
    var decoderCalled = false
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = {
              decoderCalled = true
              DecodedArgbFrame(IntArray(8) { 0xff000000.toInt() }, 4, 2)
            })

    // A 4x2 frame needs 12 bytes of I420; this payload is 6, so the raw path
    // must decline it. The payload is a valid JPEG so the fallback can proceed.
    val jpegBody =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    // Sub-millisecond value here too: the JPEG path reads source.timestampUs
    // directly, so the fallback must preserve precision as exactly as the raw
    // path does.
    val adapted = adapter.adapt(rawFrame(timestampUs = 9_123L, data = jpegBody, width = 4, height = 2))

    assertEquals(false, adapted.usedRawYuv)
    assertEquals(true, decoderCalled)
    assertEquals(9_123L, adapted.frame.presentationTimeUs)
    assertEquals(4, adapted.frame.width)
  }

  private fun rawFrame(
      timestampUs: Long,
      data: ByteArray,
      width: Int = 2,
      height: Int = 2,
  ): VideoFrame =
      VideoFrame(
          timestampMs = timestampUs / 1000,
          width = width,
          height = height,
          data = data,
          format = VideoFrameFormat.RAW_YUV,
          timestampUs = timestampUs,
      )

  private fun extentosFrame(
      timestampMs: Long,
      width: Int = 2,
      height: Int = 2,
  ): VideoFrame =
      VideoFrame(
          timestampMs,
          width,
          height,
          byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()),
      )
}
