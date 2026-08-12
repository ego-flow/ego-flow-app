package io.egoflow.app.stream.rtmp

import android.media.MediaFormat

enum class RtmpVideoCodec(
    val preferenceValue: String,
    val displayName: String,
    val mimeType: String,
    val fourCc: String,
) {
    H264(
        preferenceValue = "h264",
        displayName = "H.264",
        mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
        fourCc = "avc1",
    ),
    H265(
        preferenceValue = "h265",
        displayName = "H.265",
        mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
        fourCc = "hvc1",
    ),
    ;

    companion object {
        fun fromPreferenceValue(value: String?): RtmpVideoCodec {
            return values().firstOrNull { it.preferenceValue.equals(value, ignoreCase = true) } ?: H264
        }
    }
}
