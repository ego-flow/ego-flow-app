package io.egoflow.app.auth

import com.google.gson.Gson
import io.egoflow.app.egoflow.IngestType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [EgoFlow Stream Registration Client - Standalone Module]
 * Standalone coroutine-based client that calls POST /api/v1/streams/register.
 * Used for things like the connection-test action on the settings screen.
 * Note: the real streaming flow uses EgoFlowBackendClient.registerStreamSession() instead.
 */
class EgoFlowStreamClient(
    private val client: OkHttpClient = defaultHttpClient(),
    private val gson: Gson = Gson(),
) {
    suspend fun registerStream(
        baseUrl: String,
        authToken: String,
        repositoryId: String,
        deviceType: String,
        ingestType: IngestType = IngestType.MEDIAMTX,
    ): EgoFlowRegisterStreamResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().removeSuffix("/")
        if (normalizedBaseUrl.isEmpty()) {
            return@withContext EgoFlowRegisterStreamResult.Failure("EgoFlow API base URL is required")
        }
        val parsedBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
            ?: return@withContext EgoFlowRegisterStreamResult.Failure(
                "EgoFlow API base URL must include http:// or https://"
            )
        val trimmedToken = authToken.trim()
        if (trimmedToken.isEmpty()) {
            return@withContext EgoFlowRegisterStreamResult.Failure("JWT token is required")
        }
        val trimmedRepositoryId = repositoryId.trim()
        if (trimmedRepositoryId.isEmpty()) {
            return@withContext EgoFlowRegisterStreamResult.Failure("repository_id is required")
        }
        val trimmedDeviceType = deviceType.trim()
        if (trimmedDeviceType.isEmpty()) {
            return@withContext EgoFlowRegisterStreamResult.Failure("device_type is required")
        }

        val requestBody = gson.toJson(
            RegisterStreamRequest(
                repositoryId = trimmedRepositoryId,
                deviceType = trimmedDeviceType,
                ingestType = ingestType.wireValue,
            )
        )

        val request = Request.Builder()
            .url(parsedBaseUrl.newBuilder().addPathSegments("api/v1/streams/register").build())
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $trimmedToken")
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorResponse = responseBody
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { gson.fromJson(it, ErrorEnvelope::class.java) }.getOrNull() }
                    val message = errorResponse?.error?.message
                        ?: "Stream registration failed with HTTP ${response.code}"
                    return@withContext EgoFlowRegisterStreamResult.Failure(message)
                }

                val parsed = runCatching {
                    gson.fromJson(responseBody, RegisterStreamResponse::class.java)
                }.getOrNull()

                val recordingSessionId = parsed?.recordingSessionId?.trim().orEmpty()
                if (recordingSessionId.isEmpty()) {
                    return@withContext EgoFlowRegisterStreamResult.Failure(
                        "Register response was missing recording_session_id"
                    )
                }

                return@withContext EgoFlowRegisterStreamResult.Success(
                    recordingSessionId = recordingSessionId,
                    refreshedToken = response.header(HEADER_REFRESHED_TOKEN)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) {
            return@withContext EgoFlowRegisterStreamResult.Failure(e.message?.trim().orEmpty().ifEmpty {
                "Network request failed"
            })
        }
    }

    private data class RegisterStreamRequest(
        val repositoryId: String,
        val deviceType: String,
        val ingestType: String,
    )

    private data class RegisterStreamResponse(
        val recordingSessionId: String?,
    )

    private data class ErrorEnvelope(
        val error: ErrorBody?,
    )

    private data class ErrorBody(
        val code: String?,
        val message: String?,
    )

    companion object {
        private const val HEADER_REFRESHED_TOKEN = "X-Refreshed-Token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}

sealed interface EgoFlowRegisterStreamResult {
    data class Success(
        val recordingSessionId: String,
        val refreshedToken: String?,
    ) : EgoFlowRegisterStreamResult

    data class Failure(
        val message: String,
    ) : EgoFlowRegisterStreamResult
}
