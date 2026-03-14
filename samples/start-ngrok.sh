#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VIDEO_DIR="$SCRIPT_DIR/video-ingest-server"
DEFAULT_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/ngrok/ngrok.yml"
TEMP_CONFIG="$(mktemp)"
NGROK_API="${NGROK_API:-http://127.0.0.1:4040/api/tunnels}"

cleanup() {
  rm -f "$TEMP_CONFIG"
}
trap cleanup EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd ngrok
require_cmd python3
require_cmd curl

cat >"$TEMP_CONFIG" <<'EOF'
version: 2
tunnels:
  video-ingest-rtmp:
    proto: tcp
    addr: 1935
  video-ingest-viewer:
    proto: http
    addr: 8088
  openclaw-gateway:
    proto: http
    addr: 127.0.0.1:18789
EOF

if [[ -f "$DEFAULT_CONFIG" ]]; then
  CONFIG_ARGS=(--config "$DEFAULT_CONFIG" --config "$TEMP_CONFIG")
  echo "ngrok config: $DEFAULT_CONFIG + generated config"
else
  CONFIG_ARGS=(--config "$TEMP_CONFIG")
  echo "ngrok config: generated config"
fi

echo "Starting ngrok tunnels for RTMP, viewer, and OpenClaw..."
echo
echo "Expected local services:"
echo "  RTMP ingest      : tcp://127.0.0.1:1935"
echo "  Web viewer       : http://127.0.0.1:8088"
echo "  OpenClaw gateway : http://127.0.0.1:18789"
echo

if ! OUTPUT="$(ngrok start --all "${CONFIG_ARGS[@]}" 2>&1)"; then
  echo "$OUTPUT"

  if grep -q 'ERR_NGROK_4018' <<<"$OUTPUT"; then
    echo
    echo "ngrok account authentication is not configured."
    echo "Run this once with your ngrok authtoken:"
    echo "  ngrok config add-authtoken <YOUR_AUTHTOKEN>"
    echo
    echo "Get the token from:"
    echo "  https://dashboard.ngrok.com/get-started/your-authtoken"
  elif grep -q 'already online\|session limit\|ERR_NGROK_108' <<<"$OUTPUT"; then
    echo
    echo "Another ngrok session is already running."
    echo "Stop the old process first, then rerun this script."
    echo "Examples:"
    echo "  pkill -f 'ngrok start --all'"
    echo "  pkill -f 'ngrok http'"
  fi

  exit 1
fi

echo "$OUTPUT"
echo

sleep 2

if curl --max-time 2 -fsS "$NGROK_API" >/dev/null 2>&1; then
  echo "Discovered public URLs:"
  curl --max-time 2 -fsS "$NGROK_API" | python3 - <<'PY'
import json
import sys

payload = json.load(sys.stdin)
tunnels = payload.get("tunnels", [])

labels = {
    "1935": "RTMP ingest",
    "8088": "Viewer",
    "127.0.0.1:18789": "OpenClaw gateway",
}

openclaw_url = None
for tunnel in tunnels:
    config = tunnel.get("config", {})
    addr = str(config.get("addr", ""))
    public_url = tunnel.get("public_url", "")
    label = labels.get(addr, labels.get(addr.split(":")[-1], addr))
    print(f"  {label:<16} {public_url}")
    if addr == "127.0.0.1:18789":
        openclaw_url = public_url

if openclaw_url:
    print()
    print("Use these values in Secrets.kt or the app Settings:")
    print(f'  openClawHost = "{openclaw_url}"')
    print('  openClawPort = 443')
    print()
    print("Gateway token:")
    print("  openclaw config get gateway.auth.token")
    print()
    print("Hook token:")
    print("  Not required for the current Android app flow.")
else:
    print()
    print("OpenClaw tunnel was not found in ngrok's API output.", file=sys.stderr)
PY
else
  echo "ngrok API is not reachable at $NGROK_API yet."
  echo "Open http://127.0.0.1:4040 to inspect the tunnels manually."
fi
echo
echo "Run from $VIDEO_DIR if you also want to inspect MediaMTX logs:"
echo "  docker compose logs -f"
