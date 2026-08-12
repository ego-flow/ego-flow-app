package io.egoflow.app.auth

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [EgoFlow Auth Client - Standalone Module]
 * Standalone coroutine-based client that calls POST /api/v1/auth/app/login.
 * Used for things like the connection-test action on the settings screen.
 * Note: the real streaming flow uses EgoFlowBackendClient.login() instead.
 */
class EgoFlowAuthClient(
    private val client: OkHttpClient = defaultHttpClient(),
    private val gson: Gson = Gson(),
) {
    suspend fun login(
        baseUrl: String,
        userId: String,
        password: String,
    ): EgoFlowLoginResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().removeSuffix("/")
        if (normalizedBaseUrl.isEmpty()) {
            return@withContext EgoFlowLoginResult.Failure("EgoFlow API base URL is required")
        }
        val parsedBaseUrl = normalizedBaseUrl.toHttpUrlOrNull()
            ?: return@withContext EgoFlowLoginResult.Failure(
                "EgoFlow API base URL must include http:// or https://"
            )
        if (userId.isBlank()) {
            return@withContext EgoFlowLoginResult.Failure("EgoFlow user ID is required")
        }
        if (password.isBlank()) {
            return@withContext EgoFlowLoginResult.Failure("EgoFlow password is required")
        }

        val requestBody = gson.toJson(
            LoginRequest(
                id = userId.trim(),
                password = password,
            )
        )

        val request = Request.Builder()
            .url(parsedBaseUrl.newBuilder().addPathSegments("api/v1/auth/app/login").build())
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
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
                        ?: "Login failed with HTTP ${response.code}"
                    return@withContext EgoFlowLoginResult.Failure(message)
                }

                val parsed = runCatching {
                    gson.fromJson(responseBody, LoginResponse::class.java)
                }.getOrNull()

                val token = parsed?.token?.trim().orEmpty()
                val returnedUserId = parsed?.user?.id?.trim().orEmpty()
                if (token.isEmpty() || returnedUserId.isEmpty()) {
                    return@withContext EgoFlowLoginResult.Failure("Login response was missing token or user id")
                }

                return@withContext EgoFlowLoginResult.Success(
                    token = token,
                    userId = returnedUserId,
                    displayName = parsed?.user?.displayName,
                    role = parsed?.user?.role,
                )
            }
        } catch (e: Exception) {
            return@withContext EgoFlowLoginResult.Failure(mapLoginException(e, parsedBaseUrl.isHttps))
        }
    }

    private fun mapLoginException(
        exception: Exception,
        isHttps: Boolean,
    ): String {
        val message = exception.message?.trim().orEmpty()
        if (isTlsProtocolMismatch(message, isHttps)) {
            return "TLS handshake failed. The server may be HTTP-only. Check whether the API Base URL should use http:// instead of https://."
        }
        return message.ifEmpty { "Network request failed" }
    }

    private fun isTlsProtocolMismatch(
        message: String,
        isHttps: Boolean,
    ): Boolean {
        if (!isHttps) {
            return false
        }
        val lowerMessage = message.lowercase()
        return "unable to parse tls packet header" in lowerMessage ||
            ("ssl" in lowerMessage && "unexpected" in lowerMessage) ||
            ("tls" in lowerMessage && "unexpected" in lowerMessage)
    }

    private data class LoginRequest(
        val id: String,
        val password: String,
    )

    private data class LoginResponse(
        val token: String?,
        val user: LoginUser?,
    )

    private data class LoginUser(
        val id: String?,
        val role: String?,
        val displayName: String?,
    )

    private data class ErrorEnvelope(
        val error: ErrorBody?,
    )

    private data class ErrorBody(
        val code: String?,
        val message: String?,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}

sealed interface EgoFlowLoginResult {
    data class Success(
        val token: String,
        val userId: String,
        val displayName: String?,
        val role: String?,
    ) : EgoFlowLoginResult

    data class Failure(
        val message: String,
    ) : EgoFlowLoginResult
}
