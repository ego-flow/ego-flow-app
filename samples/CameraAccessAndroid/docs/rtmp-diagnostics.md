# RTMP Failure Analysis

This document diagnoses the main failure modes when the Android app publishes video/audio to an RTMP server and the server stores the stream.

The observations below are based on the current repository state, especially:
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt)
- [RtmpPublisher.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpPublisher.kt)
- [RtmpAudioRecorder.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpAudioRecorder.kt)
- [mediamtx.yml](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/server/mediamtx.yml)
- [save-stream.sh](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/server/save-stream.sh)

## Critical

### 1. Server recording is not enabled by default
Symptoms:
- RTMP publish succeeds and HLS playback works, but no file is created on the server.
- Operators assume MediaMTX is recording automatically and chase the wrong component.

Evidence:
- [mediamtx.yml](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/server/mediamtx.yml#L10) sets `record: no`.
- Recording is delegated to [save-stream.sh](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/server/save-stream.sh#L12), which runs `ffmpeg -i <rtmp-url> -c copy`.

Impact:
- Storage failures can be misdiagnosed as ingest failures.
- Recording reliability depends on a second process that must be launched, monitored, and restarted separately.

What to check:
- MediaMTX logs for publisher connection.
- Whether `ffmpeg` is running at all.
- Whether the output path passed to `save-stream.sh` exists and is writable.

### 2. No reconnect or backoff strategy
Symptoms:
- Streaming stops permanently after a short network interruption, server restart, or TCP timeout.
- Users must restart streaming manually from the app.

Evidence:
- [RtmpPublisher.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpPublisher.kt#L72) opens a plain socket with `soTimeout = 5_000`.
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L75) and [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L96) treat send/connect failures as terminal and call `stopInternal()`.

Impact:
- A transient outage becomes a full session loss.
- Long-running unattended recording is not reliable.

What to check:
- RTMP diagnostics overlay entries from [RtmpDiagnostics.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpDiagnostics.kt#L10).
- Whether the app logs `Connect failed`, `Audio sample failed`, or `Phone/Glasses frame failed`.

### 3. Plain RTMP only
Symptoms:
- Publish works on LAN but fails or is blocked on some public or enterprise networks.
- Security review rejects the design for production traffic.

Evidence:
- [RtmpPublisher.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpPublisher.kt#L142) accepts only `rtmp://`.
- The socket layer is raw TCP with no TLS negotiation.

Impact:
- Audio/video can be observed or tampered with on hostile networks.
- Public deployment options are restricted.

What to check:
- Whether the publish URL is LAN-only or exposed over the internet.
- Whether a proxy, VPN, or firewall is silently dropping TCP/1935.

## High

### 4. Audio path is present in code but not proven end-to-end
Symptoms:
- Video plays but recordings are silent.
- Some devices fail to start streaming when microphone permission is denied or the mic is in use by another app.

Evidence:
- [RtmpAudioRecorder.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpAudioRecorder.kt#L25) starts `AudioRecord`.
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L159) creates an AAC encoder and [RtmpPublisher.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpPublisher.kt#L109) sends AAC config/data.
- Older README text claimed audio was not sent, which means operational expectations may still be wrong.

Impact:
- Audio support can be assumed to exist even when the final stored file has no usable audio track.
- Permission or device-specific microphone issues can take down the whole RTMP session because audio failures currently stop the streamer.

What to check:
- Runtime `RECORD_AUDIO` grant state.
- `ffprobe` output of the saved file to confirm an AAC track exists.
- Whether recording still works when mic permission is denied.

### 5. Audio and video use separate clock origins
Symptoms:
- Lip-sync drift on longer sessions.
- Muxed files contain both tracks, but alignment gets worse over time.

Evidence:
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L280) computes video PTS from `streamStartTimeNs`.
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L297) computes audio PTS from `audioStartTimeNs`.

Impact:
- Initial A/V skew depends on which producer starts first.
- Drift can surface only in storage or replay, not necessarily in live monitoring.

What to check:
- Compare first audio/video DTS/PTS with `ffprobe -show_packets`.
- Run at least one 20+ minute recording and inspect sync at beginning, middle, and end.

### 6. Recorder process is fragile during source interruptions
Symptoms:
- The saved MP4 is truncated or missing the tail after the publisher disconnects.
- A server restart or RTMP reconnect attempt leaves multiple partial files.

Evidence:
- [save-stream.sh](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/server/save-stream.sh#L12) performs a single `ffmpeg` ingest with no restart loop, segmentation, or health supervision.

Impact:
- A single ingest hiccup can end the saved artifact.
- Operators may believe the stream is still being archived because live playback recovered separately.

What to check:
- Exit code and stderr of `ffmpeg`.
- Whether the file remains playable and seekable after an abnormal stop.

## Medium

### 7. Encoder and colorspace compatibility can vary by device
Symptoms:
- Green/purple tint, corrupted frames, or encoder initialization failures on some Android devices.
- Works on one phone and fails on another.

Evidence:
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L355) selects a hardware H.264 encoder by supported YUV formats.
- Frame conversion depends on `I420` or `NV12` assumptions before input to `MediaCodec`.

Impact:
- Device-specific failures are likely even if the general design is sound.

What to check:
- Test at least two Android models.
- Log the chosen codec name and resulting frame appearance.

### 8. Backpressure is minimal, so overload becomes silent dropping
Symptoms:
- Quality degrades under CPU pressure, but the app stays nominally connected.
- Recordings contain stutter or long visual gaps.

Evidence:
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L207) drops frames if the encoder input buffer is too small.
- [RtmpStreamer.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpStreamer.kt#L224) does the same for audio chunks.
- Diagnostics only retain the last 12 entries in [RtmpDiagnostics.kt](/home/js1044k/ego-flow-app/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/rtmp/RtmpDiagnostics.kt#L11).

Impact:
- Intermittent drops may be invisible unless someone is actively watching logs.

What to check:
- Device CPU load and thermal throttling.
- Whether drop messages appear during phone-camera mode or long sessions.

## Recommended Validation Sequence

1. Verify ingest independently from storage.
   Confirm MediaMTX sees the publisher before debugging file output.
2. Verify storage independently from live playback.
   Run `save-stream.sh`, then inspect the resulting file with `ffprobe`.
3. Test microphone failure modes.
   Try with `RECORD_AUDIO` denied and with another app holding the mic.
4. Run one long session.
   Check A/V sync and file seekability after 20+ minutes.
5. Induce network faults.
   Toggle Wi-Fi or restart MediaMTX and confirm the app does not recover automatically.

## Acceptance Criteria For A "Reliable" Setup

- The intended recording process is explicit: either MediaMTX built-in recording or a supervised external recorder.
- A short network interruption does not require manual recovery, or the operational runbook states that manual restart is required.
- Saved files are playable, seekable, and contain the expected tracks.
- Audio support is either verified end-to-end or explicitly disabled/documented.
- At least one long-duration test has confirmed acceptable A/V sync drift.
