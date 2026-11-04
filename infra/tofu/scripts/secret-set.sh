#!/usr/bin/env bash
# Merge keys into one service's runtime secret in the environment's cloud secret store, without touching the keys
# already there (Terraform seeds non-secret values once and then ignores the value; humans and rotation own it).
#
#   scripts/secret-set.sh <aws|gcp|azure> <env> <service> KEY=VALUE [KEY=@file] [KEY=-] ...
#     KEY=VALUE   literal value (avoid for real secrets: it lands in your shell history)
#     KEY=@path   value read from a file (PEM keys, JSON service accounts)
#     KEY=-       value read from stdin / prompted without echo (one key per run)
#   scripts/secret-set.sh aws dev payments STRIPE_SECRET_KEY=- STRIPE_WEBHOOK_SECRET=-   # prompts twice
#   scripts/secret-set.sh gcp uat identity SIGNING_KEY_PEM=@signing-key.pem
#   scripts/secret-set.sh azure prod orders --list                                     # key names only
#
# Names follow modules/<cloud>/secrets: AWS Secrets Manager "afrotransact/<env>/<service>"; GCP Secret Manager and
# Azure Key Vault "afrotransact-<env>-<service>" (Azure: the vault name comes from AZURE_KEY_VAULT or
# `tofu output -json` in infra/tofu/envs/<env>). The full list of keys per service is in
# docs/runbooks/first-environment.md (step 4). Requires jq and the cloud CLI, logged in as an operator.
set -euo pipefail

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[[ $# -ge 4 ]] || usage
cloud="$1" env="$2" svc="$3"; shift 3
case "$cloud" in aws|gcp|azure) ;; *) usage ;; esac
case "$env" in dev|uat|prod) ;; *) usage ;; esac
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

read_current() {
  case "$cloud" in
    aws)   aws secretsmanager get-secret-value --secret-id "afrotransact/$env/$svc" --query SecretString --output text ;;
    gcp)   gcloud secrets versions access latest --secret "afrotransact-$env-$svc" ;;
    azure) az keyvault secret show --vault-name "$(vault)" --name "afrotransact-$env-$svc" --query value -o tsv ;;
  esac
}

write_new() { # json on stdin
  local tmp; tmp="$(mktemp)"; trap 'rm -f "$tmp"' RETURN
  cat > "$tmp"
  case "$cloud" in
    aws)   aws secretsmanager put-secret-value --secret-id "afrotransact/$env/$svc" --secret-string "file://$tmp" >/dev/null ;;
    gcp)   gcloud secrets versions add "afrotransact-$env-$svc" --data-file "$tmp" >/dev/null ;;
    azure) az keyvault secret set --vault-name "$(vault)" --name "afrotransact-$env-$svc" --file "$tmp" >/dev/null ;;
  esac
}

vault() {
  if [[ -n "${AZURE_KEY_VAULT:-}" ]]; then echo "$AZURE_KEY_VAULT"; return; fi
  local dir; dir="$(cd "$(dirname "$0")/../envs/$env" && pwd)"
  local url; url="$(cd "$dir" && tofu output -json cluster_secret_store 2>/dev/null | jq -r '.azurekv.vaultUrl // empty')"
  [[ -n "$url" ]] || { echo "set AZURE_KEY_VAULT (Key Vault name of $env)" >&2; exit 2; }
  url="${url#https://}"; echo "${url%%.*}"
}

current="$(read_current)"
[[ -n "$current" ]] || current='{}'
jq -e 'type == "object"' >/dev/null <<<"$current" || { echo "existing secret is not a JSON object" >&2; exit 1; }

if [[ "$1" == "--list" ]]; then
  jq -r 'keys[]' <<<"$current"
  exit 0
fi

updates='{}'
for kv in "$@"; do
  key="${kv%%=*}" val="${kv#*=}"
  [[ "$kv" == *=* && "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "bad argument: $kv (KEY=VALUE, KEY=@file, KEY=-)" >&2; exit 2; }
  case "$val" in
    @*) val="$(cat "${val#@}")" ;;
    -)  if [[ -t 0 ]]; then read -r -s -p "$key: " val; echo >&2; else val="$(cat)"; fi ;;
  esac
  updates="$(jq --arg k "$key" --arg v "$val" '. + {($k): $v}' <<<"$updates")"
done

jq -s '.[0] * .[1]' <(echo "$current") <(echo "$updates") | write_new
echo "updated $(jq -r 'keys | join(", ")' <<<"$updates") in $cloud $env/$svc" >&2
