#!/usr/bin/env bash
# Shared helpers for bootstrap.sh / secrets.sh / deploy.sh (run on the server, as root).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
STATE_DIR="${STATE_DIR:-/var/lib/afrotransact}"
NS="${NS:-afrotransact-dev}"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

log() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

load_config() {
  [[ -f "$HERE/config.env" ]] || die "create $HERE/config.env from config.env.example"
  set -a; . "$HERE/config.env"; [[ -f "$HERE/secrets.local.env" ]] && . "$HERE/secrets.local.env"; set +a
  : "${DOMAIN:?}" "${IMAGE_REGISTRY:?}" "${IMAGE_TAG:?}" "${DB_MODE:=cluster}"
  [[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] || die "IMAGE_TAG must be the full 40-character git sha the CI built"
  case "$DB_MODE" in cluster|external) ;; *) die "DB_MODE must be cluster or external" ;; esac
  if [[ "$DB_MODE" == external ]]; then
    : "${EXTERNAL_PG_HOST:?}" "${EXTERNAL_PG_ADMIN_USER:?}" "${EXTERNAL_PG_ADMIN_PASSWORD:?}"
  fi
}

# Services listed in services.conf: name runtime database wave.
services() { grep -Ev '^\s*(#|$)' "$HERE/services.conf"; }

# A value generated once and kept in $STATE_DIR/generated.env, so re-running secrets.sh never rotates anything.
gen() { # gen NAME [bytes]  -> prints the value
  local name="$1" bytes="${2:-32}" file="$STATE_DIR/generated.env" v
  install -d -m 700 "$STATE_DIR"; touch "$file"; chmod 600 "$file"
  v="$(grep -E "^${name}=" "$file" | head -1 | cut -d= -f2- || true)"
  if [[ -z "$v" ]]; then
    v="$(openssl rand -base64 "$bytes" | tr -d '/+=\n' | cut -c1-"$((bytes * 4 / 3))")"
    printf '%s=%s\n' "$name" "$v" >> "$file"
  fi
  printf '%s' "$v"
}

# Drops documents of the given kinds from a multi-document YAML stream (External Secrets and Prometheus CRDs are not
# installed on this server; the Secrets they would make are created by secrets.sh).
strip_kinds() {
  awk -v kinds="$1" 'BEGIN{n=split(kinds,k,"|"); RS="\n---\n"; ORS="\n---\n"}
    { drop=0; for(i=1;i<=n;i++) if ($0 ~ ("(^|\n)kind: " k[i] "(\n|$)")) drop=1; if(!drop) print }'
}
STRIP_KINDS='ExternalSecret|ServiceMonitor|PodMonitor|PrometheusRule|ClusterSecretStore|SecretStore'

secret() { # secret NAMESPACE NAME KEY=VALUE...   (create or replace, values never echoed)
  local ns="$1" name="$2"; shift 2
  local args=() kv
  for kv in "$@"; do args+=("--from-literal=$kv"); done
  kubectl -n "$ns" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}
