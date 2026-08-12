package io.egoflow.app.transport.http

import java.nio.ByteBuffer
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

internal fun interface I420Scaler {
    fun scale(
        input: ByteArray,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteArray
}

internal sealed interface I420NormalizationResult {
    data class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    ) : I420NormalizationResult

    data class Dropped(
        val reason: String,
        val cause: Throwable? = null,
    ) : I420NormalizationResult
}

/** Keeps the first valid frame size for the lifetime of one HTTP recording. */
internal class I420FrameNormalizer(
    private val scaler: I420Scaler,
) {
    private var canonicalWidth = 0
    private var canonicalHeight = 0

    fun normalize(input: ByteArray, width: Int, height: Int): I420NormalizationResult {
        val inputSize = packedI420Size(width, height)
            ?: return I420NormalizationResult.Dropped("Invalid I420 dimensions: ${width}x$height")
        if (input.size != inputSize) {
            return I420NormalizationResult.Dropped(
                "Invalid I420 byte length for ${width}x$height: ${input.size}, expected $inputSize",
            )
        }

        if (canonicalWidth == 0) {
            canonicalWidth = width
            canonicalHeight = height
        }

        if (width == canonicalWidth && height == canonicalHeight) {
            return I420NormalizationResult.Frame(input, width, height)
        }

        return try {
            val scaled = scaler.scale(input, width, height, canonicalWidth, canonicalHeight)
            val expectedSize = packedI420Size(canonicalWidth, canonicalHeight)!!
            if (scaled.size != expectedSize) {
                I420NormalizationResult.Dropped(
                    "Scaler returned ${scaled.size} bytes for ${canonicalWidth}x$canonicalHeight, " +
                        "expected $expectedSize",
                )
            } else {
                I420NormalizationResult.Frame(scaled, canonicalWidth, canonicalHeight)
            }
        } catch (e: Exception) {
            I420NormalizationResult.Dropped(
                "Failed to scale I420 frame ${width}x$height to ${canonicalWidth}x$canonicalHeight",
                e,
            )
        } catch (e: LinkageError) {
            I420NormalizationResult.Dropped(
                "Failed to load or call the native I420 scaler",
                e,
            )
        }
    }
}

/** libwebrtc/libyuv adapter used only when the source resolution changes. */
internal object LibWebRtcI420Scaler : I420Scaler {
    override fun scale(
        input: ByteArray,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteArray {
        WebRtcNativeLibrary.ensureLoaded()

        val source = JavaI420Buffer.allocate(inputWidth, inputHeight)
        try {
            val inputYSize = inputWidth * inputHeight
            val inputChromaWidth = inputWidth / 2
            val inputChromaHeight = inputHeight / 2
            val inputChromaSize = inputChromaWidth * inputChromaHeight
            copyPackedPlaneToStrided(
                input,
                0,
                source.dataY,
                source.strideY,
                inputWidth,
                inputHeight,
            )
            copyPackedPlaneToStrided(
                input,
                inputYSize,
                source.dataU,
                source.strideU,
                inputChromaWidth,
                inputChromaHeight,
            )
            copyPackedPlaneToStrided(
                input,
                inputYSize + inputChromaSize,
                source.dataV,
                source.strideV,
                inputChromaWidth,
                inputChromaHeight,
            )

            val scaledBuffer =
                source.cropAndScale(
                    0,
                    0,
                    inputWidth,
                    inputHeight,
                    outputWidth,
                    outputHeight,
                )
            try {
                val scaled = scaledBuffer as? VideoFrame.I420Buffer
                    ?: error("libwebrtc returned a non-I420 scaled buffer")
                return scaled.toPackedByteArray()
            } finally {
                scaledBuffer.release()
            }
        } finally {
            source.release()
        }
    }
}

/** HTTP recording can run without WHIP, so load the JNI library at this boundary. */
private object WebRtcNativeLibrary {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("jingle_peerconnection_so")
                loaded = true
            }
        }
    }
}

private fun VideoFrame.I420Buffer.toPackedByteArray(): ByteArray {
    val ySize = width * height
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val chromaSize = chromaWidth * chromaHeight
    val output = ByteArray(ySize + chromaSize * 2)
    copyStridedPlaneToPacked(dataY, strideY, output, 0, width, height)
    copyStridedPlaneToPacked(dataU, strideU, output, ySize, chromaWidth, chromaHeight)
    copyStridedPlaneToPacked(dataV, strideV, output, ySize + chromaSize, chromaWidth, chromaHeight)
    return output
}

internal fun copyPackedPlaneToStrided(
    src: ByteArray,
    srcOffset: Int,
    dst: ByteBuffer,
    dstStride: Int,
    rowWidth: Int,
    rows: Int,
) {
    val target = dst.duplicate()
    for (row in 0 until rows) {
        target.position(row * dstStride)
        target.put(src, srcOffset + row * rowWidth, rowWidth)
    }
}

internal fun copyStridedPlaneToPacked(
    src: ByteBuffer,
    srcStride: Int,
    dst: ByteArray,
    dstOffset: Int,
    rowWidth: Int,
    rows: Int,
) {
    val source = src.duplicate()
    for (row in 0 until rows) {
        source.position(row * srcStride)
        source.get(dst, dstOffset + row * rowWidth, rowWidth)
    }
}

private fun packedI420Size(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
    val pixels = width.toLong() * height.toLong()
    val size = pixels * 3L / 2L
    return size.takeIf { it <= Int.MAX_VALUE }?.toInt()
}
