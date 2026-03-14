package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.rtmp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class RtmpAudioRecorder(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    companion object {
        private const val TAG = "RtmpAudioRecorder"
    }

    var onPcmData: ((ByteArray, Long) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return

        val channelMask =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }
        val minBufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        require(minBufferSize > 0) { "AudioRecord buffer size unavailable" }

        val record =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2,
            )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

        audioRecord = record
        record.startRecording()
        isCapturing = true

        captureThread =
            Thread(
                {
                    val buffer = ByteArray(minBufferSize)
                    while (isCapturing) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        onPcmData?.invoke(buffer.copyOf(read), System.nanoTime())
                    }
                },
                "rtmp-audio-capture",
            ).also { it.start() }

        Log.d(TAG, "RTMP audio capture started")
    }

    fun stop() {
        if (!isCapturing) return
        isCapturing = false

        captureThread?.join(1000)
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "RTMP audio capture stopped")
    }
}
