package io.egoflow.app.stream.rtmp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures PCM audio for the RTMP stream from either the phone or the Meta glasses microphone,
 * depending on [audioSourceProvider].
 *
 * Glasses audio is shared through the Android system Bluetooth stack
 * (HFP/SCO). To capture it we route the communication audio path to the glasses' Bluetooth device
 * via [AudioManager] and record with [MediaRecorder.AudioSource.VOICE_COMMUNICATION]. The phone mic
 * uses the plain [MediaRecorder.AudioSource.MIC] path with no routing. Routing is reverted on [stop].
 */
class RtmpAudioRecorder(
    context: Context,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val audioSourceProvider: () -> RtmpAudioSource = { RtmpAudioSource.AUTO },
) {
    companion object {
        private const val TAG = "RtmpAudioRecorder"
    }

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var onPcmData: ((ByteArray, Long) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var routedToCommunicationDevice = false
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

        // Resolve glasses vs. phone, routing the communication path to the glasses when applicable.
        val useGlasses = resolveUseGlasses()
        val audioSource =
            if (useGlasses) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
        if (useGlasses) routeToGlasses()

        val record =
            AudioRecord(
                audioSource,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2,
            )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            clearGlassesRouting()
            error("AudioRecord init failed")
        }

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

        RtmpDiagnostics.log("Audio capture started source=${if (useGlasses) "glasses" else "phone"}")
        Log.d(TAG, "RTMP audio capture started (glasses=$useGlasses)")
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
        clearGlassesRouting()
        Log.d(TAG, "RTMP audio capture stopped")
    }

    /** Resolves whether to capture from the glasses; AUTO falls back to phone when none is present. */
    private fun resolveUseGlasses(): Boolean =
        when (audioSourceProvider()) {
            RtmpAudioSource.PHONE -> false
            RtmpAudioSource.AUTO -> findGlassesCommDevice() != null
            RtmpAudioSource.GLASSES -> {
                val present = findGlassesCommDevice() != null
                if (!present) {
                    RtmpDiagnostics.log("Audio source GLASSES requested but no BT comm device; using phone mic")
                }
                present
            }
        }

    private fun findGlassesCommDevice(): AudioDeviceInfo? =
        audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    private fun routeToGlasses() {
        val device = findGlassesCommDevice() ?: return
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            val ok = audioManager.setCommunicationDevice(device)
            routedToCommunicationDevice = ok
            RtmpDiagnostics.log("Routed audio to ${device.productName} (ok=$ok)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to route to glasses comm device", e)
            RtmpDiagnostics.log("Failed to route audio to glasses: ${e.message}")
            audioManager.mode = previousAudioMode
        }
    }

    private fun clearGlassesRouting() {
        if (!routedToCommunicationDevice) return
        try {
            audioManager.clearCommunicationDevice()
        } catch (_: Exception) {
        }
        audioManager.mode = previousAudioMode
        routedToCommunicationDevice = false
    }
}
