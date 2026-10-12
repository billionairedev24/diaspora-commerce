#!/usr/bin/env bash
# Run a k6 scenario against the local (or remote) stack.
#
# Usage:
#   tools/perf/run-k6.sh smoke
#   tools/perf/run-k6.sh gateway-readonly
#   tools/perf/run-k6.sh search
#   K6_VUS=50 K6_DURATION=2m tools/perf/run-k6.sh search
#   PERF_BEARER_TOKEN=… tools/perf/run-k6.sh gateway-readonly
#
# Requires: k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/env.sh"

SCENARIO="${1:-smoke}"
SCRIPT="$ROOT/k6/scenarios/${SCENARIO}.js"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 not found. Install: brew install k6  (or see tools/perf/README.md)" >&2
  exit 2
fi

if [[ ! -f "$SCRIPT" ]]; then
  echo "Unknown scenario: $SCENARIO (expected $SCRIPT)" >&2
  echo "Available: $(cd "$ROOT/k6/scenarios" && ls -1 *.js 2>/dev/null | sed 's/.js$//' | tr '\n' ' ')" >&2
  exit 2
fi

export GATEWAY_BFF_URL SEARCH_URL PERF_MARKET PERF_ZIP PERF_SEARCH_Q PERF_BEARER_TOKEN
export K6_VUS="${K6_VUS:-$PERF_VUS}"
export K6_DURATION="${K6_DURATION:-$PERF_DURATION}"

echo "==> k6 scenario=$SCENARIO vus=$K6_VUS duration=$K6_DURATION"
echo "    gateway=$GATEWAY_BFF_URL search=$SEARCH_URL"
exec k6 run "$SCRIPT" "${@:2}"
