package io.egoflow.app.stream.rtmp

/**
 * Which microphone feeds the streamed audio.
 *
 * The Meta glasses mic is shared through the Android system
 * Bluetooth stack (HFP/SCO). [RtmpAudioRecorder] captures it by routing the communication audio
 * path to the glasses' Bluetooth device and recording with a VOICE_COMMUNICATION source.
 */
enum class RtmpAudioSource(
    val preferenceValue: String,
    val displayName: String,
) {
    /** Glasses mic when a Bluetooth communication device is detected, otherwise the phone mic. */
    AUTO("auto", "Auto"),

    /** Force the glasses mic; falls back to the phone mic if no Bluetooth comm device is present. */
    GLASSES("glasses", "Glasses"),

    /** Force the phone's built-in mic. */
    PHONE("phone", "Phone"),
    ;

    companion object {
        fun fromPreferenceValue(value: String?): RtmpAudioSource {
            return values().firstOrNull { it.preferenceValue.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}
