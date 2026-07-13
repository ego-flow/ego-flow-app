/*
 * Thin wrapper over a single libwebrtc PeerConnection used to publish one WHIP
 * session.
 *
 * Responsibilities:
 *   - Own the per-session WebRTC objects (factory, EglBase, PeerConnection,
 *     VideoSource/Track) and tear them down on dispose().
 *   - Accept raw I420 frames from the glasses/phone capture threads
 *     (feedI420) and push them into the video source. WebRTC's own encoder
 *     produces the H.264 the WHIP answer negotiates -- no MediaCodec here.
 *   - Drive the offer/answer handshake as suspend functions, bridging
 *     libwebrtc's callback API to coroutines.
 *
 * We use NON-TRICKLE ICE: createOfferAndGather() waits for ICE gathering to
 * finish so the offer we hand back already carries every candidate. That keeps
 * the WHIP exchange a single POST (no PATCH/trickle), which is the simplest
 * reliable interop with MediaMTX.
 *
 * Note: org.webrtc.VideoFrame (used here) is distinct from EgoFlow's
 * GlassesVideoFrame; frames cross this boundary as plain I420 byte arrays.
 */
package io.egoflow.app.transport.whip

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WhipPeerConnection(private val context: Context) {

    /** Fired once when the connection reaches CONNECTED. */
    var onConnected: (() -> Unit)? = null

    /** Fired on an unrecoverable connection failure (ICE/DTLS failed). */
    var onFailed: ((String) -> Unit)? = null

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    // Serializes feedI420 against dispose() so we never push a frame into a
    // source that is being torn down on another thread.
    private val lifecycleLock = Any()
    @Volatile private var disposed = false
    @Volatile private var capturing = false

    private val connectedOnce = AtomicBoolean(false)
    private val framesFed = AtomicLong(0L)

    // Completes when ICE gathering finishes; consulted by createOfferAndGather.
    private val gatheringComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Build the factory + peer connection + send-only video track. Native init
     *  is heavy -- call from a worker dispatcher, not Main. */
    fun start() {
        ensureFactoryInitialized(context)
        eglBase = EglBase.create()
        factory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBase.eglBaseContext,
                        /* enableIntelVp8Encoder = */ true,
                        /* enableH264HighProfile = */ true,
                    ),
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()

        // STUN lets us gather a server-reflexive candidate so connectivity checks
        // succeed across NAT. Without it we only offer host (LAN) candidates, which
        // fail ICE for any non-LAN MediaMTX -- surfaced upstream as a NETWORK drop.
        // Mirrors the server's webrtcICEServers2 (mediamtx.yml).
        val iceServers =
            STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val rtcConfig =
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Gather every candidate once so the offer we POST is complete (non-trickle).
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }

        val pc =
            factory.createPeerConnection(rtcConfig, PeerObserver())
                ?: error("Failed to create PeerConnection")
        peerConnection = pc

        val source = factory.createVideoSource(/* isScreencast = */ false)
        videoSource = source
        val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
        videoTrack = track
        source.capturerObserver.onCapturerStarted(true)
        capturing = true

        // Publish-only: send our camera, expect nothing back. Seed a single send
        // encoding with our target bitrate so the encoder has room to keep the
        // source resolution (see configureSendParameters).
        val sendEncoding =
            RtpParameters.Encoding(/* rid = */ null, /* active = */ true, /* scaleResolutionDownBy = */ null)
                .apply { maxBitrateBps = MAX_BITRATE_BPS }
        val transceiver =
            pc.addTransceiver(
                track as MediaStreamTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    /* streamIds = */ emptyList(),
                    /* sendEncodings = */ listOf(sendEncoding),
                ),
            )
        preferH264(transceiver)
        configureSendParameters(transceiver)
    }

    // Without an explicit bitrate + degradation preference, libwebrtc starts from a
    // conservative bitrate and, for a camera track, prefers to drop RESOLUTION under
    // pressure -- so the published stream collapses to ~360p regardless of the source
    // resolution (504x896 glasses / 480x640 phone). Raising maxBitrate and asking the
    // encoder to MAINTAIN_RESOLUTION (sacrificing framerate first) keeps the source's
    // native resolution on the wire.
    private fun configureSendParameters(transceiver: RtpTransceiver) {
        val sender = transceiver.sender
        val params = sender.parameters ?: return
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        // addTransceiver seeded one encoding; ensure the bitrate ceiling sticks even if
        // libwebrtc re-derived the encodings list.
        if (params.encodings.isEmpty()) {
            Log.w(TAG, "No send encodings to configure; bitrate ceiling not applied")
        } else {
            params.encodings.forEach { it.maxBitrateBps = MAX_BITRATE_BPS }
        }
        runCatching { sender.parameters = params }
            .onFailure { Log.w(TAG, "configureSendParameters failed; using encoder defaults", it) }
    }

    // Force H.264 to the front of the offer. WebRTC would otherwise often
    // negotiate VP8/VP9, which MediaMTX can ingest but CANNOT write to its fMP4
    // recording -- it logs "no supported tracks found, skipping recording" and no
    // segment is ever created, so the recording never reaches the dashboard.
    // fMP4 recording supports H.264/H.265/AV1; H.264 is the safe, universally
    // decodable choice for the downstream finalize/transcode.
    private fun preferH264(transceiver: RtpTransceiver) {
        val capabilities =
            factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
        // Keep H.264 plus its retransmission/FEC helpers; drop VP8/VP9/AV1 so the
        // answerer has no non-recordable codec to choose.
        val kept = capabilities.codecs.filter {
            when (it.name.uppercase()) {
                "H264", "RTX", "RED", "ULPFEC" -> true
                else -> false
            }
        }
        if (kept.none { it.name.equals("H264", ignoreCase = true) }) {
            Log.w(TAG, "No H264 sender capability; leaving default codecs (recording may be skipped)")
            return
        }
        // H.264 first; helpers after.
        val ordered =
            kept.filter { it.name.equals("H264", ignoreCase = true) } +
                kept.filterNot { it.name.equals("H264", ignoreCase = true) }
        runCatching { transceiver.setCodecPreferences(ordered) }
            .onFailure { Log.w(TAG, "setCodecPreferences(H264) failed; using defaults", it) }
    }

    /** Push one I420 frame to the encoder. Returns true if it was accepted.
     *  Safe to call from any capture thread; a no-op once disposed. */
    fun feedI420(data: ByteArray, width: Int, height: Int, timestampNs: Long): Boolean {
        if (disposed || !capturing) return false
        if (width <= 0 || height <= 0) return false
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width * height
        val chromaSize = chromaWidth * chromaHeight
        if (data.size < ySize + 2 * chromaSize) return false

        synchronized(lifecycleLock) {
            val source = videoSource ?: return false
            if (disposed || !capturing) return false
            val buffer = JavaI420Buffer.allocate(width, height)
            copyPlane(data, 0, width, buffer.dataY, buffer.strideY, width, height)
            copyPlane(data, ySize, chromaWidth, buffer.dataU, buffer.strideU, chromaWidth, chromaHeight)
            copyPlane(data, ySize + chromaSize, chromaWidth, buffer.dataV, buffer.strideV, chromaWidth, chromaHeight)

            val frame = VideoFrame(buffer, /* rotation = */ 0, timestampNs)
            source.capturerObserver.onFrameCaptured(frame)
            // Releases our ref to the buffer; the source retained its own as needed.
            frame.release()
            framesFed.incrementAndGet()
            return true
        }
    }

    fun framesFed(): Long = framesFed.get()

    /** createOffer → setLocalDescription → await ICE gathering → return the
     *  fully-populated local SDP to POST. */
    suspend fun createOfferAndGather(): String {
        val pc = peerConnection ?: throw WhipException(null, "PeerConnection not started")
        val offer =
            suspendCancellableCoroutine<SessionDescription> { cont ->
                pc.createOffer(
                    object : SimpleSdpObserver() {
                        override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                        override fun onCreateFailure(error: String?) =
                            cont.resumeWithException(WhipException(null, "createOffer failed: $error"))
                    },
                    MediaConstraints(),
                )
            }
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() = cont.resume(Unit)
                    override fun onSetFailure(error: String?) =
                        cont.resumeWithException(WhipException(null, "setLocalDescription failed: $error"))
                },
                offer,
            )
        }
        // Wait for all candidates; if the network is slow, POST whatever we have.
        if (withTimeoutOrNull(GATHER_TIMEOUT_MS) { gatheringComplete.await() } == null) {
            Log.w(TAG, "ICE gathering timed out after ${GATHER_TIMEOUT_MS}ms; posting partial offer")
        }
        return (peerConnection?.localDescription ?: offer).description
    }

    suspend fun setRemoteAnswer(sdpAnswer: String) {
        val pc = peerConnection ?: throw WhipException(null, "PeerConnection not started")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer)
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() = cont.resume(Unit)
                    override fun onSetFailure(error: String?) =
                        cont.resumeWithException(WhipException(null, "setRemoteDescription failed: $error"))
                },
                answer,
            )
        }
    }

    fun dispose() {
        synchronized(lifecycleLock) {
            if (disposed) return
            disposed = true
            capturing = false
            if (!gatheringComplete.isCompleted) gatheringComplete.complete(Unit)
            runCatching { videoSource?.capturerObserver?.onCapturerStopped() }
            runCatching { peerConnection?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { factory.dispose() }
            runCatching { eglBase.release() }
            peerConnection = null
            videoTrack = null
            videoSource = null
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE && !gatheringComplete.isCompleted) {
                gatheringComplete.complete(Unit)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (connectedOnce.compareAndSet(false, true)) onConnected?.invoke()
                }
                PeerConnection.PeerConnectionState.FAILED -> onFailed?.invoke("connection failed")
                else -> Unit
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (connectedOnce.compareAndSet(false, true)) onConnected?.invoke()
                }
                PeerConnection.IceConnectionState.FAILED -> onFailed?.invoke("ICE failed")
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private companion object {
        const val TAG = "WhipPeerConnection"
        const val VIDEO_TRACK_ID = "egoflow_video"
        const val GATHER_TIMEOUT_MS = 3_000L

        // Upper bound handed to the encoder so it has bitrate headroom to keep the
        // source resolution rather than down-scaling. Sized for 720x1280@30 H.264;
        // BWE still ramps below this when the network can't sustain it.
        const val MAX_BITRATE_BPS = 4_000_000

        // STUN servers for srflx candidate gathering (NAT traversal). Matches the
        // server's webrtcICEServers2 entry; the public Google STUN needs no auth.
        val STUN_URLS = listOf("stun:stun.l.google.com:19302")

        @Volatile private var factoryInitialized = false

        // PeerConnectionFactory.initialize must run exactly once per process.
        @Synchronized
        fun ensureFactoryInitialized(context: Context) {
            if (factoryInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            factoryInitialized = true
        }
    }
}

/** Copies [rows] rows of [rowWidth] bytes from a tightly/strided source plane in
 *  [src] into a (possibly differently strided) destination [ByteBuffer]. */
private fun copyPlane(
    src: ByteArray,
    srcOffset: Int,
    srcStride: Int,
    dst: ByteBuffer,
    dstStride: Int,
    rowWidth: Int,
    rows: Int,
) {
    dst.clear()
    var srcPos = srcOffset
    for (row in 0 until rows) {
        dst.position(row * dstStride)
        dst.put(src, srcPos, rowWidth)
        srcPos += srcStride
    }
}

/** SdpObserver with empty defaults so each call site overrides only what it needs. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
