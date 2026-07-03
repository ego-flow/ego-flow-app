# EgoFlow

`ego-flow-app` is the **app layer** of the EgoFlow `glasses → app → server` pipeline. This
repository is scoped to the **live-streaming track**: it connects Meta AI (DAT) glasses to a phone
app and publishes the live camera feed to an EgoFlow backend. The slab-append → Clone-server
(file-upload) track is not here — it lives in the sibling fork **Clone Glasses**, which shares this
repo's `:core` `Transport` interface.

The repository ships two samples:

- **`samples/CameraAccessAndroid`** — the primary, server-integrated Android client.
- **`samples/CameraAccess`** — an iOS DAT reference app (not on the server flow).

Read this repository as a set of samples plus architecture notes, not as one finished
cross-platform product.

## Repository Layout

| Path | What it is | Status |
| --- | --- | --- |
| `samples/CameraAccessAndroid` | Android DAT app: live glasses preview, photo capture, and live streaming over **RTMP / WHIP / HTTP-chunk** to the EgoFlow backend | Primary, server-integrated |
| `samples/CameraAccess` | iOS DAT app: glasses/iPhone preview + photo, plus a **Gemini Live + OpenClaw** voice/vision assistant | Reference-only, **work in progress** — not on the server flow |

The RTMP/WHIP receiver (MediaMTX) is an **external** reference stack and is no longer vendored in
this repository. Point the app at whatever EgoFlow backend / MediaMTX host you are testing against.

## Architecture

`CameraAccessAndroid` is a five-module Gradle project built around a single transport seam:

- `:app` — Compose UI, `StreamViewModel`, foreground `StreamingService`, settings, DAT/phone
  source selection.
- `:core` — transport-agnostic contract: the `Transport` interface, `TransportFactory`, shared
  `VideoEncoder` (MediaCodec H.264/HEVC) and YUV conversion.
- `:transport-rtmp` — RTMP/RTMPS publish + the EgoFlow backend client (`EgoFlowBackendClient`),
  auth/repo prefs.
- `:transport-whip` — WebRTC-HTTP Ingestion (WHIP) publish via libwebrtc.
- `:transport-http` — chunked fragmented-MP4 upload ingest.

