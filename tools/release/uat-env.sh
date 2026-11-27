#!/usr/bin/env bash
# One private file for every UAT value, and the tools that route each value to where it belongs.
#
#   tools/release/uat-env.sh init              create ~/.afrotransact/uat.all.env (mode 600) from infra/ovh-vps/uat.all.env.example
#   tools/release/uat-env.sh fill [group|--only NAME]   ask for each blank value one by one (hidden for secrets, checked, saved at once; Enter skips)
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
# checks: promurl | lokiurl | ghuser | ghtoken | nonblank | hex32 | digits | token | sktest | pktest | whsec | age | rediss | cidr32 | sha40 | sslmode | file | email | pw16 | url | uatenv | days | mode | domain | host
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
GHCR_USER|cluster|1|ghuser|your GitHub USER NAME (not the token), for example octocat
GHCR_TOKEN|cluster|1|ghtoken|GitHub classic token with only read:packages (starts ghp_)
ADMIN_EMAIL|cluster|1|email|a real address you read
ADMIN_PASSWORD|cluster|1|pw16|16+ characters
ADMIN_PHONE|cluster|0|nonblank|optional
STRIPE_SECRET_KEY|cluster|1|sktest|sk_test_ or rk_test_ (TEST mode only)
STRIPE_PUBLISHABLE_KEY|cluster|1|pktest|pk_test_
STRIPE_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the payments endpoint
STRIPE_CONNECT_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the Connect endpoint
RESEND_API_KEY|cluster|1|nonblank|Resend API key
RESEND_WEBHOOK_SECRET|cluster|1|whsec|whsec_ value of the Resend webhook
OPENROUTER_API_KEY|cluster|1|nonblank|OpenRouter key (set its spend limit in the dashboard)
GOOGLE_PLACES_API_KEY|cluster|0|nonblank|optional
GOOGLE_MAPS_API_KEY|cluster|0|nonblank|optional
R2_ACCOUNT_ID|cluster|0|hex32|blank = CLOUDFLARE_ACCOUNT_ID
R2_ACCESS_KEY_ID|cluster|1|nonblank|public bucket token id
R2_SECRET_ACCESS_KEY|cluster|1|nonblank|public bucket token secret
R2_PRIVATE_ACCESS_KEY_ID|cluster|1|nonblank|private (KYC) bucket token id
R2_PRIVATE_SECRET_ACCESS_KEY|cluster|1|nonblank|private (KYC) bucket token secret
CLOUDFLARED_TUNNEL_TOKEN|cluster|1|nonblank|from `cloudflared tunnel token <id>`
GRAFANA_ENV|cluster|1|uatenv|uat
GRAFANA_PROM_URL|cluster|1|promurl|public https://prometheus-prod-NN-<region>.grafana.net/api/prom/push (not an internal host, must end in /api/prom/push)
GRAFANA_PROM_USER|cluster|1|digits|numeric instance id
GRAFANA_LOKI_URL|cluster|1|lokiurl|public https://logs-prod-NNN.grafana.net/loki/api/v1/push (not an internal host, must end in /loki/api/v1/push)
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

