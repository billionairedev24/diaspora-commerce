#!/usr/bin/env bash
# hey — HTTP load generator (https://github.com/rakyll/hey)
#
# Usage:
#   tools/perf/run-hey.sh
#   tools/perf/run-hey.sh http://127.0.0.1:8201/v1/search?q=rice&market=US
#
# Requires: hey (go install github.com/rakyll/hey@latest)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/env.sh"

REQUESTS="${PERF_AB_REQUESTS:-2000}"
CONCURRENCY="${PERF_AB_CONCURRENCY:-25}"
DURATION="${HEY_DURATION:-}"

if ! command -v hey >/dev/null 2>&1; then
  echo "hey not found. Install: go install github.com/rakyll/hey@latest" >&2
  exit 2
fi

run_one() {
  local url="$1"
  local label="$2"
  echo ""
  echo "==> hey $label"
  echo "    $url"
  local args=(-n "$REQUESTS" -c "$CONCURRENCY" -m GET)
  if [[ -n "$PERF_BEARER_TOKEN" ]]; then
    args+=(-H "Authorization: Bearer $PERF_BEARER_TOKEN")
  fi
  if [[ -n "$DURATION" ]]; then
    args=(-z "$DURATION" -c "$CONCURRENCY" -m GET)
  fi
  hey "${args[@]}" "$url"
}

if [[ $# -gt 0 ]]; then
  run_one "$1" "custom"
  exit 0
fi

Q_ENC="$(python3 -c "import urllib.parse; print(urllib.parse.quote('${PERF_SEARCH_Q}'))")"
run_one "${GATEWAY_BFF_URL}/healthz" "gateway healthz"
run_one "${SEARCH_URL}/v1/search?q=${Q_ENC}&market=${PERF_MARKET}&limit=24" "search"
