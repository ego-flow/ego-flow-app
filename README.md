# EgoFlow

EgoFlow includes Android and iOS app samples, Android transport modules, and project notes for
camera access and streaming workflows.

## Repository Layout

| Path | Contents |
| --- | --- |
| `samples/CameraAccessAndroid` | Android Gradle project named `EgoFlow` with app, core, RTMP, WHIP, and HTTP transport modules |
| `samples/CameraAccess` | iOS Xcode project with DAT camera access, iPhone camera support, Gemini code, and OpenClaw bridge code |
| `docs/` | Korean project notes for scope, architecture, and Android RTMP details |
| `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` | License and upstream attribution records |

## Android Sample

`samples/CameraAccessAndroid` is a five-module Gradle project:

| Module | Role |
| --- | --- |
| `:app` | Compose UI, `StreamViewModel`, foreground `StreamingService`, settings, DAT/phone source selection |
| `:core` | `Transport` interface, `TransportFactory`, shared video encoder, YUV conversion |
| `:transport-rtmp` | RTMP/RTMPS publisher, backend HTTP client, auth/repository preference stores |
| `:transport-whip` | WHIP publish path using libwebrtc |
| `:transport-http` | Fragmented-MP4 chunk recorder/uploader |

The Android app exposes RTMP, WHIP, and HTTP choices in `SettingsScreen`, persists app settings in
`SettingsManager`, stores auth data through `AuthPrefs`, and stores the selected repository through
`RepoPrefs`.

### Android Build Requirements

The Android Gradle configuration uses:

- Android Gradle Plugin `8.6.0`
- Kotlin `2.1.20`
- `compileSdk 35`, `minSdk 31`, `targetSdk 34`
- Meta Wearables DAT Android SDK from GitHub Packages
- libwebrtc dependency `io.github.webrtc-sdk:android:125.6422.07`

For command-line builds, create `samples/CameraAccessAndroid/local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
github_token=YOUR_GITHUB_PAT
```

The GitHub token can also be provided as `GITHUB_TOKEN`. It is used by
`settings.gradle.kts` to access `maven.pkg.github.com/facebook/meta-wearables-dat-android`.

Release signing is optional local configuration. Copy
`samples/CameraAccessAndroid/keystore.properties.example` to
`samples/CameraAccessAndroid/keystore.properties` and fill in the signing values when building a
release artifact. Without that file, debug builds are unaffected and requested release builds fail.

### Android Build And Test

```bash
cd samples/CameraAccessAndroid
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
```

Transport-module JVM tests are present under:

- `transport-rtmp/src/test`
- `transport-whip/src/test`
- `transport-http/src/test`

### Android Backend Calls Implemented In Code

The Android backend client code is in
`samples/CameraAccessAndroid/transport-rtmp/src/main/java/io/egoflow/app/egoflow/EgoFlowBackendClient.kt`.
It constructs `/api/v1` requests for login, repository listing, stream registration, publish-ticket
creation, close-intent, and HTTP stream chunk upload.

## iOS Sample

`samples/CameraAccess` contains an Xcode project at
`samples/CameraAccess/CameraAccess.xcodeproj`. The Swift sources include:

- DAT registration and wearable camera views/view models
- iPhone camera support
- mock-device UI guarded by `canImport(MWDATMockDevice)`
- Gemini Live service and session view model code
- OpenClaw bridge and tool-call routing code
- in-app settings for Gemini/OpenClaw values

Local iOS secrets are configured from
`samples/CameraAccess/CameraAccess/Secrets.swift.example`. Copy it to
`samples/CameraAccess/CameraAccess/Secrets.swift`; that destination is ignored by
`samples/CameraAccess/.gitignore`.

## Repository Docs

Project notes live under `docs/`:

- `docs/01. project_guide.md`
- `docs/02. project_scope.md`
- `docs/03. project_architecture.md`
- `docs/04. project_rtmp_android.md`

## Attribution And License

This repository contains code derived from Meta Wearables DAT sample apps and from the VisionClaw
project. See:

- `THIRD_PARTY_NOTICES.md`
- `LICENSE`
- `NOTICE`
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
