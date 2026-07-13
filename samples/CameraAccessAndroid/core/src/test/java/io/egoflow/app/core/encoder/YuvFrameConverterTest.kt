package io.egoflow.app.core.encoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YuvFrameConverterTest {
  @Test
  fun `ARGB black converts to BT601 limited range I420`() {
    val black = IntArray(4) { 0xff000000.toInt() }

    val i420 = YuvFrameConverter.argbToI420(black, width = 2, height = 2)

    assertArrayEquals(byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()), i420)
  }

  @Test
  fun `ARGB white converts to BT601 limited range I420`() {
    val white = IntArray(4) { 0xffffffff.toInt() }

    val i420 = YuvFrameConverter.argbToI420(white, width = 2, height = 2)

    assertArrayEquals(
        byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()),
        i420,
    )
  }

  @Test
  fun `ARGB red converts to BT601 limited range I420`() {
    val red = IntArray(4) { 0xffff0000.toInt() }

    val i420 = YuvFrameConverter.argbToI420(red, width = 2, height = 2)

    assertArrayEquals(
        byteArrayOf(82, 82, 82, 82, 90, 240.toByte()),
        i420,
    )
  }

  @Test
  fun `ARGB conversion rejects odd dimensions`() {
    assertThrows(IllegalArgumentException::class.java) {
      YuvFrameConverter.argbToI420(IntArray(6), width = 3, height = 2)
    }
  }

  @Test
  fun `ARGB conversion rejects the wrong pixel count`() {
    assertThrows(IllegalArgumentException::class.java) {
      YuvFrameConverter.argbToI420(IntArray(3), width = 2, height = 2)
    }
  }
}
