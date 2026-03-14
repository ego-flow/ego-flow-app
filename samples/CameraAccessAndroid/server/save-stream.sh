#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-./recordings}"
STREAM_URL="${2:-rtmp://127.0.0.1:1935/live/glasses}"

mkdir -p "$OUT_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="$OUT_DIR/glasses-$TIMESTAMP.mp4"

ffmpeg \
  -i "$STREAM_URL" \
  -c copy \
  -movflags +faststart \
  "$OUT_FILE"
