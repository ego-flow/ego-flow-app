# EgoFlow — Android Client

The primary, server-integrated EgoFlow app. It connects to Meta AI (DAT) glasses, shows a live
camera preview, captures photos, and publishes the live feed to an EgoFlow backend over one of the
two supported `v0.0.1` transports (**RTMP/RTMPS or HTTP-chunk**). Adapted from Meta's
`CameraAccess` DAT sample. A WHIP implementation remains in the source tree as a dormant
experimental module, but the shipping UI does not select it.

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

- supported live streaming over RTMP/RTMPS and HTTP-chunk transports
- a dormant WHIP (WebRTC) module retained for future qualification
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
| `:transport-whip` | Dormant experimental WHIP (WebRTC-HTTP Ingestion) implementation via libwebrtc; not selectable in `v0.0.1` |
| `:transport-http` | chunked fragmented-MP4 upload ingest |

`:app` depends only on the `:core` `Transport` interface; each transport implementation is sealed
in its own module. See [`docs/03. project_architecture.md`](../../docs/03.%20project_architecture.md).

## Features

- Connect to Meta AI glasses via the Device Access Toolkit (DAT)
- Live camera preview from the glasses, with a phone-camera fallback source
- Capture and share photos
- Log in to an EgoFlow backend and select a repository to stream into
- Publish the live feed over RTMP/RTMPS or HTTP-chunk (selectable on the Record screen)
- Experimental RTMP microphone controls and a mic-diagnostics screen; keep microphone streaming
  off for the `v0.0.1` first PoC because audio is outside release acceptance

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 17 (with `javac`). If your system default points at a JRE-only install (e.g. `/usr/lib/jvm/java-21-openjdk-amd64` on some Debian/Ubuntu setups), export `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` before running Gradle.
- Android SDK: `compileSdk 35`, `minSdk 31` (Android 12.0+)
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

If `./gradlew` fails with `Toolchain installation ... does not provide the required capabilities: [JAVA_COMPILER]`, your default `java` points to a JRE-only install; set `JAVA_HOME` to a JDK 17 as shown above.

### Using Android Studio

1. Open the project in Android Studio
1. Click **Run** > **Run...** > **app**

### Device setup and runtime

1. Complete the root
   [Getting Started With Meta AI Glasses](../../README.md#getting-started-with-meta-ai-glasses)
   guide, including Meta AI pairing, permissions, and APK signing-certificate registration.
1. For a local development build, set `mwdat_application_id=0` and `mwdat_client_token=0` in the
   gitignored local properties (or set both equivalent environment variables to `0`), then enable
   Developer Mode in the Meta AI app. A release-signed or release-channel build instead uses its
   registered Integration credentials and tester access.
1. Launch the app on the device.
1. In **Settings**, enter the EgoFlow **Server URL**.
1. **Log in** with your EgoFlow user id and password.
1. Select the **repository** to stream into (the Live tab is gated on having one).
1. Press **Connect**, complete the Meta registration dialog, and wait until the glasses are shown
   as registered and available.
1. On the **Record** screen, turn **Enable Live Streaming** on for RTMP or off for HTTP upload.
1. Press **Start streaming**. Approve DAT Camera access if it has not already been granted,
   then confirm that the glasses-camera preview appears.
1. Use the on-screen controls to capture photos, stop streaming, and disconnect.

## Transports

| Transport | `v0.0.1` status | `ingestType` | Video | Audio | Live playback |
| --- | --- | --- | --- | --- | --- |
| **RTMP/RTMPS** | Supported | `MEDIAMTX` | H.264 / HEVC | Off for first-PoC acceptance | Yes (MediaMTX) |
| **HTTP** | Supported | `HTTP` | fragmented-MP4 chunks, uploaded live | No (video-only) | No |
| **WHIP** | Dormant/experimental; not selectable | `MEDIAMTX` | WebRTC H.264 implementation | No (video-only) | Not release-qualified |

The WHIP source shares MediaMTX ingest with RTMP and differs only in the publish step. Keeping the
module and its unit tests in the build does not make it a supported `v0.0.1` product path. Future
activation requires a UI selection, real-glasses WebRTC/ICE E2E evidence, and updated release docs.

RTMP microphone code captures the glasses mic over Bluetooth HFP/SCO (via `AudioManager`), not as
DAT audio frames. Microphone streaming is intentionally excluded from the first-PoC acceptance;
the verified release flow keeps **Stream audio** off.

## Tests

JVM unit tests (JUnit4 + OkHttp MockWebServer) live in the transport modules:

```bash
./gradlew test
```

Coverage: `:transport-rtmp` (`EgoFlowAuthClient`, `EgoFlowBackendClient` HTTP-stream handling, RTMP
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
