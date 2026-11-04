#!/usr/bin/env bash
# Apache Bench (ab) against common read-only endpoints.
#
# Usage:
#   tools/perf/run-ab.sh
#   tools/perf/run-ab.sh http://127.0.0.1:8200/healthz
#   PERF_AB_REQUESTS=5000 PERF_AB_CONCURRENCY=50 tools/perf/run-ab.sh
#
# Requires: ab (macOS: pre-installed; Linux: apache2-utils)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/env.sh"

if ! command -v ab >/dev/null 2>&1; then
  echo "ab not found. Linux: apt install apache2-utils / yum install httpd-tools" >&2
  exit 2
fi

run_one() {
  local url="$1"
  local label="$2"
  echo ""
  echo "==> ab -n $PERF_AB_REQUESTS -c $PERF_AB_CONCURRENCY $label"
  echo "    $url"
  ab -n "$PERF_AB_REQUESTS" -c "$PERF_AB_CONCURRENCY" -k -q "$url"
}

if [[ $# -gt 0 ]]; then
  run_one "$1" "custom"
  exit 0
fi

run_one "${GATEWAY_BFF_URL}/healthz" "gateway-bff healthz"
run_one "${SEARCH_URL}/healthz" "search healthz"
run_one "${GATEWAY_BFF_URL}/v1/home?market=${PERF_MARKET}&zip=${PERF_ZIP}" "gateway-bff home"
run_one "${SEARCH_URL}/v1/search?q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${PERF_SEARCH_Q}'))")&market=${PERF_MARKET}&limit=24" "search query"
