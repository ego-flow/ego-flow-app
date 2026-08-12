# EgoFlow

EgoFlow currently supports Android. This repository contains the Android app, transport modules,
an unsupported iOS experimental source reference, and project notes for camera access and streaming
workflows. The Android app is the only supported `v0.0.1` client.

## Release Candidate

The project is preparing `v0.0.1`; it has not been publicly launched as a final app release. After
the release refs are finalized, use the following commands to inspect or build the candidate:

```bash
git clone https://github.com/ego-flow/ego-flow-app.git
cd ego-flow-app
git checkout v0.0.1
```

See [CHANGELOG.md](CHANGELOG.md) for release notes.

### Platform Availability

- **Android:** The only supported `v0.0.1` client. No public Google Play listing is available yet;
  build the candidate from source using the documented local credentials and signing setup.
- **iOS:** Not currently supported. iOS support is planned for a future release.

## Repository Layout

| Path | Contents |
| --- | --- |
| `samples/CameraAccessAndroid` | Android Gradle project named `EgoFlow` with app, core, RTMP, WHIP, and HTTP transport modules |
| `samples/CameraAccess` | Source-only iOS Gemini/OpenClaw experiment; excluded from supported builds and demonstrations |
| `docs/` | Korean project notes for scope, architecture, and Android RTMP details |
| `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md` | License and upstream attribution records |
| `sbom.cdx.json`, `SBOM.md` | Android release-runtime CycloneDX SBOM and its scope/limitations |

## Android Sample

`samples/CameraAccessAndroid` is a five-module Gradle project:

| Module | Role |
| --- | --- |
| `:app` | Compose UI, `StreamViewModel`, foreground `StreamingService`, settings, DAT/phone source selection |
| `:core` | `Transport` interface, `TransportFactory`, shared video encoder, YUV conversion |
| `:transport-rtmp` | RTMP/RTMPS publisher, backend HTTP client, auth/repository preference stores |
| `:transport-whip` | Dormant experimental WHIP publish implementation using libwebrtc; not selectable in `v0.0.1` |
| `:transport-http` | Fragmented-MP4 chunk recorder/uploader |

The supported `v0.0.1` recording UI exposes RTMP/RTMPS live streaming and HTTP chunk upload.
The `:transport-whip` implementation remains in the source tree as a dormant experimental module,
but it is not selectable in the shipping UI and is not part of the `v0.0.1` hardware acceptance or
contest demonstration. `SettingsManager` persists the active RTMP/HTTP choice, `AuthPrefs` stores
auth data, and `RepoPrefs` stores the selected repository.

### Android Build Requirements

The Android Gradle configuration uses:

- Android Gradle Plugin `8.6.0`
- Kotlin `2.1.20`
- `compileSdk 35`, `minSdk 31`
- Meta Wearables DAT Android SDK from GitHub Packages
- libwebrtc dependency `io.github.webrtc-sdk:android:125.6422.07`

For command-line builds, create `samples/CameraAccessAndroid/local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
github_token=YOUR_GITHUB_PAT
mwdat_application_id=YOUR_META_WEARABLES_APP_ID
mwdat_client_token=YOUR_META_WEARABLES_CLIENT_TOKEN
```

The GitHub token can also be provided as `GITHUB_TOKEN`. It is used by
`settings.gradle.kts` to access `maven.pkg.github.com/facebook/meta-wearables-dat-android`.
The Meta Wearables credentials can also be provided as `MWDAT_APPLICATION_ID` and
`MWDAT_CLIENT_TOKEN`; they are injected into the Android manifest at build time and should not be
committed.

Release signing is optional local configuration. Copy
`samples/CameraAccessAndroid/keystore.properties.example` to
`samples/CameraAccessAndroid/keystore.properties` and fill in the signing values when building a
release artifact. Without that file, debug builds are unaffected and requested release builds fail.

### Getting Started With Meta AI Glasses

EgoFlow uses Meta's Wearables Device Access Toolkit (DAT) to register the Android app with the
Meta AI app and request access to a supported pair of glasses. Complete the following preparation
on the Android phone before starting a glasses-based recording:

1. Install the Meta AI app on an Android 12 (API 31) or newer phone supported by EgoFlow.
2. For a registered Integration or release-channel build, sign in with a Meta account that can
   access that Integration or release channel. Local Developer Mode builds use the separate `0`/`0`
   path documented below.
3. Pair the supported glasses in the Meta AI app, confirm that they are connected, and keep
   Bluetooth and Internet access enabled.
4. If the Integration is distributed through a release channel, accept the tester invitation and
   select that channel in the Meta AI app before opening EgoFlow.

Meta's registration and device-permission flows are separate. In EgoFlow, start registration and
complete the Meta registration dialog presented by the installed DAT version. In EgoFlow's pinned
DAT 0.8.0, that dialog opens in place rather than switching to the Meta AI app. After registration
succeeds, the first `Start streaming` attempt checks and, when needed, requests DAT Camera
permission for the glasses camera. Do not start a stream until EgoFlow reports that the glasses are
registered and available. See Meta's
[DAT Android registration and permissions guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/permissions-registration/SKILL.md)
for the upstream model.

On first launch, EgoFlow also requests Android runtime permissions:

- Camera, Microphone, and Bluetooth are required by the current app permission gate.
- Notifications are recommended but optional when Android 13 or newer requests that runtime
  permission.
- The `v0.0.1` proof-of-concept and hardware acceptance keep the `Stream audio` setting off.
  Granting the Android Microphone permission does not enable capture or change that test scope.

#### Register A Source-Built APK

