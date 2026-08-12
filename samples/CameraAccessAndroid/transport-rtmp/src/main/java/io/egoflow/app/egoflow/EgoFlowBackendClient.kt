package io.egoflow.app.egoflow

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.egoflow.app.settings.AuthPrefs
import io.egoflow.app.settings.RepoPrefs
import java.io.IOException
import java.net.ProtocolException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

data class RegisteredRepository(
    val id: String,
    val name: String,
    val ownerId: String,
    val myRole: String,
    val visibility: String,
)

data class RegisteredStreamSession(
    val authToken: String,
    val userId: String,
    val recordingSessionId: String,
)

data class PublishTicketGrant(
    val authToken: String,
    val streamPath: String,
    val publishTicket: String,
    // Ready-to-use RTMP publish URL, assembled per the streaming contract:
    // rtmp://{backendBaseUrl host}:1935/{stream_path}?ticket={publish_ticket}.
    val publishUrl: String,
)

/** Result of an HTTP-chunk ingest call (start / chunk / finish). [authToken] is the
 *  effective token after any `X-Refreshed-Token` rotation (or 401 re-login on the
 *  chunk path); callers thread it into the next call. */
data class HttpStreamState(
    val authToken: String,
    val bytesReceived: Long,
    val lastSequence: Long?,
)

class EgoFlowBackendException(
    val statusCode: Int?,
    val code: String?,
    message: String,
) : IOException(message)

private const val HTTP_CHUNK_READ_TIMEOUT_SECONDS = 20L

