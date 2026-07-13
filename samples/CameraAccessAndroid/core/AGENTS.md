# :core

Surface-agnostic foundations shared by `:app` and every `:transport-*`
module.

## What lives here

The vendor-neutral transport seam and shared media conversion:

- `transport/api/Transport.kt` — the 5-method interface every outbound
  protocol (RTMP, slab-append, future ones) implements
- `transport/api/TransportTypes.kt` — `TransportId`, `VideoCodec`,
  `StopReason`, `GlassesVideoFrame`, `EncodedFrame`, `TransportState`
- `transport/api/TransportFactory.kt` — factory + `TransportDeps`
  container; the registry pattern `:app`'s `Application` class wires
  up at boot
- `encoder/YuvFrameConverter.kt` — shared I420/NV12, rotation, Bitmap,
  and ARGB-to-I420 conversion

## Dependency direction

```
:app ──▶ :transport-rtmp ──▶ :core
       ├──▶ :transport-whip ──▶ :core
       └──▶ :transport-http ──▶ :core
```

`:core` depends on **no other workspace module**. Only platform
(`android.*`) and kotlinx-coroutines-core are allowed. SDK-owned
frame types must be normalized by `:app` before crossing this boundary.

If you find yourself wanting to `import` something from
`io.egoflow.app.{stream,
egoflow, settings, ui, ...}` (i.e. anything that lives in `:app`
or `:transport-rtmp`) here in `:core` — STOP. You've inverted the
graph. Move the dep down or restructure.

## What does NOT belong here

- Anything transport-specific: publish tickets, byte offsets, HLS
  URLs, owner leases, MediaMTX, MKV, slab hashing, etc. Those live
  in `:transport-*`.
- UI / Compose code: stays in `:app`.
- ViewModel orchestration / reconnect policy: stays in `:app` as
  outer-loop policy on top of Transport.

## Transport interface — rules

The interface is intentionally THIN:

```kotlin
interface Transport {
  val state: StateFlow<TransportState>
  suspend fun startSession(sessionId: String, codec: VideoCodec)
  fun sendGlassesFrame(frame: GlassesVideoFrame)
  fun sendPhoneFrame(i420: ByteArray, width: Int, height: Int)
  suspend fun stopSession(reason: StopReason)
  fun videoFramesSent(): Long
}
```

**Do not** add a method here just because one transport happens to
need it. If a method is meaningful to ONLY one transport, it
belongs inside that implementation — not the interface.

**Split trigger**: if `Transport` starts accreting more than ~8
methods, or `TransportState` becomes a tagged-union of transport-
specific variants, that's the signal to revisit the
"dual-transport in one app" decision. See `..` plan's split-trigger
checklist.