# looks_secret VALUE: true for the common token/key prefixes (used to stop a secret being typed into a non-secret field)
looks_secret() { [[ "$1" =~ ^(gh[pousr]_|github_pat_|sk_|rk_|pk_|whsec_|glc_|AGE-SECRET-KEY-) ]]; }

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
    whsec)    [[ "$v" =~ ^whsec_[A-Za-z0-9+/=_-]{16,}$ ]] ;;   # Stripe: letters and digits; Resend (Svix): base64, so + / = too
    age)      [[ "$v" =~ ^age1[a-z0-9]{50,}$ ]] ;;
    rediss)   [[ "$v" =~ ^rediss://[^[:space:]]+@[^[:space:]]+:[0-9]+ ]] ;;
    cidr32)   [[ "$v" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/32$ ]] ;;
    sha40)    [[ "$v" =~ ^[0-9a-f]{40}$ ]] ;;
    sslmode)  [[ "$v" =~ ^(require|verify-ca|verify-full)$ ]] ;;
    file)     [[ -r "$v" ]] ;;
    email)    [[ "$v" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] ;;
    pw16)     [[ ${#v} -ge 16 ]] ;;
    url)      [[ "$v" =~ ^https://[^[:space:]]+$ ]] ;;
    promurl)  [[ "$v" =~ ^https://[A-Za-z0-9.-]+\.grafana\.net/api/prom/push$ ]] && [[ "$v" != *internal* && "$v" != *local-nlb* ]] ;;
    lokiurl)  [[ "$v" =~ ^https://[A-Za-z0-9.-]+\.grafana\.net/loki/api/v1/push$ ]] && [[ "$v" != *internal* && "$v" != *local-nlb* ]] ;;
    uatenv)   [[ "$v" == uat ]] ;;
    days)     [[ "$v" =~ ^[0-9]+$ ]] && [[ "$v" -ge 3 && "$v" -le 365 ]] ;;
    mode)     [[ "$v" =~ ^(sweep|lifecycle|off)$ ]] ;;
    domain)   [[ "$v" =~ ^[a-z0-9.-]+\.[a-z]{2,}$ ]] ;;
    host)     [[ "$v" =~ ^[A-Za-z0-9.-]+$ ]] ;;
    ghuser)   [[ "$v" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$ ]] && ! looks_secret "$v" ;;
    ghtoken)  [[ "$v" =~ ^(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})$ ]] ;;
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
  local a b
  a="$(envfile_get "$ENV_FILE" CLOUDFLARE_DEPLOY_TOKEN)"; b="$(envfile_get "$ENV_FILE" CLOUDFLARE_INFRA_TOKEN)"
  if [[ ( "$group" == all || "$group" == github || "$group" == tofu ) && -n "$a" && "$a" == "$b" ]]; then bad "CLOUDFLARE_DEPLOY_TOKEN equals CLOUDFLARE_INFRA_TOKEN: use two tokens (the GitHub one must be the weaker)"; fi
  if [[ "$group" == all || "$group" == cluster ]]; then
    a="$(envfile_get "$ENV_FILE" STRIPE_WEBHOOK_SECRET)"; b="$(envfile_get "$ENV_FILE" STRIPE_CONNECT_WEBHOOK_SECRET)"
    [[ -n "$a" && "$a" == "$b" ]] && bad "STRIPE_WEBHOOK_SECRET equals STRIPE_CONNECT_WEBHOOK_SECRET: each endpoint has its own whsec_"
    a="$(envfile_get "$ENV_FILE" EXTERNAL_PG_SSLMODE)"
    case "$a" in verify-ca|verify-full)
      [[ -r "$(envfile_get "$ENV_FILE" EXTERNAL_PG_CA_FILE)" ]] || bad "EXTERNAL_PG_SSLMODE=$a needs EXTERNAL_PG_CA_FILE (readable PEM)" ;; esac
    [[ "$a" == "require" ]] && warn "EXTERNAL_PG_SSLMODE=require encrypts but does not verify the server; move to verify-full before PROD"
    [[ -z "$(envfile_get "$ENV_FILE" R2_ACCOUNT_ID)" && -z "$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)" ]] && bad "R2_ACCOUNT_ID and CLOUDFLARE_ACCOUNT_ID are both blank"
  fi

  # first-apply placeholders that were never replaced (fill records a hash of each, never the value)
  if [[ -r "$ENV_FILE.placeholders" ]]; then
    local pn ph
    while read -r pn ph; do
      [[ -n "$pn" ]] || continue
      [[ -n "$(envfile_get "$ENV_FILE" "$pn")" && "$(hash_of "$(envfile_get "$ENV_FILE" "$pn")")" == "$ph" ]] && warn "$pn is still the random first-apply placeholder: replace it with the real value once the endpoint exists"
    done <"$ENV_FILE.placeholders"
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
  if [[ -t 1 && "${1:-}" != "--show" && "${2:-}" != "--show" ]]; then
    echo "This prints secret values for eval. Use: eval \"\$(tools/release/uat-env.sh tofu-env)\"   (refusing to print to a terminal; --show overrides)" >&2; exit 1
  fi
  local n v
  if [[ "${1:-}" == "cloudflare" || "${2:-}" == "cloudflare" ]]; then
    # only the Cloudflare pair: lets you run infra/cloudflare before the OVH values exist
    local tok acc
    tok="$(envfile_get "$ENV_FILE" CLOUDFLARE_INFRA_TOKEN)"; acc="$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)"
    valid_value token "$tok" || { echo "CLOUDFLARE_INFRA_TOKEN is blank or malformed (uat-env.sh fill --only CLOUDFLARE_INFRA_TOKEN)" >&2; exit 1; }
    valid_value hex32 "$acc" || { echo "CLOUDFLARE_ACCOUNT_ID is blank or malformed (uat-env.sh fill --only CLOUDFLARE_ACCOUNT_ID)" >&2; exit 1; }
    printf 'export CLOUDFLARE_API_TOKEN=%q\n' "$tok"
    printf 'export CLOUDFLARE_ACCOUNT_ID=%q\n' "$acc"
    return 0
  fi
  stage_check tofu >&2 || { echo "Fix the tofu group first (or use: tofu-env cloudflare for the Cloudflare run only)." >&2; exit 1; }
  for n in OVH_APPLICATION_KEY OVH_APPLICATION_SECRET OVH_CONSUMER_KEY; do printf 'export %s=%q\n' "$n" "$(envfile_get "$ENV_FILE" "$n")"; done
  printf 'export OVH_ENDPOINT=%q\n' ovh-us
  printf 'export CLOUDFLARE_API_TOKEN=%q\n' "$(envfile_get "$ENV_FILE" CLOUDFLARE_INFRA_TOKEN)"
  printf 'export CLOUDFLARE_ACCOUNT_ID=%q\n' "$(envfile_get "$ENV_FILE" CLOUDFLARE_ACCOUNT_ID)"
  printf 'export TF_VAR_ovh_service_name=%q\n' "$(envfile_get "$ENV_FILE" OVH_SERVICE_NAME)"
  printf 'export TF_VAR_ovh_endpoint=%q\n' ovh-us
  v="$(envfile_get "$ENV_FILE" OVH_ADMIN_CIDR)"
  printf 'export TF_VAR_admin_cidrs=%q\n' "[\"$v\"]"
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

