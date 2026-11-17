#!/usr/bin/env bash
# Shared helpers for bootstrap.sh / secrets.sh / deploy.sh (run on the server, as root).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
STATE_DIR="${STATE_DIR:-/var/lib/afrotransact}"
# PROFILE=dev (default, the test box: dev values, all 26 services) | prod (the launch set, APP_ENV=prod, namespace afrotransact-prod).
# prod is used by infra/ops/secrets to RENDER the production Secrets on an operator's machine; the single-server deploy.sh
# stays a test path (docs/runbooks/ovh-vps.md) and is not how production is deployed (docs/runbooks/ovh-production.md).
PROFILE="${PROFILE:-dev}"
# PROFILE=uat (release-1, UAT on OVH): the launch set like prod, APP_ENV=uat, namespace afrotransact-uat, no OpenFGA, Stripe TEST keys.
case "$PROFILE" in dev|prod|uat) ;; *) echo "error: PROFILE must be dev, uat or prod" >&2; exit 1 ;; esac
PROD_LIKE=false; [[ "$PROFILE" == prod || "$PROFILE" == uat ]] && PROD_LIKE=true
NS="${NS:-afrotransact-$PROFILE}"
SERVICES_CONF="${SERVICES_CONF:-$( [[ "$PROD_LIKE" == true ]] && echo "$HERE/services.launch.conf" || echo "$HERE/services.conf" )}"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"

log() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

load_config() {
  [[ -f "$HERE/config.env" ]] || die "create $HERE/config.env from config.env.example"
  set -a; . "$HERE/config.env"; [[ -f "$HERE/secrets.local.env" ]] && . "$HERE/secrets.local.env"; set +a
  : "${DOMAIN:?}" "${IMAGE_REGISTRY:?}" "${IMAGE_TAG:?}" "${DB_MODE:=cluster}" "${STOREFRONT_HOST:=www}"
  case "$STOREFRONT_HOST" in www|apex) ;; *) die "STOREFRONT_HOST must be www or apex" ;; esac
  # Optional explicit web hosts (full hostnames, default seller.$DOMAIN / admin.$DOMAIN); same rules as tools/ovh/web-hosts.sh.
  : "${SELLER_HOST:=}" "${ADMIN_HOST:=}" "${MEDIA_PUBLIC_URL:=}"
  local h re='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$'
  for h in "$SELLER_HOST" "$ADMIN_HOST"; do
    [[ -z "$h" || "$h" =~ $re ]] || die "SELLER_HOST / ADMIN_HOST must be a full lower-case hostname (got '$h')"
  done
  [[ -z "$MEDIA_PUBLIC_URL" || "$MEDIA_PUBLIC_URL" =~ ^https://[a-z0-9.-]+(/[A-Za-z0-9._~/-]*)?$ ]] || die "MEDIA_PUBLIC_URL must be an https URL"
  [[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] || die "IMAGE_TAG must be the full 40-character git sha the CI built"
  case "$DB_MODE" in cluster|external) ;; *) die "DB_MODE must be cluster or external" ;; esac
  if [[ "$DB_MODE" == external ]]; then
    : "${EXTERNAL_PG_HOST:?}" "${EXTERNAL_PG_ADMIN_USER:?}" "${EXTERNAL_PG_ADMIN_PASSWORD:?}"
  fi
}

# Services listed in services.conf: name runtime database wave.
services() { grep -Ev '^\s*(#|$)' "$SERVICES_CONF"; }

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

# SECRETS_OUT=<dir>: do not talk to a cluster; write each Secret as <dir>/<namespace>.<name>.yaml (kubectl is only used offline,
# `--dry-run=client`) so it can be encrypted with SOPS/age and committed (infra/ops/secrets/README.md). Without it: apply live.
secret() { # secret NAMESPACE NAME KEY=VALUE...   (create or replace, values never echoed)
  # Each value goes into its own 0600 file under a private temp directory and kubectl reads it with --from-file=KEY=path:
  #  - a value never appears in kubectl's argv (visible in `ps` and /proc/<pid>/cmdline to every local user);
  #  - a multi-line value (SIGNING_KEY_PEM, a CA bundle) arrives byte for byte, as ONE key.
  # The KEY is everything before the FIRST "="; the VALUE is the rest, newlines included.
  local ns="$1" name="$2"; shift 2
  local args=() kv key dir i=0
  dir="$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/at-secret.XXXXXX")"
  for kv in "$@"; do
    key="${kv%%=*}"
    [[ "$key" =~ ^[A-Za-z0-9._-]+$ ]] || { rm -rf "$dir"; die "secret $name: '$key' is not a valid Secret key name"; }
    i=$((i + 1))
    ( umask 077; printf '%s' "${kv#*=}" > "$dir/$i" )
    args+=("--from-file=$key=$dir/$i")
  done
  local rc=0
  if [[ -n "${SECRETS_OUT:-}" ]]; then
    install -d -m 700 "$SECRETS_OUT"
    ( umask 077; kubectl -n "$ns" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml > "$SECRETS_OUT/$ns.$name.yaml" ) || rc=$?
  else
    kubectl -n "$ns" create secret generic "$name" "${args[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null || rc=$?
  fi
  rm -rf "$dir"
  return "$rc"
}
