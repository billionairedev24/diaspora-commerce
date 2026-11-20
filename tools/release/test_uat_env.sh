#!/usr/bin/env bash
# Offline test of uat-env.sh and the central-file path of uat-github-setup.sh. Builds a throwaway tree; touches nothing real; no network; fake gh.
set -uo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
FAILS=0
t() { if "$@"; then :; else FAILS=$((FAILS+1)); echo "TEST FAILED: $*"; fi; }
eq()  { [[ "$1" == "$2" ]] || { echo "  expected [$2] got [$1]"; return 1; }; }
has() { grep -q -- "$2" <<<"$1" || { echo "  missing: $2"; return 1; }; }
lacks() { ! grep -q -- "$2" <<<"$1" || { echo "  LEAKED a value into output"; return 1; }; }

mkdir -p "$T/repo/tools/release" "$T/repo/infra/ovh-vps" "$T/home"
cp "$SRC"/tools/release/{uat-env.sh,uat-github-setup.sh,lib-envfile.sh} "$T/repo/tools/release/"
cp "$SRC"/infra/ovh-vps/{uat.all.env.example,config.uat.env.example,secrets.uat.env.example} "$T/repo/infra/ovh-vps/"
cp "$SRC/.gitignore" "$T/repo/.gitignore"; git -C "$T/repo" init -q
ENVX="$T/repo/tools/release/uat-env.sh"; export HOME="$T/home"; export UAT_ENV_FILE="$T/home/.afrotransact/uat.all.env"

# secrets with sentinel text so leaks are detectable
S_SK="sk_test_SENTINELsecretkey0001"; S_PK="pk_test_SENTINELpublic00001"
S_W1="whsec_SENTINELwebhookone0000001"; S_W2="whsec_SENTINELwebhooktwo0000002"
S_DEP="DEPLOYtokenSENTINELaaaaaaaaaaaaaaaaaaaa1"; S_INF="INFRAtokenSENTINELbbbbbbbbbbbbbbbbbbbbb2"
S_PW='pa ss#word$with"quote-SENTINEL'

fill() { # fill NAME VALUE (sets or replaces a line in the user's file)
  local f="$UAT_ENV_FILE" n="$1" v="$2"
  grep -v -E "^${n}=" "$f" >"$f.new"; printf '%s=%s\n' "$n" "$v" >>"$f.new"; mv "$f.new" "$f"; chmod 600 "$f"
}
fill_all() {
  printf -- '-----BEGIN RSA PRIVATE KEY-----\nSENTINELPEM\n-----END RSA PRIVATE KEY-----\n' >"$T/app.pem"
  fill GITOPS_APP_ID 48213; fill GITOPS_APP_PRIVATE_KEY_FILE "$T/app.pem"
  fill CLOUDFLARE_ACCOUNT_ID 0123456789abcdef0123456789abcdef; fill CLOUDFLARE_DEPLOY_TOKEN "$S_DEP"; fill CLOUDFLARE_INFRA_TOKEN "$S_INF"
  fill OVH_APPLICATION_KEY ovhkeySENTINEL; fill OVH_APPLICATION_SECRET ovhsecretSENTINEL; fill OVH_CONSUMER_KEY ovhconsumerSENTINEL
  fill OVH_SERVICE_NAME 0123456789abcdef; fill OVH_ADMIN_CIDR 203.0.113.7/32
  fill EXTERNAL_PG_HOST db.example.layerbase.test; fill EXTERNAL_PG_ADMIN_USER admin; fill EXTERNAL_PG_ADMIN_PASSWORD "\"$S_PW\""
  fill EXTERNAL_REDIS_URL 'rediss://user:SENTINELpw@cache.example.test:6380'
  fill GHCR_USER octo; fill GHCR_TOKEN ghp_SENTINELghcrtoken0000000000000000000
  fill ADMIN_EMAIL owner@example.test; fill ADMIN_PASSWORD 'SENTINEL-admin-password-16+'
  fill STRIPE_SECRET_KEY "$S_SK"; fill STRIPE_PUBLISHABLE_KEY "$S_PK"; fill STRIPE_WEBHOOK_SECRET "$S_W1"; fill STRIPE_CONNECT_WEBHOOK_SECRET "$S_W2"
  fill RESEND_API_KEY re_SENTINELresend; fill RESEND_WEBHOOK_SECRET whsec_SENTINELresendhook0000003; fill OPENROUTER_API_KEY sk-or-SENTINEL
  fill R2_ACCESS_KEY_ID r2idSENTINEL; fill R2_SECRET_ACCESS_KEY r2secSENTINEL; fill R2_PRIVATE_ACCESS_KEY_ID r2pidSENTINEL; fill R2_PRIVATE_SECRET_ACCESS_KEY r2psecSENTINEL
  fill CLOUDFLARED_TUNNEL_TOKEN tunnelSENTINEL
  fill GRAFANA_PROM_URL https://prom.example.test/push; fill GRAFANA_PROM_USER 123456; fill GRAFANA_LOKI_URL https://loki.example.test/push; fill GRAFANA_LOKI_USER 654321; fill GRAFANA_ALLOY_TOKEN glc_SENTINEL
  fill BACKUP_AGE_RECIPIENT "age1$(printf 'q%.0s' $(seq 1 58))"; fill BACKUP_B2_KEY_ID b2idSENTINEL; fill BACKUP_B2_APPLICATION_KEY b2keySENTINEL; fill BACKUP_B2_ENDPOINT s3.us-west-004.backblazeb2.com
}

