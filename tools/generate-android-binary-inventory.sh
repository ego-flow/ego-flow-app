#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
android_root="$repo_root/samples/CameraAccessAndroid"
apk="$android_root/app/build/outputs/apk/release/app-release.apk"
output="$repo_root/docs/android-native-binary-inventory.csv"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

for required_command in awk cp cut find mktemp paste rmdir sed shasum sort tr unzip wc zipinfo; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Required command is unavailable: $required_command" >&2
    exit 1
  fi
done

if [[ ! -f "$apk" ]]; then
  echo "Release APK is unavailable: $apk" >&2
  echo "Build it with ./gradlew :app:assembleRelease before generating the inventory." >&2
  exit 1
fi

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/egoflow-android-binaries.XXXXXX")"
cleanup() {
  find "$temp_dir" -type f -delete
  rmdir "$temp_dir"
}
trap cleanup EXIT

apk_entries="$temp_dir/apk-entries.txt"
aar_candidates="$temp_dir/aar-candidates.tsv"
generated_csv="$temp_dir/android-native-binary-inventory.csv"

zipinfo -1 "$apk" \
  | awk -F/ '$1 == "lib" && NF == 3 && $3 ~ /[.]so$/ { print }' \
  | LC_ALL=C sort > "$apk_entries"

apk_entry_count="$(wc -l < "$apk_entries" | tr -d ' ')"
if [[ "$apk_entry_count" != "308" ]]; then
  echo "Expected 308 native APK entries, found $apk_entry_count" >&2
  exit 1
fi

actual_abis="$(cut -d/ -f2 "$apk_entries" | LC_ALL=C sort -u | paste -sd, -)"
expected_abis="arm64-v8a,armeabi-v7a,x86,x86_64"
if [[ "$actual_abis" != "$expected_abis" ]]; then
  echo "Expected APK ABIs $expected_abis, found $actual_abis" >&2
  exit 1
fi

unique_library_count="$(cut -d/ -f3 "$apk_entries" | LC_ALL=C sort -u | wc -l | tr -d ' ')"
if [[ "$unique_library_count" != "77" ]]; then
  echo "Expected 77 unique native library names, found $unique_library_count" >&2
  exit 1
fi

while IFS='|' read -r coordinate group artifact version; do
  cache_dir="$gradle_cache/$group/$artifact/$version"
  if [[ ! -d "$cache_dir" ]]; then
    echo "Gradle cache entry is unavailable for $coordinate: $cache_dir" >&2
    exit 1
  fi

  aar_list="$temp_dir/$group-$artifact-$version-aars.txt"
  find "$cache_dir" -type f -name '*.aar' -print | LC_ALL=C sort > "$aar_list"
  aar_count="$(wc -l < "$aar_list" | tr -d ' ')"
  if [[ "$aar_count" != "1" ]]; then
    echo "Expected one cached AAR for $coordinate, found $aar_count" >&2
    exit 1
  fi

  aar="$(sed -n '1p' "$aar_list")"
  aar_sha256="$(shasum -a 256 "$aar" | awk '{ print $1 }')"
  aar_entries="$temp_dir/$group-$artifact-$version-entries.txt"
  zipinfo -1 "$aar" \
    | awk -F/ '$1 == "jni" && NF == 3 && $3 ~ /[.]so$/ { print }' \
    | LC_ALL=C sort > "$aar_entries"

  while IFS= read -r aar_entry; do
    binary_sha256="$(unzip -p "$aar" "$aar_entry" | shasum -a 256 | awk '{ print $1 }')"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "${aar_entry#jni/}" \
      "$binary_sha256" \
      "$coordinate" \
      "$aar_entry" \
      "$aar_sha256" >> "$aar_candidates"
  done < "$aar_entries"
done <<'COORDINATES'
androidx.camera:camera-core:1.4.1|androidx.camera|camera-core|1.4.1
androidx.datastore:datastore-core-android:1.2.1|androidx.datastore|datastore-core-android|1.2.1
androidx.graphics:graphics-path:1.0.1|androidx.graphics|graphics-path|1.0.1
com.facebook.fbjni:fbjni:0.7.0|com.facebook.fbjni|fbjni|0.7.0
com.meta.wearable:mwdat-core:0.8.0|com.meta.wearable|mwdat-core|0.8.0
com.meta.wearable:mwdat-camera:0.8.0|com.meta.wearable|mwdat-camera|0.8.0
io.github.webrtc-sdk:android:125.6422.07|io.github.webrtc-sdk|android|125.6422.07
COORDINATES

candidate_count="$(wc -l < "$aar_candidates" | tr -d ' ')"
if [[ "$candidate_count" != "$apk_entry_count" ]]; then
  echo "Expected $apk_entry_count native AAR candidates, found $candidate_count" >&2
  exit 1
fi

printf 'apk_entry,sha256,maven_coordinate,aar_entry,aar_sha256\n' > "$generated_csv"

while IFS= read -r apk_entry; do
  binary_sha256="$(unzip -p "$apk" "$apk_entry" | shasum -a 256 | awk '{ print $1 }')"
  suffix="${apk_entry#lib/}"
  matches="$temp_dir/matches.tsv"
  awk -F '\t' -v suffix="$suffix" -v sha256="$binary_sha256" \
    '$1 == suffix && $2 == sha256 { print }' "$aar_candidates" > "$matches"
  match_count="$(wc -l < "$matches" | tr -d ' ')"

  if [[ "$match_count" != "1" ]]; then
    echo "Expected one exact AAR match for $apk_entry ($binary_sha256), found $match_count" >&2
    exit 1
  fi

  IFS=$'\t' read -r _ _ coordinate aar_entry aar_sha256 < "$matches"
  printf '"%s","%s","%s","%s","%s"\n' \
    "$apk_entry" \
    "$binary_sha256" \
    "$coordinate" \
    "$aar_entry" \
    "$aar_sha256" >> "$generated_csv"
done < "$apk_entries"

generated_entry_count="$(( $(wc -l < "$generated_csv") - 1 ))"
if [[ "$generated_entry_count" != "$apk_entry_count" ]]; then
  echo "Expected $apk_entry_count generated rows, found $generated_entry_count" >&2
  exit 1
fi

cp "$generated_csv" "$output"
echo "Wrote $output"
echo "Mapped $generated_entry_count APK native entries across 4 ABIs and 7 AAR coordinates."
