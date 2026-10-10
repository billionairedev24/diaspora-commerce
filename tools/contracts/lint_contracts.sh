#!/usr/bin/env bash
# Lint every API contract offline with validators installed in the pnpm workspace (no npx downloads):
#   - contracts/openapi/*.yaml  -> Redocly lint engine (@redocly/openapi-core) with the root redocly.yaml
#   - contracts/asyncapi/*.yaml -> @asyncapi/parser (AsyncAPI schema + ruleset)
#   - contracts/asyncapi/events.yaml freshness vs fragments (tools/contracts/merge_asyncapi.py --check),
#     when python3 with PyYAML is available
#
# Usage: tools/contracts/lint_contracts.sh [--warnings] [--json report.json] [name-filter ...]
#   --warnings   print every warning, not only a per-rule count
#   --json FILE  also write the findings as JSON
# Exit status 1 when any contract has errors (or events.yaml is stale). Requires `pnpm install` first.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORTAL="$ROOT/docs/portal"

if [[ ! -d "$PORTAL/node_modules/@redocly/openapi-core" || ! -d "$PORTAL/node_modules/@asyncapi/parser" ]]; then
  echo "lint_contracts: validators not installed; run 'pnpm install' at the repository root first." >&2
  exit 2
fi

status=0
node "$PORTAL/scripts/lint-contracts.mjs" "$@" || status=$?

# Only check the merge when no filter narrows the run to other contracts.
filters=()
skip_next=0
for a in "$@"; do
  if (( skip_next )); then skip_next=0; continue; fi
  case "$a" in
    --json) skip_next=1 ;;
    --*) ;;
    *) filters+=("$a") ;;
  esac
done
if (( ${#filters[@]} == 0 )) && [[ -f "$ROOT/tools/contracts/merge_asyncapi.py" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
    echo
    if python3 "$ROOT/tools/contracts/merge_asyncapi.py" --check; then
      echo " ok  contracts/asyncapi/events.yaml is up to date with contracts/asyncapi/fragments"
    else
      echo "FAIL contracts/asyncapi/events.yaml is stale: run python3 tools/contracts/merge_asyncapi.py"
      status=1
    fi
  else
    echo "skip AsyncAPI merge check (python3 with PyYAML not available)"
  fi
fi
exit "$status"
