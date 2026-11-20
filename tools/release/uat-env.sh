#!/usr/bin/env bash
# One private file for every UAT value, and the tools that route each value to where it belongs.
#
#   tools/release/uat-env.sh init              create ~/.afrotransact/uat.all.env (mode 600) from infra/ovh-vps/uat.all.env.example
#   tools/release/uat-env.sh check [group]     blanks and bad formats by NAME (group: github | tofu | cluster | all). Never prints a value.
#   tools/release/uat-env.sh github            set the GitHub variables and secrets from the file (runs uat-github-setup.sh with it)
#   tools/release/uat-env.sh split             write infra/ovh-vps/config.env and secrets.local.env for secrets.sh (correctly quoted, mode 600)
#   eval "$(tools/release/uat-env.sh tofu-env)"   export the tofu / Cloudflare infrastructure credentials into THIS shell only
#   tools/release/uat-env.sh drift             CI guard: the example file and the tools agree with config.uat / secrets.uat examples
#
# Options: UAT_ENV_FILE=/path (default ~/.afrotransact/uat.all.env)   FORCE=1 (init: overwrite; split: when check has problems)
#
# Safety, all enforced here: the file is read as DATA (never sourced); no command prints a value; `tofu-env` refuses to write to a terminal;
# `check` fails on a LIVE Stripe key (UAT is test mode only), on a file readable by others, and on a file that git would commit;
# UAT only (the file's GRAFANA_ENV must be uat). PROD gets its own file and its own tokens.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=tools/release/lib-envfile.sh
. "$HERE/lib-envfile.sh"
EXAMPLE="$ROOT/infra/ovh-vps/uat.all.env.example"
CFG_EX="$ROOT/infra/ovh-vps/config.uat.env.example"
SEC_EX="$ROOT/infra/ovh-vps/secrets.uat.env.example"
ENV_FILE="${UAT_ENV_FILE:-$HOME/.afrotransact/uat.all.env}"
PROBLEMS=0

