# EgoFlow

EgoFlow is a repository of Meta Wearables sample apps adapted for an EgoFlow workflow: connect Meta AI glasses through the Device Access Toolkit (DAT), preview the camera stream, capture media, talk to Gemini Live, and optionally bridge tool-calling through OpenClaw. On Android, the repo also includes an RTMP ingest stack for browser playback and local recording.

This root README is the main onboarding document. Platform-specific details still live in the sample directories, but a new developer should be able to understand the system and get a first run from here.

## Repository Map

- `samples/CameraAccess`: iOS app based on Meta DAT + EgoFlow-specific Gemini/OpenClaw integration
- `samples/CameraAccessAndroid`: Android app based on Meta DAT + EgoFlow-specific Gemini/OpenClaw/RTMP integration
- `samples/video-ingest-server`: MediaMTX + viewer stack used by the Android app for RTMP ingest, HLS playback, and recording
- `samples/start-ngrok.sh`: helper script that exposes RTMP, viewer, and OpenClaw over ngrok for remote testing

## End-to-End Pipelines

### 1. Core device pipeline

1. A Meta AI glasses device connects to the app through Meta Wearables DAT.
1. The app registers with the glasses via the Meta AI app in Developer Mode.
1. DAT provides the live camera stream and photo capture APIs.
1. The app renders live preview and can forward frames/audio into Gemini Live.
1. If OpenClaw is configured, Gemini tool calls can be bridged to an external assistant.

### 2. Android streaming pipeline

1. The Android app receives frames from Meta glasses through DAT.
1. The app can optionally publish video and audio to an RTMP URL.
1. `samples/video-ingest-server` receives RTMP with MediaMTX.
1. MediaMTX exposes HLS for browser playback and writes recordings locally.
1. The included viewer page plays the HLS stream from the ingest server.

### 3. OpenClaw pipeline

1. The app collects OpenClaw host, port, and gateway token from secrets or in-app settings.
1. Gemini/OpenClaw bridge code sends requests to the OpenClaw gateway.
1. OpenClaw executes downstream assistant or tool actions outside the mobile app.

## Feature Matrix

| Capability | iOS | Android |
| --- | --- | --- |
| Meta DAT registration and streaming | Yes | Yes |
| Glasses camera preview | Yes | Yes |
| Photo capture | Yes | Yes |
| Gemini Live integration | Yes | Yes |
| OpenClaw bridge | Yes | Yes |
| Local iPhone / phone camera mode | Yes | Yes |
| RTMP publishing | No | Yes |
| MediaMTX ingest / HLS viewer / recording | No | Yes |

Android and iOS are not symmetric. The Android app owns the RTMP ingest story. The iOS app currently focuses on DAT, Gemini Live, OpenClaw, and iPhone fallback mode.

## Prerequisites

### Required for most workflows

- Git
- A Meta AI glasses device for glasses-based testing
- Meta AI mobile app with Developer Mode enabled
- A Gemini API key from `https://aistudio.google.com/apikey` if you want Gemini features

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
- OpenClaw running locally or on a reachable host if you want tool-calling

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

- `geminiAPIKey`: required for Gemini Live
- `openClawHost`, `openClawPort`, `openClawGatewayToken`: required for OpenClaw gateway access
- Android only: `rtmpEnabled`, `rtmpPublishUrl` for RTMP publishing

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

4. Fill in at least the Gemini key. Add OpenClaw and RTMP values only if you need those features.
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
- Gemini features no longer show placeholder configuration

## iOS Setup and Run

### Quickstart

1. Open `samples/CameraAccess`.
1. Copy the secrets template:

```bash
cd samples/CameraAccess
cp CameraAccess/Secrets.swift.example CameraAccess/Secrets.swift
```

3. Fill in at least the Gemini key. Add OpenClaw values if you need tool-calling.
4. Open `samples/CameraAccess/CameraAccess.xcodeproj` in Xcode.
5. Let Xcode resolve the Swift package dependency for `meta-wearables-dat-ios`.
6. Select a physical iPhone target and confirm signing settings.
7. Build and run the app.
8. Complete DAT registration with the Meta AI app, or use iPhone mode when appropriate.

### iOS-specific notes

- The project deployment target is iOS 17.0.
- The app uses the Meta DAT iOS Swift package directly from Xcode.
- OpenClaw defaults in the sample secrets file assume a Bonjour-style local host name for Mac-based development.

### Success looks like

- Xcode resolves packages successfully
- The app launches on device
- Registration completes or iPhone mode is available
- Live preview appears and Gemini can connect when configured

## Android RTMP Ingest and Viewer Setup

Use this only if you want browser playback or local recording from the Android app.

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

### Verify

- MediaMTX logs show a publisher connection
- Viewer page opens at `http://<SERVER_IP>:8088`
- HLS playlist exists at `http://<SERVER_IP>:8888/live/glasses/index.m3u8`
- Recording files appear locally

## ngrok / Remote Debug Flow

Use this when the Android device cannot reach your machine directly over LAN, or when you want a public tunnel for OpenClaw and the viewer.

### Start ngrok

```bash
cd samples
./start-ngrok.sh
```

The helper script exposes:

- RTMP ingest on local port `1935`
- browser viewer on local port `8088`
- OpenClaw gateway on local `127.0.0.1:18789`

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
- Gemini connects when a valid API key is present
- OpenClaw requests succeed when host/token are configured
- Android only: RTMP publishing connects and MediaMTX shows the stream
- Android only: the viewer plays the stream and recordings are saved

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

This repository contains code copied from or adapted from Meta Wearables DAT samples and VisionClaw-derived code. Keep attribution and upstream references aligned with the actual imported sources.

See:

- `THIRD_PARTY_NOTICES.md`
- `LICENSE`
- `NOTICE`
- `https://wearables.developer.meta.com/terms`
- `https://wearables.developer.meta.com/acceptable-use-policy`
- `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