# ---------------------------------------------------------------------------------------------------------------------------
# fill: interactive, one value at a time
# ---------------------------------------------------------------------------------------------------------------------------
TTY_IN="${SETUP_TTY:-/dev/tty}"
# names whose value is not secret: echoed back so you can catch a typo. Everything else is typed hidden.
PLAIN_RE='^(DOMAIN|IMAGE_REGISTRY|IMAGE_TAG|DB_MODE|DB_LAYOUT|EXTERNAL_PG_HOST|EXTERNAL_PG_ADMIN_USER|EXTERNAL_PG_SSLMODE|GHCR_USER|ADMIN_EMAIL|ADMIN_PHONE|OVH_SERVICE_NAME|OVH_ADMIN_CIDR|CLOUDFLARE_ACCOUNT_ID|GITOPS_APP_ID|GRAFANA_ENV|GRAFANA_[A-Z_]*_URL|GRAFANA_[A-Z_]*_USER|GRAFANA_OTLP_ENDPOINT|GRAFANA_INSTANCE_ID|BACKUP_AGE_RECIPIENT|BACKUP_B2_BUCKET|BACKUP_B2_ENDPOINT|BACKUP_RETENTION_[A-Z]*|BACKUP_PUSHGATEWAY_URL|BACKUP_HEALTHCHECK_URL|R2_ACCOUNT_ID|[A-Z_]*_FILE)$'

