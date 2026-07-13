package io.egoflow.app.extentos

import com.extentos.glasses.core.VideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtentosFrameAdapterTest {
  @Test
  fun `converts decoded JPEG pixels to I420 and milliseconds to microseconds`() {
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

    assertEquals(42_000L, adapted.frame.presentationTimeUs)
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
  fun `rejects a timestamp that does not advance`() {
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder =
                JpegFrameDecoder { DecodedArgbFrame(IntArray(4) { 0xff000000.toInt() }, 2, 2) }
        )
    adapter.adapt(extentosFrame(timestampMs = 10L))

    val exception =
        assertThrows(IllegalArgumentException::class.java) {
          adapter.adapt(extentosFrame(timestampMs = 10L))
        }

    assertEquals(
        "Extentos frame timestamp 10000us did not advance beyond 10000us",
        exception.message,
    )
  }

  @Test
  fun `reset starts a new monotonic timestamp sequence`() {
    val adapter =
        ExtentosFrameAdapter(
            jpegDecoder =
                JpegFrameDecoder { DecodedArgbFrame(IntArray(4) { 0xff000000.toInt() }, 2, 2) }
        )
    adapter.adapt(extentosFrame(timestampMs = 10L))

    adapter.reset()

    assertEquals(10_000L, adapter.adapt(extentosFrame(timestampMs = 10L)).frame.presentationTimeUs)
  }

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
