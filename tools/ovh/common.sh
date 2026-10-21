#!/usr/bin/env bash
# Shared by tools/ovh/*.sh (docs/runbooks/ovh-vps.md). Sourced, never run. bash 3.2 (macOS) compatible: no associative
# arrays, no mapfile, no ${var,,}.

OVH_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVH_TF="$OVH_REPO/infra/ovh-vps/terraform"
OVH_ENV="ovh-vps"                                   # the GitHub Environment
OVH_ENV_FILE=${OVH_SECRETS_FILE:-$OVH_REPO/.ovh/$OVH_ENV.env}   # optional answers file (git-ignored): KEY=value lines
OVH_LOCATIONS_FILE="$OVH_TF/locations.json"

die() { echo "$*" >&2; exit 1; }

need() {
  local c
  for c in "$@"; do command -v "$c" >/dev/null 2>&1 || die "$c is needed (not found on PATH): docs/runbooks/ovh-vps.md, 'What you need'"; done
}

# The infrastructure tool: OpenTofu (what the repository uses), else Terraform.
tf_bin() { if command -v tofu >/dev/null 2>&1; then echo tofu; elif command -v terraform >/dev/null 2>&1; then echo terraform; else die "tofu (or terraform) is needed"; fi; }

# env_get <KEY>: a value from the answers file. With OVH_SECRETS_SOURCE=env (the pipeline) it is the environment variable.
env_get() {
  if [ "${OVH_SECRETS_SOURCE:-file}" = env ]; then
    case "$1" in ([A-Z]*) printf '%s\n' "${!1:-}" ;; esac
    return 0
  fi
  [ -f "$OVH_ENV_FILE" ] || return 0
  awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$OVH_ENV_FILE"
}

# ovh_location [name]: checks the location against locations.json, sets OVH_LOCATION. loc_get <key> reads a field.
ovh_location() {
  OVH_LOCATION=${1:-${OVH_LOCATION:-us-east-va}}
  need jq
  jq -e --arg l "$OVH_LOCATION" 'has($l)' "$OVH_LOCATIONS_FILE" >/dev/null \
    || die "OVH_LOCATION must be us-east-va or us-west-or (OVHcloud US) or ca-bhs (OVHcloud Canada), not '$OVH_LOCATION'"
}
loc_get() { jq -r --arg l "$OVH_LOCATION" --arg k "$1" '.[$l][$k]' "$OVH_LOCATIONS_FILE"; }
