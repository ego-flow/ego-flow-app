package io.egoflow.app.stream.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * [RTMP Protocol Implementation]
 * Implements the RTMP protocol directly over a TCP socket to publish video/audio to a MediaMTX server.
 *
 * connect() flow:
 * 1. Open TCP socket (host:port)
 * 2. RTMP handshake (C0/C1/S0/S1/S2/C2)
 * 3. Set chunk size → connect AMF command (tcUrl, app, fourCcList)
 * 4. releaseStream → FCPublish → createStream → publish
 *    → at this point MediaMTX sends an auth request (POST /api/v1/auth/rtmp) to authHTTPAddress
 * 5. Receive onStatus(NetStream.Publish.Start) → publish succeeded
 *
 * Afterward, encoded media data is sent as RTMP messages via sendVideoSample/sendAudioSample.
 * H.265 (HEVC) Enhanced RTMP is also supported; on connect, fourCcList advertises codec support to the server.
 */
class RtmpPublisher(
    private val publishUrl: String,
    private val preferredVideoCodec: RtmpVideoCodec = RtmpVideoCodec.H264,
    private val diagnosticsContext: RtmpPublishDiagnosticsContext? = null,
    private val publishAllowed: () -> Boolean = { true },
) : Closeable {
    companion object {
        private const val TAG = "RtmpPublisher"
        private const val RTMP_DEFAULT_PORT = 1935
        private const val RTMPS_DEFAULT_PORT = 1936
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        // Connect-time await loops (awaitCommandResult/awaitCreateStreamResult/awaitOnStatus) need
        // to check their COMMAND_TIMEOUT_MS deadline periodically, so soTimeout sets the granularity
        // for those. Post-handshake the dedicated reader thread just loops over null returns.
        private const val SOCKET_READ_TIMEOUT_MS = 500
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val READER_JOIN_TIMEOUT_MS = 500L
        private const val OUT_CHUNK_SIZE = 4096
        private const val TYPE_SET_CHUNK_SIZE = 1
        private const val TYPE_ACK = 3
        private const val TYPE_WINDOW_ACK_SIZE = 5
        private const val TYPE_SET_PEER_BANDWIDTH = 6
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
        private const val TYPE_AMF0_COMMAND = 20

        internal fun parsePublishUrl(url: String): ParsedUrl {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "rtmp" || scheme == "rtmps") { "Only rtmp:// and rtmps:// URLs are supported" }
            val host = requireNotNull(uri.host) { "RTMP host is missing" }
            val segments = uri.path
                ?.split('/')
                ?.filter { it.isNotBlank() }
                .orEmpty()
            require(segments.size >= 2) { "RTMP URL must look like rtmp://host/app/streamKey" }
            val querySuffix = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            val app = segments.first()
            val streamKey = segments.drop(1).joinToString("/") + querySuffix
            val port =
                if (uri.port == -1) {
                    if (scheme == "rtmps") RTMPS_DEFAULT_PORT else RTMP_DEFAULT_PORT
                } else {
                    uri.port
                }
            val tcUrl = "$scheme://$host:$port/$app"
            return ParsedUrl(
                scheme = requireNotNull(scheme),
                host = host,
                port = port,
                app = app,
                streamKey = streamKey,
                tcUrl = tcUrl,
                usesTls = scheme == "rtmps",
            )
        }
    }

    internal data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: Int,
        val app: String,
        val streamKey: String,
        val tcUrl: String,
        val usesTls: Boolean,
    )

    private data class ChunkHeader(
        val timestamp: Int,
        val messageLength: Int,
        val messageTypeId: Int,
        val messageStreamId: Int,
    )

    private data class ChunkState(
        var header: ChunkHeader? = null,
        var buffer: ByteArray = ByteArray(0),
        var bytesRead: Int = 0,
    )

    private sealed interface AmfValue {
        data class AmfNumber(val value: Double) : AmfValue
        data class AmfString(val value: String) : AmfValue
        data class AmfObject(val value: Map<String, AmfValue>) : AmfValue
        data object AmfNull : AmfValue
        data object AmfBooleanTrue : AmfValue
        data object AmfBooleanFalse : AmfValue
    }

    private val parsedUrl = parsePublishUrl(publishUrl)
    @Volatile private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var inChunkSize = 128
    private var streamId = 0
    private val chunkStates = mutableMapOf<Int, ChunkState>()
    private var pendingAudioConfig: ByteArray? = null
    private val videoPublishState = RtmpVideoPublishState()
    private val videoSamplePacer = RtmpSamplePacer()

    // Serializes all writes to [output]. Held by T_sender for media/lifecycle writes and by
    // T_reader when it auto-replies to control messages (Window Acknowledgement Size, etc.).
    // BufferedOutputStream is not thread-safe; without this, the reader's ack writes could
    // interleave mid-chunk with a sender's video payload and corrupt the RTMP framing.
    private val writeLock = ReentrantLock()
    @Volatile private var aborted = false
    @Volatile private var readerRunning = false
    private var readerThread: Thread? = null

    // [Establish RTMP connection]
    // TCP socket → RTMP handshake → set chunk size → connect → createStream → publish.
    // On publish, MediaMTX authenticates via authHTTPAddress (POST /api/v1/auth/rtmp).
    // On successful auth, MediaMTX fires the stream-ready hook (runOnReady),
    // which transitions the server's RecordingSession from PENDING → STREAMING.
    fun connect() {
        videoSamplePacer.reset()
        ensurePublishAllowed()
        val connectingFields =
            mutableMapOf<String, Any?>(
                "scheme" to parsedUrl.scheme,
                "host" to parsedUrl.host,
                "port" to parsedUrl.port,
                "publishUrl" to publishUrl,
            ).apply {
                putAll(diagnosticsContextFields())
            }
        RtmpDiagnostics.logEvent(
            event = "transport.connecting",
            fields = connectingFields,
        )
        val socket = openSocket()
        this.socket = socket
        ensurePublishAllowed()
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        doHandshake()
        sendSetChunkSize(OUT_CHUNK_SIZE)
        sendConnect()
        awaitCommandResult("_result", 1.0)
        sendReleaseStream()
        sendFCPublish()
        sendCreateStream()
        streamId = awaitCreateStreamResult()
        ensurePublishAllowed()
        sendPublish()
        awaitOnStatus("NetStream.Publish.Start")
        Log.i(TAG, "RTMP publish started: ${RtmpDiagnostics.maskSensitiveUrl(publishUrl)}")
        pendingAudioConfig = null
        videoPublishState.reset()
        val publishStartedFields =
            mutableMapOf<String, Any?>(
                "scheme" to parsedUrl.scheme,
                "host" to parsedUrl.host,
                "port" to parsedUrl.port,
            ).apply {
                putAll(diagnosticsContextFields())
            }
        RtmpDiagnostics.logEvent(
            event = "transport.publish_started",
            fields = publishStartedFields,
        )
        startReader()
    }

    // [Send video codec config]
    // Called on MediaCodec's OUTPUT_FORMAT_CHANGED.
    // Sends the SPS/PPS (H.264) or VPS/SPS/PPS (H.265) sequence header over RTMP.
    // This config must be sent first so the receiver can decode.
    fun sendVideoConfig(
        videoCodec: RtmpVideoCodec,
        format: MediaFormat,
        refreshH265ParameterSetsInBand: Boolean = false,
    ) {
        val config = RtmpVideoPacketizer.buildCodecConfig(videoCodec, format)
        videoPublishState.queueConfig(videoCodec, config, refreshH265ParameterSetsInBand)
        RtmpDiagnostics.log("Queued ${videoCodec.displayName} config")
    }

    // [Send encoded video frame]
    // Packetizes H.264/H.265 NAL units into RTMP video messages and sends them.
    // Waits for the first key frame, and sends pendingVideoConfig (SPS/PPS) right before the key frame.
    // Returns: true if a sample was actually sent.
    fun sendVideoSample(
        videoCodec: RtmpVideoCodec,
        buffer: ByteArray,
        info: MediaCodec.BufferInfo,
        pacingEnabled: Boolean = true,
    ): Boolean {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return false
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (!videoPublishState.canSendSample(isKeyFrame)) return false
        if (
            pacingEnabled &&
            !videoSamplePacer.awaitPresentationTime(info.presentationTimeUs, ::isVideoPacingAllowed)
        ) {
            return false
        }
        val timestampMs = (info.presentationTimeUs / 1000L).toInt()
        val prepared = requireNotNull(videoPublishState.prepareSample(isKeyFrame))
        if (prepared.sequenceHeader != null) {
            sendVideoMessage(prepared.sequenceHeader, timestampMs)
        }
        val compositionTimeMs = 0
        val packet =
            RtmpVideoPacketizer.buildVideoPacket(
                videoCodec,
                buffer,
                isKeyFrame,
                compositionTimeMs,
                prependedNalUnits = prepared.inBandParameterSets,
            )
        sendVideoMessage(packet, timestampMs)
        if (pacingEnabled) {
            videoSamplePacer.markSent(info.presentationTimeUs)
        }
        return true
    }

    // [Send audio codec config]
    // Sends the AAC AudioSpecificConfig (csd-0) as the RTMP audio sequence header.
    fun sendAudioConfig(format: MediaFormat) {
        val asc = format.getByteBuffer("csd-0") ?: return
        val ascBytes = ByteArray(asc.remaining()).also { asc.get(it) }
        pendingAudioConfig = buildAacSequenceHeader(ascBytes)
        RtmpDiagnostics.log("Sent AAC config bytes=${ascBytes.size}")
    }

    // [Send encoded audio frame]
    // Sends an AAC frame as an RTMP audio message.
    // Audio is only sent after video has started, and pendingAudioConfig is sent before the first audio frame.
    fun sendAudioSample(buffer: ByteArray, info: MediaCodec.BufferInfo): Boolean {
        if (!videoPublishState.hasPublishedVideoSample) return false
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return false
        val timestampMs = (info.presentationTimeUs / 1000L).toInt()
        if (pendingAudioConfig != null) {
            sendAudioMessage(requireNotNull(pendingAudioConfig), timestampMs)
            pendingAudioConfig = null
        }
        val packet = buildAacAudioPacket(buffer)
        sendAudioMessage(packet, timestampMs)
        return true
    }

    // [Close RTMP connection]
    // Flushes the output buffer, then closes the TCP socket.
    // When the socket closes, MediaMTX detects the disconnect and fires the stream-not-ready hook.
    override fun close() {
        // Tell the reader to stop first; closing the socket below will unblock its blocking read.
        readerRunning = false
        try {
            writeLock.withLock { output?.flush() }
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        readerThread?.let { thread ->
            // The reader exits via the socket-close → IOException path. Join briefly so we don't
            // leak a thread; if the join times out the daemon flag still cleans it up on shutdown.
            try {
                thread.join(READER_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        readerThread = null
        socket = null
        input = null
        output = null
        streamId = 0
        pendingAudioConfig = null
        videoPublishState.reset()
        videoSamplePacer.reset()
        RtmpDiagnostics.log("Publisher closed")
    }

    /**
     * Closes the socket without waiting for [writeLock]. This is the cross-thread escape hatch for
     * a sender whose pacing wait or blocking write prevented bounded shutdown/config enqueue from
     * making progress. The sender still performs regular idempotent [close] cleanup after unblocking.
     */
    internal fun abort() {
        aborted = true
        readerRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun ensurePublishAllowed() {
        if (aborted || !publishAllowed()) {
            throw RtmpTransportException("RTMP publish was cancelled before activation.")
        }
    }

    private fun isVideoPacingAllowed(): Boolean = !aborted

    private fun diagnosticsContextFields(): Map<String, Any> = diagnosticsContext?.toFields() ?: emptyMap()

    private fun doHandshake() {
        val input = requireNotNull(input)
        val output = requireNotNull(this.output)

        val c1 = ByteArray(1536)
        c1[0] = 0
        c1[1] = 0
        c1[2] = 0
        c1[3] = 0
        for (index in 8 until c1.size) {
            c1[index] = (index and 0xff).toByte()
        }

        output.writeByte(0x03)
        output.write(c1)
        output.flush()

        input.readUnsignedByte()
        val s1 = ByteArray(1536)
        val s2 = ByteArray(1536)
        input.readFully(s1)
        input.readFully(s2)
        output.write(s1)
        output.flush()
    }

    private fun sendConnect() {
        val commandObject =
            linkedMapOf<String, Any>(
                "app" to parsedUrl.app,
                "type" to "nonprivate",
                "flashVer" to "FMLE/3.0 (compatible; EgoFlow)",
                "tcUrl" to parsedUrl.tcUrl,
                "fpad" to false,
                "capabilities" to 15.0,
                "audioCodecs" to 3575.0,
                "videoCodecs" to 252.0,
                "videoFunction" to 1.0,
            ).apply {
                if (preferredVideoCodec == RtmpVideoCodec.H265) {
                    this["fourCcList"] = listOf(RtmpVideoCodec.H265.fourCc, RtmpVideoCodec.H264.fourCc)
                }
            }
        val body = AmfWriter().apply {
            writeString("connect")
            writeNumber(1.0)
            writeObject(commandObject)
        }.toByteArray()
        writeMessage(TYPE_AMF0_COMMAND, 0, body, 0, 3)
    }

    private fun sendReleaseStream() {
        val body = AmfWriter().apply {
            writeString("releaseStream")
            writeNumber(2.0)
            writeNull()
            writeString(parsedUrl.streamKey)
        }.toByteArray()
        writeMessage(TYPE_AMF0_COMMAND, 0, body, 0, 3)
    }

    private fun sendFCPublish() {
        val body = AmfWriter().apply {
            writeString("FCPublish")
            writeNumber(3.0)
            writeNull()
            writeString(parsedUrl.streamKey)
        }.toByteArray()
        writeMessage(TYPE_AMF0_COMMAND, 0, body, 0, 3)
    }

    private fun sendCreateStream() {
        val body = AmfWriter().apply {
            writeString("createStream")
            writeNumber(4.0)
            writeNull()
        }.toByteArray()
        writeMessage(TYPE_AMF0_COMMAND, 0, body, 0, 3)
    }

    private fun sendPublish() {
        val body = AmfWriter().apply {
            writeString("publish")
            writeNumber(5.0)
            writeNull()
            writeString(parsedUrl.streamKey)
            writeString("live")
        }.toByteArray()
        writeMessage(TYPE_AMF0_COMMAND, streamId, body, 0, 5)
    }

    private fun sendSetChunkSize(size: Int) {
        val payload = ByteArray(4)
        writeInt32(payload, 0, size)
        writeMessage(TYPE_SET_CHUNK_SIZE, 0, payload, 0, 2)
    }

    private fun sendVideoMessage(payload: ByteArray, timestampMs: Int) {
        writeMessage(TYPE_VIDEO, streamId, payload, timestampMs, 6)
    }

    private fun sendAudioMessage(payload: ByteArray, timestampMs: Int) {
        writeMessage(TYPE_AUDIO, streamId, payload, timestampMs, 4)
    }

    private fun awaitCreateStreamResult(): Int {
        val deadlineAt = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() >= deadlineAt) {
                throw RtmpTimeoutException("Timed out waiting for createStream result.")
            }
            val message = readMessage() ?: continue
            when (message.first.messageTypeId) {
                TYPE_WINDOW_ACK_SIZE -> {
                    if (message.second.size >= 4) {
                        sendAcknowledgement(readInt32(message.second, 0))
                    }
                }
                TYPE_SET_PEER_BANDWIDTH, TYPE_ACK, TYPE_AUDIO, TYPE_VIDEO -> Unit
                TYPE_SET_CHUNK_SIZE -> {
                    if (message.second.size >= 4) {
                        inChunkSize = readInt32(message.second, 0)
                    }
                }
                TYPE_AMF0_COMMAND -> {
                    val values = AmfReader(message.second).readAll()
                    val commandName = (values.getOrNull(0) as? AmfValue.AmfString)?.value ?: continue
                    val transactionId = (values.getOrNull(1) as? AmfValue.AmfNumber)?.value ?: -1.0
                    if (commandName == "_result" && transactionId == 4.0) {
                        return ((values.getOrNull(3) as? AmfValue.AmfNumber)?.value ?: 0.0).toInt()
                    }
                }
            }
        }
    }

    private fun awaitCommandResult(expectedCommand: String, transactionId: Double) {
        val deadlineAt = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() >= deadlineAt) {
                throw RtmpTimeoutException("Timed out waiting for RTMP command result $expectedCommand.")
            }
            val message = readMessage() ?: continue
            when (message.first.messageTypeId) {
                TYPE_SET_CHUNK_SIZE -> {
                    if (message.second.size >= 4) {
                        inChunkSize = readInt32(message.second, 0)
                    }
                }
                TYPE_WINDOW_ACK_SIZE -> {
                    if (message.second.size >= 4) {
                        sendAcknowledgement(readInt32(message.second, 0))
                    }
                }
                TYPE_SET_PEER_BANDWIDTH, TYPE_ACK, TYPE_AUDIO, TYPE_VIDEO -> Unit
                TYPE_AMF0_COMMAND -> {
                    val values = AmfReader(message.second).readAll()
                    val commandName = (values.getOrNull(0) as? AmfValue.AmfString)?.value ?: continue
                    val currentTransactionId =
                        (values.getOrNull(1) as? AmfValue.AmfNumber)?.value ?: -1.0
                    if (commandName == expectedCommand && currentTransactionId == transactionId) {
                        return
                    }
                }
            }
        }
    }

    private fun awaitOnStatus(expectedCode: String) {
        val deadlineAt = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() >= deadlineAt) {
                throw RtmpTimeoutException("Timed out waiting for RTMP status $expectedCode.")
            }
            val message = readMessage() ?: continue
            when (message.first.messageTypeId) {
                TYPE_SET_CHUNK_SIZE -> {
                    if (message.second.size >= 4) {
                        inChunkSize = readInt32(message.second, 0)
                    }
                }
                TYPE_WINDOW_ACK_SIZE -> {
                    if (message.second.size >= 4) {
                        sendAcknowledgement(readInt32(message.second, 0))
                    }
                }
                TYPE_SET_PEER_BANDWIDTH, TYPE_ACK, TYPE_AUDIO, TYPE_VIDEO -> Unit
                TYPE_AMF0_COMMAND -> {
                    val values = AmfReader(message.second).readAll()
                    val commandName = (values.getOrNull(0) as? AmfValue.AmfString)?.value ?: continue
                    if (commandName != "onStatus") continue
                    val info = values.getOrNull(3) as? AmfValue.AmfObject ?: continue
                    val code = (info.value["code"] as? AmfValue.AmfString)?.value ?: continue
                    val description =
                        (info.value["description"] as? AmfValue.AmfString)?.value
                            ?: "RTMP status $code"
                    if (code == expectedCode) {
                        return
                    }
                    throw when {
                        code.contains("Rejected", ignoreCase = true) ||
                            code.contains("Unauthorized", ignoreCase = true) ||
                            code.contains("Invalid", ignoreCase = true) ||
                            code.contains("BadName", ignoreCase = true) -> {
                            RtmpAuthException(description)
                        }
                        else -> {
                            IOException(description)
                        }
                    }
                }
            }
        }
    }

    private fun openSocket(): Socket {
        return if (parsedUrl.usesTls) {
            openTlsSocket()
        } else {
            Socket().apply {
                connect(InetSocketAddress(parsedUrl.host, parsedUrl.port), SOCKET_CONNECT_TIMEOUT_MS)
                soTimeout = SOCKET_READ_TIMEOUT_MS
                tcpNoDelay = true
            }
        }
    }

    private fun openTlsSocket(): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val plainSocket =
            try {
                Socket().apply {
                    connect(InetSocketAddress(parsedUrl.host, parsedUrl.port), SOCKET_CONNECT_TIMEOUT_MS)
                    tcpNoDelay = true
                }
            } catch (error: Exception) {
                throw RtmpTlsHandshakeException("Failed to open RTMPS TCP socket.", error)
            }

        val socket =
            try {
                factory.createSocket(plainSocket, parsedUrl.host, parsedUrl.port, true) as SSLSocket
            } catch (error: Exception) {
                try {
                    plainSocket.close()
                } catch (_: Exception) {
                }
                throw RtmpTlsHandshakeException("Failed to create RTMPS socket.", error)
            }

        try {
            socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            val sslParameters: SSLParameters = socket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = sslParameters
            socket.startHandshake()
            return socket
        } catch (error: SSLHandshakeException) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (error.message?.contains("hostname", ignoreCase = true) == true) {
                throw RtmpTlsHostnameException(
                    "RTMPS hostname verification failed for ${parsedUrl.host}:${parsedUrl.port}.",
                    error,
                )
            }
            throw RtmpTlsHandshakeException(
                "RTMPS handshake failed for ${parsedUrl.host}:${parsedUrl.port}.",
                error,
            )
        } catch (error: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw error
        }
    }

    private fun sendAcknowledgement(size: Int) {
        val payload = ByteArray(4)
        writeInt32(payload, 0, size)
        writeMessage(TYPE_ACK, 0, payload, 0, 2)
    }

    // [Dedicated reader thread]
    // Started at the end of connect(). Continuously reads from the socket so the server's flow-control
    // messages (Window Acknowledgement Size, Set Chunk Size, Set Peer Bandwidth) are handled promptly,
    // even while T_sender is mid-write on a large keyframe. Without this, the old "pump between writes"
    // model could miss a Window Ack request long enough for some servers to stop reading from us, the
    // kernel send buffer would fill, writeMessage() would block, and the codec-side send queue would
    // overflow -- surfacing as periodic "Dropped non-keyframe video (queue full)" bursts.
    //
    // Reads happen on this thread; the only writes it issues are tiny ack frames, serialized with
    // T_sender via [writeLock].
    private fun startReader() {
        readerRunning = true
        val thread = Thread({ runReaderLoop() }, "rtmp-reader").apply { isDaemon = true }
        readerThread = thread
        thread.start()
    }

    private fun runReaderLoop() {
        while (readerRunning) {
            val message =
                try {
                    readMessage()
                } catch (_: Exception) {
                    return
                } ?: continue
            try {
                handleReaderControl(message.first.messageTypeId, message.second)
            } catch (_: IOException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "Reader handler error", e)
            }
        }
    }

    private fun handleReaderControl(typeId: Int, payload: ByteArray) {
        when (typeId) {
            TYPE_WINDOW_ACK_SIZE -> {
                if (payload.size >= 4) {
                    sendAcknowledgement(readInt32(payload, 0))
                }
            }
            TYPE_SET_CHUNK_SIZE -> {
                if (payload.size >= 4) {
                    // Only mutated from this thread post-handshake; readMessage() also runs here.
                    inChunkSize = readInt32(payload, 0)
                }
            }
            TYPE_SET_PEER_BANDWIDTH, TYPE_ACK, TYPE_AUDIO, TYPE_VIDEO -> Unit
            else -> Unit
        }
    }

    private fun readMessage(): Pair<ChunkHeader, ByteArray>? {
        val input = input ?: return null
        try {
            while (true) {
                val basicHeader = input.readUnsignedByte()
                val fmt = basicHeader shr 6
                val csid = when (val base = basicHeader and 0x3f) {
                    0 -> input.readUnsignedByte() + 64
                    1 -> input.readUnsignedByte() + (input.readUnsignedByte() * 256) + 64
                    else -> base
                }

                val state = chunkStates.getOrPut(csid) { ChunkState() }
                val previous = state.header
                val header =
                    when (fmt) {
                        0 -> {
                            val timestamp = readUInt24(input)
                            val messageLength = readUInt24(input)
                            val messageTypeId = input.readUnsignedByte()
                            val messageStreamId = readInt32LittleEndian(input)
                            ChunkHeader(timestamp, messageLength, messageTypeId, messageStreamId)
                        }
                        1 -> {
                            val prev = requireNotNull(previous)
                            val timestampDelta = readUInt24(input)
                            val messageLength = readUInt24(input)
                            val messageTypeId = input.readUnsignedByte()
                            ChunkHeader(prev.timestamp + timestampDelta, messageLength, messageTypeId, prev.messageStreamId)
                        }
                        2 -> {
                            val prev = requireNotNull(previous)
                            val timestampDelta = readUInt24(input)
                            ChunkHeader(prev.timestamp + timestampDelta, prev.messageLength, prev.messageTypeId, prev.messageStreamId)
                        }
                        else -> requireNotNull(previous)
                    }

                state.header = header
                if (state.buffer.size != header.messageLength || fmt == 0 || fmt == 1) {
                    state.buffer = ByteArray(header.messageLength)
                    state.bytesRead = 0
                }

                if (header.timestamp >= 0xFFFFFF) {
                    input.readInt()
                }

                val remaining = header.messageLength - state.bytesRead
                val chunk = min(remaining, inChunkSize)
                input.readFully(state.buffer, state.bytesRead, chunk)
                state.bytesRead += chunk
                if (state.bytesRead == header.messageLength) {
                    state.bytesRead = 0
                    return header to state.buffer
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: EOFException) {
        }
        return null
    }

    private fun writeMessage(
        typeId: Int,
        messageStreamId: Int,
        payload: ByteArray,
        timestamp: Int,
        chunkStreamId: Int,
    ) {
        val output = requireNotNull(output)
        writeLock.withLock {
            var offset = 0
            while (offset < payload.size) {
                val chunkSize = min(OUT_CHUNK_SIZE, payload.size - offset)
                if (offset == 0) {
                    writeBasicHeader(output, 0, chunkStreamId)
                    writeUInt24(output, timestamp.coerceAtMost(0xFFFFFF))
                    writeUInt24(output, payload.size)
                    output.writeByte(typeId)
                    writeInt32LittleEndian(output, messageStreamId)
                    if (timestamp >= 0xFFFFFF) {
                        output.writeInt(timestamp)
                    }
                } else {
                    writeBasicHeader(output, 3, chunkStreamId)
                }
                output.write(payload, offset, chunkSize)
                offset += chunkSize
            }
            output.flush()
        }
    }

    private fun buildAacSequenceHeader(audioSpecificConfig: ByteArray): ByteArray {
        return ByteArray(2 + audioSpecificConfig.size).apply {
            this[0] = 0xAE.toByte()
            this[1] = 0x00
            System.arraycopy(audioSpecificConfig, 0, this, 2, audioSpecificConfig.size)
        }
    }

    private fun buildAacAudioPacket(rawAacFrame: ByteArray): ByteArray {
        return ByteArray(2 + rawAacFrame.size).apply {
            this[0] = 0xAE.toByte()
            this[1] = 0x01
            System.arraycopy(rawAacFrame, 0, this, 2, rawAacFrame.size)
        }
    }

    private fun readUInt24(input: DataInputStream): Int {
        return (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or
            input.readUnsignedByte()
    }

    private fun writeUInt24(output: DataOutputStream, value: Int) {
        output.writeByte((value shr 16) and 0xff)
        output.writeByte((value shr 8) and 0xff)
        output.writeByte(value and 0xff)
    }

    private fun writeBasicHeader(output: DataOutputStream, fmt: Int, chunkStreamId: Int) {
        output.writeByte((fmt shl 6) or (chunkStreamId and 0x3f))
    }

    private fun readInt32(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xff) shl 24) or
            ((buffer[offset + 1].toInt() and 0xff) shl 16) or
            ((buffer[offset + 2].toInt() and 0xff) shl 8) or
            (buffer[offset + 3].toInt() and 0xff)
    }

    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 24) and 0xff).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 3] = (value and 0xff).toByte()
    }

    private fun readInt32LittleEndian(input: DataInputStream): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun writeInt32LittleEndian(output: DataOutputStream, value: Int) {
        output.writeByte(value and 0xff)
        output.writeByte((value shr 8) and 0xff)
        output.writeByte((value shr 16) and 0xff)
        output.writeByte((value shr 24) and 0xff)
    }

    private inner class AmfWriter {
        private val data = ArrayList<Byte>()

        fun writeString(value: String) {
            data += 0x02
            data += ((value.length shr 8) and 0xff).toByte()
            data += (value.length and 0xff).toByte()
            value.toByteArray(Charsets.UTF_8).forEach { data += it }
        }

        fun writeNumber(value: Double) {
            data += 0x00
            val bits = java.lang.Double.doubleToLongBits(value)
            for (shift in 56 downTo 0 step 8) {
                data += ((bits shr shift) and 0xff).toByte()
            }
        }

        fun writeBoolean(value: Boolean) {
            data += 0x01
            data += if (value) 0x01 else 0x00
        }

        fun writeNull() {
            data += 0x05
        }

        fun writeObject(values: Map<String, Any>) {
            data += 0x03
            for ((key, value) in values) {
                data += ((key.length shr 8) and 0xff).toByte()
                data += (key.length and 0xff).toByte()
                key.toByteArray(Charsets.UTF_8).forEach { data += it }
                when (value) {
                    is String -> writeString(value)
                    is Double -> writeNumber(value)
                    is Boolean -> writeBoolean(value)
                    is List<*> -> writeStrictArray(value)
                    else -> error("Unsupported AMF value: ${value::class.java.simpleName}")
                }
            }
            data += 0x00
            data += 0x00
            data += 0x09
        }

        fun writeStrictArray(values: List<*>) {
            data += 0x0a
            val size = values.size
            for (shift in 24 downTo 0 step 8) {
                data += ((size shr shift) and 0xff).toByte()
            }
            values.forEach { value ->
                when (value) {
                    is String -> writeString(value)
                    is Double -> writeNumber(value)
                    is Boolean -> writeBoolean(value)
                    else -> error("Unsupported AMF array value: ${value?.javaClass?.simpleName ?: "null"}")
                }
            }
        }

        fun toByteArray(): ByteArray = ByteArray(data.size) { index -> data[index] }
    }

    private inner class AmfReader(private val data: ByteArray) {
        private var offset = 0

        fun readAll(): List<AmfValue> {
            val values = mutableListOf<AmfValue>()
            while (offset < data.size) {
                values += readValue() ?: break
            }
            return values
        }

        private fun readValue(): AmfValue? {
            if (offset >= data.size) return null
            return when (val type = data[offset++].toInt() and 0xff) {
                0x00 -> {
                    val bits = readLong()
                    AmfValue.AmfNumber(java.lang.Double.longBitsToDouble(bits))
                }
                0x01 -> if (readByte() != 0) AmfValue.AmfBooleanTrue else AmfValue.AmfBooleanFalse
                0x02 -> AmfValue.AmfString(readString(readUnsignedShort()))
                0x03 -> readObject()
                0x05, 0x06 -> AmfValue.AmfNull
                0x08 -> {
                    skip(4)
                    readObject()
                }
                else -> {
                    Log.w(TAG, "Unsupported AMF type: $type")
                    null
                }
            }
        }

        private fun readObject(): AmfValue.AmfObject {
            val values = linkedMapOf<String, AmfValue>()
            while (offset + 3 <= data.size) {
                val keyLength = readUnsignedShort()
                if (keyLength == 0 && peekByte() == 0x09) {
                    offset++
                    break
                }
                val key = readString(keyLength)
                val value = readValue() ?: break
                values[key] = value
            }
            return AmfValue.AmfObject(values)
        }

        private fun readUnsignedShort(): Int {
            val value = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            offset += 2
            return value
        }

        private fun readLong(): Long {
            var result = 0L
            repeat(8) {
                result = (result shl 8) or (data[offset++].toLong() and 0xff)
            }
            return result
        }

        private fun readByte(): Int = data[offset++].toInt() and 0xff

        private fun peekByte(): Int = data[offset].toInt() and 0xff

        private fun readString(length: Int): String {
            val value = String(data, offset, length, Charsets.UTF_8)
            offset += length
            return value
        }

        private fun skip(length: Int) {
            offset += length
        }
    }
}