internal fun createHttpChunkClient(baseClient: OkHttpClient): OkHttpClient =
    baseClient
        .newBuilder()
        .readTimeout(HTTP_CHUNK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

/**
 * [EgoFlow Backend API Client]
 * Synchronous HTTP client used by StreamViewModel.
 * The streaming flow calls these APIs sequentially:
 * 1. login() → POST /api/v1/auth/app/login (obtain JWT token)
 * 2. registerStreamSession() → POST /api/v1/streams/register (register session, returns recordingSessionId)
 * 3. requestPublishTicket() → POST /api/v1/streams/:id/publish-ticket (issue stream_path + publish_ticket, TTL 60s)
 * 4. closeIntent() → POST /api/v1/recordings/:id/close-intent (record NORMAL_DISCONNECT before socket close)
 *
 * Every method runs synchronously on an IO thread, so callers must invoke it from Dispatchers.IO.
 */
class EgoFlowBackendClient(
    // Injectable seams so the REST surface is unit-testable against a MockWebServer
    // without standing up the Android Keystore auth store. Production callers use the
    // no-arg constructor: the shared OkHttpClient + AuthPrefs-derived base URL.
    httpClientOverride: OkHttpClient? = null,
    private val apiBaseUrlOverride: okhttp3.HttpUrl? = null,
) {
    private val gson = Gson()
    private val client = httpClientOverride ?: sharedHttpClient
    private val httpChunkClient = createHttpChunkClient(client)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val octetStreamMediaType = "application/octet-stream".toMediaType()

    // Login reused across list/register calls so a repo-list refresh doesn't cost a
    // fresh login (+ password resend) every time. Refreshed on a 401, same as the
    // streaming path's withReLoginOnUnauthorized.
    @Volatile
    private var cachedLogin: LoginResponse? = null

    fun listMaintainedRepositories(): List<RegisteredRepository> {
        val apiBaseUrl = normalizedApiBaseUrl()
        val response =
            withCachedLoginRetry(apiBaseUrl) { login ->
                executeJson<RepositoryListResponse>(
                    Request.Builder()
                        .url(apiBaseUrl.newBuilder().addPathSegment("repositories").addPathSegment("maintain").build())
                        .addHeader("Authorization", "Bearer ${login.token}")
                        .get()
                        .build(),
                )
            }

        return response.repositories
            .orEmpty()
            .mapNotNull { repository ->
                val id = repository.id?.trim().orEmpty()
                val name = repository.name?.trim().orEmpty()
                val ownerId = repository.ownerId?.trim().orEmpty()
                val myRole = repository.myRole?.trim().orEmpty()
                val visibility = repository.visibility?.trim().orEmpty()
                if (id.isEmpty() || name.isEmpty() || ownerId.isEmpty() || myRole.isEmpty()) {
                    null
                } else {
                    RegisteredRepository(
                        id = id,
                        name = name,
                        ownerId = ownerId,
                        myRole = myRole,
                        visibility = visibility.ifEmpty { "private" },
                    )
                }
            }
    }

    // [Register streaming session]
    // 1) Obtain JWT token via login()
    // 2) Register session via POST /api/v1/streams/register
    //    - body: { repositoryId, deviceType, ingestType } (ingestType is server-required)
    //    - Server creates (or reuses) a PENDING RecordingSession and returns recording_session_id
    // 3) If the response carries an X-Refreshed-Token header, swap in the refreshed token
    // Returns: RegisteredStreamSession(authToken, userId, recordingSessionId)
    fun registerStreamSession(deviceType: String, ingestType: IngestType): RegisteredStreamSession {
        val apiBaseUrl = normalizedApiBaseUrl()
        val repositoryId =
            RepoPrefs.egoFlowRepositoryId.trim().ifBlank {
                throw IOException("EgoFlow repository is not selected.")
            }
        val registerRequest =
            RegisterStreamRequest(
                repositoryId = repositoryId,
                deviceType = deviceType,
                ingestType = ingestType.wireValue,
            )

        return withCachedLoginRetry(apiBaseUrl) { login ->
            val response =
                executeJsonResponse<RegisterStreamResponse>(
                    Request.Builder()
                        .url(apiBaseUrl.newBuilder().addPathSegment("streams").addPathSegment("register").build())
                        .addHeader("Authorization", "Bearer ${login.token}")
                        .post(gson.toJson(registerRequest).toRequestBody(jsonMediaType))
                        .build(),
                )

            val refreshedToken = response.headers[REFRESHED_TOKEN_HEADER]?.trim().orEmpty()
            val authToken = refreshedToken.ifEmpty { login.token }

            RegisteredStreamSession(
                authToken = authToken,
                userId = login.user.id,
                recordingSessionId = response.body.recordingSessionId,
            )
        }
    }

    fun requestPublishTicket(session: RegisteredStreamSession): PublishTicketGrant {
        val apiBaseUrl = normalizedApiBaseUrl()
        return withReLoginOnUnauthorized(session.authToken, apiBaseUrl) { authToken ->
            val response =
                executeJsonResponse<PublishTicketResponse>(
                    Request.Builder()
                        .url(
                            apiBaseUrl
                                .newBuilder()
                                .addPathSegment("streams")
                                .addPathSegment(session.recordingSessionId)
                                .addPathSegment("publish-ticket")
                                .build(),
                        )
                        .addHeader("Authorization", "Bearer $authToken")
                        .post("{}".toRequestBody(jsonMediaType))
                        .build(),
                )

            val refreshedToken = response.headers[REFRESHED_TOKEN_HEADER]?.trim().orEmpty()
            val effectiveAuthToken = refreshedToken.ifEmpty { authToken }

            val streamPath = response.body.streamPath.trim().trim('/').ifBlank {
                throw IOException("Publish ticket response is missing stream_path.")
            }
            val publishTicket = response.body.publishTicket.trim().ifBlank {
                throw IOException("Publish ticket response is missing publish_ticket.")
            }

            PublishTicketGrant(
                authToken = effectiveAuthToken,
                streamPath = streamPath,
                publishTicket = publishTicket,
                publishUrl = buildPublishUrl(apiBaseUrl, streamPath, publishTicket),
            )
        }
    }

    // [Record close intent]
    // POST /api/v1/recordings/:recordingSessionId/close-intent
    // body: { reason: "NORMAL_DISCONNECT" }
    // Records end_reason=NORMAL_DISCONNECT on the STREAMING session without changing its
    // status; the App closes the RTMP publisher socket only after this returns 200 OK.
    fun closeIntent(session: RegisteredStreamSession): String? {
        val closeRequest = CloseIntentRequest(reason = "NORMAL_DISCONNECT")
        val apiBaseUrl = normalizedApiBaseUrl()
        return withReLoginOnUnauthorized(session.authToken, apiBaseUrl) { authToken ->
            executeWithoutBody(
                Request.Builder()
                    .url(
                        apiBaseUrl
                            .newBuilder()
                            .addPathSegment("recordings")
                            .addPathSegment(session.recordingSessionId)
                            .addPathSegment("close-intent")
                            .build(),
                    )
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(gson.toJson(closeRequest).toRequestBody(jsonMediaType))
                    .build(),
            ) ?: authToken
        }
    }

    // ---- HTTP-chunk upload ingest (ingestType "HTTP") ----
    // These mirror the realtime control plane but target the /http-streams/{id}/*
    // routes (see .mermaid/HTTP/{3.http-start,4.http-stream}.mmd). The whole
    // register → publish-ticket → start → chunks → finish sequence runs at upload
    // time (after the local recording is finalized), so the 60s publish-ticket TTL
    // never bites. Responses are snake_case.

    // POST /api/v1/http-streams/:recordingSessionId/start  body { publish_ticket }
    // Consumes the publish ticket and moves the session PENDING → STREAMING.
    fun httpStreamStart(session: RegisteredStreamSession, publishTicket: String): HttpStreamState {
        val apiBaseUrl = normalizedApiBaseUrl()
        return withReLoginOnUnauthorized(session.authToken, apiBaseUrl) { authToken ->
            val response =
                executeJsonResponse<HttpStreamStateResponse>(
                    Request.Builder()
                        .url(
                            apiBaseUrl
                                .newBuilder()
                                .addPathSegment("http-streams")
                                .addPathSegment(session.recordingSessionId)
                                .addPathSegment("start")
                                .build(),
                        )
                        .addHeader("Authorization", "Bearer $authToken")
                        .post(gson.toJson(HttpStreamStartRequest(publishTicket)).toRequestBody(jsonMediaType))
                        .build(),
                )
            response.toState(authToken)
        }
    }

    // POST /api/v1/http-streams/:recordingSessionId/chunks  (octet-stream body)
    // Hot path: no re-login wrapper. On a 401 we re-login exactly once and replay
    // the SAME chunk (sequence/offset unchanged), returning the refreshed token so
    // the caller threads it into the next chunk. [length] lets callers reuse a
    // single scratch buffer across chunks.
    fun httpStreamChunk(
        authToken: String,
        recordingSessionId: String,
        sequence: Long,
        offset: Long,
        chunk: ByteArray,
        length: Int,
    ): HttpStreamState {
        val apiBaseUrl = normalizedApiBaseUrl()
        fun call(token: String): HttpStreamState {
            val response =
                executeJsonResponse<HttpStreamStateResponse>(
                    Request.Builder()
                        .url(
                            apiBaseUrl
                                .newBuilder()
                                .addPathSegment("http-streams")
                                .addPathSegment(recordingSessionId)
                                .addPathSegment("chunks")
                                .build(),
                        )
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("X-Chunk-Sequence", sequence.toString())
                        .addHeader("X-Chunk-Offset", offset.toString())
                        .post(chunk.toRequestBody(octetStreamMediaType, 0, length))
                        .build(),
                    httpChunkClient,
                )
            return response.toState(token)
        }
        return try {
            call(authToken)
        } catch (error: EgoFlowBackendException) {
            if (error.statusCode != 401) throw error
            val refreshed = login(apiBaseUrl).also { cachedLogin = it }
            call(refreshed.token)
        }
    }

    // POST /api/v1/http-streams/:recordingSessionId/finish  body { total_bytes }
    // Closes the session (CLOSED + NORMAL_DISCONNECT) and enqueues finalize. The
    // server triple-checks total_bytes == Redis.bytesReceived == on-disk size.
    fun httpStreamFinish(authToken: String, recordingSessionId: String, totalBytes: Long): HttpStreamState {
        val apiBaseUrl = normalizedApiBaseUrl()
        return withReLoginOnUnauthorized(authToken, apiBaseUrl) { token ->
            val response =
                executeJsonResponse<HttpStreamStateResponse>(
                    Request.Builder()
                        .url(
                            apiBaseUrl
                                .newBuilder()
                                .addPathSegment("http-streams")
                                .addPathSegment(recordingSessionId)
                                .addPathSegment("finish")
                                .build(),
                        )
                        .addHeader("Authorization", "Bearer $token")
                        .post(gson.toJson(HttpStreamFinishRequest(totalBytes)).toRequestBody(jsonMediaType))
                        .build(),
                )
            response.toState(token)
        }
    }

    private fun JsonResponse<HttpStreamStateResponse>.toState(fallbackToken: String): HttpStreamState {
        val refreshedToken = headers[REFRESHED_TOKEN_HEADER]?.trim().orEmpty()
        return HttpStreamState(
            authToken = refreshedToken.ifEmpty { fallbackToken },
            bytesReceived = body.bytesReceived,
            lastSequence = body.lastSequence,
        )
    }

    // [Backend login]
    // Log in via POST /api/v1/auth/app/login using the userId/password stored in AuthPrefs.
    // The returned JWT token is used in the Authorization header for subsequent
    // register/publish-ticket/close-intent requests.
    private fun login(apiBaseUrl: okhttp3.HttpUrl): LoginResponse {
        val request =
            Request.Builder()
                .url(apiBaseUrl.newBuilder().addPathSegment("auth").addPathSegment("app").addPathSegment("login").build())
                .post(
                    gson.toJson(
                        LoginRequest(
                            id = AuthPrefs.egoFlowUserId.trim(),
                            password = AuthPrefs.egoFlowPassword.trim(),
                        ),
                    ).toRequestBody(jsonMediaType),
                )
                .build()

        return executeJson(request)
    }

    // RTMP publish URL per the streaming contract: the host is the backend base URL's
    // hostname, the port is always 1935, the path is the server-returned stream_path
    // (live/{repositoryName}/{recordingSessionId}, whose slashes are real path segments),
    // and the opaque publish_ticket is the (URL-encoded) ?ticket= query value.
    private fun buildPublishUrl(
        apiBaseUrl: okhttp3.HttpUrl,
        streamPath: String,
        publishTicket: String,
    ): String {
        val encodedTicket = URLEncoder.encode(publishTicket, Charsets.UTF_8.name())
        return "rtmp://${apiBaseUrl.host}:1935/$streamPath?ticket=$encodedTicket"
    }

    private fun normalizedApiBaseUrl() =
        apiBaseUrlOverride ?: normalizeApiBaseUrl(AuthPrefs.egoFlowApiBaseUrl.trim())

    private fun normalizeApiBaseUrl(raw: String): okhttp3.HttpUrl {
        val withScheme =
            when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.isBlank() -> throw IllegalArgumentException("EgoFlow API base URL is blank.")
                // Default a scheme-less host to HTTPS so a bare "api.example.com" never sends the
                // login body (id + password) in cleartext. Explicit http:// is still honored for
                // lab deployments; the current network-security-config permits it app-wide.
                else -> "https://$raw"
            }

        val withoutTrailingSlash = withScheme.trimEnd('/')
        val apiBase =
            if (withoutTrailingSlash.endsWith("/api/v1")) {
                withoutTrailingSlash
            } else {
                "$withoutTrailingSlash/api/v1"
            }

        return try {
            apiBase.toHttpUrl()
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid EgoFlow API base URL: $apiBase", error)
        }
    }

    private inline fun <reified T> executeJson(request: Request): T =
        executeJsonResponse<T>(request).body

    private inline fun <reified T> executeJsonResponse(
        request: Request,
        requestClient: OkHttpClient = client,
    ): JsonResponse<T> {
        requestClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw parseBackendException(body, "Request failed with ${response.code}", response.code)
            }

            val parsedBody =
                gson.fromJson(body, T::class.java)
                    ?: throw ProtocolException("Response body was empty.")

            // Keep the raw okhttp3.Headers: its get() is case-insensitive, so
            // response.headers[REFRESHED_TOKEN_HEADER] resolves regardless of the
            // server's header casing. (toMultimap() lowercases into a case-sensitive
            // map, which silently broke the X-Refreshed-Token lookup.)
            return JsonResponse(
                body = parsedBody,
                headers = response.headers,
            )
        }
    }

    private fun executeWithoutBody(request: Request): String? {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw parseBackendException(body, "Request failed with ${response.code}", response.code)
            }

            return response.header(REFRESHED_TOKEN_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseBackendException(body: String, fallback: String, statusCode: Int): IOException {
        val parsed =
            runCatching { gson.fromJson(body, ErrorEnvelope::class.java) }
                .getOrNull()
                ?.error

        val code = parsed?.code?.trim()?.takeIf { it.isNotEmpty() }
        val message = parsed?.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        return EgoFlowBackendException(statusCode = statusCode, code = code, message = message)
    }

    private inline fun <T> withReLoginOnUnauthorized(
        initialAuthToken: String,
        apiBaseUrl: okhttp3.HttpUrl,
        block: (String) -> T,
    ): T {
        return try {
            block(initialAuthToken)
        } catch (error: EgoFlowBackendException) {
            if (error.statusCode != 401) {
                throw error
            }

            val refreshedLogin = login(apiBaseUrl)
            cachedLogin = refreshedLogin
            block(refreshedLogin.token)
        }
    }

    // List/register variant: runs [block] with the cached login (logging in once if
    // there isn't one), and on a 401 re-logs in fresh and retries exactly once.
    private inline fun <T> withCachedLoginRetry(
        apiBaseUrl: okhttp3.HttpUrl,
        block: (LoginResponse) -> T,
    ): T {
        val login = cachedLogin ?: login(apiBaseUrl).also { cachedLogin = it }
        return try {
            block(login)
        } catch (error: EgoFlowBackendException) {
            if (error.statusCode != 401) {
                throw error
            }

            val refreshedLogin = login(apiBaseUrl)
            cachedLogin = refreshedLogin
            block(refreshedLogin)
        }
    }

    private data class JsonResponse<T>(
        val body: T,
        val headers: okhttp3.Headers,
    )

    private data class LoginRequest(
        val id: String,
        val password: String,
    )

    private data class RegisterStreamRequest(
        val repositoryId: String,
        val deviceType: String,
        val ingestType: String,
    )

    private data class LoginResponse(
        val token: String,
        val user: LoginUser,
    )

    private data class LoginUser(
        val id: String,
    )

    private data class RepositoryListResponse(
        val repositories: List<RepositoryResponse>?,
    )

    private data class RepositoryResponse(
        val id: String?,
        val name: String?,
        @SerializedName("owner_id")
        val ownerId: String?,
        @SerializedName("my_role")
        val myRole: String?,
        val visibility: String?,
    )

    private data class RegisterStreamResponse(
        val recordingSessionId: String,
    )

    private data class PublishTicketResponse(
        @SerializedName("stream_path")
        val streamPath: String,
        @SerializedName("publish_ticket")
        val publishTicket: String,
    )

    private data class CloseIntentRequest(
        val reason: String,
    )

    private data class HttpStreamStartRequest(
        @SerializedName("publish_ticket")
        val publishTicket: String,
    )

    private data class HttpStreamFinishRequest(
        @SerializedName("total_bytes")
        val totalBytes: Long,
    )

    // Shared response shape for /http-streams/{id}/{start,chunks,finish} (snake_case).
    // start omits last_sequence (null); chunks omits status; finish adds segment_status.
    private data class HttpStreamStateResponse(
        @SerializedName("recording_session_id")
        val recordingSessionId: String? = null,
        val status: String? = null,
        @SerializedName("segment_status")
        val segmentStatus: String? = null,
        @SerializedName("bytes_received")
        val bytesReceived: Long = 0L,
        @SerializedName("last_sequence")
        val lastSequence: Long? = null,
    )

    private data class ErrorEnvelope(
        val error: ErrorPayload? = null,
    )

    private data class ErrorPayload(
        val code: String? = null,
        val message: String? = null,
    )

    companion object {
        private const val REFRESHED_TOKEN_HEADER = "X-Refreshed-Token"

        // One OkHttpClient — and its thread pool + connection pool — shared across all
        // EgoFlowBackendClient instances, per OkHttp's recommended usage.
        private val sharedHttpClient = OkHttpClient()
    }
}