A source-built APK used with a registered Integration or release channel must use Android identity
values that match the same Meta Wearables Developer Center Integration:

- Android package name: `io.egoflow.app` (the Developer Center `Package` field)
- the APK signing certificate represented by the Developer Center `App signature` field
- `mwdat_application_id` / `MWDAT_APPLICATION_ID`
- `mwdat_client_token` / `MWDAT_CLIENT_TOKEN`

Build the exact release artifact that will be installed. The signing check also requires `xxd` and
OpenSSL plus the Android SDK's
[`apksigner`](https://developer.android.com/tools/apksigner#options-verify); fresh SDK installations
do not always add `apksigner` to `PATH`. Point `APKSIGNER` at the executable under the installed
Build Tools version, then inspect the embedded signer certificate:

```bash
cd samples/CameraAccessAndroid
if ! ./gradlew assembleRelease; then
  printf 'Release APK build failed.\n' >&2
  exit 1
fi
APK_PATH="app/build/outputs/apk/release/app-release.apk"
APKSIGNER="<path-to-android-sdk>/build-tools/<version>/apksigner"
if [ ! -x "$APKSIGNER" ] || ! command -v xxd >/dev/null || \
   ! command -v openssl >/dev/null; then
  printf 'apksigner, xxd, and OpenSSL are required.\n' >&2
  exit 1
fi
"$APKSIGNER" verify --print-certs "$APK_PATH"
```

The current Developer Center `App signature` value is the signer certificate's SHA-256 digest,
encoded as unpadded Base64URL. For the single-signer `v0.0.1` APK, derive it from the
`Signer #1 certificate SHA-256 digest` line without exposing a keystore or password:

```bash
CERT_SHA256_HEX="$(
  "$APKSIGNER" verify --print-certs "$APK_PATH" |
    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p'
)"
if [[ ! "$CERT_SHA256_HEX" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'Expected exactly one SHA-256 signer digest.\n' >&2
  exit 1
fi
APP_SIGNATURE="$(
  printf '%s' "$CERT_SHA256_HEX" |
    xxd -r -p |
    openssl base64 -A |
    tr '+/' '-_' |
    tr -d '='
)"
if [[ ! "$APP_SIGNATURE" =~ ^[A-Za-z0-9_-]{43}$ ]]; then
  printf 'App signature conversion failed.\n' >&2
  exit 1
fi
printf '%s\n' "$APP_SIGNATURE"
```

Register that value in the Android Integration's `App signature` field before installing the APK.
If `apksigner` reports multiple signers, do not guess which digest to register; follow the current
Developer Center guidance for that artifact. Verify that `Package`, `App signature`,
`Application ID`, and the client token all belong to the same Integration. A debug keystore, a
release keystore, and a lab-specific keystore produce different app signatures; building with a
different keystore requires the new signing certificate to be registered before that APK can use
the Integration.

For a cold installation, remove any existing `io.egoflow.app` package before installing the
verified release artifact. Uninstalling deletes that app's local settings and login data:

```bash
if adb shell pm path io.egoflow.app >/dev/null 2>&1; then
  adb uninstall io.egoflow.app || exit 1
fi
adb install "$APK_PATH"
```

Use `adb install -r "$APK_PATH"` only when the installed app and the new APK are known to use the
same signing certificate and preserving local app data is intentional. Otherwise Android rejects
the update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

When using a release channel, the Meta AI login account must also be an approved tester for the
selected channel. Follow Meta's
[Android Integration](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/),
[project registration](https://wearables.developer.meta.com/docs/develop/dat/manage-projects/), and
[release-channel](https://wearables.developer.meta.com/docs/develop/dat/set-up-release-channels)
guides for the current Developer Center workflow.

Developer Mode builds that intentionally use Meta's placeholder DAT credentials follow the
development registration path instead. For that local development path, set both placeholder
credentials to `0` in the gitignored local properties or equivalent environment variables:

```properties
mwdat_application_id=0
mwdat_client_token=0
```

Developer Mode is not a substitute for verifying the registered certificate and release-channel
configuration of a release APK.

Do not commit real application IDs, client tokens, certificate digests, keystore paths, or keystore
passwords. Keep them in the gitignored local files or environment variables described above.

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

## iOS Sample (Planned Support)

EgoFlow does not currently support iOS. The sample below is retained as reference material for
future development and is not part of the `v0.0.1` supported release. It is not built, packaged,
or used in the contest demonstration, and no Gemini or OpenClaw model is called by the submitted
Core artifacts.

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
- `docs/05. licensing-and-provenance.md`
- `docs/decisions/0001-v0.0.1-android-transport-scope.md`
- `docs/provenance-inventory.csv`

Community and operations policies:

- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security](SECURITY.md)
- [Support](SUPPORT.md)
- [Governance](GOVERNANCE.md)
- [SBOM scope and generation](SBOM.md)

## Attribution And License

This repository contains code derived from Meta Wearables DAT sample apps and from the VisionClaw
project. The reviewed Meta terms do not authorize MIT or OSI relicensing of that derived source.
This app repository is therefore published as mixed, source-available material under the applicable
upstream terms and is excluded from EgoFlow's MIT/OSI claim. The MIT components are
`ego-flow-server` and `ego-flow-py`. Do not treat this entire tree as open source until the derived
code is replaced or separate written permission is obtained. See:

- `THIRD_PARTY_NOTICES.md`
- `LICENSE`
- `NOTICE`
- `CONTRIBUTING.md`
- `CODE_OF_CONDUCT.md`
- `docs/05. licensing-and-provenance.md`
