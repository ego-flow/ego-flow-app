# EgoFlow

EgoFlow is a repository of Meta Wearables sample apps adapted for an ego-centric video pipeline: stream camera data from Meta AI glasses through the mobile app, forward it to a MediaMTX server over RTMP, and store or replay the stream in near real time.

Today, the end-to-end `glasses -> app -> server` pipeline is implemented on Android. iOS is intended to support the same pipeline, but RTMP forwarding and server-side ingest from iOS are not implemented yet.

Some `Gemini` and `OpenClaw` integration code is still present because parts of the project were brought over from [VisionClaw](https://github.com/Intent-Lab/VisionClaw). Those paths are not the main focus of EgoFlow and are expected to be removed or reduced over time.

This root README is the main onboarding document. Platform-specific details still live in the sample directories, but a new developer should be able to understand the system and get a first run from here.

## Repository Map

- `samples/CameraAccess`: iOS app based on Meta DAT; currently supports device connection and preview flows, with RTMP ingest support planned but not implemented yet
- `samples/CameraAccessAndroid`: Android app based on Meta DAT; currently implements the main EgoFlow streaming pipeline, including RTMP publishing to MediaMTX
- `samples/video-ingest-server`: MediaMTX + viewer stack used for RTMP ingest, HLS playback, and recording
- `samples/start-ngrok.sh`: helper script that exposes RTMP, viewer, and the legacy OpenClaw gateway over ngrok for remote testing

## End-to-End Pipelines

### 1. Main streaming pipeline

1. A Meta AI glasses device connects to the app through Meta Wearables DAT.
1. The app registers with the glasses via the Meta AI app in Developer Mode.
1. DAT provides the live camera stream and photo capture APIs.
1. The mobile app renders the ego-centric preview and prepares frames/audio for forwarding.
1. The app publishes the stream to an RTMP endpoint backed by MediaMTX.
1. MediaMTX receives the stream, exposes HLS for playback, and writes recordings to disk.

Current status:

- Android: implemented
- iOS: planned, but RTMP forwarding and MediaMTX ingest integration are not implemented yet

### 2. Local device workflow

1. The app receives frames from Meta glasses through DAT.
1. The app can render local preview even when RTMP forwarding is not being used.
1. Platform-specific fallback modes can still be used for local testing and development.

### 3. Legacy assistant pipeline

1. The app collects OpenClaw host, port, and gateway token from secrets or in-app settings.
1. Gemini/OpenClaw bridge code sends requests to the OpenClaw gateway.
1. OpenClaw executes downstream assistant or tool actions outside the mobile app.
1. This pipeline exists for compatibility with imported code and should be treated as non-core.

## Feature Matrix

| Capability | iOS | Android |
| --- | --- | --- |
| Meta DAT registration and streaming | Yes | Yes |
| Glasses camera preview | Yes | Yes |
| Photo capture | Yes | Yes |
| Local iPhone / phone camera mode | Yes | Yes |
| RTMP publishing to MediaMTX | Planned, not implemented | Yes |
| End-to-end `glasses -> app -> server` pipeline | Planned, not implemented | Yes |
| HLS playback and recording via MediaMTX | Planned, not implemented | Yes |
| Legacy Gemini integration | Residual code only | Residual code only |
| Legacy OpenClaw bridge | Residual code only | Residual code only |

Android and iOS are not symmetric yet. Android currently owns the core EgoFlow streaming story. iOS currently covers DAT registration, preview/capture flows, and iPhone fallback mode while the RTMP/server pipeline is still pending.

## Prerequisites

### Required for most workflows

- Git
- A Meta AI glasses device for glasses-based testing
- Meta AI mobile app with Developer Mode enabled

### Android

- Android Studio
- Android SDK
- JDK 17 is the safest local default for this repo
- A GitHub classic personal access token for Meta DAT Android Maven packages
- Android 12+ device or emulator support for build/install flow

### iOS

- Xcode
- An iPhone running a compatible iOS version for deployment
- Apple signing setup for local device builds

### Optional infrastructure

- Docker and Docker Compose for `samples/video-ingest-server`
- `ngrok` for remote/tunneled testing
- A Gemini API key from `https://aistudio.google.com/apikey` if you need the residual Gemini paths
- OpenClaw running locally or on a reachable host if you need the residual tool-calling paths

## Configuration Sources and Secrets

### Android

- Meta DAT Maven auth:
  - `GITHUB_TOKEN` environment variable, or
  - `github_token=...` in `samples/CameraAccessAndroid/local.properties`
- App secrets:
  - copy `samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/Secrets.kt.example`
  - create `Secrets.kt` in the same directory
- Runtime overrides:
  - the app persists settings through `SettingsManager`
  - saved in-app settings override values from `Secrets.kt`
  - use the app's reset action if changes in `Secrets.kt` do not appear

### iOS

- App secrets:
  - copy `samples/CameraAccess/CameraAccess/Secrets.swift.example`
  - create `Secrets.swift` in the same directory
- Runtime overrides:
  - the app persists settings through `SettingsManager`
  - saved in-app settings override values from `Secrets.swift`
  - reset app settings if you need the secrets file to take effect again

### Secrets you are likely to need

- Android today: `rtmpEnabled`, `rtmpPublishUrl` for RTMP publishing
- iOS later: equivalent RTMP configuration will be needed once server streaming is implemented
- `geminiAPIKey`: only needed for the legacy Gemini integration
- `openClawHost`, `openClawPort`, `openClawGatewayToken`: only needed for the legacy OpenClaw gateway path

## Android Setup and Run

### Quickstart

1. Open `samples/CameraAccessAndroid`.
1. Provide your GitHub token:

```bash
export GITHUB_TOKEN=YOUR_GITHUB_CLASSIC_PAT
```

Or put `github_token=YOUR_GITHUB_CLASSIC_PAT` in `samples/CameraAccessAndroid/local.properties`.

3. Copy the secrets template:

```bash
cd samples/CameraAccessAndroid
cp app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/Secrets.kt.example \
   app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/Secrets.kt
```

4. Fill in RTMP values if you want to use the main Android streaming pipeline. Add Gemini/OpenClaw values only if you are intentionally testing the leftover legacy paths.
5. Open the project in Android Studio and sync Gradle.
6. Build and run the `app` configuration, or use Gradle:

```bash
cd samples/CameraAccessAndroid
./gradlew assembleDebug
```

7. Install the APK on a device and launch the app.
8. Turn on Developer Mode in the Meta AI app.
9. In the sample app, press `Connect` and complete DAT registration.

### Success looks like

- Android permissions are granted
- The app reaches the registered state
- The glasses stream appears in the app
- RTMP publishing connects when enabled

## iOS Setup and Run

### Quickstart

1. Open `samples/CameraAccess`.
1. Copy the secrets template:

```bash
cd samples/CameraAccess
cp CameraAccess/Secrets.swift.example CameraAccess/Secrets.swift
```

3. Fill in Gemini/OpenClaw values only if you are intentionally testing the leftover legacy paths.
4. Open `samples/CameraAccess/CameraAccess.xcodeproj` in Xcode.
5. Let Xcode resolve the Swift package dependency for `meta-wearables-dat-ios`.
6. Select a physical iPhone target and confirm signing settings.
7. Build and run the app.
8. Complete DAT registration with the Meta AI app, or use iPhone mode when appropriate.

### iOS-specific notes

- The project deployment target is iOS 17.0.
- The app uses the Meta DAT iOS Swift package directly from Xcode.
- The `glasses -> app -> server` RTMP pipeline is not implemented on iOS yet.
- OpenClaw defaults in the sample secrets file assume a Bonjour-style local host name for Mac-based development.
- Gemini/OpenClaw code remains in the sample for now, but it is not the primary direction of the project.

### Success looks like

- Xcode resolves packages successfully
- The app launches on device
- Registration completes or iPhone mode is available
- Live preview appears
- RTMP forwarding is not expected to work on iOS yet

## MediaMTX RTMP Ingest and Viewer Setup

This is the core server-side part of EgoFlow. Use it to receive the glasses stream from the app, replay it in the browser, and store recordings locally.

### Start the ingest server

```bash
cd samples/video-ingest-server
docker compose up -d
docker compose ps
docker compose logs -f
```

This stack exposes:

- RTMP ingest on `1935`
- HLS on `8888`
- browser viewer on `8088`

Recordings are written under:

```text
samples/video-ingest-server/recordings/live/glasses/
```

### Configure the Android app

Set the Android publish URL to:

```text
rtmp://<SERVER_IP>:1935/live/glasses
```

Then enable RTMP publishing in the app settings.

### iOS status

iOS does not publish to this MediaMTX pipeline yet. When iOS RTMP support is implemented, this same ingest stack is expected to be reused.

### Verify

- MediaMTX logs show a publisher connection
- Viewer page opens at `http://<SERVER_IP>:8088`
- HLS playlist exists at `http://<SERVER_IP>:8888/live/glasses/index.m3u8`
- Recording files appear locally

## ngrok / Remote Debug Flow

Use this when the Android device cannot reach your machine directly over LAN, or when you want a public tunnel for the MediaMTX ingest/viewer stack and, if needed, the legacy OpenClaw gateway.

### Start ngrok

```bash
cd samples
./start-ngrok.sh
```

The helper script exposes:

- RTMP ingest on local port `1935`
- browser viewer on local port `8088`
- OpenClaw gateway on local `127.0.0.1:18789` when that legacy path is in use

Inspect URLs through the script output or `http://127.0.0.1:4040`.

### Use the correct URL type

- Android RTMP publish field:
  - must use the public TCP ngrok tunnel
  - example: `rtmp://0.tcp.ngrok.io:12345/live/glasses`
- Browser viewer:
  - use the HTTPS viewer URL
- OpenClaw:
  - use the HTTPS OpenClaw tunnel as `openClawHost`
  - usually pair it with port `443`

Do not place the viewer URL or HLS URL into the RTMP publish field.

## Verification Checklist

Use this after setup:

- Meta AI Developer Mode is enabled
- Placeholder secrets are replaced
- DAT registration succeeds
- First live frame appears in the app
- Android: RTMP publishing connects and MediaMTX shows the stream
- Android: the viewer plays the stream and recordings are saved
- iOS: local DAT preview works, but server streaming is not implemented yet
- Legacy only: Gemini connects when a valid API key is present
- Legacy only: OpenClaw requests succeed when host/token are configured

## Troubleshooting

### Android dependency resolution fails

- Confirm `GITHUB_TOKEN` or `github_token` is set
- Use a GitHub classic PAT, not a fine-grained token unless you know the package access is correct

### Config changes do not take effect

- The app may still be using saved settings from `SettingsManager`
- Reset settings inside the app and retry

### DAT registration does not complete

- Confirm Developer Mode is enabled in the Meta AI app
- Confirm Bluetooth and other requested permissions were granted

### RTMP publish fails

- Confirm the publish URL matches `rtmp://host/app/streamKey`
- Confirm ports `1935`, `8088`, and `8888` are reachable on the server machine
- Check MediaMTX logs in `samples/video-ingest-server`
- See `samples/CameraAccessAndroid/docs/rtmp-diagnostics.md` for deeper failure analysis

### ngrok works for viewer but not RTMP

- RTMP requires the TCP tunnel, not the HTTPS viewer tunnel

## Related Docs

- `samples/CameraAccess/README.md`
- `samples/CameraAccessAndroid/README.md`
- `samples/video-ingest-server/README.md`
- `samples/CameraAccessAndroid/docs/rtmp-diagnostics.md`

## Attribution and License

This repository contains code copied from or adapted from Meta Wearables DAT samples and some VisionClaw-derived code that still remains in the tree. Keep attribution and upstream references aligned with the actual imported sources.

See:

- `THIRD_PARTY_NOTICES.md`
- `LICENSE`
- `NOTICE`
- `https://wearables.developer.meta.com/terms`
- `https://wearables.developer.meta.com/acceptable-use-policy`
- `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
