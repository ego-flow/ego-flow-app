# :core

Surface-agnostic foundations shared by `:app` and every `:transport-*`
module.

## What lives here

Today, just the **Transport seam**:

- `transport/api/Transport.kt` — the 5-method interface every outbound
  protocol (RTMP, slab-append, future ones) implements
- `transport/api/TransportTypes.kt` — `TransportId`, `VideoCodec`,
  `StopReason`, `EncodedFrame`, `TransportState`
- `transport/api/TransportFactory.kt` — factory + `TransportDeps`
  container; the registry pattern `:app`'s `Application` class wires
  up at boot

Phase 2 will pull in more (Wearables glue, MediaCodec wrapper,
foreground service, presentation queue, …) as SlabTransport reveals
what's actually shared. Until then, those utilities stay in `:app`
even though they're conceptually module-shareable — moving them
without a second consumer is premature.

## Dependency direction

```
:app ──▶ :transport-rtmp ──▶ :core
       └──▶ :transport-slab (Phase 2) ──▶ :core
```

`:core` depends on **no other workspace module**. Only platform
(android.*), Meta SDK (`mwdat-camera` for `VideoFrame` at the
Transport boundary), and kotlinx-coroutines-core.

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
  fun sendGlassesFrame(frame: VideoFrame)
  fun sendGlassesFrameCompressed(frame: VideoFrame)
  fun sendBitmapFrame(bitmap: Bitmap)
  suspend fun stopSession(reason: StopReason)
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
