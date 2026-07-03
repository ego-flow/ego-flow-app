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
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import io.egoflow.app.R
import io.egoflow.app.core.transport.api.StopReason
import io.egoflow.app.core.transport.api.Transport
import io.egoflow.app.core.transport.api.TransportDeps
import io.egoflow.app.core.transport.api.TransportFactory
import io.egoflow.app.core.transport.api.TransportFailureReason
import io.egoflow.app.core.transport.api.TransportId
import io.egoflow.app.core.transport.api.TransportStartException
import io.egoflow.app.core.transport.api.TransportState
import io.egoflow.app.core.transport.api.VideoCodec
import io.egoflow.app.phone.PhoneCameraManager
import io.egoflow.app.settings.SettingsManager
import io.egoflow.app.stream.rtmp.RtmpVideoCodec
import io.egoflow.app.transport.http.HttpTransportFactory
import io.egoflow.app.transport.rtmp.RtmpTransportFactory
import io.egoflow.app.transport.whip.WhipTransportFactory
import io.egoflow.app.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
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
 * 2. handleVideoFrame() → transport.sendGlassesFrame(Compressed); phone path → sendPhoneFrame
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
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null
  private var stream: Stream? = null
  private var phoneCameraManager: PhoneCameraManager? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var transportSessionJob: Job? = null
  private var fpsSamplerJob: Job? = null

  private var currentSessionId: String? = null
  private var stopRequested = false
  // Tracks the prior DeviceSessionState so the session-state collector can tell a
  // device-initiated resume (PAUSED -> STARTED) from a fresh start.
  private var previousDeviceSessionState: DeviceSessionState? = null

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
  }

  // flatMapLatest re-subscribes to the new transport's state automatically
  // whenever transportHolder swaps instances on a protocol change.
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun collectTransportState() {
    viewModelScope.launch {
      transportHolder.flatMapLatest { it.state }.collect { reflectTransportState(it) }
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

  // [Start glasses-mode streaming]
  // Idempotent: never starts the same session twice (avoids the case where switching
  // tabs brings StreamScreen back into composition and re-fires the LaunchedEffect).
  fun startStream() {
    if (session != null) {
      Log.d(TAG, "startStream ignored -- session already active")
      return
    }
    stopRequested = false
    previousDeviceSessionState = null
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()
    sessionErrorJob?.cancel()

    StreamingService.start(getApplication())

    _uiState.update { it.copy(streamingMode = StreamingMode.GLASSES) }

    // Transport bootstrap is independent of the glasses session -- start it now so the
    // RTMP/backend round-trip runs in parallel with the device session spinning up.
    startTransportSession()
    startFpsSampler()

    // 0.7.0 session model: create a DeviceSession, observe its state, and attach the
    // camera Stream once the session reaches STARTED. (0.6.0's one-shot
    // Wearables.startStreamSession was removed.)
    Wearables.createSession(deviceSelector)
        .onSuccess { createdSession ->
          session = createdSession
          sessionErrorJob =
              viewModelScope.launch {
                createdSession.errors.collect { error -> handleSessionError(error) }
              }
          observeSessionState(createdSession)
          createdSession.start()
        }
        .onFailure { error, _ ->
          Log.e(TAG, "Failed to create session: ${error.description}")
          wearablesViewModel.setRecentError("Streaming failed: ${error.description}")
          stopStream(StopReason.FATAL_ERROR)
          wearablesViewModel.onStreamFailed()
        }
  }

  // Mirror the DeviceSession lifecycle (IDLE/STARTING/STARTED/PAUSED/STOPPING/STOPPED)
  // into UI state for the health card's "Device" row, and attach the camera Stream the
  // first time the session reaches STARTED. A later STARTED (e.g. PAUSED -> STARTED on a
  // tap-to-resume gesture) finds stream != null and is left alone so the SDK resumes the
  // existing stream rather than recreating it.
  private fun observeSessionState(deviceSession: DeviceSession) {
    sessionStateJob =
        viewModelScope.launch {
          deviceSession.state.collect { current ->
            val prev = previousDeviceSessionState
            previousDeviceSessionState = current
            Log.i(TAG, "DeviceSessionState transition: $prev -> $current")
            _uiState.update { it.copy(deviceSessionState = current) }
            when (current) {
              DeviceSessionState.STARTED -> {
                // Reaching STARTED clears any "DAT app update required" surfaced from an
                // earlier failed attempt.
                wearablesViewModel.setDatAppUpdateRequired(false)
                if (prev == DeviceSessionState.PAUSED && stream != null) {
                  // Device-initiated resume (tap gesture). The SDK resumes the existing
                  // stream internally -- do NOT recreate it.
                  Log.d(TAG, "Session resumed from PAUSED -- stream stays alive")
                } else if (stream == null) {
                  addGlassesStream(deviceSession)
                }
              }
              DeviceSessionState.PAUSED -> {
                // Tap gesture paused the session -- keep the stream alive for resume.
                Log.d(TAG, "Session paused (tap gesture) -- keeping stream alive for resume")
              }
              else -> Unit
            }
          }
        }
  }

  private fun addGlassesStream(deviceSession: DeviceSession) {
    viewModelScope.launch {
      deviceSession
          .addStream(
              StreamConfiguration(
                  videoQuality = SettingsManager.videoQuality,
                  frameRate = 30,
                  // Only RTMP ships pre-encoded HEVC; WHIP and HTTP re-encode from raw
                  // YUV, so they need uncompressed glasses frames.
                  compressVideo =
                      SettingsManager.rtmpCompressVideo &&
                          SettingsManager.transportMode == TransportId.RTMP,
              ),
          )
          .onSuccess { addedStream ->
            stream = addedStream
            // Off Main: frame ingestion does the YUV byte-copy (inside
            // RtmpStreamer.sendGlassesFrame). Main thread should only do UI
            // work; allocation/encoding belongs on a worker thread (and
            // ultimately on the RTMP executor).
            videoJob =
                viewModelScope.launch(Dispatchers.Default) {
                  addedStream.videoStream.collect { handleVideoFrame(it) }
                }
            stateJob =
                viewModelScope.launch {
                  var prevStreamState: StreamState? = null
                  addedStream.state.collect { current ->
                    Log.i(TAG, "StreamState transition: $prevStreamState -> $current")
                    prevStreamState = current
                    _uiState.update {
                      val startedAt =
                          if (current == StreamState.STREAMING && it.streamingStartedAtMs == null) {
                            SystemClock.elapsedRealtime()
                          } else {
                            it.streamingStartedAtMs
                          }
                      it.copy(streamState = current, streamingStartedAtMs = startedAt)
                    }
                    // With the transport disabled there is no Streaming signal
                    // from it, so the glasses stream going live is what clears
                    // the "starting" overlay.
                    if (current == StreamState.STREAMING && !SettingsManager.rtmpEnabled) {
                      wearablesViewModel.onStreamStarted()
                    }
                    if (current == StreamState.STOPPED || current == StreamState.CLOSED) {
                      // The glasses ended the video stream on their side (folded, taken off,
                      // thermal/critical stream error, etc.). Unlike the other glasses-stop
                      // paths this carries no DeviceSessionError, so surface a reason here ourselves
                      // -- otherwise the stream just vanishes with no explanation for the user.
                      val aliveMs =
                          _uiState.value.streamingStartedAtMs?.let {
                            SystemClock.elapsedRealtime() - it
                          }
                      Log.w(
                          TAG,
                          "Glasses ended video stream (state=$current) after " +
                              "${aliveMs ?: "?"}ms of STREAMING, frames received=${inputFrameCount.get()}; " +
                              "no DeviceSessionError accompanied this -- likely thermal/critical " +
                              "stream error, folded, or taken off",
                      )
                      wearablesViewModel.setRecentError(
                          getApplication<Application>().getString(R.string.error_glasses_stream_ended),
                      )
                      stopStream(StopReason.GLASSES_STOP)
                      wearablesViewModel.onStreamFailed()
                    }
                  }
                }
            errorJob =
                viewModelScope.launch {
                  addedStream.errorStream.collect { error ->
                    if (error == StreamError.STREAM_ERROR) {
                      Log.d(TAG, "Non-critical stream error, stream continues: ${error.description}")
                      return@collect
                    }
                    Log.e(TAG, "Fatal glasses stream error: $error (${error.description})")
                    wearablesViewModel.setRecentError(streamErrorMessage(error))
                    stopStream(StopReason.FATAL_ERROR)
                    wearablesViewModel.onStreamFailed()
                  }
                }
            addedStream.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to add stream to session: ${error.description}")
            wearablesViewModel.setRecentError("Streaming failed: ${error.description}")
            stopStream(StopReason.FATAL_ERROR)
            wearablesViewModel.onStreamFailed()
          }
    }
  }

  private fun handleSessionError(error: DeviceSessionError) {
    val aliveMs =
        _uiState.value.streamingStartedAtMs?.let { SystemClock.elapsedRealtime() - it }
    Log.e(
        TAG,
        "Session error: $error (${error.description}) after ${aliveMs ?: "?"}ms of STREAMING, " +
            "frames received=${inputFrameCount.get()}",
    )
    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    // The glasses ended the session from their side (folded, taken off, battery, etc.).
    // This is a normal glasses-initiated stop -- never infer an app-update requirement from
    // it. Update-required is reported explicitly by the SDK via the dedicated error below.
    if (error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      // If we're already prompting for an update, a follow-up device-ended error is just
      // noise -- don't overwrite the message.
      if (!alreadyShowingUpdateRequired) {
        wearablesViewModel.setRecentError(sessionErrorMessage(error))
      }
      stopStream(StopReason.GLASSES_STOP)
      wearablesViewModel.onStreamFailed()
      return
    }

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    wearablesViewModel.setRecentError(sessionErrorMessage(error))
    stopStream(StopReason.FATAL_ERROR)
    wearablesViewModel.onStreamFailed()
  }

  // Map known thermal/battery/power/connectivity faults to clear user-facing copy; fall back
  // to the SDK's own description for anything else.
  private fun sessionErrorMessage(error: DeviceSessionError): String {
    val resId =
        when (error) {
          DeviceSessionError.THERMAL_CRITICAL,
          DeviceSessionError.THERMAL_EMERGENCY -> R.string.error_thermal
          DeviceSessionError.BATTERY_CRITICAL -> R.string.error_battery_critical
          DeviceSessionError.PEAK_POWER_SHUTDOWN -> R.string.error_peak_power
          DeviceSessionError.DEVICE_DISCONNECTED -> R.string.error_device_disconnected
          DeviceSessionError.DEVICE_POWERED_OFF -> R.string.error_device_powered_off
          else -> null
        }
    return resId?.let { getApplication<Application>().getString(it) } ?: error.description
  }

  private fun streamErrorMessage(error: StreamError): String {
    val resId =
        when (error) {
          StreamError.THERMAL_HOT,
          StreamError.THERMAL_EMERGENCY -> R.string.error_thermal
          StreamError.BATTERY_LOW -> R.string.error_battery_low
          StreamError.PEAK_POWER_LIMIT -> R.string.error_peak_power
          StreamError.TIMEOUT -> R.string.error_stream_timeout
          else -> null
        }
    return resId?.let { getApplication<Application>().getString(it) } ?: error.description
  }

  // [Start phone-camera mode]
  // Idempotent (same reason as startStream).
  fun startPhoneCamera(lifecycleOwner: LifecycleOwner) {
    if (phoneCameraManager != null) {
      Log.d(TAG, "startPhoneCamera ignored -- camera already active")
      return
    }
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
          streamState = StreamState.STREAMING,
          streamingStartedAtMs = SystemClock.elapsedRealtime(),
      )
    }
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
        // and there's no on-device HEVC pass-through, so the codec/compress
        // settings don't apply.
        SettingsManager.transportMode == TransportId.WHIP -> VideoCodec.H264
        // HTTP runs the on-device encoder, so the codec choice applies, but
        // compression (HEVC pass-through) is forced off -- the encoder produces the
        // chosen codec into the MP4 (ffmpeg finalize handles both H.264 and HEVC).
        SettingsManager.transportMode == TransportId.HTTP ->
            SettingsManager.rtmpVideoCodec.toCoreCodec()
        SettingsManager.rtmpCompressVideo -> VideoCodec.HEVC
        else -> SettingsManager.rtmpVideoCodec.toCoreCodec()
      }

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
    stopRequested = true
    transportSessionJob?.cancel()
    fpsSamplerJob?.cancel()
    fpsSamplerJob = null

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    stream?.stop()
    stream = null
    session?.stop()
    session = null
    previousDeviceSessionState = null
    phoneCameraManager?.stop()
    phoneCameraManager = null
    // deviceSessionState is now sourced from the active DeviceSession.state, so it's
    // scoped to this stream's lifecycle -- reset it with the rest of the UI state.
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
      StreamingService.stop(getApplication())
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

    if (uiState.value.streamState == StreamState.STREAMING) {
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        // Build the Bitmap once, on tap, from the camera's latest I420 frame
        // (the hot path no longer produces a Bitmap per frame).
        phoneCameraManager?.captureLatestFrameBitmap()?.let { frame ->
          _uiState.update { it.copy(capturedPhoto = frame, isShareDialogVisible = true) }
        }
        return
      }

      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
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
  private fun handleVideoFrame(videoFrame: VideoFrame) {
    inputFrameCount.incrementAndGet()
    // Codec-config frames (0.7.0) carry only HEVC parameter sets, not raw YUV, so they
    // must take the compressed path even if isCompressed weren't set.
    if (videoFrame.isCompressed || videoFrame.isCodecConfig) {
      transport.sendGlassesFrameCompressed(videoFrame)
    } else {
      transport.sendGlassesFrame(videoFrame)
    }
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

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {}
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed =
          Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    stopStream()
    transportSessionJob?.cancel()
    stateJob?.cancel()
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
