# Source from perf runners:  `. "$(dirname "$0")/env.sh"`
# Override any variable in the shell or via `.local/perf.env` (gitignored).

PERF_MARKET="${PERF_MARKET:-US}"
PERF_ZIP="${PERF_ZIP:-77042}"
PERF_SEARCH_Q="${PERF_SEARCH_Q:-jollof rice}"

GATEWAY_BFF_URL="${GATEWAY_BFF_URL:-http://127.0.0.1:8200}"
SEARCH_URL="${SEARCH_URL:-http://127.0.0.1:8201}"
CART_URL="${CART_URL:-http://127.0.0.1:8203}"
PROMISE_URL="${PROMISE_URL:-http://127.0.0.1:8204}"
IDENTITY_URL="${IDENTITY_URL:-http://127.0.0.1:8100}"

# Apache Bench / hey defaults
PERF_AB_REQUESTS="${PERF_AB_REQUESTS:-1000}"
PERF_AB_CONCURRENCY="${PERF_AB_CONCURRENCY:-20}"

# k6 defaults (also passed as K6_VUS / K6_DURATION)
PERF_VUS="${PERF_VUS:-10}"
PERF_DURATION="${PERF_DURATION:-30s}"

# Optional bearer for authenticated routes (shopper, staff, service token)
PERF_BEARER_TOKEN="${PERF_BEARER_TOKEN:-}"

_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -f "$_REPO_ROOT/.local/perf.env" ]]; then
  # shellcheck disable=SC1091
  source "$_REPO_ROOT/.local/perf.env"
fi
