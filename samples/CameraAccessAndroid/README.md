# Camera Access App

A sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, and managing connection states.

## Attribution

This directory includes code copied from or adapted from the following upstream sources:

- VisionClaw repository: `https://github.com/Intent-Lab/VisionClaw` at `917a05f79c4cbf8afff711b22f1057ff262eb6fa`
- Meta Android repository: `https://github.com/facebook/meta-wearables-dat-android` at `82af01b2b9bf9f76b596be671f9b883f568e5286`
- Upstream sample path: `samples/CameraAccess`

Original copyright and license notices from Meta have been retained in source files where applicable.

## Local modifications

This repository modifies the upstream sample for EgoFlow-specific behavior, including:

- RTMP publishing support and ingest integration
- app configuration changes
- UI and flow changes
- OpenClaw-related integration changes

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Share captured photos
- Optionally publish the live video to a local RTMP server for realtime ingest and recording

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Add your personal access token (classic) to the `local.properties` file (see [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup))
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Capture photos
   - View and save captured photos
   - Disconnect from the device

## End-to-end video streaming test

This app can mirror the live video stream to an RTMP ingest server running on another machine or on the same PC. The full flow is:

1. Android app publishes `H.264 over RTMP`
1. `../video-ingest-server` receives the stream with MediaMTX
1. Browser watches the stream over HLS
1. MediaMTX receives the incoming stream on the server PC
1. Recording is optional and currently requires a separate recorder process such as `server/save-stream.sh`

Use one of the following two environments:

- `Production`: `ngrok` is not used. The Android device reaches the server directly over LAN.
- `Debug/Test`: `ngrok` is used. The Android device publishes to the public `ngrok` TCP tunnel, while the ingest server still runs on this PC.

### Common prerequisites

- Docker and Docker Compose are installed on this PC.
- Android SDK and `adb` are available.
- A GitHub classic personal access token is set either:
  - in `local.properties` as `github_token=...`, or
  - in the shell as `GITHUB_TOKEN=...`
- The ingest server code is present at `/home/js1044k/EgoFlow/samples/video-ingest-server`.

The Android dependency reads the token in [settings.gradle.kts](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/settings.gradle.kts).

### 1. Build and run the Android app

From this directory:

```bash
cd /home/js1044k/EgoFlow/samples/CameraAccessAndroid
./gradlew assembleDebug
"/mnt/c/Users/Jinsu Kim/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
"/mnt/c/Users/Jinsu Kim/AppData/Local/Android/Sdk/platform-tools/adb.exe" shell am start -n com.meta.wearable.dat.externalsampleapps.cameraaccess/.MainActivity
```

If you prefer, [run.sh](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/run.sh) shows the same flow with an explicit `JAVA_HOME` and `adb` path.

Once the app is running:

1. Turn on Developer Mode in the Meta AI app.
1. Launch the sample app.
1. Press `Connect`.
1. Open `Settings`.
1. Configure `RTMP Publish`:
   - enable `RTMP publishing`
   - set `Publish URL` according to the environment below

The settings UI and placeholders live in [SettingsScreen.kt](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/SettingsScreen.kt), and built-in defaults can be defined in [Secrets.kt.example](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/Secrets.kt.example).

### OpenClaw over ngrok

Use this when the glasses or Android device cannot reach this PC over LAN, but you still want the app's OpenClaw bridge to call the local gateway.

#### 1. Keep OpenClaw running locally

The current default OpenClaw setup is safe for local use:

- `gateway.port = 18789`
- `gateway.bind = loopback`
- `gateway.auth.mode = token`

This app calls:

```text
POST /v1/chat/completions
```

through the values configured in `Secrets.kt` or the in-app Settings screen.

#### 2. Start an ngrok HTTPS tunnel to the local gateway

From the parent `samples` directory:

```bash
cd /home/js1044k/EgoFlow/samples
./start-ngrok.sh
```

The combined script starts these tunnels at once:

- RTMP on local port `1935`
- viewer on local port `8088`
- OpenClaw on local `127.0.0.1:18789`

It also prints the exact values to copy into `Secrets.kt`:

- `openClawHost`
- `openClawPort`

If the printed URL is:

```text
https://abc123.ngrok-free.app
```

use:

```kotlin
const val openClawHost = "https://abc123.ngrok-free.app"
const val openClawPort = 443
```

#### 3. Gateway token source

The Android app authenticates to OpenClaw with the gateway token, not the hook token.

Read the existing token:

```bash
openclaw config get gateway.auth.token
```

Create or rotate it:

```bash
openclaw doctor --generate-gateway-token
```

OpenClaw stores this value under `gateway.auth.token` in:

```text
~/.openclaw/openclaw.json
```

#### 4. Hook token source

The current Android app does not send requests to `/hooks/*`, so `openClawHookToken` is not required for the app's current flow.

If you separately expose OpenClaw hooks for webhook ingress, define a distinct token:

```bash
openclaw config set hooks.token YOUR_RANDOM_SECRET
```

Verify it:

```bash
openclaw config get hooks.token
```

OpenClaw requires `hooks.token` to be different from `gateway.auth.token`.

