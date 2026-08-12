# Contributing to EgoFlow App

Thank you for helping improve EgoFlow App. This repository is a mixed, source-available app tree;
it is not included in EgoFlow's MIT or OSI-open-source claim. Read [README.md](README.md),
[LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and
[the provenance review](docs/05.%20licensing-and-provenance.md) before proposing a change.

## Contribution scope

Issues, documentation fixes, reproducible bug reports, and focused improvements are welcome. Code
changes require a maintainer-approved Issue before implementation because files in this repository
may be EgoFlow-authored, copied or adapted from Meta/VisionClaw samples, or governed by other
third-party terms.

Do not submit a code pull request until a maintainer has confirmed its intended files and scope.
Approval to investigate an Issue is not a promise that a resulting patch can be accepted.

## Provenance statement

Every code, asset, or build-file pull request must include:

- the paths changed;
- whether the contribution is entirely your original work;
- every source, repository, commit, document, or generated scaffold used;
- the applicable license or terms for each non-original input;
- whether any code, UI, asset, or test fixture was copied or adapted;
- which copyright and attribution notices were preserved; and
- confirmation that no confidential source, private SDK material, credential, or personal recording
  is included.

A similar filename or public GitHub URL is not sufficient evidence of redistribution rights. Do not
remove or rewrite Meta or other upstream notices merely to make a file appear first-party.

## Pull requests

1. Open an Issue describing the change and wait for a maintainer to approve the scope.
2. Fork the repository and create a focused branch from `main`.
3. Add or update tests for behavior changes.
4. Update public documentation when supported behavior, dependencies, data handling, or terms change.
5. Run the checks below without committing credentials or signing material.
6. Include the provenance statement and user-visible impact in the pull request.

For Android changes:

```bash
cd samples/CameraAccessAndroid
./gradlew test
./gradlew assembleDebug
```

The Android build requires local Meta/GitHub credentials documented in the README. Keep
`local.properties`, `keystore.properties`, tokens, client secrets, signing keys, and generated
artifacts out of Git.

The iOS Gemini/OpenClaw tree is reference source only and is excluded from supported builds and the
contest demonstration. Changes in that directory receive no release or compatibility guarantee.

## Automated gates

`.github/workflows/ci.yml` runs only for `main` and the release branch `v0.0.1`;
the preserved `extentos` branch is intentionally outside this workflow. CI builds the debug Android
artifact and runs available tests without release signing material, scans the checked-out tree with
the checksum-verified Gitleaks CLI, validates the committed CycloneDX 1.6 SBOM, and reviews new
runtime dependencies in pull requests. A failed secret or dependency check must be fixed or resolved
through a narrowly documented review; do not add broad path exclusions or real credentials.

## Issues and sensitive data

Use [GitHub Issues](https://github.com/ego-flow/ego-flow-app/issues) for reproducible bugs and
feature requests. Do not include access tokens, Meta credentials, server passwords, private
repository names, personal recordings, raw frames, transcripts, or private logs.

Report vulnerabilities through the private process in [SECURITY.md](SECURITY.md). Report conduct
concerns through the private contact in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Contribution terms

There is no app-wide MIT license and no Meta CLA requirement for EgoFlow contributors. A contribution
can be accepted only when the contributor has the right to submit it under the terms applicable to
the target files and the maintainers can preserve all required notices. Maintainers may decline or
isolate a contribution when its rights cannot be established.
