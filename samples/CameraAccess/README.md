# EgoFlow — iOS Reference App

An iOS DAT sample that connects to Meta AI (DAT) glasses (or the iPhone camera) for live preview
and photo capture, wired to a **Gemini Live** bidirectional audio/vision assistant and an
**OpenClaw** agentic tool-calling bridge. Adapted from Meta's `CameraAccess` DAT sample and
VisionClaw.

> **⚠️ Work in progress.** This iOS app is still under active development. It is a **reference**
> implementation and is **not** the server-integrated EgoFlow client — it does **not** implement
> the RTMP / WHIP / HTTP-chunk transports or the EgoFlow backend flow. The server-integrated client
> is the Android app in [`samples/CameraAccessAndroid`](../CameraAccessAndroid/README.md). Expect
> incomplete features and breaking changes.

> **v0.0.1 release scope:** This directory is retained as mixed-scope source reference only. It is
> excluded from supported release builds and the contest demonstration. The submitted EgoFlow Core
> does not load or call Gemini, OpenClaw, or another runtime AI model.

## What it does

- Connects to Meta AI glasses via the Device Access Toolkit (DAT) for live preview and photo capture
- Offers an iPhone-camera mode as an alternative source, plus a mock device for hardware-free testing
- Streams camera frames + audio to **Gemini Live** (Google's bidirectional WebSocket live API) for a
  real-time voice + vision assistant
- Delegates agentic actions to an **OpenClaw** gateway (an OpenAI-compatible endpoint on your Mac)
  via a single tool-calling bridge

## Attribution

This directory includes code copied from or adapted from the following upstream sources:

- VisionClaw repository: `https://github.com/Intent-Lab/VisionClaw` at `917a05f79c4cbf8afff711b22f1057ff262eb6fa`
- Meta iOS repository: `https://github.com/facebook/meta-wearables-dat-ios` at `28a81e14735c563bbf1504a76189b766c2a04c4e`
- Upstream sample path: `samples/CameraAccess`

Original copyright and license notices from Meta have been retained in source files where applicable.

## Local modifications

This repository modifies the upstream sample for EgoFlow-specific behavior, including:

- Gemini Live voice/vision assistant integration
- OpenClaw agentic tool-calling bridge
- app configuration, UI, and flow changes

## Setup

1. Open `samples/CameraAccess/CameraAccess.xcodeproj` in Xcode (a physical iPhone is recommended
   for realistic testing).
2. Copy `CameraAccess/Secrets.swift.example` to `CameraAccess/Secrets.swift` (gitignored) and fill
   in the config keys:
   - `geminiAPIKey` — required for the assistant ([Google AI Studio](https://aistudio.google.com/apikey))
   - `openClawHost` — optional, e.g. `http://YOUR_MAC_HOSTNAME.local`
   - `openClawPort` — optional, default `18789`
   - `openClawHookToken`, `openClawGatewayToken` — optional

   These values can also be overridden from the in-app Settings screen.

## License / terms

The copied and adapted materials in this directory remain subject to the original upstream license or developer terms that apply to them.

See:

- `/THIRD_PARTY_NOTICES.md`
- `/LICENSE`
- `/NOTICE`
- `https://wearables.developer.meta.com/terms`
- `https://wearables.developer.meta.com/acceptable-use-policy`
- `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
- `https://github.com/facebook/meta-wearables-dat-ios`

Keep the repository URLs and commit hashes in this file in sync with the actual upstream sources used.
