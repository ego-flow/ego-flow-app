package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.rtmp

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.min

class RtmpPublisher(private val publishUrl: String) : Closeable {
    companion object {
        private const val TAG = "RtmpPublisher"
        private const val RTMP_DEFAULT_PORT = 1935
        private const val OUT_CHUNK_SIZE = 4096
        private const val TYPE_SET_CHUNK_SIZE = 1
        private const val TYPE_ACK = 3
        private const val TYPE_WINDOW_ACK_SIZE = 5
        private const val TYPE_SET_PEER_BANDWIDTH = 6
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
        private const val TYPE_AMF0_COMMAND = 20
    }

    private data class ParsedUrl(
        val host: String,
        val port: Int,
        val app: String,
        val streamKey: String,
        val tcUrl: String,
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

    private val parsedUrl = parseUrl(publishUrl)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var inChunkSize = 128
    private var streamId = 0
    private val chunkStates = mutableMapOf<Int, ChunkState>()
    private var videoConfigSent = false

    fun connect() {
        RtmpDiagnostics.log("Connecting to ${parsedUrl.host}:${parsedUrl.port}/${parsedUrl.app}/${parsedUrl.streamKey}")
        val socket = Socket(parsedUrl.host, parsedUrl.port).apply { soTimeout = 5_000 }
        this.socket = socket
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
        sendPublish()
        awaitOnStatus("NetStream.Publish.Start")
        Log.i(TAG, "RTMP publish started: $publishUrl")
        RtmpDiagnostics.log("Publish started")
    }

    fun sendVideoConfig(format: MediaFormat) {
        val sps = format.getByteBuffer("csd-0") ?: return
        val pps = format.getByteBuffer("csd-1") ?: return
        val spsBytes = ByteArray(sps.remaining()).also { sps.get(it) }
        val ppsBytes = ByteArray(pps.remaining()).also { pps.get(it) }
        val payload = buildAvcSequenceHeader(spsBytes, ppsBytes)
        sendVideoMessage(payload, 0)
        videoConfigSent = true
        RtmpDiagnostics.log("Sent AVC config sps=${spsBytes.size} pps=${ppsBytes.size}")
    }

    fun sendVideoSample(buffer: ByteArray, info: MediaCodec.BufferInfo) {
        if (!videoConfigSent || info.size <= 0) return
        val compositionTimeMs = 0
        val packet = buildAvcVideoPacket(buffer, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, compositionTimeMs)
        sendVideoMessage(packet, (info.presentationTimeUs / 1000L).toInt())
    }

    fun sendAudioConfig(format: MediaFormat) {
        val asc = format.getByteBuffer("csd-0") ?: return
        val ascBytes = ByteArray(asc.remaining()).also { asc.get(it) }
        val payload = buildAacSequenceHeader(ascBytes)
        sendAudioMessage(payload, 0)
        RtmpDiagnostics.log("Sent AAC config bytes=${ascBytes.size}")
    }

    fun sendAudioSample(buffer: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val packet = buildAacAudioPacket(buffer)
        sendAudioMessage(packet, (info.presentationTimeUs / 1000L).toInt())
    }

    override fun close() {
        try {
            output?.flush()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        streamId = 0
        videoConfigSent = false
        RtmpDiagnostics.log("Publisher closed")
    }

    private fun parseUrl(url: String): ParsedUrl {
        val uri = Uri.parse(url)
        require(uri.scheme.equals("rtmp", ignoreCase = true)) { "Only rtmp:// URLs are supported" }
        val host = requireNotNull(uri.host) { "RTMP host is missing" }
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        require(segments.size >= 2) { "RTMP URL must look like rtmp://host/app/streamKey" }
        val app = segments.first()
        val streamKey = segments.drop(1).joinToString("/")
        val port = if (uri.port == -1) RTMP_DEFAULT_PORT else uri.port
        val tcUrl = "rtmp://$host:$port/$app"
        return ParsedUrl(host, port, app, streamKey, tcUrl)
    }

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
        val body = AmfWriter().apply {
            writeString("connect")
            writeNumber(1.0)
            writeObject(
                linkedMapOf(
                    "app" to parsedUrl.app,
                    "type" to "nonprivate",
                    "flashVer" to "FMLE/3.0 (compatible; EgoFlow)",
                    "tcUrl" to parsedUrl.tcUrl,
                    "fpad" to false,
                    "capabilities" to 15.0,
                    "audioCodecs" to 3575.0,
                    "videoCodecs" to 252.0,
                    "videoFunction" to 1.0,
                ),
            )
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
        while (true) {
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
        while (true) {
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
        while (true) {
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
                    if (code == expectedCode) {
                        return
                    }
                }
            }
        }
    }

    private fun sendAcknowledgement(size: Int) {
        val payload = ByteArray(4)
        writeInt32(payload, 0, size)
        writeMessage(TYPE_ACK, 0, payload, 0, 2)
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

    private fun buildAvcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val payload = ByteArray(16 + sps.size + pps.size)
        var index = 0
        payload[index++] = 0x17
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x00
        payload[index++] = 0x01
        payload[index++] = sps[1]
        payload[index++] = sps[2]
        payload[index++] = sps[3]
        payload[index++] = 0xff.toByte()
        payload[index++] = 0xe1.toByte()
        payload[index++] = ((sps.size shr 8) and 0xff).toByte()
        payload[index++] = (sps.size and 0xff).toByte()
        System.arraycopy(sps, 0, payload, index, sps.size)
        index += sps.size
        payload[index++] = 0x01
        payload[index++] = ((pps.size shr 8) and 0xff).toByte()
        payload[index++] = (pps.size and 0xff).toByte()
        System.arraycopy(pps, 0, payload, index, pps.size)
        return payload
    }

    private fun buildAvcVideoPacket(
        annexBData: ByteArray,
        isKeyFrame: Boolean,
        compositionTimeMs: Int,
    ): ByteArray {
        val nalUnits = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 4 < annexBData.size) {
            val startCodeLength =
                when {
                    annexBData[offset] == 0.toByte() &&
                        annexBData[offset + 1] == 0.toByte() &&
                        annexBData[offset + 2] == 1.toByte() -> 3
                    annexBData[offset] == 0.toByte() &&
                        annexBData[offset + 1] == 0.toByte() &&
                        annexBData[offset + 2] == 0.toByte() &&
                        annexBData[offset + 3] == 1.toByte() -> 4
                    else -> {
                        offset++
                        continue
                    }
                }
            val nalStart = offset + startCodeLength
            var nalEnd = annexBData.size
            var scan = nalStart
            while (scan + 3 < annexBData.size) {
                val found =
                    (annexBData[scan] == 0.toByte() &&
                        annexBData[scan + 1] == 0.toByte() &&
                        annexBData[scan + 2] == 1.toByte()) ||
                        (scan + 4 < annexBData.size &&
                            annexBData[scan] == 0.toByte() &&
                            annexBData[scan + 1] == 0.toByte() &&
                            annexBData[scan + 2] == 0.toByte() &&
                            annexBData[scan + 3] == 1.toByte())
                if (found) {
                    nalEnd = scan
                    break
                }
                scan++
            }
            if (nalEnd > nalStart) {
                nalUnits += annexBData.copyOfRange(nalStart, nalEnd)
            }
            offset = nalEnd
        }

        val payloadSize = 5 + nalUnits.sumOf { 4 + it.size }
        return ByteArray(payloadSize).apply {
            var index = 0
            this[index++] = if (isKeyFrame) 0x17 else 0x27
            this[index++] = 0x01
            this[index++] = ((compositionTimeMs shr 16) and 0xff).toByte()
            this[index++] = ((compositionTimeMs shr 8) and 0xff).toByte()
            this[index++] = (compositionTimeMs and 0xff).toByte()
            for (nal in nalUnits) {
                writeInt32(this, index, nal.size)
                index += 4
                System.arraycopy(nal, 0, this, index, nal.size)
                index += nal.size
            }
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
                    else -> error("Unsupported AMF value: ${value::class.java.simpleName}")
                }
            }
            data += 0x00
            data += 0x00
            data += 0x09
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
