package io.egoflow.app.core.encoder

import android.graphics.Bitmap

/**
 * YUV color-space helpers shared by every transport. Lives in :core
 * rather than :transport-rtmp because both the RTMP encoder pipeline
 * and the future MKV-write pipeline need the same `i420ToNv12`
 * (encoder input-format match) conversion.
 *
 * The phone-camera path no longer round-trips through a Bitmap: CameraX
 * already delivers YUV420, so PhoneCameraManager repacks it straight to
 * I420 (no JPEG, no per-pixel RGB math). [rotateI420] applies the camera
 * orientation on that planar buffer, and [i420ToBitmap] is used only on a
 * photo-capture tap to materialize the latest frame for the share sheet.
 */
object YuvFrameConverter {
    /**
     * Convert packed ARGB pixels to planar I420 using BT.601 limited-range coefficients.
     * Chroma is sampled once per 2x2 pixel block after averaging that block's RGB values.
     */
    fun argbToI420(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "I420 dimensions must be positive" }
        require(width % 2 == 0 && height % 2 == 0) { "I420 dimensions must be even" }

        val frameSizeLong = width.toLong() * height.toLong()
        require(frameSizeLong <= Int.MAX_VALUE) { "Frame dimensions are too large" }
        val frameSize = frameSizeLong.toInt()
        require(pixels.size == frameSize) {
            "Expected $frameSize ARGB pixels, received ${pixels.size}"
        }

        val outputSizeLong = frameSizeLong * 3L / 2L
        require(outputSizeLong <= Int.MAX_VALUE) { "I420 buffer is too large" }
        val output = ByteArray(outputSizeLong.toInt())
        val chromaWidth = width / 2
        val uOffset = frameSize
        val vOffset = frameSize + frameSize / 4

        for (row in 0 until height) {
            val rowOffset = row * width
            for (col in 0 until width) {
                val pixel = pixels[rowOffset + col]
                val red = pixel shr 16 and 0xff
                val green = pixel shr 8 and 0xff
                val blue = pixel and 0xff
                output[rowOffset + col] = rgbToY(red, green, blue).toByte()
            }
        }

        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                var redSum = 0
                var greenSum = 0
                var blueSum = 0
                for (rowOffset in 0..1) {
                    for (colOffset in 0..1) {
                        val pixel = pixels[(row + rowOffset) * width + col + colOffset]
                        redSum += pixel shr 16 and 0xff
                        greenSum += pixel shr 8 and 0xff
                        blueSum += pixel and 0xff
                    }
                }

                val red = (redSum + 2) / 4
                val green = (greenSum + 2) / 4
                val blue = (blueSum + 2) / 4
                val chromaIndex = row / 2 * chromaWidth + col / 2
                output[uOffset + chromaIndex] = rgbToU(red, green, blue).toByte()
                output[vOffset + chromaIndex] = rgbToV(red, green, blue).toByte()
            }
        }

        return output
    }

    private fun rgbToY(red: Int, green: Int, blue: Int): Int =
        (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).coerceIn(0, 255)

    private fun rgbToU(red: Int, green: Int, blue: Int): Int =
        (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128).coerceIn(0, 255)

    private fun rgbToV(red: Int, green: Int, blue: Int): Int =
        (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128).coerceIn(0, 255)

    /** Interleave a planar I420 buffer into semi-planar NV12, writing into the
     *  caller-provided [out] (sized width*height*3/2). [out] is reused frame to
     *  frame by the encoder instead of being reallocated per frame. */
    fun i420ToNv12(i420: ByteArray, width: Int, height: Int, out: ByteArray) {
        val frameSize = width * height
        val quarterFrameSize = frameSize / 4
        System.arraycopy(i420, 0, out, 0, frameSize)
        for (index in 0 until quarterFrameSize) {
            out[frameSize + index * 2] = i420[frameSize + index]
            out[frameSize + index * 2 + 1] = i420[frameSize + quarterFrameSize + index]
        }
    }

    /**
     * Rotate a planar I420 buffer by [degrees] (0/90/180/270, clockwise).
     * For 90/270 the returned buffer's effective dimensions are swapped
     * (width↔height); the byte length is unchanged. Each plane (Y, then the
     * two w/2×h/2 chroma planes) is rotated independently — the same shape
     * libyuv's I420Rotate produces.
     */
    fun rotateI420(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        if (degrees == 0) return src
        val out = ByteArray(src.size)
        val frame = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaFrame = chromaWidth * chromaHeight
        rotatePlane(src, 0, width, height, out, 0, degrees)
        rotatePlane(src, frame, chromaWidth, chromaHeight, out, frame, degrees)
        rotatePlane(src, frame + chromaFrame, chromaWidth, chromaHeight, out, frame + chromaFrame, degrees)
        return out
    }

    // Rotates a single packed plane. Destination plane width becomes the source
    // height for 90/270 (rows ↔ columns), so the destination index uses `h` as
    // its stride in those cases.
    private fun rotatePlane(
        src: ByteArray,
        srcOffset: Int,
        w: Int,
        h: Int,
        dst: ByteArray,
        dstOffset: Int,
        degrees: Int,
    ) {
        when (degrees) {
            90 -> {
                for (r in 0 until h) {
                    val srcRow = srcOffset + r * w
                    for (c in 0 until w) {
                        // dstRow = c, dstCol = h - 1 - r, dstWidth = h
                        dst[dstOffset + c * h + (h - 1 - r)] = src[srcRow + c]
                    }
                }
            }
            180 -> {
                for (r in 0 until h) {
                    val srcRow = srcOffset + r * w
                    val dstRow = dstOffset + (h - 1 - r) * w
                    for (c in 0 until w) {
                        dst[dstRow + (w - 1 - c)] = src[srcRow + c]
                    }
                }
            }
            270 -> {
                for (r in 0 until h) {
                    val srcRow = srcOffset + r * w
                    for (c in 0 until w) {
                        // dstRow = w - 1 - c, dstCol = r, dstWidth = h
                        dst[dstOffset + (w - 1 - c) * h + r] = src[srcRow + c]
                    }
                }
            }
            else -> System.arraycopy(src, srcOffset, dst, dstOffset, w * h)
        }
    }

    /**
     * Decode a planar I420 buffer to an ARGB_8888 Bitmap (BT.601). Per-pixel and
     * allocation-heavy, so this is intended for the one-shot photo-capture path —
     * NOT the per-frame streaming path, which never builds a Bitmap.
     */
    fun i420ToBitmap(i420: ByteArray, width: Int, height: Int): Bitmap {
        val frame = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaFrame = chromaWidth * chromaHeight
        val pixels = IntArray(frame)
        var pixelIndex = 0
        for (row in 0 until height) {
            val yRow = row * width
            val uvRow = (row / 2) * chromaWidth
            for (col in 0 until width) {
                val y = (i420[yRow + col].toInt() and 0xff) - 16
                val uvIndex = uvRow + (col / 2)
                val u = (i420[frame + uvIndex].toInt() and 0xff) - 128
                val v = (i420[frame + chromaFrame + uvIndex].toInt() and 0xff) - 128
                val c = if (y < 0) 0 else y
                val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[pixelIndex++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
