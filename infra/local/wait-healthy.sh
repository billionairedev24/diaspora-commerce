#!/usr/bin/env bash
# Waits until every long-running container of the compose project is healthy and every one-shot init job
# (hc-tools, kafka-init, minio-init, elasticsearch-init, openfga-migrate) exited 0. `docker compose up --wait`
# cannot be used because it treats exited init jobs as failures.
#   usage: wait-healthy.sh [timeout-seconds] [compose args...]
set -euo pipefail
timeout="${1:-600}"; shift || true
compose=(docker compose -f "$(dirname "$0")/docker-compose.yml" "$@")
deadline=$((SECONDS + timeout))
while :; do
  pending=(); failed=()
  while IFS='|' read -r svc state health code; do
    [[ -z "$svc" ]] && continue
    if [[ "$state" == exited ]]; then
      [[ "$code" == 0 ]] || failed+=("$svc(exit $code)")
    elif [[ "$state" != running || ( -n "$health" && "$health" != healthy ) ]]; then
      pending+=("$svc:${health:-$state}")
    fi
  done < <("${compose[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}')
  if ((${#failed[@]})); then echo "failed: ${failed[*]} — see: make logs SERVICE=<name>" >&2; exit 1; fi
  if ((${#pending[@]} == 0)); then echo "all containers healthy"; exit 0; fi
  if ((SECONDS > deadline)); then echo "timed out waiting for: ${pending[*]}" >&2; exit 1; fi
  echo "waiting for ${#pending[@]}: ${pending[*]}"
  sleep 5
done
