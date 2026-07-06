# EgoFlow — Android Client

The primary, server-integrated EgoFlow app. It connects to Meta AI (DAT) glasses, shows a live
camera preview, captures photos, and publishes the live feed to an EgoFlow backend over one of
three transports (**RTMP / WHIP / HTTP-chunk**). Adapted from Meta's `CameraAccess` DAT sample.

For the repository-wide overview, architecture, and full backend API contract, see the
[root README](../../README.md) and [`docs/`](../../docs/).

## Attribution

This directory includes code copied from or adapted from the following upstream sources:

- VisionClaw repository: `https://github.com/Intent-Lab/VisionClaw` at `917a05f79c4cbf8afff711b22f1057ff262eb6fa`
- Meta Android repository: `https://github.com/facebook/meta-wearables-dat-android` at `82af01b2b9bf9f76b596be671f9b883f568e5286`
- Upstream sample path: `samples/CameraAccess`

Original copyright and license notices from Meta have been retained in source files where applicable.

## Local modifications

This repository modifies the upstream sample for EgoFlow-specific behavior, including:

- live streaming over RTMP/RTMPS, WHIP (WebRTC), and HTTP-chunk transports
- EgoFlow backend integration (login, repository selection, recording-session lifecycle)
- a phone-camera fallback source and a mic-diagnostics screen
- app configuration, UI, and flow changes

## Sibling fork

Clone Glasses (slab-append → Clone server) was forked from this
app at commit `a6f3a89`. Sync ritual + shared-bucket file list
live in that repo: `~/2026-1-urop/clone/apps/smartglasses/README.md`.

## Module layout

Five Gradle modules built around a single transport seam:

| Module | Role |
| --- | --- |
| `:app` | Compose UI, `StreamViewModel`, foreground `StreamingService`, settings, DAT/phone source selection |
| `:core` | `Transport` interface, `TransportFactory`, shared `VideoEncoder` (MediaCodec H.264/HEVC), YUV conversion |
| `:transport-rtmp` | RTMP/RTMPS publish + `EgoFlowBackendClient`, auth/repo prefs |
| `:transport-whip` | WHIP (WebRTC-HTTP Ingestion) publish via libwebrtc |
| `:transport-http` | chunked fragmented-MP4 upload ingest |

`:app` depends only on the `:core` `Transport` interface; each transport implementation is sealed
in its own module. See [`docs/03. project_architecture.md`](../../docs/03.%20project_architecture.md).

## Features

- Connect to Meta AI glasses via the Device Access Toolkit (DAT)
- Live camera preview from the glasses, with a phone-camera fallback source
- Capture and share photos
- Log in to an EgoFlow backend and select a repository to stream into
- Publish the live feed over RTMP, WHIP, or HTTP-chunk (selectable in Settings)
- RTMP audio with a selectable mic source (auto / glasses / phone); a mic-diagnostics screen

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 17 (with `javac`). If your system default points at a JRE-only install (e.g. `/usr/lib/jvm/java-21-openjdk-amd64` on some Debian/Ubuntu setups), export `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` before running Gradle.
- Android SDK: `compileSdk 35`, `minSdk 31` (Android 12.0+), `targetSdk 34`
- A GitHub classic personal access token with `read:packages` (to download the Meta Wearables DAT SDK)
- A Meta AI glasses device for testing (optional for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Ensure `local.properties` contains the following fields:

   ```properties
   sdk.dir=/path/to/your/Android/sdk
   github_token=YOUR_GITHUB_PAT
   mwdat_application_id=YOUR_META_WEARABLES_APP_ID
   mwdat_client_token=YOUR_META_WEARABLES_CLIENT_TOKEN
   ```

   - `sdk.dir`: path to your Android SDK installation. Android Studio sets this automatically; for manual builds, find it in **Android Studio > Settings > Android SDK** (the *Android SDK Location* field) or set `ANDROID_HOME` and use that path.
   - `github_token`: a GitHub personal access token (classic) with `read:packages` scope, required to download the Meta Wearables DAT SDK. May instead be supplied as the `GITHUB_TOKEN` environment variable. See [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup).
   - `mwdat_application_id` / `mwdat_client_token`: app credentials from Meta Wearables Developer Center, injected into the manifest at build time. They may instead be supplied as `MWDAT_APPLICATION_ID` and `MWDAT_CLIENT_TOKEN` environment variables. Do not commit these values.
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

> **There is no `Secrets.kt`.** Runtime configuration (server URL, credentials, transport,
> repository) is entered in-app and stored in SharedPreferences — nothing is compiled in. The only
> build-time secrets are `github_token`, `mwdat_application_id`, `mwdat_client_token`, and, for
> signed release builds, `keystore.properties` (gitignored; template `keystore.properties.example`
> with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without release signing
> credentials, debug builds use AGP's default debug keystore, and a requested release build
> (`assembleRelease`/`bundleRelease`) fails instead of signing with a debug key.

## Running the app

### Build and install via command line

1. Set up Java environment (if needed):

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

2. Build the debug APK:

```bash
./gradlew assembleDebug
```

Or build and install directly to a connected device:

```bash
./gradlew installDebug
```

Then launch the app:

```bash
adb shell am start -n io.egoflow.app/.MainActivity
```

`run.sh` wraps these steps (sets `JAVA_HOME` to JDK 17, exports `GITHUB_TOKEN`, builds and
installs/launches via adb) for Linux/WSL setups.

If `./gradlew` fails with `Toolchain installation ... does not provide the required capabilities: [JAVA_COMPILER]`, your default `java` points to a JRE-only install; set `JAVA_HOME` to a JDK 17 as shown above.

### Using Android Studio

1. Open the project in Android Studio
1. Click **Run** > **Run...** > **app**

### Device setup and runtime

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app on the device.
1. In **Settings**, choose the streaming transport (RTMP / WHIP / HTTP) and enter the EgoFlow **Server URL**.
1. **Log in** with your EgoFlow user id and password.
1. Select the **repository** to stream into (the Live tab is gated on having one).
1. Press **Connect** to complete DAT registration; the glasses camera stream then appears.
1. Use the on-screen controls to capture photos, start/stop streaming, and disconnect.

## Transports

| Transport | `ingestType` | Video | Audio | Live playback |
| --- | --- | --- | --- | --- |
| **RTMP** | `MEDIAMTX` | H.264 / HEVC | Yes — mic source auto/glasses/phone | Yes (MediaMTX) |
| **WHIP** | `MEDIAMTX` | WebRTC H.264 | No (video-only) | Yes (MediaMTX) |
| **HTTP** | `HTTP` | fragmented-MP4 chunks, uploaded live | No (video-only) | No |

WHIP shares MediaMTX ingest with RTMP and differs only in the publish step. For RTMP audio, the
glasses mic is captured over Bluetooth HFP/SCO (via `AudioManager`), not as DAT audio frames.

## Tests

JVM unit tests (JUnit4 + OkHttp MockWebServer) live in the transport modules:

```bash
./gradlew test
```

Coverage: `:transport-rtmp` (auth clients, `EgoFlowBackendClient` HTTP-stream handling, RTMP
diagnostics/publisher/packetizer/queue/failures), `:transport-whip` (`WhipClientTest`), and
`:transport-http` (`FragmentedMp4MuxerTest`). The `:app` module has no unit tests.

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
