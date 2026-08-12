# EgoFlow App SBOM

The canonical machine-readable app SBOM is [`sbom.cdx.json`](sbom.cdx.json), generated as CycloneDX 1.6 JSON from the resolved Android `releaseRuntimeClasspath` graph.

## Generate

Requirements are the same as the supported Android release build: JDK 17, the checked-in Gradle wrapper, Android SDK configuration, GitHub Packages access for the Meta Wearables dependencies, and the Meta app credentials described in the root README.

```bash
./tools/generate-sbom.sh
```

The script uses the official CycloneDX Gradle plugin 3.3.0 through [`tools/cyclonedx.init.gradle.kts`](tools/cyclonedx.init.gradle.kts). The plugin is injected only for SBOM generation and does not change the normal Gradle build configuration.

The Android project commits Gradle dependency lock state for every module. When intentionally changing dependencies, regenerate all lockfiles before regenerating the SBOM:

```bash
cd samples/CameraAccessAndroid
./gradlew --write-locks \
  :app:dependencies \
  :core:dependencies \
  :transport-http:dependencies \
  :transport-rtmp:dependencies \
  :transport-whip:dependencies
cd ../..
./tools/generate-sbom.sh
```

## Included scope

- the Android `:app` release-runtime component and its direct and transitive Gradle dependencies;
- the local `:core`, `:transport-http`, `:transport-rtmp`, and `:transport-whip` modules;
- Meta Wearables Device Access Toolkit artifacts, including `mwdat-core` and `mwdat-camera`;
- package versions, package URLs, available license metadata, external references, and the dependency graph reported by Gradle and upstream package metadata.

## Signed APK native-binary reconciliation

The package SBOM is supplemented by [`docs/android-native-binary-inventory.csv`](docs/android-native-binary-inventory.csv). The inventory was generated from the signed v0.0.1 release APK with [`tools/generate-android-binary-inventory.sh`](tools/generate-android-binary-inventory.sh) and records the APK entry, binary SHA-256, exact Maven coordinate, matching AAR entry, and AAR SHA-256.

- The reconciled release APK SHA-256 is `4f48f8f07d0b77210ed48b150e04c66864d653885b3a12cd184c9e16d75a850b`.
- 308 of 308 APK `.so` entries matched an AAR entry by both ABI-relative path and SHA-256.
- The APK contains 77 native library names for each of four ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- The matched binaries came from seven resolved coordinates: AndroidX Camera Core 1.4.1 (8 entries), AndroidX DataStore Core Android 1.2.1 (4), AndroidX Graphics Path 1.0.1 (4), fbjni 0.7.0 (8), Meta `mwdat-core` 0.8.0 (88), Meta `mwdat-camera` 0.8.0 (192), and WebRTC SDK Android 125.6422.07 (4).
- The inventory SHA-256 is `bc5f7666d870de80f379c487fad3e6eb6d35084659375f5e7678367b34a5daf3`.

This reconciliation identifies which resolved AAR supplied each packaged native entry. It does not independently assign a license to embedded subcomponents or override the publisher's license and notice files. The applicable package-level evidence and remaining Meta redistribution limitation are recorded in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

The four local Android modules intentionally have no standard license identifier in the generated SBOM. The app is a mixed/source-available repository and is outside EgoFlow's MIT/OSI claim; assigning MIT to those components would contradict [`docs/05. licensing-and-provenance.md`](docs/05.%20licensing-and-provenance.md).

## Excluded scope and known gaps

- The source-only iOS Gemini/OpenClaw experiment is excluded because it is not part of the supported v0.0.1 build or contest demonstration.
- Server, Python package, and container components have separate SBOMs and are not merged into this app document.
- Source-file ownership and copied/modified upstream code are tracked in [`docs/provenance-inventory.csv`](docs/provenance-inventory.csv), not inferred from package metadata.
- Native libraries embedded inside third-party AARs may not appear as independently named components when their publisher metadata does not expose them. The supplementary inventory attributes every signed-APK native entry to its exact AAR, but transitive native subcomponents remain governed by publisher metadata and notices rather than inferred relicensing.
- The SBOM records the release-runtime graph, while the committed lockfiles cover additional debug, test, Android tooling, and plugin configurations. Those build-time-only entries are intentionally absent from the runtime SBOM.
- A component with missing license metadata is unresolved, not license-free. Review its upstream terms before redistribution.