say()  { printf '\n== %s\n' "$*"; }
ok()   { printf 'OK    %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; PROBLEMS=$((PROBLEMS+1)); }
warn() { printf 'WARN  %s\n' "$*"; }

# SPEC: NAME|group|required(1/0)|check|what a good value looks like (no values here)
# checks: nonblank | hex32 | digits | token | sktest | pktest | whsec | age | rediss | cidr32 | sha40 | sslmode | file | email | pw16 | url | uatenv | days | mode | domain | host
spec() {
  cat <<'EOF'
GITOPS_APP_ID|github|1|digits|number from the GitHub App's About page
GITOPS_APP_PRIVATE_KEY_FILE|github|1|file|path of the App's .pem (a path, not the key)
CLOUDFLARE_ACCOUNT_ID|github|1|hex32|32 hex characters
CLOUDFLARE_DEPLOY_TOKEN|github|1|token|Cloudflare API token, 30+ characters
OVH_APPLICATION_KEY|tofu|1|nonblank|OVH API application key
OVH_APPLICATION_SECRET|tofu|1|nonblank|OVH API application secret
OVH_CONSUMER_KEY|tofu|1|nonblank|OVH API consumer key
OVH_SERVICE_NAME|tofu|1|nonblank|OVH Public Cloud project id
OVH_ADMIN_CIDR|tofu|1|cidr32|your IP followed by /32
CLOUDFLARE_INFRA_TOKEN|tofu|1|token|Cloudflare API token for tofu, 30+ characters
DOMAIN|cluster|1|domain|bare domain such as afrotransact.com
IMAGE_REGISTRY|cluster|1|nonblank|ghcr.io/<owner>/<repo>
IMAGE_TAG|cluster|1|sha40|40 hex characters
DB_MODE|cluster|1|nonblank|external
DB_LAYOUT|cluster|1|nonblank|schema
EXTERNAL_PG_HOST|cluster|1|host|Layerbase Postgres host name
EXTERNAL_PG_ADMIN_USER|cluster|1|nonblank|Layerbase admin user
EXTERNAL_PG_ADMIN_PASSWORD|cluster|1|nonblank|Layerbase admin password
EXTERNAL_PG_SSLMODE|cluster|1|sslmode|require | verify-ca | verify-full
EXTERNAL_PG_CA_FILE|cluster|0|file|path of the provider CA (PEM); required for verify-ca / verify-full
EXTERNAL_REDIS_URL|cluster|1|rediss|rediss://user:password@host:port
GHCR_USER|cluster|1|nonblank|GitHub user for the pull token
GHCR_TOKEN|cluster|1|token|read:packages token
ADMIN_EMAIL|cluster|1|email|a real address you read
ADMIN_PASSWORD|cluster|1|pw16|16+ characters
ADMIN_PHONE|cluster|0|nonblank|optional
STRIPE_SECRET_KEY|cluster|1|sktest|sk_test_ or rk_test_ (TEST mode only)
STRIPE_PUBLISHABLE_KEY|cluster|1|pktest|pk_test_
STRIPE_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the payments endpoint
STRIPE_CONNECT_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the Connect endpoint
STRIPE_BILLING_WEBHOOK_SECRET|cluster|0|whsec|optional (bank-debit plan fees)
RESEND_API_KEY|cluster|1|nonblank|Resend API key
RESEND_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the Resend webhook
OPENROUTER_API_KEY|cluster|1|nonblank|OpenRouter key (set its spend limit in the dashboard)
GOOGLE_PLACES_API_KEY|cluster|0|nonblank|optional
GOOGLE_MAPS_API_KEY|cluster|0|nonblank|optional
SMARTY_AUTH_ID|cluster|0|nonblank|optional
SMARTY_AUTH_TOKEN|cluster|0|nonblank|optional
R2_ACCOUNT_ID|cluster|0|hex32|blank = CLOUDFLARE_ACCOUNT_ID
R2_ACCESS_KEY_ID|cluster|1|nonblank|public bucket token id
R2_SECRET_ACCESS_KEY|cluster|1|nonblank|public bucket token secret
R2_PRIVATE_ACCESS_KEY_ID|cluster|1|nonblank|private (KYC) bucket token id
R2_PRIVATE_SECRET_ACCESS_KEY|cluster|1|nonblank|private (KYC) bucket token secret
CLOUDFLARED_TUNNEL_TOKEN|cluster|1|nonblank|from `cloudflared tunnel token <id>`
GRAFANA_ENV|cluster|1|uatenv|uat
GRAFANA_PROM_URL|cluster|1|url|https:// URL
GRAFANA_PROM_USER|cluster|1|digits|numeric instance id
GRAFANA_LOKI_URL|cluster|1|url|https:// URL
GRAFANA_LOKI_USER|cluster|1|digits|numeric instance id
GRAFANA_ALLOY_TOKEN|cluster|1|nonblank|access-policy token
GRAFANA_OTLP_ENDPOINT|cluster|0|url|optional
GRAFANA_INSTANCE_ID|cluster|0|nonblank|optional
GRAFANA_API_TOKEN|cluster|0|nonblank|optional
BACKUP_AGE_RECIPIENT|cluster|1|age|age1... PUBLIC key
BACKUP_B2_KEY_ID|cluster|1|nonblank|Backblaze application key id
BACKUP_B2_APPLICATION_KEY|cluster|1|nonblank|Backblaze application key
BACKUP_B2_BUCKET|cluster|1|nonblank|bucket name
BACKUP_B2_ENDPOINT|cluster|1|host|s3.<region>.backblazeb2.com
BACKUP_RETENTION_DAYS|cluster|1|days|3 to 365
BACKUP_RETENTION_MODE|cluster|1|mode|sweep | lifecycle | off
BACKUP_PUSHGATEWAY_URL|cluster|0|url|optional
BACKUP_HEALTHCHECK_URL|cluster|0|url|optional
EOF
}

# valid_value CHECK VALUE: 0 when the value has the right shape. Values are never printed by the callers.
valid_value() {
  local c="$1" v="$2"
  case "$c" in
    nonblank) [[ -n "$v" ]] ;;
    hex32)    [[ "$v" =~ ^[0-9a-fA-F]{32}$ ]] ;;
    digits)   [[ "$v" =~ ^[0-9]{3,}$ ]] ;;
    token)    [[ "$v" =~ ^[A-Za-z0-9_.-]{30,}$ ]] ;;
    sktest)   [[ "$v" =~ ^(sk|rk)_test_[A-Za-z0-9]{10,}$ ]] ;;
    pktest)   [[ "$v" =~ ^pk_test_[A-Za-z0-9]{10,}$ ]] ;;
    whsec)    [[ "$v" =~ ^whsec_[A-Za-z0-9]{16,}$ ]] ;;
    age)      [[ "$v" =~ ^age1[a-z0-9]{50,}$ ]] ;;
    rediss)   [[ "$v" =~ ^rediss://[^[:space:]]+@[^[:space:]]+:[0-9]+ ]] ;;
    cidr32)   [[ "$v" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/32$ ]] ;;
    sha40)    [[ "$v" =~ ^[0-9a-f]{40}$ ]] ;;
    sslmode)  [[ "$v" =~ ^(require|verify-ca|verify-full)$ ]] ;;
    file)     [[ -r "$v" ]] ;;
    email)    [[ "$v" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] ;;
    pw16)     [[ ${#v} -ge 16 ]] ;;
    url)      [[ "$v" =~ ^https://[^[:space:]]+$ ]] ;;
    uatenv)   [[ "$v" == uat ]] ;;
    days)     [[ "$v" =~ ^[0-9]+$ ]] && [[ "$v" -ge 3 && "$v" -le 365 ]] ;;
    mode)     [[ "$v" =~ ^(sweep|lifecycle|off)$ ]] ;;
    domain)   [[ "$v" =~ ^[a-z0-9.-]+\.[a-z]{2,}$ ]] ;;
    host)     [[ "$v" =~ ^[A-Za-z0-9.-]+$ ]] ;;
    *)        return 0 ;;
  esac
}

