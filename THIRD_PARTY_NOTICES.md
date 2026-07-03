# Third-Party Notices

This repository includes sample code derived from upstream Meta Wearables sample applications and from the VisionClaw repository.

## Meta / VisionClaw derived sample code

The following directories contain code copied from or adapted from upstream sample applications:

- `samples/CameraAccess`
- `samples/CameraAccessAndroid`

The upstream sources to confirm and record are:

- VisionClaw repository: `https://github.com/Intent-Lab/VisionClaw`
- VisionClaw commit: `917a05f79c4cbf8afff711b22f1057ff262eb6fa`
- Meta iOS repository: `https://github.com/facebook/meta-wearables-dat-ios`
- Meta iOS sample path: `samples/CameraAccess`
- Meta iOS commit: `28a81e14735c563bbf1504a76189b766c2a04c4e`
- Meta Android repository: `https://github.com/facebook/meta-wearables-dat-android`
- Meta Android sample path: `samples/CameraAccess`
- Meta Android commit: `82af01b2b9bf9f76b596be671f9b883f568e5286`

Portions of the above directories were modified in this repository for EgoFlow-specific behavior, including app configuration changes, UI changes, and streaming-related changes.

Original copyright and license headers from Meta have been retained in copied source files where applicable. Additional "modified in this repository" notices have been added to clarify that the files are adapted versions.

## Applicable license / terms

Based on the files currently present in this repository, the Meta-derived sample code appears to refer to the following upstream terms:

- Meta Wearables Developer Terms
- Meta Wearables Acceptable Use Policy

See:

- `LICENSE`
- `NOTICE`
- Meta Wearables Developer Terms: `https://wearables.developer.meta.com/terms`
- Meta Wearables Acceptable Use Policy: `https://wearables.developer.meta.com/acceptable-use-policy`
- VisionClaw repository license: `https://github.com/Intent-Lab/VisionClaw/blob/main/LICENSE`
- Meta Android NOTICE: `https://github.com/facebook/meta-wearables-dat-android/blob/main/NOTICE`

The repository and commit metadata recorded above should be kept in sync with the actual upstream sources used.

## Third-party notices preserved from upstream

This repository also includes an upstream-style `NOTICE` file with attributions for third-party software that may apply to portions of the Meta-derived materials. Preserve any notices that apply to the files or binaries you redistribute.

## Gradle wrapper

The Android sample includes Gradle wrapper scripts:

- `samples/CameraAccessAndroid/gradlew`
- `samples/CameraAccessAndroid/gradlew.bat`

Those files carry their own Apache 2.0 notices and should be kept when redistributed.

## Distribution checklist

Before publishing or redistributing this repository or a derivative:

- confirm each upstream repository URL and commit hash
- confirm that the applicable Meta terms allow your intended redistribution
- keep the repository URLs and commit hashes in this file in sync with the actual upstream sources used
- verify which upstream notices and license texts apply to shipped files and binaries
- verify that any included assets, keystores, recordings, or sample media are safe to redistribute
