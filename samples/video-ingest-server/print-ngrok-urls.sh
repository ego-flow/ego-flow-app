#!/usr/bin/env bash
set -euo pipefail

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required"
  exit 1
fi

JSON="$(curl -fsS http://127.0.0.1:4040/api/tunnels)"

if command -v jq >/dev/null 2>&1; then
  echo "$JSON" | jq -r '
    .tunnels[]
    | "\(.name)\t\(.public_url)"'
  exit 0
fi

echo "$JSON"
