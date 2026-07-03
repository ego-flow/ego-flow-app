package io.egoflow.app.stream.rtmp

import android.util.Log
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RtmpDiagnostics {
    private const val TAG = "RtmpDiagnostics"
    private const val MAX_ENTRIES = 50
    private val SENSITIVE_QUERY_KEYS = setOf("user", "pass", "token", "ticket", "auth", "authorization")
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun log(message: String) {
        val entry = "${timestampFormat.format(Date())}  $message"
        runCatching {
            Log.i(TAG, entry)
        }
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun logEvent(
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val payload =
            buildString {
                append("event=")
                append(sanitizeFieldValue("event", event))
                for ((key, value) in fields) {
                    if (value == null) {
                        continue
                    }
                    append(' ')
                    append(key)
                    append('=')
                    append(sanitizeFieldValue(key, value.toString()))
                }
            }
        log(payload)
    }

    fun maskSensitiveUrl(url: String): String {
        val trimmedUrl = url.trim()
        val uri = runCatching { URI(trimmedUrl) }.getOrNull() ?: return trimmedUrl
        val rawQuery = uri.rawQuery ?: return trimmedUrl
        if (rawQuery.isBlank()) {
            return trimmedUrl
        }

        val maskedQuery = rawQuery
            .split('&')
            .joinToString("&") { parameter ->
                val separatorIndex = parameter.indexOf('=')
                if (separatorIndex < 0) {
                    return@joinToString parameter
                }

                val rawKey = parameter.substring(0, separatorIndex)
                val value = parameter.substring(separatorIndex + 1)
                if (rawKey.lowercase(Locale.US) in SENSITIVE_QUERY_KEYS && value.isNotEmpty()) {
                    "$rawKey=***"
                } else {
                    parameter
                }
            }

        return trimmedUrl.replaceFirst(rawQuery, maskedQuery)
    }

    private fun sanitizeFieldValue(key: String, value: String): String {
        val normalized =
            if (key.lowercase(Locale.US).contains("url")) {
                maskSensitiveUrl(value)
            } else {
                value
            }
        val collapsed = normalized.replace(Regex("\\s+"), "_")
        return collapsed.ifEmpty { "\"\"" }
    }
}

data class RtmpPublishDiagnosticsContext(
    val recordingSessionId: String,
    val repositoryName: String,
    val connectionId: String,
    val generation: Long,
) {
    fun toFields(): Map<String, Any> =
        linkedMapOf(
            "recordingSessionId" to recordingSessionId,
            "repositoryName" to repositoryName,
            "connectionId" to connectionId,
            "generation" to generation,
        )
}
