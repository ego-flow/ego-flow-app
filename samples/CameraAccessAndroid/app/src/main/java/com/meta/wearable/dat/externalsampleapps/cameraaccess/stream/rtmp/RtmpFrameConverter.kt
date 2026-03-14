package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.rtmp

import android.graphics.Bitmap

object RtmpFrameConverter {
    fun bitmapToI420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val frameSize = width * height
        val quarterFrameSize = frameSize / 4
        val yPlane = ByteArray(frameSize)
        val uPlane = ByteArray(quarterFrameSize)
        val vPlane = ByteArray(quarterFrameSize)
        val pixels = IntArray(frameSize)

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yPlane[yIndex++] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && col % 2 == 0 && uvIndex < quarterFrameSize) {
                    uPlane[uvIndex] = u.coerceIn(0, 255).toByte()
                    vPlane[uvIndex] = v.coerceIn(0, 255).toByte()
                    uvIndex++
                }
            }
        }

        return ByteArray(frameSize + quarterFrameSize * 2).apply {
            System.arraycopy(yPlane, 0, this, 0, frameSize)
            System.arraycopy(uPlane, 0, this, frameSize, quarterFrameSize)
            System.arraycopy(vPlane, 0, this, frameSize + quarterFrameSize, quarterFrameSize)
        }
    }

    fun i420ToNv12(i420: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val quarterFrameSize = frameSize / 4
        return ByteArray(frameSize + quarterFrameSize * 2).apply {
            System.arraycopy(i420, 0, this, 0, frameSize)
            for (index in 0 until quarterFrameSize) {
                this[frameSize + index * 2] = i420[frameSize + index]
                this[frameSize + index * 2 + 1] = i420[frameSize + quarterFrameSize + index]
            }
        }
    }
}
