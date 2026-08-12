/*
 * HttpLiveUploader -- ships fMP4 segments to the /http-streams control plane WHILE
 * capture is still running (overlapped), replacing the old store-and-forward
 * HttpChunkUploader that only ran after the recording was finalized.
 *
 * Sequence:
 *   start():   register(HTTP) → publish-ticket → /start (consumes ticket, PENDING→
 *              STREAMING) — then a send loop begins draining queued segments.
 *   enqueue(): the recorder feeds init segment + each moof/mdat fragment here.
 *   /chunks:   the send loop coalesces queued bytes (>=TARGET_CHUNK, <=FLUSH_MS
 *              idle heartbeat, or <=MAX_CHUNK_INTERVAL_MS since the last chunk send)
 *              and POSTs them in order (sequence 0.., offset = cumulative bytes).
 *   finishAndAwait(): drains the queue, POSTs /finish (total_bytes == bytes sent).
 *
 * Runs on its OWN process-lifetime coroutine scope (NOT a viewModelScope): the final
 * drain + /finish must survive the ViewModel teardown that the stop path triggers.
 * The foreground StreamingService keeps the process alive for the session + finish.
 *
 * Backpressure: a bounded queue. If the network can't keep up with the encoder
 * bitrate, enqueue() blocks the codec thread (bounding memory) and the session may
 * ultimately fall behind and time out server-side -- an inherent live-upload tradeoff.
 */
package io.egoflow.app.transport.http

import android.util.Log
import io.egoflow.app.egoflow.EgoFlowBackendClient
import io.egoflow.app.egoflow.HttpStreamState
import io.egoflow.app.egoflow.IngestType
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HttpLiveUploader(
    private val backend: EgoFlowBackendClient = EgoFlowBackendClient(),
    // Called (cumulative bytes acked) after each chunk so the UI/notification can
    // surface live upload progress. Invoked on the send-loop coroutine.
    private val onProgress: (sent: Long) -> Unit = {},
) {
    companion object {
        private const val TAG = "HttpLiveUploader"

        // Coalesce queued segment bytes up to this before POSTing (well under the
        // server's 64 MB per-chunk cap).
        private const val TARGET_CHUNK = 128 * 1024

        // Heartbeat: flush whatever is buffered at least this often so the server's
        // 10s idle reconcile never closes a live session mid-capture.
        private const val FLUSH_MS = 3_000L

        // Force buffered bytes out even if segments continue arriving below TARGET_CHUNK.
        private const val MAX_CHUNK_INTERVAL_MS = 5_000L

        // Bounded backlog (segments). Caps memory if the uplink lags the encoder.
        private const val QUEUE_CAPACITY = 64
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<ByteArray>(QUEUE_CAPACITY)
    private var sendLoopJob: Job? = null

    // Owned solely by the send-loop coroutine after start().
    private var recordingSessionId: String = ""
    private var token: String = ""
    private var offset: Long = 0L
    private var sequence: Long = 0L

    @Volatile private var failed = false

    /**
     * Run the control plane (register → publish-ticket → /start) and begin the send
     * loop. Throws on failure so the transport can surface a TransportStartException.
     */
    suspend fun start(deviceType: String) = withContext(Dispatchers.IO) {
        val registered = backend.registerStreamSession(deviceType, IngestType.HTTP)
        val grant = backend.requestPublishTicket(registered)
        val session = registered.copy(authToken = grant.authToken)
        val state: HttpStreamState = backend.httpStreamStart(session, grant.publishTicket)
        recordingSessionId = session.recordingSessionId
        token = state.authToken
        sendLoopJob = scope.launch { runSendLoop() }
    }

    /** Queue one segment (init or fragment). Blocks the caller (codec thread) when the
     *  backlog is full -- intentional backpressure. No-op once the uploader has failed
     *  or finished. */
    fun enqueue(segment: ByteArray) {
        if (failed) return
        // Blocks the codec thread when the backlog is full (intentional backpressure).
        // Returns a closed result if the queue is finished/cancelled -- drop the segment.
        queue.trySendBlocking(segment)
    }

    /** Close the queue, drain remaining segments, and POST /finish. Returns once the
     *  send loop has fully finished. If the caller's scope is cancelled mid-await the
     *  send loop still completes on this uploader's own scope. */
    suspend fun finishAndAwait() {
        queue.close()
        sendLoopJob?.join()
        scope.cancel()
    }

    /** Abort without /finish (non-graceful stop). The server session times out and is
     *  recovered as a partial recording. */
    fun cancel() {
        failed = true
        queue.close()
        scope.cancel()
    }

    private suspend fun runSendLoop() {
        val agg = ByteArrayOutputStream()
        var lastChunkSentAtMs = nowMs()
        fun flushBuffered() {
            lastChunkSentAtMs = nowMs()
            sendChunk(agg)
        }
        try {
            while (true) {
                val timeoutMs = if (agg.size() > 0) {
                    val elapsedSinceLastChunk = nowMs() - lastChunkSentAtMs
                    minOf(FLUSH_MS, (MAX_CHUNK_INTERVAL_MS - elapsedSinceLastChunk).coerceAtLeast(0L))
                } else {
                    FLUSH_MS
                }
                val received = if (timeoutMs == 0L) {
                    null
                } else {
                    withTimeoutOrNull(timeoutMs) { queue.receiveCatching() }
                }
                if (received == null) {
                    // Heartbeat tick or max-send-interval deadline: flush whatever we have.
                    if (agg.size() > 0) flushBuffered()
                    continue
                }
                val segment = received.getOrNull()
                    ?: break // queue closed and drained
                agg.write(segment)
                val elapsedSinceLastChunk = nowMs() - lastChunkSentAtMs
                if (agg.size() >= TARGET_CHUNK || elapsedSinceLastChunk >= MAX_CHUNK_INTERVAL_MS) {
                    flushBuffered()
                }
            }
            if (agg.size() > 0) flushBuffered()
            if (offset > 0) {
                backend.httpStreamFinish(token, recordingSessionId, offset)
                Log.d(TAG, "Live upload finished: $offset bytes in $sequence chunks")
            } else {
                // No bytes ever sent (session started but captured nothing): the server
                // has no raw file to finalize, so /finish would fail -- let it time out.
                Log.w(TAG, "Live upload had no data; skipping /finish (server will time out)")
            }
        } catch (e: Exception) {
            // Stop sending. The server's idle timeout finalizes whatever it has as a
            // partial recording (the chunk protocol has no resume).
            failed = true
            Log.w(TAG, "Live upload aborted after $offset bytes", e)
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    // POST the accumulated bytes as one /chunks call and reset the accumulator.
    private fun sendChunk(agg: ByteArrayOutputStream) {
        val payload = agg.toByteArray()
        agg.reset()
        val state = backend.httpStreamChunk(token, recordingSessionId, sequence, offset, payload, payload.size)
        val expected = offset + payload.size
        if (state.bytesReceived != expected) {
            throw IOException(
                "HTTP chunk desync at seq $sequence: server bytes_received=" +
                    "${state.bytesReceived}, expected=$expected",
            )
        }
        token = state.authToken
        offset = expected
        sequence += 1
        onProgress(offset)
    }
}
