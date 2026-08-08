package io.egoflow.app.extentos

import com.extentos.glasses.core.VideoFrame
import com.extentos.glasses.core.VideoFrameFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    assertEquals(6, adapted.sourceSizeBytes)
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
  fun `a mis-sized RAW_YUV buffer fails with a layout error and never reaches the decoder`() {
    // The SDK never labels JPEG bytes as RAW_YUV: on hardware the raw branch has no
    // encode fallback (MetaHardwareBridge: `if (rawRequested) bytes`), and the
    // simulator DROPS a frame it cannot convert rather than emitting it as JPEG
    // (BrowserSimTransport: `jpegToI420(data) ?: return`). A mis-sized RAW_YUV
    // payload is therefore still raw, and handing it to the JPEG decoder could only
    // turn a precise layout problem into a misleading "not a complete JPEG payload".
    var decoderCalled = false
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = {
              decoderCalled = true
              DecodedArgbFrame(IntArray(8), 4, 2)
            })

    // A 4x2 frame needs 12 bytes of packed I420; this payload is 6.
    val exception =
        assertThrows(IllegalArgumentException::class.java) {
          adapter.adapt(rawFrame(timestampUs = 9_123L, data = ByteArray(6), width = 4, height = 2))
        }

    assertEquals(false, decoderCalled)
    assertTrue(
        "message should name both sizes, was: ${exception.message}",
        exception.message!!.contains("requires 12 bytes, got 6"),
    )
  }

  @Test
  fun `the JPEG path preserves sub-millisecond timestamps`() {
    // Same precision guard as the raw path: adaptJpeg reads source.timestampUs
    // directly, so a regression to timestampMs * 1000 must fail here too.
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder = { DecodedArgbFrame(IntArray(4) { 0xff000000.toInt() }, 2, 2) })

    val adapted = adapter.adapt(jpegFrame(timestampUs = 9_123L))

    assertEquals(false, adapted.usedRawYuv)
    assertEquals(9_123L, adapted.frame.presentationTimeUs)
  }

  private fun jpegFrame(timestampUs: Long): VideoFrame =
      VideoFrame(
          timestampMs = timestampUs / 1000,
          width = 2,
          height = 2,
          data = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte()),
          format = VideoFrameFormat.JPEG,
          timestampUs = timestampUs,
      )

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