echo "-- init"
out="$("$ENVX" init 2>&1)"; t has "$out" "created"
t eq "$(stat -c %a "$UAT_ENV_FILE" 2>/dev/null || stat -f %Lp "$UAT_ENV_FILE")" 600
out="$("$ENVX" init 2>&1)"; rc=$?; t eq "$rc" 1; t has "$out" "already exists"

echo "-- check on the blank example fails and names the blanks"
out="$("$ENVX" check 2>&1)"; rc=$?; t test "$rc" -ne 0; t has "$out" "STRIPE_SECRET_KEY is blank"; t has "$out" "GITOPS_APP_ID is blank"

echo "-- a complete file passes and prints no value"
fill_all
out="$("$ENVX" check 2>&1)"; rc=$?; t eq "$rc" 0; t has "$out" "0 problem"
t lacks "$out" SENTINEL

echo "-- guards"
fill STRIPE_SECRET_KEY sk_live_SENTINELlive00000001; out="$("$ENVX" check cluster 2>&1)"; t has "$out" "LIVE Stripe key"; fill STRIPE_SECRET_KEY "$S_SK"
fill CLOUDFLARE_INFRA_TOKEN "$S_DEP"; out="$("$ENVX" check 2>&1)"; t has "$out" "equals CLOUDFLARE_INFRA_TOKEN"; fill CLOUDFLARE_INFRA_TOKEN "$S_INF"
fill STRIPE_CONNECT_WEBHOOK_SECRET "$S_W1"; out="$("$ENVX" check cluster 2>&1)"; t has "$out" "each endpoint has its own"; fill STRIPE_CONNECT_WEBHOOK_SECRET "$S_W2"
chmod 644 "$UAT_ENV_FILE"; out="$("$ENVX" check 2>&1)"; t has "$out" "must be 600"; chmod 600 "$UAT_ENV_FILE"
fill GRAFANA_ENV prod; out="$("$ENVX" check cluster 2>&1)"; t has "$out" "GRAFANA_ENV has the wrong shape"; fill GRAFANA_ENV uat
fill OVH_ADMIN_CIDR 0.0.0.0/0; out="$("$ENVX" check tofu 2>&1)"; t has "$out" "OVH_ADMIN_CIDR has the wrong shape"; fill OVH_ADMIN_CIDR 203.0.113.7/32
fill BACKUP_RETENTION_DAYS "14 # two weeks"; out="$("$ENVX" check cluster 2>&1)"; t has "$out" "unquoted value contains"; fill BACKUP_RETENTION_DAYS 14
fill EXTERNAL_PG_SSLMODE verify-full; out="$("$ENVX" check cluster 2>&1)"; t has "$out" "needs EXTERNAL_PG_CA_FILE"; fill EXTERNAL_PG_SSLMODE require
out="$("$ENVX" check 2>&1)"; t eq "$?" 0