`:app` depends only on the `:core` `Transport` interface; each transport implementation is sealed
in its own module so alternative transports (including the fork's slab-append) can slot into the
same seam. See [`docs/03. project_architecture.md`](./docs/03.%20project_architecture.md) for the
full breakdown and data-flow diagrams.

## Android Sample — Quick Start

`samples/CameraAccessAndroid` is the current reference implementation of the
`glasses → app → server` live path.

### Requirements

- Android Studio.
- **JDK 17** (with `javac`).
- Android SDK: `compileSdk 35`, `minSdk 31` (Android 12), `targetSdk 34`. Toolchain: AGP `8.6.0`,
  Kotlin `2.1.20`.
- A **GitHub classic personal access token with `read:packages`** — the Meta Wearables DAT Android
  SDK is served from `maven.pkg.github.com/facebook/meta-wearables-dat-android`.
- For real-device testing: Meta AI glasses and the Meta AI app with **Developer Mode** enabled.

### Setup

1. Create `samples/CameraAccessAndroid/local.properties` (gitignored) with:
   ```properties
   sdk.dir=/path/to/Android/sdk
   github_token=YOUR_GITHUB_PAT
   ```
   The token may instead be supplied as the `GITHUB_TOKEN` environment variable.
2. *(Optional, release builds only)* Configure signing via `keystore.properties` (gitignored;
   template `keystore.properties.example`) with `storeFile`, `storePassword`, `keyAlias`,
   `keyPassword`. Without it, debug builds use AGP's default debug keystore and a release build
   (`assembleRelease`/`bundleRelease`) fails instead of signing with a debug key.

> **There is no `Secrets.kt`.** Runtime configuration (server URL, credentials, transport,
> repository) is entered in-app and stored in SharedPreferences — nothing is compiled in. The only
> build-time secrets are `github_token` and the optional release keystore.

### Build

```bash
cd samples/CameraAccessAndroid
./gradlew assembleDebug
./gradlew installDebug
```

`run.sh` is a convenience script (sets `JAVA_HOME` to JDK 17, exports `GITHUB_TOKEN`, builds and
installs/launches via adb) for Linux/WSL setups.

### Configure at runtime

1. **Settings** — pick the streaming transport (**RTMP / WHIP / HTTP**) and enter the EgoFlow
   **Server URL**. Audio and codec options apply to RTMP only.
2. **Login** — enter your EgoFlow user id and password.
3. **Repository** — select the repository to stream into (the Live tab is gated on having one).

Settings persist in SharedPreferences: `SettingsManager` (transport/codec/audio prefs),
encrypted `AuthPrefs` (server URL + credentials, AES256-GCM), and `RepoPrefs` (selected repository).

### Transports at a glance

| Transport | `ingestType` | Video | Audio | Live playback |
| --- | --- | --- | --- | --- |
| **RTMP** | `MEDIAMTX` | H.264 / HEVC | Yes — mic source auto/glasses/phone | Yes (MediaMTX) |
| **WHIP** | `MEDIAMTX` | WebRTC H.264 | No (video-only) | Yes (MediaMTX) |
| **HTTP** | `HTTP` | fragmented-MP4 chunks, uploaded live | No (video-only) | No |

WHIP is not a separate ingest type — it shares MediaMTX ingest and differs only in the publish
step. For RTMP audio, the glasses mic is captured over **Bluetooth HFP/SCO** (via `AudioManager`),
not as DAT audio frames.

### Permissions

The app requests: Bluetooth (+ `BLUETOOTH_CONNECT`), Camera, Record Audio / Modify Audio Settings,
Internet / Network State, Foreground Service (`connectedDevice|dataSync`), and Wake Lock.

## iOS Sample — Reference (Work in Progress)

`samples/CameraAccess` is a DAT reference app that is **still under active development** and is
**not** the server-integrated EgoFlow client — it does not implement the RTMP/WHIP/HTTP transports
or the backend flow. It demonstrates glasses and iPhone-camera preview + photo capture (plus a mock
device for hardware-free testing), wired to a **Gemini Live** bidirectional audio/vision assistant
and an **OpenClaw** agentic tool-calling bridge. Expect incomplete features and breaking changes.

### Setup

1. Open `samples/CameraAccess/CameraAccess.xcodeproj` in Xcode (a physical iPhone is recommended
   for realistic testing).
2. Copy `samples/CameraAccess/CameraAccess/Secrets.swift.example` to `Secrets.swift` (gitignored).
3. Fill in the config keys:
   - `geminiAPIKey` — required for the assistant ([Google AI Studio](https://aistudio.google.com/apikey)).
   - `openClawHost`, `openClawPort` (default `18789`), `openClawHookToken`, `openClawGatewayToken`
     — optional, for the OpenClaw gateway on your Mac.

These values can also be overridden from the in-app Settings screen.

## Backend API Contract

When the Android app streams, `:transport-rtmp/EgoFlowBackendClient` talks to the EgoFlow backend
under an `/api/v1` base (scheme-less hosts default to `https://`). JWT is carried as
`Authorization: Bearer`; a rotated token may arrive in the `X-Refreshed-Token` response header, and
a `401` triggers one silent re-login + retry.

| Method & path | Purpose |
| --- | --- |
| `POST /auth/app/login` | `{id, password}` → `{token, user}` |
| `GET /repositories/maintain` | List repositories for the repo picker |
| `POST /streams/register` | `{repositoryId, deviceType, ingestType}` (ingestType `MEDIAMTX`\|`HTTP`, required) → `{recordingSessionId}` |
| `POST /streams/{id}/publish-ticket` | → `{stream_path, publish_ticket}` (short-TTL) |
| `POST /recordings/{id}/close-intent` | `{reason: "NORMAL_DISCONNECT"}` — RTMP/WHIP teardown |

The RTMP publish URL is assembled as
`rtmp://{host}:1935/{stream_path}?ticket={url-encoded publish_ticket}`.

**HTTP-chunk ingest plane** (`ingestType HTTP`):

| Method & path | Purpose |
| --- | --- |
| `POST /http-streams/{id}/start` | `{publish_ticket}` → session status |
| `POST /http-streams/{id}/chunks` | raw `application/octet-stream` body + `X-Chunk-Sequence` / `X-Chunk-Offset` headers |
| `POST /http-streams/{id}/finish` | `{total_bytes}` |

**WHIP publish plane** (validated by MediaMTX via the `?ticket=` value, no `Authorization` header):
`POST {origin}/{stream_path}/whip?ticket=...` (SDP offer → SDP answer + `Location`), and a
best-effort `DELETE {resourceUrl}` on stop.

See [`docs/04. project_rtmp_android.md`](./docs/04.%20project_rtmp_android.md) for the full
recording-session lifecycle (register → publish-ticket → publish → close-intent).

## Tests

The transport modules carry JVM unit tests (JUnit4 + OkHttp MockWebServer). Run them with:

```bash
cd samples/CameraAccessAndroid
./gradlew test
```

Coverage lives in `:transport-rtmp` (auth clients, `EgoFlowBackendClient` HTTP-stream handling,
RTMP diagnostics/publisher/packetizer/queue/failure paths), `:transport-whip` (`WhipClientTest`),
and `:transport-http` (`FragmentedMp4MuxerTest`). The `:app` module has no unit tests.

## Repository Docs

Deep-dive architecture and track docs live under [`docs/`](./docs/) (written in Korean):

- [`docs/01. project_guide.md`](./docs/01.%20project_guide.md) — index and reading order.
- [`docs/02. project_scope.md`](./docs/02.%20project_scope.md) — repo scope, sample map, tech stack.
- [`docs/03. project_architecture.md`](./docs/03.%20project_architecture.md) — modules, `Transport` seam, per-transport data flow (RTMP / WHIP / HTTP).
- [`docs/04. project_rtmp_android.md`](./docs/04.%20project_rtmp_android.md) — RTMP transport deep dive + shared backend session contract.

## Attribution And License

This repository contains code derived from Meta Wearables DAT sample apps (iOS and Android) and
from the VisionClaw project (Intent-Lab). Original Meta copyright/license headers are retained in
the copied sources, with "Modified in this repository for EgoFlow" notices added.

See:

- [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md) — upstream sources and commit hashes.
- [`LICENSE`](./LICENSE), [`NOTICE`](./NOTICE), [`CONTRIBUTING.md`](./CONTRIBUTING.md)
- Meta Wearables Developer Terms — `https://wearables.developer.meta.com/terms`
- Meta Wearables Acceptable Use Policy — `https://wearables.developer.meta.com/acceptable-use-policy`
- VisionClaw license — `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