mode_of() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1" 2>/dev/null; }

stage_init() {
  [[ -r "$EXAMPLE" ]] || { echo "missing $EXAMPLE" >&2; exit 2; }
  if [[ -e "$ENV_FILE" && "${FORCE:-0}" != "1" ]]; then echo "$ENV_FILE already exists (FORCE=1 to overwrite; it would lose your values)." >&2; exit 1; fi
  mkdir -p "$(dirname "$ENV_FILE")" && chmod 700 "$(dirname "$ENV_FILE")" 2>/dev/null || true
  ( umask 077; cp "$EXAMPLE" "$ENV_FILE" ) && chmod 600 "$ENV_FILE"
  ok "created $ENV_FILE (mode 600)"
  cat <<EOF
Next:
  1. Open it in an editor and fill in the names, in the order of docs/runbooks/release-1-go-live.md. Every group says where each value comes from.
  2. Put the same values in your password manager (item "AfroTransact UAT"). This file is the working copy, not the master.
  3. tools/release/uat-env.sh check            (names only, never values)
EOF
}

stage_check() {
  local group="${1:-all}"
  [[ -r "$ENV_FILE" ]] || { echo "no file at $ENV_FILE: run 'uat-env.sh init'" >&2; exit 2; }
  say "File safety ($ENV_FILE)"
  local m; m="$(mode_of "$ENV_FILE")"
  if [[ "$m" == "600" || "$m" == "400" ]]; then ok "mode $m"; else bad "mode is ${m:-unknown}, must be 600 (chmod 600)"; fi
  case "$ENV_FILE" in
    "$ROOT"/*)
      if git -C "$ROOT" check-ignore -q "$ENV_FILE" 2>/dev/null; then ok "inside the repository but git-ignored"; else bad "inside the repository and NOT git-ignored: move it out (default ~/.afrotransact/)"; fi ;;
    *) ok "outside the repository" ;;
  esac

  say "Values ($group): names only"
  local name grp req chk hint v n_ok=0
  while IFS='|' read -r name grp req chk hint; do
    [[ -n "$name" ]] || continue
    [[ "$group" == "all" || "$group" == "$grp" ]] || continue
    v="$(envfile_get "$ENV_FILE" "$name")"
    if [[ -z "$v" ]]; then
      if [[ "$req" == "1" ]]; then
        # R2_ACCOUNT_ID style defaults are optional rows; required rows blank = problem
        bad "$name is blank [$grp] ($hint)"
      fi
      continue
    fi
    if envfile_inline_comment "$ENV_FILE" "$name"; then bad "$name: unquoted value contains ' #': remove the comment or quote the value"; continue; fi
    if valid_value "$chk" "$v"; then n_ok=$((n_ok+1)); else bad "$name has the wrong shape [$grp] (want: $hint)"; fi
    case "$v" in sk_live_*|rk_live_*|pk_live_*) bad "$name is a LIVE Stripe key: UAT is test mode only" ;; esac
  done < <(spec)
  ok "$n_ok value(s) present and well formed"

  # relations between values
  local a b c
  a="$(envfile_get "$ENV_FILE" CLOUDFLARE_DEPLOY_TOKEN)"; b="$(envfile_get "$ENV_FILE" CLOUDFLARE_INFRA_TOKEN)"
  if [[ ( "$group" == all || "$group" == github || "$group" == tofu ) && -n "$a" && "$a" == "$b" ]]; then bad "CLOUDFLARE_DEPLOY_TOKEN equals CLOUDFLARE_INFRA_TOKEN: use two tokens (the GitHub one must be the weaker)"; fi
  if [[ "$group" == all || "$group" == cluster ]]; then
    a="$(envfile_get "$ENV_FILE" STRIPE_WEBHOOK_SECRET)"; b="$(envfile_get "$ENV_FILE" STRIPE_CONNECT_WEBHOOK_SECRET)"; c="$(envfile_get "$ENV_FILE" STRIPE_BILLING_WEBHOOK_SECRET)"
    [[ -n "$a" && "$a" == "$b" ]] && bad "STRIPE_WEBHOOK_SECRET equals STRIPE_CONNECT_WEBHOOK_SECRET: each endpoint has its own whsec_"
    [[ -n "$c" && ( "$c" == "$a" || "$c" == "$b" ) ]] && bad "STRIPE_BILLING_WEBHOOK_SECRET equals another webhook secret"
    a="$(envfile_get "$ENV_FILE" EXTERNAL_PG_SSLMODE)"
    case "$a" in verify-ca|verify-full)
      [[ -r "$(envfile_get "$ENV_FILE" EXTERNAL_PG_CA_FILE)" ]] || bad "EXTERNAL_PG_SSLMODE=$a needs EXTERNAL_PG_CA_FILE (readable PEM)" ;; esac
    [[ "$a" == "require" ]] && warn "EXTERNAL_PG_SSLMODE=require encrypts but does not verify the server; move to verify-full before PROD"
    [[ -z "$(envfile_get "$ENV_FILE" R2_ACCOUNT_ID)" && -z "$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)" ]] && bad "R2_ACCOUNT_ID and CLOUDFLARE_ACCOUNT_ID are both blank"
  fi

  # typos: names in the file the tools do not know
  local known; known="$(spec | cut -d'|' -f1)"
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    grep -qx "$name" <<<"$known" || warn "unknown name $name (typo?): ignored by every tool"
  done < <(envfile_names "$ENV_FILE" | sort -u)
  local dups; dups="$(envfile_names "$ENV_FILE" | sort | uniq -d | tr '\n' ' ')"
  [[ -z "$dups" ]] || warn "defined more than once (the last one wins): $dups"

  echo; echo "$PROBLEMS problem(s)."
  [[ "$PROBLEMS" -eq 0 ]]
}

stage_github() {
  [[ -r "$ENV_FILE" ]] || { echo "no file at $ENV_FILE: run 'uat-env.sh init'" >&2; exit 2; }
  stage_check github || { echo "Fix the github group first (uat-env.sh check github)." >&2; exit 1; }
  PROBLEMS=0
  UAT_ENV_FILE="$ENV_FILE" exec "$HERE/uat-github-setup.sh" set
}

# write_env OUT NAME... : writes NAME=<quoted value> for every name that has a value
write_env() {
  local out="$1"; shift
  local n v
  if [[ -e "$out" ]] && ! head -1 "$out" | grep -q '^# generated by uat-env.sh'; then
    echo "refusing to overwrite $out: it was not generated by this tool (move it aside first)" >&2; return 1
  fi
  ( umask 077
    { echo "# generated by uat-env.sh from $(basename "$ENV_FILE"): do not edit, do not commit"
      for n in "$@"; do
        v="$(envfile_get "$ENV_FILE" "$n")"
        [[ -z "$v" && "$n" == R2_ACCOUNT_ID ]] && v="$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)"
        [[ -n "$v" ]] || continue
        printf '%s=%q\n' "$n" "$v"
      done
    } >"$out" )
  chmod 600 "$out"
}

stage_split() {
  [[ -r "$ENV_FILE" ]] || { echo "no file at $ENV_FILE: run 'uat-env.sh init'" >&2; exit 2; }
  if ! stage_check cluster; then
    [[ "${FORCE:-0}" == "1" ]] || { echo "Fix the cluster group first, or FORCE=1 to write what is there (apply.sh will refuse empty mandatory keys)." >&2; exit 1; }
    warn "FORCE=1: writing with problems"
  fi
  local cfg sec
  cfg="$(envfile_names "$CFG_EX" | sort -u | tr '\n' ' ') EXTERNAL_PG_CA_FILE"
  sec="$(envfile_names "$SEC_EX" | sort -u | tr '\n' ' ')"
  # shellcheck disable=SC2086
  write_env "$ROOT/infra/ovh-vps/config.env" $cfg && ok "wrote infra/ovh-vps/config.env (git-ignored, mode 600)"
  # shellcheck disable=SC2086
  write_env "$ROOT/infra/ovh-vps/secrets.local.env" $sec && ok "wrote infra/ovh-vps/secrets.local.env (git-ignored, mode 600)"
  git -C "$ROOT" check-ignore -q infra/ovh-vps/config.env infra/ovh-vps/secrets.local.env && ok "both are git-ignored" || bad "a written file is NOT git-ignored: do not commit it"
  echo "Next (runbook section 6): export PROFILE=uat DB_LAYOUT=schema STATE_DIR=\$HOME/.afrotransact-uat SECRETS_OUT=\$(mktemp -d); cd infra/ovh-vps; ./secrets.sh platform && ./secrets.sh services"
}

stage_tofu_env() {
  [[ -r "$ENV_FILE" ]] || { echo "no file at $ENV_FILE: run 'uat-env.sh init'" >&2; exit 2; }
  if [[ -t 1 && "${1:-}" != "--show" ]]; then
    echo "This prints secret values for eval. Use: eval \"\$(tools/release/uat-env.sh tofu-env)\"   (refusing to print to a terminal; --show overrides)" >&2; exit 1
  fi
  stage_check tofu >&2 || { echo "Fix the tofu group first." >&2; exit 1; }
  local n v
  for n in OVH_APPLICATION_KEY OVH_APPLICATION_SECRET OVH_CONSUMER_KEY; do printf 'export %s=%q\n' "$n" "$(envfile_get "$ENV_FILE" "$n")"; done
  printf 'export OVH_ENDPOINT=%q\n' ovh-us
  printf 'export CLOUDFLARE_API_TOKEN=%q\n' "$(envfile_get "$ENV_FILE" CLOUDFLARE_INFRA_TOKEN)"
  printf 'export CLOUDFLARE_ACCOUNT_ID=%q\n' "$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)"
  printf 'export TF_VAR_ovh_service_name=%q\n' "$(envfile_get "$ENV_FILE" OVH_SERVICE_NAME)"
  printf 'export TF_VAR_ovh_endpoint=%q\n' ovh-us
  v="$(envfile_get "$ENV_FILE" OVH_ADMIN_CIDR)"
  printf 'export TF_VAR_admin_cidrs=%q\n' "[\"$v\"]"
  printf 'export TF_VAR_account_id=%q\n' "$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)"
}

stage_drift() {
  local fail=0 n known
  known="$(spec | cut -d'|' -f1)"
  for f in "$CFG_EX" "$SEC_EX"; do
    while IFS= read -r n; do
      [[ -n "$n" ]] || continue
      grep -qx "$n" <<<"$known" || { echo "DRIFT: $n is in $(basename "$f") but not in the central spec (uat-env.sh)"; fail=1; }
      grep -qE "^${n}=" "$EXAMPLE" || { echo "DRIFT: $n is in $(basename "$f") but not in uat.all.env.example"; fail=1; }
    done < <(envfile_names "$f")
  done
  while IFS= read -r n; do
    [[ -n "$n" ]] || continue
    grep -qE "^${n}=" "$EXAMPLE" || { echo "DRIFT: $n is in the central spec but not in uat.all.env.example"; fail=1; }
  done <<<"$known"
  while IFS= read -r n; do
    [[ -n "$n" ]] || continue
    grep -qx "$n" <<<"$known" || { echo "DRIFT: $n is in uat.all.env.example but not in the central spec"; fail=1; }
  done < <(envfile_names "$EXAMPLE")
  # every GitHub secret the UAT setup script handles is covered
  for n in GITOPS_APP_ID GITOPS_APP_PRIVATE_KEY_FILE CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_DEPLOY_TOKEN; do
    grep -qx "$n" <<<"$known" || { echo "DRIFT: github item $n missing from the spec"; fail=1; }
  done
  [[ "$fail" -eq 0 ]] && echo "uat-env drift check: OK ($(grep -c . <<<"$known") names)"
  return "$fail"
}

case "${1:-}" in
  init)     stage_init ;;
  check)    stage_check "${2:-all}" ;;
  github)   stage_github ;;
  split)    stage_split ;;
  tofu-env) stage_tofu_env "${2:-}" ;;
  drift)    stage_drift ;;
  *) sed -n '2,16p' "$0"; exit 0 ;;
esac
