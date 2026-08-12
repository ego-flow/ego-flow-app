# Changelog

All notable changes to EgoFlow App are documented in this file.

## v0.0.1

### Added

- Supported Android streaming over RTMP/RTMPS and HTTP chunk upload.
- A dormant, experimental WHIP module retained for future qualification; it is not selectable in
  the `v0.0.1` UI or included in release acceptance.
- Meta Wearables DAT camera integration with a phone-camera fallback.
- EgoFlow backend login, repository selection, and recording-session lifecycle support.
- Local release signing configuration through ignored property files.

### Changed

- Release documentation and repository layout were prepared for the first public release.
- Clarified that Android is the only supported client and that the retained iOS Gemini/OpenClaw
  source is excluded from release builds and the contest demonstration.
- Clarified that the app repository is mixed, source-available material rather than part of
  EgoFlow's MIT/OSI claim.

### Fixed

- Preserved RTMP playback across glasses-stream resolution changes by refreshing codec state and
  maintaining a monotonic output timeline.
- Increased only the HTTP chunk-response read timeout from 10 to 20 seconds for slower remote
  uploads. Resumable reconciliation and terminal-failure UI remain post-v0.0.1 work.
