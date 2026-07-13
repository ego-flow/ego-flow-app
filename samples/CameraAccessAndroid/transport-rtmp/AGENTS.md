# :transport-rtmp

RTMP-over-TCP transport that talks to MediaMTX via ego-flow-server's
session and publish-ticket REST control plane.

## What lives here

All RTMP- and ego-flow-specific code:

- `transport/rtmp/RtmpTransport.kt` — implements `core.Transport`;
  composes the streamer + backend client + heartbeat coroutine
  into one lifecycle
- `stream/rtmp/RtmpStreamer.kt` — MediaCodec encoder pipeline +
  RTMP send orchestration (single-threaded `ExecutorService`)
- `stream/rtmp/VideoEncoder.kt` — MediaCodec wrapper extracted from
  RtmpStreamer; mechanical encoder (no fallback policy)
- `stream/rtmp/RtmpPublisher.kt` — TCP RTMP wire protocol
  (handshake, AMF0 commands, FLV tag framing)
- `stream/rtmp/RtmpFrameConverter.kt` — I420 ↔ NV12 + Bitmap → I420
- `stream/rtmp/RtmpVideoPacketizer.kt` — H.264/HEVC NAL extraction
- `stream/rtmp/RtmpVideoCodec.kt` — RTMP-side codec enum with
  mimeType / displayName / preferenceValue
- `stream/rtmp/RtmpDiagnostics.kt` — structured diagnostic log ring
- `stream/rtmp/RtmpTransportFailures.kt` — error classification
  (TLS, encoding, network, …)
- `stream/rtmp/RtmpAudioRecorder.kt` — PCM capture + AAC encoder
  wiring
- `egoflow/EgoFlowBackendClient.kt` — REST client: login,
  register-stream, publish-ticket, heartbeat, stop
- `auth/EgoFlowAuthClient.kt`, `auth/EgoFlowStreamClient.kt` —
  finer-grained auth/stream HTTP helpers used by the backend
  client
- `settings/AuthPrefs.kt`, `settings/RepoPrefs.kt` —
  SharedPreferences for EgoFlow auth + repo selection

## What does NOT belong here

- `core.Transport` interface or `core.VideoCodec` enum (those live
  in `:core`)
- Anything UI / Compose (lives in `:app`)
- StreamViewModel orchestration: stays in `:app` for now, will use
  `RtmpTransport` via `core.Transport` after the Phase 2 cutover
- `core.SettingsManager` (still in `:app`; tied to RtmpVideoCodec
  via legacy field — moves in Phase 2)

## Dependencies

```kotlin
implementation(project(":core"))   // Transport interface + types
implementation(libs.okhttp)        // EgoFlow REST plane
implementation(libs.gson)
testImplementation("junit:junit:4.13.2")
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

Coroutines come transitively from `:core` (which exposes
`kotlinx-coroutines-core` via `api(...)`).

## Tests

7 test classes, 24 tests:

- `stream/rtmp/RtmpVideoPacketizerTest` (5) — NAL extraction +
  HEVC/AVC unit-type detection
- `stream/rtmp/RtmpPublisherTest` (3) — RTMP handshake / chunk
  encoding
- `stream/rtmp/RtmpDiagnosticsTest` (3) — diagnostic ring buffer
  semantics
- `stream/rtmp/RtmpTransportFailuresTest` (5) — error classifier
- `auth/EgoFlowAuthClientTest` (5) — mockwebserver-backed auth
  flow
- `auth/EgoFlowStreamClientTest` (3) — mockwebserver-backed stream
  endpoints

## RtmpTransport — current status

Lives in `transport/rtmp/RtmpTransport.kt` and is on the active
runtime path through `core.Transport`. It accepts only EgoFlow-owned
I420 `GlassesVideoFrame` values; SDK-specific frames must never enter
this module. State surfaces via `StateFlow<TransportState>`.