#### 5. Apply the values in the app

Set the values either:

- in [Secrets.kt](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/Secrets.kt), or
- in the app's `Settings > OpenClaw`

If the app already has saved settings, `SettingsManager` will prefer those over `Secrets.kt`. Use `Reset to Defaults` in Settings after editing `Secrets.kt`.

### 2. Start the ingest server on this PC

From the server directory:

```bash
cd /home/js1044k/EgoFlow/samples/video-ingest-server
docker compose up -d
docker compose ps
docker compose logs -f
```

This stack is defined in [docker-compose.yml](/home/js1044k/EgoFlow/samples/video-ingest-server/docker-compose.yml) and [mediamtx.yml](/home/js1044k/EgoFlow/samples/video-ingest-server/mediamtx.yml). It exposes:

- RTMP ingest: local port `1935`
- HLS endpoint: local port `8888`
- Browser viewer: local port `8088`

When the app starts publishing to `live/glasses`, recordings are automatically written on this PC under:

```text
/home/js1044k/EgoFlow/samples/video-ingest-server/recordings/live/glasses/
```

### 3. Production environment: no ngrok

Use this when the Android device can reach this PC directly over the same LAN or VPN.

#### Server address

Find the LAN IP address of this PC. One common command is:

```bash
hostname -I
```

Assume the result includes `192.168.0.10`. Then configure the app with:

```text
rtmp://192.168.0.10:1935/live/glasses
```

#### Browser-side verification

Open on any machine that can reach this PC:

- Viewer page: `http://192.168.0.10:8088`
- Raw HLS stream: `http://192.168.0.10:8888/live/glasses/index.m3u8`

#### Expected test result

1. The app connects to the glasses and starts streaming.
1. The app publishes RTMP to `192.168.0.10:1935`.
1. `docker compose logs -f` on this PC shows MediaMTX receiving the publisher.
1. The browser viewer starts playing the stream.
1. If `server/save-stream.sh` or another recorder is running, a recording file appears under its configured output directory.

### 4. Debug/Test environment: ngrok enabled

Use this when the Android device cannot directly reach this PC, or when you want to test over a public tunnel.

#### Start ngrok

From the parent `samples` directory:

```bash
cd /home/js1044k/EgoFlow/samples
./start-ngrok.sh
```

This starts the combined tunnel set and creates:

- TCP tunnel for RTMP on local port `1935`
- HTTP tunnel for viewer on local port `8088`
- HTTP tunnel for OpenClaw on local `127.0.0.1:18789`

The local HLS endpoint on port `8888` still exists through MediaMTX, but it is not exposed through `ngrok` in this setup.

In another terminal, inspect the tunnel URLs:

```bash
cd /home/js1044k/EgoFlow/samples/video-ingest-server
./print-ngrok-urls.sh
```

Or open:

```text
http://127.0.0.1:4040
```

#### App publish URL

If the RTMP tunnel is reported as:

```text
tcp://0.tcp.ngrok.io:12345
```

set the app `Publish URL` to:

```text
rtmp://0.tcp.ngrok.io:12345/live/glasses
```

Use the `ngrok` TCP tunnel only for RTMP publishing. Do not put the viewer or HLS HTTPS URL into the app publish field.

#### Browser-side verification

Use the public HTTPS tunnel URL:

- `video-ingest-viewer`: open the viewer page itself

For example:

- Viewer page: `https://example-viewer.ngrok-free.app`

#### Expected test result

1. The app connects to the glasses and starts streaming.
1. The app publishes RTMP to the public `ngrok` TCP address.
1. `ngrok` forwards the RTMP connection back to this PC on port `1935`.
1. MediaMTX receives the stream and exposes HLS locally.
1. The public `ngrok` viewer URL plays the stream.
1. If `server/save-stream.sh` or another recorder is running, a recording file appears under its configured output directory.

### Notes

- The app code currently attempts microphone capture and AAC-over-RTMP in addition to H.264 video. Validate end-to-end audio on your target device and recorder before relying on it in production.
- The app publishes both glasses stream frames and phone camera frames.
- The encoder uses the device's built-in H.264 encoder through `MediaCodec`.
- SRT is not wired yet. RTMP was chosen because it fits the current Android codebase with no extra native dependency.
- On free `ngrok` plans, public URLs may change each time you restart `ngrok`.
- If streaming fails in `Production`, check firewall rules on this PC for ports `1935`, `8088`, and `8888`.
- A repo-specific failure analysis is documented in [docs/rtmp-diagnostics.md](/home/js1044k/EgoFlow/samples/CameraAccessAndroid/docs/rtmp-diagnostics.md).

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.

The copied and adapted materials in this directory also require the upstream attribution and license or terms records tracked in:

- `/THIRD_PARTY_NOTICES.md`
- `/LICENSE`
- `/NOTICE`
- `https://wearables.developer.meta.com/terms`
- `https://wearables.developer.meta.com/acceptable-use-policy`
- `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
- `https://github.com/facebook/meta-wearables-dat-android/blob/main/NOTICE`

Keep the repository URLs and commit hashes in this file in sync with the actual upstream sources used.
