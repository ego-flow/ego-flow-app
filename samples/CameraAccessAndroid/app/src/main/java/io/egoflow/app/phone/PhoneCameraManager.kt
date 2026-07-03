package io.egoflow.app.phone

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.egoflow.app.core.encoder.YuvFrameConverter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PhoneCameraManager(private val context: Context) {
    companion object {
        private const val TAG = "PhoneCameraManager"
    }

    /**
     * Delivers each camera frame already repacked to planar I420 (upright,
     * encoder-ready). CameraX hands us YUV420, so we never JPEG-encode or build a
     * Bitmap on the hot path — that round-trip plus the per-pixel RGB→YUV transcode
     * was the app's biggest per-frame waste.
     */
    var onFrame: ((i420: ByteArray, width: Int, height: Int) -> Unit)? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Latest upright I420 frame, kept so capturePhoto() can materialize a Bitmap
    // on demand (once, on tap) instead of stashing a full-res Bitmap every frame.
    private val latestFrame = AtomicReference<Frame?>(null)

    private class Frame(val i420: ByteArray, val width: Int, val height: Int)

    fun start(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        processFrame(imageProxy)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to convert frame: ${e.message}")
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis,
                )

                Log.d(TAG, "Phone camera started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        latestFrame.set(null)
        Log.d(TAG, "Phone camera stopped")
    }

    /** Build a Bitmap from the most recent frame, for the photo-capture share sheet.
     *  Returns null before the first frame arrives. */
    fun captureLatestFrameBitmap(): Bitmap? {
        val frame = latestFrame.get() ?: return null
        return YuvFrameConverter.i420ToBitmap(frame.i420, frame.width, frame.height)
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val packed = imageProxyToI420(imageProxy)
        val rotation = imageProxy.imageInfo.rotationDegrees
        val i420 = YuvFrameConverter.rotateI420(packed, imageProxy.width, imageProxy.height, rotation)
        val (width, height) =
            if (rotation == 90 || rotation == 270) {
                imageProxy.height to imageProxy.width
            } else {
                imageProxy.width to imageProxy.height
            }
        latestFrame.set(Frame(i420, width, height))
        onFrame?.invoke(i420, width, height)
    }

    // Repack a YUV_420_888 ImageProxy into a tightly-packed I420 buffer
    // (Y plane, then U plane, then V plane), honoring each plane's row/pixel
    // stride. No color-space math — YUV_420_888 is already YUV.
    private fun imageProxyToI420(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val out = ByteArray(width * height + chromaWidth * chromaHeight * 2)

        // Y plane.
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var outPos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(out, outPos, width)
            outPos += width
        }

        // U plane, then V plane. I420 orders U before V.
        outPos = copyChromaPlane(image.planes[1], chromaWidth, chromaHeight, out, outPos)
        copyChromaPlane(image.planes[2], chromaWidth, chromaHeight, out, outPos)
        return out
    }

    private fun copyChromaPlane(
        plane: ImageProxy.PlaneProxy,
        chromaWidth: Int,
        chromaHeight: Int,
        out: ByteArray,
        startPos: Int,
    ): Int {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var pos = startPos
        if (pixelStride == 1) {
            for (row in 0 until chromaHeight) {
                buffer.position(row * rowStride)
                buffer.get(out, pos, chromaWidth)
                pos += chromaWidth
            }
        } else {
            // Semi-planar source (pixelStride == 2): take every pixelStride-th byte.
            // Absolute get(index) avoids per-row buffer allocations.
            for (row in 0 until chromaHeight) {
                var index = row * rowStride
                for (col in 0 until chromaWidth) {
                    out[pos++] = buffer.get(index)
                    index += pixelStride
                }
            }
        }
        return pos
    }
}
