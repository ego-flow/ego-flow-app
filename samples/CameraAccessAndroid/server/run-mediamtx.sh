#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker run --rm -it \
  --name egoflow-mediamtx \
  -p 1935:1935 \
  -p 8888:8888 \
  -v "$SCRIPT_DIR/mediamtx.yml:/mediamtx.yml:ro" \
  bluenviron/mediamtx:latest
