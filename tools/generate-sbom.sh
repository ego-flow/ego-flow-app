#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
android_root="$repo_root/samples/CameraAccessAndroid"
generated_bom="$android_root/app/build/reports/cyclonedx-direct/bom.json"

cd "$android_root"
./gradlew --no-daemon --init-script "$script_dir/cyclonedx.init.gradle.kts" :app:cyclonedxDirectBom

if [[ ! -f "$generated_bom" ]]; then
  echo "CycloneDX output was not generated at $generated_bom" >&2
  exit 1
fi

cp "$generated_bom" "$repo_root/sbom.cdx.json"
echo "Wrote $repo_root/sbom.cdx.json"
