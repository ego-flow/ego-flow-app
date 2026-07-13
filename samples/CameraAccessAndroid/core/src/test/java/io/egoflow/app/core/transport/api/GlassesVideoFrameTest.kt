package io.egoflow.app.core.transport.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlassesVideoFrameTest {
  @Test
  fun `constructor copies the source I420 bytes`() {
    val source = byteArrayOf(1, 2, 3, 4, 5, 6)

    val frame = GlassesVideoFrame(source, width = 2, height = 2, presentationTimeUs = 10L)
    source[0] = 99

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame.copyI420())
  }

  @Test
  fun `copyI420 returns a defensive copy`() {
    val frame =
        GlassesVideoFrame(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            width = 2,
            height = 2,
            presentationTimeUs = 10L,
        )

    val firstRead = frame.copyI420()
    firstRead[0] = 99

    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame.copyI420())
  }

  @Test
  fun `constructor rejects an invalid I420 buffer length`() {
    assertThrows(IllegalArgumentException::class.java) {
      GlassesVideoFrame(
          byteArrayOf(1, 2, 3, 4, 5),
          width = 2,
          height = 2,
          presentationTimeUs = 10L,
      )
    }
  }

  @Test
  fun `constructor rejects odd dimensions`() {
    assertThrows(IllegalArgumentException::class.java) {
      GlassesVideoFrame(
          ByteArray(9),
          width = 3,
          height = 2,
          presentationTimeUs = 10L,
      )
    }
  }

  @Test
  fun `constructor rejects non-positive dimensions`() {
    assertThrows(IllegalArgumentException::class.java) {
      GlassesVideoFrame(
          ByteArray(0),
          width = 0,
          height = 2,
          presentationTimeUs = 10L,
      )
    }
  }

  @Test
  fun `constructor rejects a negative presentation timestamp`() {
    assertThrows(IllegalArgumentException::class.java) {
      GlassesVideoFrame(
          ByteArray(6),
          width = 2,
          height = 2,
          presentationTimeUs = -1L,
      )
    }
  }
}
