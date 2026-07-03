package io.egoflow.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.camera.types.VideoQuality
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.stream.rtmp.RtmpAudioSource
import io.egoflow.app.stream.rtmp.RtmpVideoCodec
import io.egoflow.app.ui.theme.ThemePreference

object SettingsManager {
    private const val PREFS_NAME = "egoflow_settings"
    private const val DEFAULT_RTMP_VIDEO_CODEC = "h264"
    private const val DEFAULT_VIDEO_QUALITY = "MEDIUM"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Which outbound protocol the transport layer uses. Defaults to RTMP so
    // existing installs keep their current behavior; WHIP is the WebRTC path.
    var transportMode: TransportId
        get() = runCatching {
            TransportId.valueOf(prefs.getString("transportMode", TransportId.RTMP.name).orEmpty())
        }.getOrDefault(TransportId.RTMP)
        set(value) = prefs.edit().putString("transportMode", value.name).apply()

    var rtmpEnabled: Boolean
        get() = prefs.getBoolean("rtmpEnabled", true)
        set(value) = prefs.edit().putBoolean("rtmpEnabled", value).apply()

    var rtmpAudioEnabled: Boolean
        get() = prefs.getBoolean("rtmpAudioEnabled", false)
        set(value) = prefs.edit().putBoolean("rtmpAudioEnabled", value).apply()

    var audioSource: RtmpAudioSource
        get() = RtmpAudioSource.fromPreferenceValue(prefs.getString("audioSource", RtmpAudioSource.AUTO.preferenceValue))
        set(value) = prefs.edit().putString("audioSource", value.preferenceValue).apply()

    var rtmpCompressVideo: Boolean
        get() = prefs.getBoolean("rtmpCompressVideo", false)
        set(value) = prefs.edit().putBoolean("rtmpCompressVideo", value).apply()

    var rtmpVideoCodec: RtmpVideoCodec
        get() = RtmpVideoCodec.fromPreferenceValue(prefs.getString("rtmpVideoCodec", DEFAULT_RTMP_VIDEO_CODEC))
        set(value) = prefs.edit().putString("rtmpVideoCodec", value.preferenceValue).apply()

    var rtmpDebugOverlayEnabled: Boolean
        get() = prefs.getBoolean("rtmpDebugOverlayEnabled", false)
        set(value) = prefs.edit().putBoolean("rtmpDebugOverlayEnabled", value).apply()

    var videoQuality: VideoQuality
        get() = runCatching {
            VideoQuality.valueOf(prefs.getString("videoQuality", DEFAULT_VIDEO_QUALITY).orEmpty())
        }.getOrDefault(VideoQuality.MEDIUM)
        set(value) = prefs.edit().putString("videoQuality", value.name).apply()

    var themeMode: ThemePreference
        get() = runCatching {
            ThemePreference.valueOf(prefs.getString("themeMode", ThemePreference.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemePreference.SYSTEM)
        set(value) = prefs.edit().putString("themeMode", value.name).apply()

    fun resetAll() {
        prefs.edit().clear().apply()
    }
}
