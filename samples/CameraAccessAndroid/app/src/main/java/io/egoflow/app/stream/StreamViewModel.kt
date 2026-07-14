/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.extentos.glasses.core.VideoFrameConfig
import io.egoflow.app.R
import io.egoflow.app.core.encoder.YuvFrameConverter
import io.egoflow.app.core.transport.api.GlassesVideoFrame
import io.egoflow.app.core.transport.api.StopReason
import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportDeps
import io.egoflow.app.core.transport.api.TransportFactory
import io.egoflow.app.core.transport.api.TransportFailureReason
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.core.transport.api.TransportStartException
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.core.transport.api.VideoCodec
import io.egoflow.app.extentos.AdaptedExtentosFrame
import io.egoflow.app.extentos.ExtentosBootstrap
import io.egoflow.app.extentos.ExtentosFrameAdapter
import io.egoflow.app.extentos.toExtentosResolution
import io.egoflow.app.phone.PhoneCameraManager
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.stream.rtmp.RtmpVideoCodec
import io.egoflow.app.transport.http.HttpTransportFactory
import io.egoflow.app.transport.rtmp.RtmpTransportFactory
import io.egoflow.app.transport.whip.WhipTransportFactory
import io.egoflow.app.wearables.WearablesViewModel
import io.egoflow.app.wearables.shouldStopGlassesCapture
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [Streaming ViewModel]
 * Receives video from the glasses/phone camera and pushes it to a server through the `Transport` interface.
 *
 * This ViewModel is transport-agnostic: it works identically with RtmpTransport or SlabTransport
 * (Phase 2). The transport choice is made by SettingsManager.transportMode, and
 * RtmpTransportFactory / (future) SlabTransportFactory builds the instance.
 *
 * Main flow:
 * 1. startStream() / startPhoneCamera() → start local capture + transport.startSession()
 * 2. Extentos JPEG → I420 adapter → transport.sendGlassesFrame; phone path → sendPhoneFrame
 * 3. Observe transport.state → update UI state (activeCodec, fallback flag, fail handling)
 * 4. stopStream() → local cleanup + transport.stopSession()
 * 5. Failed → stop the stream and return to device selection (no automatic reconnect)
 */
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private const val GLASSES_STREAM_START_TIMEOUT_MS = 20_000L
    private const val GLASSES_FRAME_RATE = 30
    private const val FRAME_DIAGNOSTIC_INTERVAL = 30L
    private val INITIAL_STATE = StreamUiState()
  }

  private val glasses = (application as ExtentosBootstrap).glasses
  private val extentosFrameAdapter = ExtentosFrameAdapter()
  private var phoneCameraManager: PhoneCameraManager? = null
  private var latestGlassesFrame: GlassesVideoFrame? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var transportSessionJob: Job? = null
  private var fpsSamplerJob: Job? = null
  private var streamStartTimeoutJob: Job? = null

  private var currentSessionId: String? = null
  private var stopRequested = false
  private var streamLifecycleGeneration = 0L

  // Inbound video frames received from the active source (glasses
  // session or phone camera). AtomicLong because the producer is
  // Dispatchers.Default (glasses) or CameraX's executor (phone),
  // while the 1Hz sampler reads from viewModelScope/Main.
  private val inputFrameCount = AtomicLong(0L)
  // Snapshots captured by the FPS sampler so each tick derives a delta.
  private var lastInputFrameCount = 0L
  private var lastOutputFrameCount = 0L
  private var lastSampleAtMs = 0L

  // Transport selection. The factory pattern picks RTMP or WHIP from
  // SettingsManager.transportMode. Because this ViewModel outlives a single
  // session, the active transport is held in a flow and rebuilt when the user
  // changes the protocol between sessions (see maybeRebuildTransport).
  private val deviceTypeProvider: () -> String = {
    when (_uiState.value.streamingMode) {
      StreamingMode.GLASSES -> "meta_glasses_android"
      StreamingMode.PHONE -> "phone_android"
    }
  }
  private var builtTransportMode: TransportId = SettingsManager.transportMode
  private val transportHolder: MutableStateFlow<Transport> =
      MutableStateFlow(buildTransport(builtTransportMode))
  private val transport: Transport
    get() = transportHolder.value

  init {
    collectTransportState()
    collectGlassesConnectionState()
  }

  // flatMapLatest re-subscribes to the new transport's state automatically
  // whenever transportHolder swaps instances on a protocol change.
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun collectTransportState() {
    viewModelScope.launch {
      transportHolder.flatMapLatest { it.state }.collect { reflectTransportState(it) }
    }
  }

  private fun collectGlassesConnectionState() {
    viewModelScope.launch {
      glasses.connection.state.collect { state ->
        val glassesCaptureIsActive =
            _uiState.value.streamingMode == StreamingMode.GLASSES && videoJob?.isActive == true
        if (state.shouldStopGlassesCapture() && glassesCaptureIsActive && !stopRequested) {
          Log.w(TAG, "Extentos disconnected during glasses capture: $state")
          stopStream(StopReason.GLASSES_STOP)
          wearablesViewModel.onStreamFailed()
        }
      }
    }
  }

  private fun buildTransport(mode: TransportId): Transport {
    val factory: TransportFactory = when (mode) {
      TransportId.WHIP -> WhipTransportFactory(deviceTypeProvider = deviceTypeProvider)
      // HTTP uploads fMP4 chunks live during capture (overlapped); the foreground
      // StreamingService (already up for the session) keeps the process alive through
      // the final drain at stop. No post-capture file handoff.
      TransportId.HTTP -> HttpTransportFactory(deviceTypeProvider = deviceTypeProvider)
      // SLAB isn't wired in this app; fall back to RTMP so the registry never
      // returns null.
      TransportId.RTMP, TransportId.SLAB ->
          RtmpTransportFactory(
              deviceTypeProvider = deviceTypeProvider,
              audioEnabledProvider = { SettingsManager.rtmpAudioEnabled },
              audioSourceProvider = { SettingsManager.audioSource },
          )
    }
    return factory.create(TransportDeps(context = getApplication()))
  }

  // Called when idle (from startTransportSession) so a Settings protocol change
  // applies to the next session. Swapping the holder rewires the state collector.
  private fun maybeRebuildTransport() {
    val desired = SettingsManager.transportMode
    if (desired == builtTransportMode) return
    builtTransportMode = desired
    transportHolder.value = buildTransport(desired)
  }

  private fun reflectTransportState(state: TransportState) {
    when (state) {
      is TransportState.Streaming -> {
        val actual = state.actualCodec.toRtmpCodec()
        _uiState.update {
          it.copy(
              transportState = state,
              activeVideoCodec = actual,
              videoCodecDidFallback =
                  it.requestedVideoCodec != null && it.requestedVideoCodec != actual,
              usesBackendSession = true,
          )
        }
        // The active transport session is live -- dismiss the "starting" overlay.
        wearablesViewModel.onStreamStarted()
      }
      is TransportState.Failed -> {
        _uiState.update { it.copy(transportState = state) }
        onTransportFailed(state)
      }
      else -> {
        // Idle / Connecting / Stopping -- mirror raw state for the overlay,
        // no other UI mutation needed.
        _uiState.update { it.copy(transportState = state) }
      }
    }
  }

  // Any transport failure is terminal -- surface the error, tear down the
  // stream, and return to device selection. (Automatic reconnect was removed.)
  private fun onTransportFailed(failure: TransportState.Failed) {
    if (stopRequested) return
    // Technical detail goes to the log; the user sees a plain-language line.
    Log.w(TAG, "Transport failed (${failure.reason}): ${failure.message}")
    wearablesViewModel.setRecentError(friendlyFailureMessage(failure.reason))
    stopStream(StopReason.FATAL_ERROR)
    wearablesViewModel.onStreamFailed()
  }

  private fun nextStreamLifecycleGeneration(): Long {
    streamLifecycleGeneration += 1
    return streamLifecycleGeneration
  }

  private fun invalidateStreamLifecycle() {
    streamLifecycleGeneration += 1
  }

  private fun isActiveLifecycle(generation: Long): Boolean =
      generation == streamLifecycleGeneration && !stopRequested

  // [Start glasses-mode streaming]
  // Idempotent: never starts the same session twice (avoids the case where switching
  // tabs brings StreamScreen back into composition and re-fires the LaunchedEffect).
  fun startStream() {
    if (videoJob?.isActive == true) {
      Log.d(TAG, "startStream ignored -- Extentos frame collection already active")
      return
    }
    val generation = nextStreamLifecycleGeneration()
    stopRequested = false
    extentosFrameAdapter.reset()
    latestGlassesFrame = null
    videoJob?.cancel()
    streamStartTimeoutJob?.cancel()
    streamStartTimeoutJob = null

    StreamingService.start(
        context = getApplication(),
        source = StreamingSource.GLASSES,
        transportMode = streamingServiceTransportMode(SettingsManager.transportMode),
    )

    _uiState.update {
      it.copy(
          streamingMode = StreamingMode.GLASSES,
          streamState = CaptureState.STARTING,
          streamingStartedAtMs = null,
      )
    }

    // Transport bootstrap is independent of the glasses session -- start it now so the
    // RTMP/backend round-trip runs in parallel with the device session spinning up.
    startTransportSession()
    startFpsSampler()
    scheduleExtentosStreamStartTimeout(generation)

    val frameConfig =
        VideoFrameConfig(
            frameRate = GLASSES_FRAME_RATE,
            resolution = SettingsManager.videoQuality.toExtentosResolution(),
        )
    videoJob =
        viewModelScope.launch(Dispatchers.Default) {
          try {
            glasses.camera.videoFrames(frameConfig).collect { sourceFrame ->
              if (!isActiveLifecycle(generation)) return@collect

              val adapted = extentosFrameAdapter.adapt(sourceFrame)
              val frameNumber = handleExtentosVideoFrame(adapted)
              if (frameNumber == 1L) {
                cancelGlassesStreamStartTimeout()
                _uiState.update {
                  it.copy(
                      streamState = CaptureState.STREAMING,
                      streamingStartedAtMs = SystemClock.elapsedRealtime(),
                  )
                }
                if (!SettingsManager.rtmpEnabled) {
                  wearablesViewModel.onStreamStarted()
                }
              }
              if (frameNumber == 1L || frameNumber % FRAME_DIAGNOSTIC_INTERVAL == 0L) {
                logExtentosFrameDiagnostics(adapted, frameNumber)
              }
            }
            if (isActiveLifecycle(generation)) {
              error("Extentos videoFrames completed while streaming was active")
            }
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            if (!isActiveLifecycle(generation)) return@launch
            Log.e(TAG, "Extentos video frame collection failed", error)
            wearablesViewModel.setRecentError(
                "Glasses video could not be processed. Reconnect the glasses and try again.",
            )
            stopStream(StopReason.FATAL_ERROR)
            wearablesViewModel.onStreamFailed()
          }
        }
  }

  private fun scheduleExtentosStreamStartTimeout(generation: Long) {
    streamStartTimeoutJob?.cancel()
    streamStartTimeoutJob =
        viewModelScope.launch {
          delay(GLASSES_STREAM_START_TIMEOUT_MS)
          if (
              isActiveLifecycle(generation) &&
                  _uiState.value.streamingMode == StreamingMode.GLASSES &&
                  _uiState.value.streamState == CaptureState.STARTING
          ) {
            Log.w(
                TAG,
                "Extentos videoFrames produced no valid frame within " +
                    "${GLASSES_STREAM_START_TIMEOUT_MS}ms",
            )
            wearablesViewModel.setRecentError(
                getApplication<Application>().getString(R.string.error_glasses_stream_start_timeout),
            )
            stopStream(StopReason.FATAL_ERROR)
            wearablesViewModel.onStreamFailed()
          }
        }
  }

  private fun cancelGlassesStreamStartTimeout() {
    streamStartTimeoutJob?.cancel()
    streamStartTimeoutJob = null
  }

  // [Start phone-camera mode]
  // Idempotent (same reason as startStream).
  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    if (phoneCameraManager != null) {
      Log.d(TAG, "startPhoneCamera ignored -- camera already active")
      return
    }
    nextStreamLifecycleGeneration()
    stopRequested = false
    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager
    manager.onFrame = { i420, width, height ->
      // CameraX delivers on its own executor (not Main), so the transport send
      // stays off Main. The camera manager keeps the latest frame for capturePhoto;
      // we no longer stash a per-frame Bitmap in UI state.
      inputFrameCount.incrementAndGet()
      transport.sendPhoneFrame(i420, width, height)
    }
    _uiState.update {
      it.copy(
          streamingMode = StreamingMode.PHONE,
          streamState = CaptureState.STREAMING,
          streamingStartedAtMs = SystemClock.elapsedRealtime(),
      )
    }
    StreamingService.start(
        context = getApplication(),
        source = StreamingSource.PHONE,
        transportMode = streamingServiceTransportMode(SettingsManager.transportMode),
    )
    startTransportSession()
    startFpsSampler()
    manager.start(lifecycleOwner)
    // Phone capture is live immediately; with the transport disabled there is
    // no later Streaming signal, so clear the "starting" overlay here.
    if (!SettingsManager.rtmpEnabled) {
      wearablesViewModel.onStreamStarted()
    }
    Log.d(TAG, "Phone camera mode started")
  }

  /** Hands off the rest of session-startup to the Transport. Wrapped
   *  in its own job so cancellation (via stopStream) interrupts a
   *  pending register + ticket round-trip cleanly. */
  private fun startTransportSession() {
    if (!SettingsManager.rtmpEnabled) {
      Log.d(TAG, "Transport disabled in settings; skipping transport.startSession")
      return
    }
    // Idle entry point -- align the active transport with the selected protocol.
    maybeRebuildTransport()
    val sid = UUID.randomUUID().toString().replace("-", "")
    currentSessionId = sid
    val codec = effectiveCodec()
    _uiState.update { it.copy(requestedVideoCodec = codec.toRtmpCodec()) }
    transportSessionJob?.cancel()
    transportSessionJob =
        viewModelScope.launch {
          try {
            transport.startSession(sid, codec)
          } catch (_: CancellationException) {
            // expected on stopStream()
          } catch (error: Exception) {
            handleTransportBootstrapFailure(error)
          }
        }
  }

  private fun effectiveCodec(): VideoCodec =
      when {
        // WHIP media is whatever the SDP negotiates (libwebrtc publishes H.264),
        // and the app does not control the negotiated codec.
        SettingsManager.transportMode == TransportId.WHIP -> VideoCodec.H264
        // Extentos exposes JPEG frames, so RTMP and HTTP both use the existing
        // on-device encoder.
        else -> SettingsManager.rtmpVideoCodec.toCoreCodec()
      }

  private fun streamingServiceTransportMode(mode: TransportId): TransportId =
      if (SettingsManager.rtmpEnabled) mode else TransportId.RTMP

  // [Input/output FPS sampler]
  // Reads the inbound and transport-side cumulative counters once per
  // second, computes deltas, and pushes the derived FPS into UI state.
  // Sampling (not per-frame state updates) keeps Compose recomposition
  // cost flat regardless of frame rate.
  private fun startFpsSampler() {
    fpsSamplerJob?.cancel()
    inputFrameCount.set(0L)
    lastInputFrameCount = 0L
    lastOutputFrameCount = transport.videoFramesSent()
    lastSampleAtMs = SystemClock.elapsedRealtime()
    fpsSamplerJob =
        viewModelScope.launch {
          while (true) {
            delay(1_000L)
            val nowMs = SystemClock.elapsedRealtime()
            val elapsedMs = (nowMs - lastSampleAtMs).coerceAtLeast(1L)
            lastSampleAtMs = nowMs

            val inputNow = inputFrameCount.get()
            val outputNow = transport.videoFramesSent()
            val inDelta = (inputNow - lastInputFrameCount).coerceAtLeast(0L)
            val outDelta = (outputNow - lastOutputFrameCount).coerceAtLeast(0L)
            lastInputFrameCount = inputNow
            lastOutputFrameCount = outputNow

            val inputFps = inDelta * 1000f / elapsedMs
            val outputFps = outDelta * 1000f / elapsedMs
            _uiState.update {
              it.copy(
                  inputFps = inputFps,
                  outputFps = outputFps,
                  inputFpsHistory =
                      (it.inputFpsHistory + inputFps).takeLast(FPS_HISTORY_SECONDS),
                  outputFpsHistory =
                      (it.outputFpsHistory + outputFps).takeLast(FPS_HISTORY_SECONDS),
              )
            }
          }
        }
  }

  // [Stop streaming]
  // Called on Stop-button tap or when the glasses connection ends.
  fun stopStream(
      reason: StopReason = StopReason.USER_STOP,
      notifyOnSuccess: Boolean = false,
  ) {
    Log.i(TAG, "stopStream requested reason=$reason notifyOnSuccess=$notifyOnSuccess")
    val serviceSource =
        when (_uiState.value.streamingMode) {
          StreamingMode.GLASSES -> StreamingSource.GLASSES
          StreamingMode.PHONE -> StreamingSource.PHONE
        }
    val serviceTransportMode = streamingServiceTransportMode(builtTransportMode)
    stopRequested = true
    invalidateStreamLifecycle()
    transportSessionJob?.cancel()
    fpsSamplerJob?.cancel()
    fpsSamplerJob = null
    streamStartTimeoutJob?.cancel()
    streamStartTimeoutJob = null

    videoJob?.cancel()
    videoJob = null
    extentosFrameAdapter.reset()
    latestGlassesFrame = null
    phoneCameraManager?.stop()
    phoneCameraManager = null
    _uiState.update { INITIAL_STATE }
    currentSessionId = null

    viewModelScope.launch {
      val stopOk =
          try {
            // For HTTP this drains the final fMP4 chunks + /finish; the foreground
            // StreamingService is stopped only afterwards so the process stays alive
            // through the drain.
            transport.stopSession(reason)
            true
          } catch (e: Exception) {
            wearablesViewModel.setRecentError("Failed to stop transport: ${e.message}")
            false
          }
      StreamingService.stop(
          context = getApplication(),
          source = serviceSource,
          transportMode = serviceTransportMode,
      )
      if (notifyOnSuccess && stopOk) {
        wearablesViewModel.setRecentSuccess("Streaming stopped successfully.")
      }
      // Teardown is done (cleanly or not) -- dismiss the "stopping" overlay and
      // let WearablesViewModel return to device selection. No-op unless a
      // user-initiated stop is in progress.
      wearablesViewModel.onStreamStopped()
    }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamState == CaptureState.STREAMING) {
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        // Build the Bitmap once, on tap, from the camera's latest I420 frame
        // (the hot path no longer produces a Bitmap per frame).
        phoneCameraManager?.captureLatestFrameBitmap()?.let { frame ->
          _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
        }
        return
      }

      val frame = latestGlassesFrame
      if (frame == null) {
        Log.w(TAG, "Cannot capture photo: no Extentos frame is available")
        return
      }

      Log.d(TAG, "Capturing the latest Extentos video frame")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch(Dispatchers.Default) {
        try {
          val bitmap =
              YuvFrameConverter.i420ToBitmap(
                  frame.copyI420(),
                  frame.width,
                  frame.height,
              )
          _uiState.update {
            it.copy(
                capturedPhoto = bitmap,
                isShareDialogVisible = true,
                isCapturing = false,
            )
          }
        } catch (error: Exception) {
          Log.e(TAG, "Failed to capture the latest Extentos frame", error)
          _uiState.update { it.copy(isCapturing = false) }
        }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  // [Handle glasses video frame]
  // Compressed mode: pass the original HEVC bitstream straight through to the transport (no re-encoding)
  // Uncompressed mode: pass the YUV frame straight through to the transport
  //
  // Runs on Dispatchers.Default (see startStream). No per-frame state
  // updates here -- the StreamUiState diff would re-render the Compose
  // tree on every video frame. Health-card style metrics belong on the
  // transport/encoder side, not in the UI flow.
  private fun handleExtentosVideoFrame(adapted: AdaptedExtentosFrame): Long {
    latestGlassesFrame = adapted.frame
    val frameNumber = inputFrameCount.incrementAndGet()
    transport.sendGlassesFrame(adapted.frame)
    return frameNumber
  }

  private fun logExtentosFrameDiagnostics(adapted: AdaptedExtentosFrame, frameNumber: Long) {
    Log.i(
        TAG,
        "Extentos frame #$frameNumber signature=jpeg " +
            "size=${adapted.jpegSizeBytes}B dimensions=${adapted.frame.width}x${adapted.frame.height} " +
            "ptsUs=${adapted.frame.presentationTimeUs} decodeUs=${adapted.decodeDurationUs} " +
            "convertUs=${adapted.conversionDurationUs}",
    )
  }

  // ----- Transport failure handling -----
  //
  // A failed bootstrap (register + ticket round-trip) is terminal: there is
  // no automatic reconnect. Surface the error, tear down, and bounce back to
  // device selection so the user can retry explicitly.
  private fun handleTransportBootstrapFailure(error: Exception) {
    if (stopRequested) return
    // startSession surfaces failures as TransportStartException carrying a
    // coarse reason; fall back to INTERNAL for anything unexpected.
    val reason = (error as? TransportStartException)?.reason ?: TransportFailureReason.INTERNAL
    Log.w(TAG, "Transport bootstrap failed ($reason): ${error.message}")
    wearablesViewModel.setRecentError(friendlyFailureMessage(reason))
    stopStream(StopReason.FATAL_ERROR)
    wearablesViewModel.onStreamFailed()
  }

  /** Maps a coarse transport failure reason onto a user-facing sentence.
   *  Keep these plain-language and actionable -- raw exception text and
   *  server categories stay in the log, never the snackbar. */
  private fun friendlyFailureMessage(reason: TransportFailureReason): String = when (reason) {
    TransportFailureReason.ALREADY_ACTIVE ->
        "A stream is already running. Stop it before starting a new one."
    TransportFailureReason.AUTH ->
        "Streaming sign-in failed. Please sign in again and retry."
    TransportFailureReason.SERVER ->
        "The streaming server rejected the request. Please try again in a moment."
    TransportFailureReason.NETWORK ->
        "Streaming stopped: network connection lost. Check your connection and retry."
    TransportFailureReason.TIMEOUT ->
        "Streaming timed out. Check your network and try again."
    TransportFailureReason.TLS ->
        "Couldn't establish a secure connection to the streaming server."
    TransportFailureReason.INTERNAL ->
        "Streaming stopped due to an unexpected error. Please try again."
  }

  override fun onCleared() {
    stopStream()
    transportSessionJob?.cancel()
    super.onCleared()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}

// core.VideoCodec (transport-agnostic) <-> RtmpVideoCodec (RTMP-side
// enum with mimeType/displayName/preferenceValue). The UI state and
// Settings both speak RtmpVideoCodec today; we map at the boundary.
private fun VideoCodec.toRtmpCodec(): RtmpVideoCodec =
    when (this) {
      VideoCodec.H264 -> RtmpVideoCodec.H264
      VideoCodec.HEVC -> RtmpVideoCodec.H265
    }

private fun RtmpVideoCodec.toCoreCodec(): VideoCodec =
    when (this) {
      RtmpVideoCodec.H264 -> VideoCodec.H264
      RtmpVideoCodec.H265 -> VideoCodec.HEVC
    }
