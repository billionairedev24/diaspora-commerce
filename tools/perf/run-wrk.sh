#!/usr/bin/env bash
# wrk — HTTP benchmarking tool (https://github.com/wg/wrk)
#
# Usage:
#   tools/perf/run-wrk.sh
#   tools/perf/run-wrk.sh http://127.0.0.1:8200/healthz
#
# Requires: wrk (brew install wrk)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/env.sh"

THREADS="${WRK_THREADS:-4}"
CONNECTIONS="${WRK_CONNECTIONS:-$PERF_AB_CONCURRENCY}"
DURATION="${WRK_DURATION:-30s}"

if ! command -v wrk >/dev/null 2>&1; then
  echo "wrk not found. Install: brew install wrk" >&2
  exit 2
fi

run_one() {
  local url="$1"
  local label="$2"
  echo ""
  echo "==> wrk -t$THREADS -c$CONNECTIONS -d$DURATION $label"
  echo "    $url"
  wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" "$url"
}

if [[ $# -gt 0 ]]; then
  run_one "$1" "custom"
  exit 0
fi

Q_ENC="$(python3 -c "import urllib.parse; print(urllib.parse.quote('${PERF_SEARCH_Q}'))")"
run_one "${GATEWAY_BFF_URL}/healthz" "gateway healthz"
run_one "${SEARCH_URL}/v1/search?q=${Q_ENC}&market=${PERF_MARKET}&limit=24" "search"
