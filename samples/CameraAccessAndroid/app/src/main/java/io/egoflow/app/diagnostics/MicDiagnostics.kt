package io.egoflow.app.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Debug-only helper that lets us answer "which microphone are we actually capturing — the phone or
 * the Meta glasses?".
 *
 * The glasses mic and speaker are exposed through Android's system Bluetooth routing
 * access through the system Bluetooth stack (HFP/SCO). So to capture the glasses microphone you route
 * the *communication* audio path to the glasses' Bluetooth device via [AudioManager] and capture with
 * a [MediaRecorder.AudioSource.VOICE_COMMUNICATION] source. This class exposes that routing plus a
 * live waveform so you can verify, by talking into each device, which one feeds the input.
 *
 * Independent of [io.egoflow.app.stream.rtmp.RtmpAudioRecorder] (which captures from
 * [MediaRecorder.AudioSource.MIC] for the RTMP stream). Use this when not streaming.
 */
class MicDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class CommDevice(
        val id: Int,
        val name: String,
        val typeLabel: String,
        val isBluetooth: Boolean,
    )

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    /** Rolling peak amplitudes in [0,1], oldest first, newest last. Drives the scrolling waveform. */
    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    /** Current RMS level in [0,1] for the level meter. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _devices = MutableStateFlow<List<CommDevice>>(emptyList())
    val devices: StateFlow<List<CommDevice>> = _devices.asStateFlow()

    /** id of the AudioDeviceInfo currently selected for communication, or null for system default. */
    private val _selectedDeviceId = MutableStateFlow<Int?>(null)
    val selectedDeviceId: StateFlow<Int?> = _selectedDeviceId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    @Volatile
    private var capturing = false

    private val rollingPeaks = ArrayDeque<Float>(WAVEFORM_BARS)

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-reads the list of devices usable for communication audio and the active selection. */
    fun refreshDevices() {
        val list = audioManager.availableCommunicationDevices.map { it.toCommDevice() }
        _devices.value = list
        _selectedDeviceId.value = audioManager.communicationDevice?.id
    }

    /**
     * Routes communication input/output to the device with [deviceId]. Pick the glasses' Bluetooth
     * entry to capture from the glasses microphone. Returns false if routing was rejected.
     */
    fun selectDevice(deviceId: Int): Boolean {
        val target = audioManager.availableCommunicationDevices.firstOrNull { it.id == deviceId }
        if (target == null) {
            _error.value = "Device no longer available; refresh the list."
            refreshDevices()
            return false
        }
        return try {
            val ok = audioManager.setCommunicationDevice(target)
            if (ok) {
                _selectedDeviceId.value = audioManager.communicationDevice?.id
                _error.value = null
            } else {
                _error.value = "System rejected routing to ${target.productName}."
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setCommunicationDevice failed", e)
            _error.value = "Routing failed: ${e.message}"
            false
        }
    }

    /** Reverts to the system default communication device (typically the phone). */
    fun clearSelection() {
        try {
            audioManager.clearCommunicationDevice()
        } catch (e: Exception) {
            Log.w(TAG, "clearCommunicationDevice failed", e)
        }
        _selectedDeviceId.value = audioManager.communicationDevice?.id
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (capturing) return
        if (!hasRecordPermission()) {
            _error.value = "RECORD_AUDIO permission not granted."
            return
        }

        val minBufferSize =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBufferSize <= 0) {
            _error.value = "AudioRecord buffer size unavailable."
            return
        }

        val record =
            try {
                AudioRecord(
                    // VOICE_COMMUNICATION (not MIC) so the input follows the selected communication
                    // device — the only way the glasses' SCO mic actually feeds the capture.
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING,
                    minBufferSize * 2,
                )
            } catch (e: Exception) {
                _error.value = "AudioRecord init failed: ${e.message}"
                return
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            _error.value = "AudioRecord init failed (state=${record.state})."
            record.release()
            return
        }

        // Communication mode is required for VOICE_COMMUNICATION + communication-device routing.
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        audioRecord = record
        rollingPeaks.clear()
        _error.value = null
        record.startRecording()
        capturing = true
        _isCapturing.value = true
        refreshDevices()

        captureThread =
            Thread(
                {
                    val buffer = ByteArray(minBufferSize)
                    while (capturing) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        onPcm(buffer, read)
                    }
                },
                "mic-diagnostics-capture",
            ).also { it.start() }

        Log.d(TAG, "Mic diagnostics capture started")
    }

    fun stop() {
        if (!capturing) return
        capturing = false
        _isCapturing.value = false

        captureThread?.join(1000)
        captureThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null

        audioManager.mode = previousAudioMode
        _level.value = 0f
        Log.d(TAG, "Mic diagnostics capture stopped")
    }

    private fun onPcm(pcm: ByteArray, byteCount: Int) {
        val sampleCount = byteCount / 2
        if (sampleCount == 0) return

        // RMS level over the whole buffer.
        var sumSq = 0.0
        var i = 0
        while (i < sampleCount) {
            val s = sampleAt(pcm, i)
            sumSq += (s.toDouble() * s.toDouble())
            i++
        }
        _level.value = (sqrt(sumSq / sampleCount) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)

        // Downsample this buffer into BARS_PER_READ peak bars and push onto the rolling window.
        val segLen = sampleCount / BARS_PER_READ
        if (segLen > 0) {
            var seg = 0
            while (seg < BARS_PER_READ) {
                val start = seg * segLen
                val end = min(sampleCount, start + segLen)
                var peak = 0
                var j = start
                while (j < end) {
                    val a = abs(sampleAt(pcm, j))
                    if (a > peak) peak = a
                    j++
                }
                rollingPeaks.addLast((peak.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f))
                if (rollingPeaks.size > WAVEFORM_BARS) rollingPeaks.removeFirst()
                seg++
            }
            _waveform.value = rollingPeaks.toFloatArray()
        }
    }

    /** Reads a signed little-endian 16-bit sample at index [index]. */
    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = pcm[index * 2 + 1].toInt() // sign-extended
        return (hi shl 8) or lo
    }

    private fun AudioDeviceInfo.toCommDevice(): CommDevice =
        CommDevice(
            id = id,
            name = productName?.toString()?.ifBlank { "Audio device" } ?: "Audio device",
            typeLabel = typeLabel(type),
            isBluetooth =
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

    companion object {
        private const val TAG = "MicDiagnostics"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val WAVEFORM_BARS = 96
        private const val BARS_PER_READ = 3

        fun typeLabel(type: Int): String =
            when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE broadcast"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
                else -> "Type $type"
            }
    }
}
