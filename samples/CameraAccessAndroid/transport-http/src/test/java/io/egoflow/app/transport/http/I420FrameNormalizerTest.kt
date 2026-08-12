package io.egoflow.app.transport.http

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class I420FrameNormalizerTest {

    @Test
    fun `first valid frame selects canonical resolution`() {
        val scaler = RecordingScaler()
        val normalizer = I420FrameNormalizer(scaler)

        val invalid = normalizer.normalize(ByteArray(3), width = 4, height = 4)
        assertTrue(invalid is I420NormalizationResult.Dropped)

        val input = ByteArray(i420Size(4, 4)) { it.toByte() }
        val accepted = normalizer.normalize(input, width = 4, height = 4) as I420NormalizationResult.Frame

        assertEquals(4, accepted.width)
        assertEquals(4, accepted.height)
        assertSame(input, accepted.bytes)
        assertEquals(0, scaler.calls.size)
    }

    @Test
    fun `same size frame takes fast path without scaling`() {
        val scaler = RecordingScaler()
        val normalizer = I420FrameNormalizer(scaler)
        normalizer.normalize(ByteArray(i420Size(4, 4)), width = 4, height = 4)

        val next = ByteArray(i420Size(4, 4)) { 7 }
        val accepted = normalizer.normalize(next, width = 4, height = 4) as I420NormalizationResult.Frame

        assertSame(next, accepted.bytes)
        assertEquals(0, scaler.calls.size)
    }

    @Test
    fun `changed size frame is scaled to canonical resolution`() {
        val output = ByteArray(i420Size(4, 4)) { 11 }
        val scaler = RecordingScaler(output)
        val normalizer = I420FrameNormalizer(scaler)
        normalizer.normalize(ByteArray(i420Size(4, 4)), width = 4, height = 4)

        val accepted =
            normalizer.normalize(ByteArray(i420Size(2, 2)), width = 2, height = 2)
                as I420NormalizationResult.Frame

        assertEquals(4, accepted.width)
        assertEquals(4, accepted.height)
        assertSame(output, accepted.bytes)
        assertEquals(listOf(ScaleCall(2, 2, 4, 4)), scaler.calls)
    }

    @Test
    fun `failed changed size frame is dropped without changing canonical state`() {
        var shouldFail = true
        val scaler =
            I420Scaler { _, _, _, outputWidth, outputHeight ->
                if (shouldFail) error("native scaler failed")
                ByteArray(i420Size(outputWidth, outputHeight)) { 9 }
            }
        val normalizer = I420FrameNormalizer(scaler)
        normalizer.normalize(ByteArray(i420Size(4, 4)), width = 4, height = 4)

        val dropped = normalizer.normalize(ByteArray(i420Size(2, 2)), width = 2, height = 2)
        assertTrue(dropped is I420NormalizationResult.Dropped)

        shouldFail = false
        val recovered =
            normalizer.normalize(ByteArray(i420Size(2, 2)), width = 2, height = 2)
                as I420NormalizationResult.Frame
        assertEquals(4, recovered.width)
        assertEquals(4, recovered.height)

        val canonical = ByteArray(i420Size(4, 4)) { 3 }
        val sameSize = normalizer.normalize(canonical, width = 4, height = 4) as I420NormalizationResult.Frame
        assertSame(canonical, sameSize.bytes)
    }

    @Test
    fun `plane copies honor source and destination stride`() {
        val packed = byteArrayOf(1, 2, 3, 4, 5, 6)
        val strided = ByteBuffer.allocate(10)

        copyPackedPlaneToStrided(
            src = packed,
            srcOffset = 0,
            dst = strided,
            dstStride = 5,
            rowWidth = 3,
            rows = 2,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 0, 4, 5, 6, 0, 0), strided.array())

        val roundTrip = ByteArray(6)
        copyStridedPlaneToPacked(
            src = strided,
            srcStride = 5,
            dst = roundTrip,
            dstOffset = 0,
            rowWidth = 3,
            rows = 2,
        )
        assertArrayEquals(packed, roundTrip)
    }

    private data class ScaleCall(
        val inputWidth: Int,
        val inputHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    private class RecordingScaler(
        private val output: ByteArray = ByteArray(0),
    ) : I420Scaler {
        val calls = mutableListOf<ScaleCall>()

        override fun scale(
            input: ByteArray,
            inputWidth: Int,
            inputHeight: Int,
            outputWidth: Int,
            outputHeight: Int,
        ): ByteArray {
            calls += ScaleCall(inputWidth, inputHeight, outputWidth, outputHeight)
            return output
        }
    }

    private fun i420Size(width: Int, height: Int): Int = width * height * 3 / 2
}