echo "-- a file inside the repo that git would commit is refused"
cp "$UAT_ENV_FILE" "$T/repo/loose.env"; chmod 600 "$T/repo/loose.env"
out="$(UAT_ENV_FILE="$T/repo/loose.env" "$ENVX" check 2>&1)"; t has "$out" "NOT git-ignored"

echo "-- split writes files that bash reads back to the same values"
out="$("$ENVX" split 2>&1)"; rc=$?; t eq "$rc" 0; t lacks "$out" SENTINEL
for f in config.env secrets.local.env; do t eq "$(stat -c %a "$T/repo/infra/ovh-vps/$f" 2>/dev/null || stat -f %Lp "$T/repo/infra/ovh-vps/$f")" 600; done
got="$(bash -c 'set -a; . "$1"; . "$2"; printf "%s" "$EXTERNAL_PG_ADMIN_PASSWORD"' _ "$T/repo/infra/ovh-vps/config.env" "$T/repo/infra/ovh-vps/secrets.local.env")"
t eq "$got" "$S_PW"
got="$(bash -c 'set -a; . "$1"; . "$2"; printf "%s|%s" "$STRIPE_SECRET_KEY" "$R2_ACCOUNT_ID"' _ "$T/repo/infra/ovh-vps/config.env" "$T/repo/infra/ovh-vps/secrets.local.env")"
t eq "$got" "$S_SK|0123456789abcdef0123456789abcdef"
echo "x" >"$T/repo/infra/ovh-vps/config.env"; out="$("$ENVX" split 2>&1)"; t has "$out" "refusing to overwrite"

echo "-- tofu-env refuses a terminal-free leak check: output evals to the right environment"
out="$("$ENVX" tofu-env 2>/dev/null)"; t has "$out" "OVH_ENDPOINT"
got="$(bash -c 'eval "$1"; printf "%s|%s|%s" "$CLOUDFLARE_API_TOKEN" "$OVH_ENDPOINT" "$TF_VAR_admin_cidrs"' _ "$out")"; t eq "$got" "$S_INF|ovh-us|[\"203.0.113.7/32\"]"

echo "-- GitHub setup takes values from the file, over stdin, and prompts only for blanks"
mkdir -p "$T/bin"; cat >"$T/bin/gh" <<'EOF'
#!/usr/bin/env bash
echo "ARGV: $*" >>"$GHLOG"
case "$1 $2" in
  "auth status") exit 0;;
  "api repos/billionairedev24/diaspora-commerce") echo billionairedev24/diaspora-commerce;;
  "api repos/billionairedev24/diaspora-commerce/environments") echo uat;;
  "api repos/billionairedev24/diaspora-commerce/actions/permissions/workflow") echo true;;
  "secret set") echo "STDIN($3)=$(cat)" >>"$GHLOG";;
esac
EOF
chmod +x "$T/bin/gh"; export GHLOG="$T/gh.log"; : >"$GHLOG"
printf '%s\n' "" "" "" "n" >"$T/answers"
out="$(PATH="$T/bin:$PATH" SETUP_TTY="$T/answers" "$T/repo/tools/release/uat-github-setup.sh" set 2>&1)"; t has "$out" "using the value from the central file"; t lacks "$out" SENTINEL
t eq "$(grep -c 'STDIN(GITOPS_APP_ID)=48213' "$GHLOG")" 1
t eq "$(grep -c "STDIN(CLOUDFLARE_API_TOKEN)=$S_DEP" "$GHLOG")" 1
t eq "$(grep -c 'STDIN(GITOPS_APP_PRIVATE_KEY)=-----BEGIN' "$GHLOG")" 1
t eq "$(grep 'ARGV:' "$GHLOG" | grep -c -e SENTINEL -e 48213)" 0
t eq "$(grep 'ARGV: secret set' "$GHLOG" | grep -c -e '--body')" 0

echo "-- drift guard on the real tree"
t "$SRC/tools/release/uat-env.sh" drift >/dev/null

[[ "$FAILS" -eq 0 ]] && echo "test_uat_env: all passed" || { echo "test_uat_env: $FAILS failed"; exit 1; }