hash_of() { if command -v sha256sum >/dev/null 2>&1; then printf '%s' "$1" | sha256sum | cut -d' ' -f1; else printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; fi; }

# the comment block above NAME in the example (its own words on where to get it); banners are skipped
example_help() {
  awk -v n="$1" '
    /^# =+$/ { blk=""; inblk=0; next }
    /^#/ { if (!inblk) { blk=""; inblk=1 } sub(/^# ?/,""); blk = blk "      " $0 "\n"; next }
    { inblk=0 }
    $0 ~ ("^" n "=") { printf "%s", blk; exit }
  ' "$EXAMPLE"
}

quote_if_needed() { case "$1" in ""|*[\ \#\$\"\'\\]*) printf '"%s"' "$1" ;; *) printf '%s' "$1" ;; esac; }

# set_value NAME VALUE: replaces the NAME= line in place (value passed through the environment, never an argument), or appends it
set_value() {
  local n="$1" v; v="$(quote_if_needed "$2")"
  if grep -qE "^${n}=" "$ENV_FILE"; then
    ( umask 077; V="$v" awk -v n="$n" 'BEGIN{p="^" n "="} $0 ~ p {print n "=" ENVIRON["V"]; next} {print}' "$ENV_FILE" >"$ENV_FILE.tmp" ) && mv "$ENV_FILE.tmp" "$ENV_FILE"
  else
    printf '%s=%s\n' "$n" "$v" >>"$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE"
}

forget_placeholder() { [[ -f "$ENV_FILE.placeholders" ]] && { grep -v "^$1 " "$ENV_FILE.placeholders" >"$ENV_FILE.placeholders.tmp" || true; mv "$ENV_FILE.placeholders.tmp" "$ENV_FILE.placeholders"; }; }

rand_pw() { if command -v openssl >/dev/null 2>&1; then openssl rand -base64 36 | tr -dc 'A-Za-z0-9' | cut -c1-24; else LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; fi; }
rand_hex() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24; else LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 48; fi; }

stage_fill() {
  local group="all" only=""
  case "${1:-}" in
    --only) only="${2:-}"; [[ -n "$only" ]] || { echo "usage: fill --only NAME" >&2; exit 2; } ;;
    github|tofu|cluster|all|"") group="${1:-all}" ;;
    *) echo "usage: fill [github|tofu|cluster|all] | fill --only NAME" >&2; exit 2 ;;
  esac
  if [[ ! -r "$ENV_FILE" ]]; then
    echo "No file yet at $ENV_FILE."; stage_init || exit 1
  fi
  exec 3<"$TTY_IN" || { echo "no terminal to ask on" >&2; exit 2; }
  echo "Filling $ENV_FILE. Each value is checked and saved the moment you enter it, so Ctrl-C loses nothing."
  echo "Enter on an empty prompt skips that value. Values for secrets are typed hidden. Nothing is printed back except non-secret values."
  local name grp req chk hint v attempt done_ok n_set=0 n_skip=0 n_have=0 hidden
  while IFS='|' read -r name grp req chk hint; do
    [[ -n "$name" ]] || continue
    if [[ -n "$only" ]]; then [[ "$name" == "$only" ]] || continue; else [[ "$group" == "all" || "$group" == "$grp" ]] || continue; fi
    if [[ -n "$(envfile_get "$ENV_FILE" "$name")" && "${FORCE:-0}" != "1" ]]; then n_have=$((n_have+1)); continue; fi
    printf '\n[%s] %s%s\n' "$grp" "$name" "$([[ "$req" == "0" ]] && echo '  (optional)')"
    example_help "$name"
    echo "      Expected: $hint"
    case "$name" in
      R2_ACCOUNT_ID) echo "      Leave this blank: it then reuses CLOUDFLARE_ACCOUNT_ID, because the same Cloudflare account owns the buckets. Enter to skip." ;;
      R2_*|CLOUDFLARED_TUNNEL_TOKEN) echo "      Usually only available after the Cloudflare tofu run (it creates the buckets and the tunnel). Enter to skip for now." ;;
      STRIPE_*WEBHOOK_SECRET|RESEND_WEBHOOK_SECRET) echo "      Usually only available after the first deploy. Enter to skip, or type p for a random first-apply placeholder (replace it later)." ;;
      ADMIN_PASSWORD) echo "      Type g to generate a strong one (it is shown once on this screen: put it in your password manager)." ;;
      OVH_ADMIN_CIDR) echo "      Look up your public IP yourself (for example your router page or a what-is-my-IP site); this tool never sends it anywhere." ;;
    esac
    hidden=1; [[ "$name" =~ $PLAIN_RE ]] && hidden=0
    attempt=0; done_ok=0
    while [[ "$attempt" -lt 3 ]]; do
      attempt=$((attempt+1)); v=""
      if [[ "$hidden" == "1" && -t 0 && "$TTY_IN" == "/dev/tty" ]]; then IFS= read -r -s -p "Value (hidden, Enter to skip): " v <&3; printf '\n'
      else IFS= read -r v <&3; fi
      if [[ -z "$v" ]]; then break; fi
      case "$name:$v" in
        ADMIN_PASSWORD:g) v="$(rand_pw)"; echo "      Generated: $v   <- store it in your password manager now" ;;
        STRIPE_*WEBHOOK_SECRET:p|RESEND_WEBHOOK_SECRET:p) v="whsec_$(rand_hex)"; printf '%s %s\n' "$name" "$(hash_of "$v")" >>"$ENV_FILE.placeholders"; chmod 600 "$ENV_FILE.placeholders"
           echo "      Saved a random placeholder (recorded so check reminds you to replace it)." ;;
      esac
      if [[ "$chk" == "file" ]]; then v="${v/#\~/$HOME}"; fi
      if [[ "$hidden" == "0" ]] && looks_secret "$v"; then
        echo "      That looks like a token or key, not a $name. Nothing was saved or shown. Try again ($attempt/3) or Enter to skip."; v=""; continue
      fi
      case "$v" in sk_live_*|rk_live_*|pk_live_*) echo "      That is a LIVE Stripe key: UAT is test mode only. Try again ($attempt/3) or Enter to skip."; continue ;; esac
      if [[ "$name" == GITOPS_APP_PRIVATE_KEY_FILE ]] && ! head -1 "$v" 2>/dev/null | grep -q -- '-----BEGIN .*PRIVATE KEY-----'; then
        echo "      That file is not a readable PEM private key. Try again ($attempt/3) or Enter to skip."; continue
      fi
      if valid_value "$chk" "$v"; then
        set_value "$name" "$v"
        case "$name:$v" in *:whsec_*) :;; *) forget_placeholder "$name" ;; esac
        if [[ "$hidden" == "0" ]]; then echo "      saved: $v"; else echo "      saved (${#v} characters, not shown)"; fi
        n_set=$((n_set+1)); done_ok=1; break
      fi
      echo "      That does not look right (want: $hint). You entered ${#v} characters. Try again ($attempt/3) or Enter to skip."
    done
    v=""
    [[ "$done_ok" == "1" ]] || { echo "      skipped"; n_skip=$((n_skip+1)); }
  done < <(spec)
  echo; echo "$n_set saved, $n_skip skipped, $n_have were already set (FORCE=1 to re-ask those)."
  if [[ -n "$only" ]]; then stage_check all || true; else stage_check "$group" || true; fi
  echo "Come back any time: tools/release/uat-env.sh fill   (it asks only for what is still blank)."
}

case "${1:-}" in
  init)     stage_init ;;
  fill)     shift; stage_fill "$@" ;;
  check)    stage_check "${2:-all}" ;;
  github)   stage_github ;;
  split)    stage_split ;;
  tofu-env) stage_tofu_env "${2:-}" "${3:-}" ;;
  drift)    stage_drift ;;
  *) sed -n '2,18p' "$0"; exit 0 ;;
esac
