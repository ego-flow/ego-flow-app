/*
 * WHIP signaling over HTTP.
 *
 * Two calls, both plain HTTP per the WHIP spec / .mermaid/3.whip-publish.mmd:
 *   - postOffer: POST the local SDP offer (Content-Type: application/sdp) to
 *     {origin}/{stream_path}/whip?ticket=..., receive the 201 SDP answer plus a
 *     Location header pointing at the created WHIP resource.
 *   - deleteSession: DELETE that resource on a graceful stop (best effort).
 *
 * Publish authorization is the opaque ?ticket= query value validated by
 * MediaMTX's authHTTPAddress callback into the backend; this client carries no
 * Authorization header (the WHIP path is not forward-authed by Caddy).
 */
package io.egoflow.app.transport.whip

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Non-2xx WHIP response, or a malformed one. [statusCode] is null for transport
 *  faults that never produced an HTTP status (DNS, connect, TLS). */
class WhipException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A successful WHIP publish: the remote SDP [sdpAnswer] to apply, and the
 *  absolute [resourceUrl] (resolved from the 201 Location) to DELETE on stop. */
data class WhipAnswer(
    val sdpAnswer: String,
    val resourceUrl: String?,
)

class WhipClient(private val client: OkHttpClient = sharedHttpClient) {
    private val sdpMediaType = "application/sdp".toMediaType()

    fun postOffer(whipUrl: String, sdpOffer: String): WhipAnswer {
        val request =
            Request.Builder()
                .url(whipUrl)
                .post(sdpOffer.toRequestBody(sdpMediaType))
                .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw WhipException(response.code, "WHIP publish failed with ${response.code}")
            }
            if (body.isBlank()) {
                throw WhipException(response.code, "WHIP publish returned an empty SDP answer")
            }
            return WhipAnswer(
                sdpAnswer = body,
                resourceUrl = resolveLocation(whipUrl, response.header("Location")),
            )
        }
    }

    /** Best-effort teardown of the WHIP resource. Failures are swallowed by the
     *  caller -- the backend also tears the session down on publisher disconnect. */
    fun deleteSession(resourceUrl: String) {
        val request = Request.Builder().url(resourceUrl).delete().build()
        client.newCall(request).execute().use { /* discard body */ }
    }

    // The 201 Location is frequently relative (e.g. "/live/.../whip/{id}");
    // resolve it against the WHIP request URL so callers hold an absolute URL.
    private fun resolveLocation(whipUrl: String, location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        return runCatching { whipUrl.toHttpUrl().resolve(loc)?.toString() }.getOrNull() ?: loc
    }

    companion object {
        // One shared client (thread pool + connection pool) per OkHttp guidance.
        private val sharedHttpClient = OkHttpClient()
    }
}
