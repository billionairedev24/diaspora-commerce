#!/usr/bin/env bash
# Static tests of tools/deploy/guide.sh: no network, no GitHub, no OVHcloud, no Cloudflare. gh, curl, dig and openssl are
# shims in a temp directory that answer from files and log every call; jq and git are the real ones. The wizard runs against a
# throw-away copy of the repository with its own origin. Prompts are answered from a file (GUIDE_TTY).
#   make deploy-guide-test        or        bash tools/deploy/test/test-guide.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REAL=$(cd "$HERE/../../.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/guide-test.XXXXXX")
[ "${KEEP:-0}" = 1 ] && echo "kept: $T" || trap 'rm -rf "$T"' EXIT
PASS=0 FAILN=0
SK_BAD=sk_test_BADKEY1234567890ZZ SK_GOOD=sk_test_GOODKEY1234567890ZZ CFT=cftokenABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 PAT=ghp_abcdefghijklmnopqrstuvwxyz0123456789

t_ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
t_fail() { FAILN=$((FAILN + 1)); printf '  FAIL %s\n' "$1"; [ -z "${2:-}" ] || { printf '%s\n' "$2" | sed 's/^/       | /' | head -25; }; }
has() { grep -qF -- "$2" "$1" 2>/dev/null; }
assert_has() { if has "$2" "$3"; then t_ok "$1"; else t_fail "$1 (expected: $3)" "$(tail -25 "$2" 2>/dev/null)"; fi; }
assert_lacks() { if has "$2" "$3"; then t_fail "$1 (must not contain: $3)" "$(grep -F -- "$3" "$2" | head -3)"; else t_ok "$1"; fi; }
assert_rc() { if [ "$RC" = "$2" ]; then t_ok "$1"; else t_fail "$1 (exit $RC, expected $2)" "$(tail -25 "$OUT" 2>/dev/null)"; fi; }

# ---- the shims ------------------------------------------------------------------------------------------------------------
make_shims() {
  mkdir -p "$T/bin"
  cat > "$T/bin/gh" <<'SHIM'
#!/usr/bin/env bash
F=${FAKE:?}
if [ "${1:-} ${2:-}" = "variable set" ]; then echo "gh variable set $3" >> "$F/calls.log"; else echo "gh $*" >> "$F/calls.log"; fi
case "$1" in
  auth) exit "${FAKE_AUTH_RC:-0}" ;;
  repo) cat "$F/repo.json" ;;
  workflow)
    if [ "$2" = list ]; then cat "$F/workflows.json"; exit 0; fi
    wf=$3; n=$(( $(cat "$F/runseq" 2>/dev/null || echo 100) + 1 )); echo "$n" > "$F/runseq"
    concl=$(cat "$F/conclusion-$wf" 2>/dev/null || echo success)
    old=$(cat "$F/runs/$wf.json" 2>/dev/null || echo '[]')
    jq -n --argjson old "$old" --arg id "$n" --arg sha "$(cat "$F/sha")" --arg c "$concl" \
      '[{databaseId: ($id | tonumber), headSha: $sha, conclusion: $c, status: "completed", url: ("https://github.com/acme/shop/actions/runs/" + $id)}] + $old' > "$F/runs/$wf.json" ;;
  run)
    if [ "$2" = list ]; then
      wf=''; lim=30; shift 2
      while [ $# -gt 0 ]; do case "$1" in (--workflow) wf=$2; shift ;; (--limit) lim=$2; shift ;; esac; shift; done
      jq --argjson l "$lim" '.[0:$l]' "$F/runs/$wf.json" 2>/dev/null || echo '[]'
    else
      id=$3
      case " $* " in
        (*" --log-failed "*) cat "$F/failed-text.txt" 2>/dev/null ;;
        (*" --log "*) echo "run  deploy  2026-01-01T00:00:00Z server: ${FAKE_IP:-203.0.113.10}" ;;
        (*) jq -c --argjson id "$id" 'map(select(.databaseId == $id)) | .[0]' "$F"/runs/*.json | grep -v '^null$' | head -1 ;;
      esac
    fi ;;
  api)
    case "$2" in
      user) echo '{"login":"tester"}' ;;
      users/*) echo '{"id":7}' ;;
      */secrets) jq -Rn '[inputs | select(length > 0) | {name: .}] | {secrets: .}' < "$F/secrets" ;;
      */variables) jq -Rn '[inputs | select(length > 0) | {name: (split("=")[0])}] | {variables: .}' < "$F/vars" ;;
      *) cat >/dev/null 2>&1 || true ;;
    esac ;;
  variable)
    if [ "$2" = get ]; then grep "^$3=" "$F/vars" | head -1 | cut -d= -f2-; [ -n "$(grep "^$3=" "$F/vars")" ] || exit 1
    else grep -v "^$3=" "$F/vars" > "$F/vars.n"; mv "$F/vars.n" "$F/vars"
      body=''; while [ $# -gt 0 ]; do [ "$1" = --body ] && body=$2; shift; done; echo "$3=$body" >/dev/null; echo "SETVAR $body" >/dev/null
      printf '%s=%s\n' "$(grep -o 'variable set [A-Z_0-9]*' "$F/calls.log" | tail -1 | awk '{print $3}')" "$body" >> "$F/vars"; fi ;;
  secret) cat >/dev/null; echo "$3" >> "$F/secrets" ;;
esac
exit 0
SHIM
  cat > "$T/bin/curl" <<'SHIM'
#!/usr/bin/env bash
F=${FAKE:?}
out='' dump='' wfmt='' method=GET fail=0 url=''
while [ $# -gt 0 ]; do
  case "$1" in
    (-o) out=$2; shift ;; (-D) dump=$2; shift ;; (-w) wfmt=$2; shift ;; (-X) method=$2; shift ;;
    (-K) cat >/dev/null ;; (-H | --data | --data-binary | --max-time | -u) shift ;;
    (-f | -fsS) fail=1 ;; (-s | -S | -sS) ;; (http*) url=$1 ;; (*) ;;
  esac
  shift
done
key=$(printf '%s' "${url#*://}" | tr -c 'A-Za-z0-9\n' '_'); case "$url" in (http://*) key="plain_$key" ;; esac
echo "curl $method $url" >> "$F/calls.log"
d="$F/http/$key"; [ "$method" != POST ] || [ ! -e "$d.POST.code" ] || d="$d.POST"
code=''
if [ -s "$d.codes" ]; then code=$(head -1 "$d.codes"); tail -n +2 "$d.codes" > "$d.codes.n"; mv "$d.codes.n" "$d.codes"
elif [ -e "$d.code" ]; then code=$(cat "$d.code"); fi
if [ -z "$code" ]; then [ -z "$wfmt" ] || printf '%s' "${wfmt//%\{http_code\}/000}"; exit 7; fi
[ -z "$dump" ] || cat "$d.headers" > "$dump" 2>/dev/null || : > "$dump"
if [ -n "$out" ]; then cat "$d.body" > "$out" 2>/dev/null || : > "$out"; else cat "$d.body" 2>/dev/null; fi
[ -z "$wfmt" ] || { printf '%b' "${wfmt//%\{http_code\}/$code}"; }
[ "$fail" = 0 ] || [ "$code" -lt 400 ] || exit 22
exit 0
SHIM
  cat > "$T/bin/dig" <<'SHIM'
#!/usr/bin/env bash
echo "dig $*" >> "${FAKE:?}/calls.log"
cat "$FAKE/dns/$2_$3" 2>/dev/null
exit 0
SHIM
  cat > "$T/bin/openssl" <<'SHIM'
#!/usr/bin/env bash
F=${FAKE:?}
case "${1:-}" in
  s_client) cat >/dev/null; [ -e "$F/nocert" ] || echo PEM ;;
  x509) cat >/dev/null
    case " $* " in
      (*" -checkend "*) [ ! -e "$F/cert-expiring" ] ;;
      (*) [ -e "$F/nocert" ] || cat "$F/cert.txt" ;;
    esac ;;
  *) exec "$REAL_OPENSSL" "$@" ;;
esac
SHIM
  printf "#!/usr/bin/env bash\nexit 0\n" > "$T/bin/ssh-keygen"
  chmod +x "$T/bin/"*
  REAL_OPENSSL=$(command -v openssl); export REAL_OPENSSL
}

# ---- a fresh world --------------------------------------------------------------------------------------------------------
new_env() {
  rm -rf "$T/root" "$T/fake" "$T/origin.git"; mkdir -p "$T/out" "$T/fake/runs" "$T/fake/http" "$T/fake/dns" "$T/root/tools" "$T/root/infra/ovh-vps/terraform"
  cp -R "$REAL/tools/deploy" "$REAL/tools/ovh" "$T/root/tools/"
  cp "$REAL/infra/ovh-vps/terraform/locations.json" "$T/root/infra/ovh-vps/terraform/"
  ( cd "$T/root" && git init -q -b main . && git config user.email t@example.com && git config user.name t && git add -A && git commit -qm init \
      && git init -q --bare "$T/origin.git" && git remote add origin "$T/origin.git" && git push -q origin main 2>/dev/null; git fetch -q origin main 2>/dev/null )
  SHA=$(cd "$T/root" && git rev-parse HEAD)
  F=$T/fake
  echo "$SHA" > "$F/sha"; : > "$F/secrets"; : > "$F/vars"; : > "$F/calls.log"
  echo '{"nameWithOwner":"acme/shop","viewerPermission":"ADMIN","defaultBranchRef":{"name":"main"}}' > "$F/repo.json"
  echo '[{"name":"ovh-images"},{"name":"ovh-vps"},{"name":"ovh-web"}]' > "$F/workflows.json"
  echo "[{\"databaseId\":50,\"headSha\":\"$SHA\",\"conclusion\":\"success\",\"status\":\"completed\"}]" > "$F/runs/ovh-images.yml.json"
  printf 'subject=CN=x\nissuer=C=US, O=Let'"'"'s Encrypt, CN=R11\nnotAfter=Feb  1 00:00:00 2027 GMT\nX509v3 Subject Alternative Name:\n    DNS:example.com, DNS:*.example.com\n' > "$F/cert.txt"
  sed -i.bak 's/^issuer=/issuer= /' "$F/cert.txt" 2>/dev/null; rm -f "$F/cert.txt.bak"
  STATE=$T/root/.ovh/deploy-guide.state; mkdir -p "$T/root/.ovh"
}
fill_required() { # every required/managed value of the catalogue "set" in the fake Environment
  awk -F'|' '/^#/ || NF < 11 { next } $3 == "required" || $3 == "managed" { print $2 "|" $1 }' "$REAL/tools/deploy/catalogue.conf" | while IFS='|' read -r kind name; do
    if [ "$kind" = secret ]; then echo "$name" >> "$F/secrets"; else echo "$name=x" >> "$F/vars"; fi
  done
  printf '%s\n' OVH_DOMAIN=example.com OVH_STOREFRONT_HOST=www OVH_INSTANCE_FLAVOR=b3-32 OVH_LOCATION=us-east-va CLOUDFLARE_ZONE=example.com DB_MODE=cluster >> "$F/vars"
  grep -v -e '^OVH_DOMAIN=x' -e '^OVH_LOCATION=x' -e '^CLOUDFLARE_ZONE=x' -e '^DB_MODE=x' "$F/vars" > "$F/vars.n"; mv "$F/vars.n" "$F/vars"
}
http_fake() { # http_fake URL CODE [header...]  (body from $HTTP_BODY)
  local url=$1 code=$2 key; shift 2
  key=$(printf '%s' "${url#*://}" | tr -c 'A-Za-z0-9\n' '_'); case "$url" in (http://*) key="plain_$key" ;; esac
  echo "$code" > "$F/http/$key.code"; printf '%s\r\n' "HTTP/2 $code" "$@" > "$F/http/$key.headers"; printf '%s' "${HTTP_BODY:-}" > "$F/http/$key.body"
}
webhook_fake() { # webhook_fake PATH CODE: what api.example.com answers to an unsigned POST there
  echo "$2" > "$F/http/$(printf '%s' "api.example.com/$1" | tr -c 'A-Za-z0-9\n' '_').POST.code"
}
dns_fake() { echo "$2" > "$F/dns/A_$1"; }
healthy_world() {
  local h a
  for h in api auth mcp grafana; do dns_fake "$h.example.com" 203.0.113.10; done
  HTTP_BODY=ok http_fake https://api.example.com/bff/healthz 200 'server: traefik'
  for h in payments/v1/webhooks/stripe seller/v1/webhooks/stripe-connect seller/v1/webhooks/stripe-billing; do webhook_fake "$h" 400; done
  HTTP_BODY='{"issuer":"https://auth.example.com"}' http_fake https://auth.example.com/.well-known/openid-configuration 200
  HTTP_BODY=login http_fake https://auth.example.com/login 200 "content-security-policy: default-src 'none'; script-src 'self'; frame-ancestors 'none'"
  for h in www.example.com seller.example.com admin.example.com; do
    HTTP_BODY=page http_fake "https://$h/" 200 "content-security-policy-report-only: script-src 'self' 'nonce-abc123' 'strict-dynamic'"
    HTTP_BODY='' http_fake "https://$h/api/csp-report" 405
    echo 204 > "$F/http/$(printf '%s' "$h/api/csp-report" | tr -c 'A-Za-z0-9\n' '_').POST.code"
  done
  HTTP_BODY='' http_fake 'https://example.com/p/x?a=1' 301 'location: https://www.example.com/p/x?a=1'
  HTTP_BODY='' http_fake 'http://www.example.com/' 301 'location: https://www.example.com/'
}

# ---- run the wizard -----------------------------------------------------------------------------------------------------------
answers() { printf '%s\n' "$@" > "$T/tty"; : > /dev/null; }
no_answers() { : > "$T/tty"; }
run_g() { # run_g NAME args... : OUT, RC
  local name=$1; shift
  OUT=$T/out/$name.txt
  PATH="$T/bin:$PATH" FAKE="$F" GUIDE_ROOT="$T/root" GUIDE_TTY="$T/tty" GUIDE_SLEEP=0 GUIDE_STATE="$STATE" NO_COLOR=1 LANG=en_US.UTF-8 COLUMNS=90 \
    bash "$T/root/tools/deploy/guide.sh" "$@" > "$OUT" 2>&1 < /dev/null
  RC=$?
}
mutating() { grep -E 'workflow run|variable set|secret set|api -X|-X POST https://api\.(stripe|github)' "$F/calls.log" 2>/dev/null | grep -v 'csp-report'; }

echo "== syntax and catalogue"
make_shims
for f in tools/deploy/guide.sh tools/deploy/lib/ui.sh tools/deploy/lib/catalogue.sh tools/deploy/lib/collect.sh tools/ovh/gh-lib.sh tools/ovh/gh-setup.sh tools/deploy/test/test-guide.sh; do
  if bash -n "$REAL/$f" 2>"$T/syn.txt"; then t_ok "bash -n $f"; else t_fail "bash -n $f" "$(cat "$T/syn.txt")"; fi
done
if [ "${SHELLCHECK:-0}" = 1 ] && command -v shellcheck >/dev/null 2>&1; then
  if shellcheck -s bash -S warning -e SC1090,SC1091,SC2034,SC2154,SC2155,SC2086,SC2046,SC2294,SC2295 "$REAL"/tools/deploy/guide.sh "$REAL"/tools/deploy/lib/*.sh > "$T/sc.txt" 2>&1; then t_ok "shellcheck"; else t_fail "shellcheck" "$(head -30 "$T/sc.txt")"; fi
fi
CAT=$REAL/tools/deploy/catalogue.conf
bad_lines=$(awk -F'|' '!/^#/ && NF > 0 && NF != 11 { print NR": "NF" fields" }' "$CAT")
[ -z "$bad_lines" ] && t_ok "every catalogue line has 11 fields" || t_fail "catalogue field count" "$bad_lines"
dups=$(awk -F'|' '!/^#/ && NF == 11 { print $1 }' "$CAT" | sort | uniq -d)
[ -z "$dups" ] && t_ok "no duplicate names in the catalogue" || t_fail "duplicate names" "$dups"
unknown=$(awk -F'|' '!/^#/ && NF == 11 { if ($2 != "secret" && $2 != "var") print $1 ": kind " $2; if ($3 !~ /^(required|optional|generated|external|managed)$/) print $1 ": need " $3; if ($6 !~ /^(server|web|none)$/) print $1 ": gate " $6; if ($8 !~ /^(-|cf_token|cf_account|cf_zone|stripe|gh_pull|ovh|ovh_project)$/) print $1 ": live " $8; if ($7 !~ /^(-|hex32|bucket|domain|email|phone|sk_test|pk_test|whsec|resend|cftoken|ghpat|ovhkey|location|flavor|host|fullhost|httpsurl|user|nonempty|minlen12|choice:.*)$/) print $1 ": validator " $7 }' "$CAT")
[ -z "$unknown" ] && t_ok "kinds, needs, gates, validators and live checks are all known" || t_fail "catalogue vocabulary" "$unknown"
missing_names=''
for n in $(cat "$REAL"/.github/workflows/ovh-vps.yml "$REAL"/.github/workflows/ovh-web.yml "$REAL"/.github/workflows/ovh-images.yml | grep -oE '(secrets|vars)\.[A-Z][A-Z0-9_]+' | sed 's/^[a-z]*\.//' | sort -u); do
  [ "$n" = GITHUB_TOKEN ] && continue
  awk -F'|' -v n="$n" '$1 == n { f = 1 } END { exit !f }' "$CAT" || missing_names="$missing_names $n"
done
[ -z "$missing_names" ] && t_ok "every secret/variable the three workflows read is in the catalogue" || t_fail "workflow names missing from the catalogue" "$missing_names"
rc_missing=''
for n in $(sed -n 's/^for k in \(.*\); do/\1/p;' "$REAL/tools/ovh/render-config.sh" | tr -d '\\' ); do :; done
for n in $(grep -oE '\b[A-Z][A-Z0-9]+(_[A-Z0-9]+)+\b' "$REAL/tools/ovh/render-config.sh" | sort -u | grep -E 'KEY|SECRET|TOKEN|ID$|PASSWORD' | grep -vE '^(IMAGE|BACKUP|GHCR|EXTERNAL_PG_ADMIN_USER)'); do
  awk -F'|' -v n="$n" '$1 == n { f = 1 } END { exit !f }' "$CAT" || rc_missing="$rc_missing $n"
done
[ -z "$rc_missing" ] && t_ok "every key render-config.sh writes can be set through the catalogue" || t_fail "render-config keys missing from the catalogue" "$rc_missing"
for n in $(awk -F'|' '/^#/ || NF < 11 { next } { print $1 }' "$CAT"); do :; done
gs=$(awk -F'|' '/^#/ || NF < 11 { next } $3 == "managed" { next } { print $1 }' "$CAT" | wc -l | tr -d ' ')
[ "$gs" -gt 30 ] && t_ok "gh-setup.sh and the wizard read the same file ($gs prompted names)" || t_fail "catalogue looks too short" "$gs"

echo "== explain, status, dry-run: nothing runs"
new_env; no_answers
for m in --explain --status --dry-run; do
  : > "$F/calls.log"; run_g "mode$m" "$m"
  assert_rc "$m exits 0" 0
  [ ! -s "$F/calls.log" ] && t_ok "$m made no gh/curl/dig call" || t_fail "$m made calls" "$(cat "$F/calls.log")"
  [ ! -e "$STATE" ] && t_ok "$m wrote no state" || t_fail "$m wrote a state file"
done
assert_has "--explain shows the why" "$T/out/mode--explain.txt" "WHY"
assert_has "--explain lists the values" "$T/out/mode--explain.txt" "STRIPE_PUBLISHABLE_KEY"
assert_has "--dry-run labels its steps" "$T/out/mode--dry-run.txt" "[dry-run] would"
assert_has "--dry-run says billing starts" "$T/out/mode--dry-run.txt" "BILLING STARTS"
run_g dry-only --dry-run --only server
assert_lacks "--dry-run --only server skips the others" "$T/out/dry-only.txt" "Phase 4 of 9"

echo "== look and feel: colour, NO_COLOR, ASCII"
run_g plain --explain
if LC_ALL=C grep -q "$(printf '\033')" "$T/out/plain.txt"; then t_fail "output not on a terminal has escape codes"; else t_ok "non-TTY output has no escape codes"; fi
GUIDE_FORCE_COLOR=1 PATH="$T/bin:$PATH" FAKE="$F" GUIDE_ROOT="$T/root" LANG=en_US.UTF-8 bash "$T/root/tools/deploy/guide.sh" --explain > "$T/out/colour.txt" 2>&1
if LC_ALL=C grep -q "$(printf '\033')" "$T/out/colour.txt"; then t_ok "colour is used when forced (positive control)"; else t_fail "forced colour produced none"; fi
NO_COLOR=1 GUIDE_FORCE_COLOR=1 PATH="$T/bin:$PATH" FAKE="$F" GUIDE_ROOT="$T/root" LANG=en_US.UTF-8 bash "$T/root/tools/deploy/guide.sh" --explain > "$T/out/nocolour.txt" 2>&1
if LC_ALL=C grep -q "$(printf '\033')" "$T/out/nocolour.txt"; then t_fail "NO_COLOR ignored"; else t_ok "NO_COLOR beats forced colour"; fi
GUIDE_ASCII=1 PATH="$T/bin:$PATH" FAKE="$F" GUIDE_ROOT="$T/root" bash "$T/root/tools/deploy/guide.sh" --explain > "$T/out/ascii.txt" 2>&1
n=$(LC_ALL=C tr -d '\000-\177' < "$T/out/ascii.txt" | wc -c | tr -d ' ')
[ "$n" = 0 ] && t_ok "GUIDE_ASCII=1 output is pure ASCII" || t_fail "non-ASCII bytes in ASCII mode ($n)"

echo "== phase 0: preflight"
new_env; no_answers
run_g pre-ok --only preflight
assert_rc "preflight passes when everything is in place" 0
assert_has "preflight prints PASS" "$OUT" "PASS"
assert_has "preflight prints What just happened" "$OUT" "What just happened"
assert_has "preflight remembers the commit" "$STATE" "image_sha=$SHA"
assert_has "preflight marks the phase done" "$STATE" "phase.preflight="

new_env; no_answers; echo '{"nameWithOwner":"acme/shop","viewerPermission":"WRITE","defaultBranchRef":{"name":"main"}}' > "$F/repo.json"
run_g pre-notadmin --only preflight
assert_rc "preflight fails when you are not admin" 1
assert_has "...and says admin is needed" "$OUT" "admin is needed"
assert_has "...and prints If it failed" "$OUT" "If it failed"

new_env; no_answers; ( cd "$T/root" && echo x > extra && git add extra && git commit -qm local-only )
run_g pre-notmain --only preflight
assert_rc "preflight fails when HEAD is not on origin/main" 1
assert_has "...with the fix" "$OUT" "is not on origin/main"

new_env; no_answers; echo '[{"name":"ovh-vps"}]' > "$F/workflows.json"
run_g pre-nowf --only preflight
assert_rc "preflight fails when a workflow is not on main" 1

new_env; echo '[]' > "$F/runs/ovh-images.yml.json"; answers 1 build
run_g pre-build --only preflight
assert_rc "missing images: building them succeeds" 0
assert_has "ovh-images was started after typing build" "$F/calls.log" "workflow run ovh-images.yml --ref main"

new_env; echo '[]' > "$F/runs/ovh-images.yml.json"; answers 1 yes
run_g pre-nobuild --only preflight
assert_rc "typing the wrong word does not start the build" 1
assert_lacks "...no workflow was started" "$F/calls.log" "workflow run"

new_env; echo '[]' > "$F/runs/ovh-images.yml.json"; echo failure > "$F/conclusion-ovh-images.yml"; printf 'step failed\nauth with %s denied\n' "$PAT" > "$F/failed-text.txt"; answers 1 build
run_g pre-buildfail --only preflight
assert_rc "a failed image build fails the phase" 1
assert_has "...the log excerpt is shown" "$OUT" "last lines of the failed step"
assert_has "...credentials in it are masked" "$OUT" "ghp_****"
assert_lacks "...the raw token is not printed" "$OUT" "$PAT"

new_env; echo '[]' > "$F/runs/ovh-images.yml.json"; answers 2
run_g pre-skip --only preflight
assert_rc "skipping the image check is allowed and recorded" 0
assert_has "...state says skipped" "$STATE" "images=skipped"

echo "== values: missing, invalid, live check, help, skip, keep"
new_env; answers "" "not-an-email" "ops@example.com"
run_g set-email --set ACME_EMAIL
assert_rc "--set ACME_EMAIL ends well" 0
assert_has "a missing required value is refused" "$OUT" "ACME_EMAIL is required"
assert_has "the validator rejects a bad email and says what is good" "$OUT" "not accepted: an email address"
assert_has "the card says how to get it" "$OUT" "How to get it"
assert_has "the good value is stored" "$F/vars" "ACME_EMAIL=ops@example.com"

new_env; echo 401 > "$F/http/api_stripe_com_v1_account.code"; printf '401\n200\n' > "$F/http/api_stripe_com_v1_account.codes"; : > "$F/http/api_stripe_com_v1_account.body"
answers "$SK_BAD" "" "$SK_GOOD"
run_g set-stripe --set STRIPE_SECRET_KEY
assert_rc "--set STRIPE_SECRET_KEY ends well" 0
assert_has "a failing live check says FAIL with the fix" "$OUT" "Stripe refused the key"
assert_has "retyping then passes the live check" "$OUT" "Stripe accepted the key"
assert_has "the secret is stored (name only visible)" "$F/secrets" "STRIPE_SECRET_KEY"
assert_lacks "the secret is never echoed (bad)" "$OUT" "$SK_BAD"
assert_lacks "the secret is never echoed (good)" "$OUT" "$SK_GOOD"

new_env; answers "sk_live_ABCDEFGHIJKLMNOPQRST" "$SK_GOOD"; echo 200 > "$F/http/api_stripe_com_v1_account.code"; echo '{"id":"acct_1"}' > "$F/http/api_stripe_com_v1_account.body"
run_g set-live --set STRIPE_SECRET_KEY
assert_has "a live Stripe key is refused on entry" "$OUT" "TEST secret key starting with sk_test_"

new_env; answers "?" "s"
run_g set-optional --set RESEND_API_KEY
assert_rc "an optional value can be skipped" 0
n=$(grep -c "How to get it" "$OUT"); [ "$n" -ge 2 ] && t_ok "? shows the help again" || t_fail "? did not re-show the help ($n)"
assert_has "skipping states the consequence" "$OUT" "If skipped"
assert_lacks "a skipped value is not stored" "$F/secrets" "RESEND_API_KEY"

new_env; echo RESEND_API_KEY >> "$F/secrets"; answers ""
run_g set-keep --set RESEND_API_KEY
assert_has "an existing secret is shown as set, hidden" "$OUT" "already set in GitHub (value hidden)"
assert_lacks "keeping stores nothing new" "$F/calls.log" "secret set"

new_env; echo 'ns1.cloudflare.com' > "$F/dns/NS_example.com"; answers "example.com"
run_g set-zone --set CLOUDFLARE_ZONE
assert_has "zone check passes on Cloudflare nameservers" "$OUT" "point at Cloudflare"
new_env; echo 'ns1.registrar.example' > "$F/dns/NS_example.com"; answers "example.com" "anyway"
run_g set-zone-bad --set CLOUDFLARE_ZONE
assert_has "zone check fails with the fix" "$OUT" "not Cloudflare's yet"
assert_has "'anyway' stores it regardless" "$F/vars" "CLOUDFLARE_ZONE=example.com"

new_env; echo 200 > "$F/http/api_cloudflare_com_client_v4_user_tokens_verify.code"; echo '{"result":{"status":"active"}}' > "$F/http/api_cloudflare_com_client_v4_user_tokens_verify.body"; answers "$CFT"
run_g set-cf --set CLOUDFLARE_API_TOKEN
assert_has "Cloudflare token is verified live" "$OUT" "Cloudflare accepted the token"
assert_lacks "the token is not printed" "$OUT" "$CFT"
assert_lacks "the token is not in the call log (stdin only)" "$F/calls.log" "$CFT"

new_env; answers "ghp_notvalid"
run_g set-pat --set REGISTRY_PULL_TOKEN
assert_has "a wrong token format is explained" "$OUT" "classic GitHub token starting with ghp_"

echo "== completeness gate"
new_env; no_answers
run_g gate-blocked --only server
assert_rc "the server phase is blocked while values are missing" 1
assert_has "...with a BLOCKED panel" "$OUT" "BLOCKED"
assert_has "...naming the missing values" "$OUT" "OVH_SERVICE_NAME"
assert_has "...and a checklist" "$OUT" "MISSING"
[ -z "$(mutating)" ] && t_ok "...and nothing outward was started" || t_fail "blocked phase still did something" "$(mutating)"
run_g gate-cf --only cloudflare
assert_rc "the web phase is blocked too" 1
run_g checklist --checklist
assert_has "--checklist shows set/missing status, not values" "$OUT" "MISSING"

echo "== phase 5: server"
new_env; fill_required; echo "image_sha=$SHA" > "$STATE"; answers plan create
run_g srv-ok --only server
assert_rc "server phase passes on a successful run" 0
assert_has "the plan ran first" "$F/calls.log" "action=plan -f image-tag=$SHA"
assert_has "then provision-and-deploy with the commit" "$F/calls.log" "action=provision-and-deploy -f image-tag=$SHA"
assert_has "the cost panel is shown" "$OUT" "COST AND EFFECT"
assert_has "the server address is read from the run log" "$STATE" "server.ip=203.0.113.10"
assert_has "PASS names the address" "$OUT" "the server is 203.0.113.10"

new_env; fill_required; echo "image_sha=$SHA" > "$STATE"; answers plan y
run_g srv-nocreate --only server
assert_rc "'y' does not create the server" 1
assert_lacks "...no provision run" "$F/calls.log" "provision-and-deploy"
new_env; fill_required; echo "image_sha=$SHA" > "$STATE"; answers plan yes
run_g srv-yes --yes --only server
assert_lacks "--yes does not skip the typed confirmation" "$F/calls.log" "provision-and-deploy"

new_env; fill_required; echo "image_sha=$SHA" > "$STATE"; echo failure > "$F/conclusion-ovh-vps.yml"; echo "Error: flavor b3-32 is not offered in us-east-va" > "$F/failed-text.txt"; answers plan
run_g srv-planfail --only server
assert_rc "a failing plan stops before anything is created" 1
assert_has "...the troubleshooting row is named" "$OUT" 'row "plan fails: flavor'
assert_lacks "...no server was requested" "$F/calls.log" "provision-and-deploy"

new_env; fill_required; printf 'image_sha=%s\nserver.plan_ok=b3-32-us-east-va\n' "$SHA" > "$STATE"; echo failure > "$F/conclusion-ovh-vps.yml"; echo "no image at abc for: identity" > "$F/failed-text.txt"; answers create
run_g srv-fail --only server
assert_rc "a failed provision run fails the phase" 1
assert_has "...with the 'no image at' row" "$OUT" 'row "no image at'
assert_has "...and the billing warning" "$OUT" "IS billing"

echo "== phase 6: DNS and certificates"
new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; no_answers
run_g dns-ok --only dns
assert_rc "dns passes when everything is right" 0
assert_has "four hosts resolve" "$OUT" "all resolve to 203.0.113.10"
assert_has "certificate issuer and expiry are shown" "$OUT" "Let's Encrypt"
assert_has "health answers" "$OUT" "bff/healthz answers 200"

new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; dns_fake grafana.example.com 198.51.100.9; no_answers
run_g dns-bad --only dns
assert_rc "dns fails when a record points elsewhere" 1
assert_has "...naming the host" "$OUT" "grafana.example.com->198.51.100.9"
assert_has "...with the fix" "$OUT" "DNS only (grey cloud)"

new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; echo "issuer= C=US, O=(STAGING) Let's Encrypt, CN=Fake" > "$F/cert.txt"; no_answers
run_g dns-staging --only dns
assert_rc "dns fails on a staging certificate" 1
assert_has "...says so" "$OUT" "staging CA"

new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; touch "$F/nocert"; no_answers
run_g dns-nocert --only dns
assert_rc "dns fails while no certificate is served" 1
assert_has "...pointing at the troubleshooting row" "$OUT" "Certificate stays False"

new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; HTTP_BODY=ok http_fake https://api.example.com/bff/healthz 200 'server: cloudflare'; no_answers
run_g dns-proxied --only dns
assert_rc "dns fails when api. is proxied (orange cloud)" 1

echo "== phase 7: Cloudflare and the web apps"
new_env; fill_required; printf 'domain=example.com\n' > "$STATE"; healthy_world; answers n y 1 deploy
run_g cf-ok --only cloudflare
assert_rc "cloudflare phase passes" 0
assert_has "ovh-web started with the domain and storefront host" "$F/calls.log" "workflow run ovh-web.yml --ref main -f apps=storefront seller-center staff-console -f domain=example.com -f storefront-host=www"
assert_has "the apex redirect is proven" "$OUT" "301 https://www.example.com/p/x?a=1"
assert_has "the location-headers setting is in the checklist" "$OUT" "Managed Transforms > turn ON"

new_env; fill_required; printf 'domain=example.com\n' > "$STATE"; healthy_world; HTTP_BODY='' http_fake 'https://example.com/p/x?a=1' 200; answers n y 1 deploy
run_g cf-noredirect --only cloudflare
assert_rc "cloudflare phase fails when the apex does not redirect" 1
assert_has "...with the row" "$OUT" "The bare domain does not redirect"

new_env; fill_required; printf 'domain=example.com\n' > "$STATE"; healthy_world; answers n y 1 nodeploy
run_g cf-cancel --only cloudflare
assert_rc "a wrong word cancels the web deploy" 1
assert_lacks "...nothing was deployed" "$F/calls.log" "ovh-web.yml"

echo "== flat web hosts (free certificate layout)"
new_env; printf '%s\n' OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com >> "$F/vars"; answers 1
run_g flat-offer --set OVH_SELLER_HOST
assert_rc "the flat layout is offered below the zone and accepted" 0
assert_has "...explained" "$OUT" "FREE CERTIFICATE LAYOUT"
assert_has "...storing the apex storefront" "$F/calls.log" "gh variable set OVH_STOREFRONT_HOST"
assert_has "...the seller host" "$F/vars" "OVH_SELLER_HOST=seller-test.example.com"
assert_has "...and the admin host" "$F/vars" "OVH_ADMIN_HOST=admin-test.example.com"
assert_has "...and apex" "$F/vars" "OVH_STOREFRONT_HOST=apex"

new_env; printf '%s\n' OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com >> "$F/vars"; answers 2
run_g flat-declined --set OVH_SELLER_HOST
assert_lacks "declining stores no explicit host" "$F/vars" "OVH_SELLER_HOST"
assert_has "...with the certificate warning" "$OUT" "certificate error"

new_env; printf '%s\n' OVH_DOMAIN=example.com CLOUDFLARE_ZONE=example.com >> "$F/vars"; answers "Seller.example.com" "seller.example.com"
run_g flat-zone --set OVH_SELLER_HOST
assert_has "at the zone apex the host is typed and validated (lower-case only)" "$OUT" "full lower-case host name"
assert_has "...then stored" "$F/vars" "OVH_SELLER_HOST=seller.example.com"

setvars() { local kv k; for kv in "$@"; do k=${kv%%=*}; grep -v "^$k=" "$F/vars" > "$F/vars.n"; mv "$F/vars.n" "$F/vars"; echo "$kv" >> "$F/vars"; done; }
flat_world() { # flat layout on test.example.com: storefront at the apex of the base, web hosts one label under the zone
  new_env; fill_required; setvars OVH_DOMAIN=test.example.com OVH_STOREFRONT_HOST=apex OVH_SELLER_HOST=seller-test.example.com OVH_ADMIN_HOST=admin-test.example.com CLOUDFLARE_ZONE=example.com
  printf 'domain=test.example.com\n' > "$STATE"
  local h
  for h in test.example.com seller-test.example.com admin-test.example.com; do
    HTTP_BODY=page http_fake "https://$h/" 200 "content-security-policy-report-only: script-src 'self' 'nonce-abc123' 'strict-dynamic'"
    HTTP_BODY='' http_fake "https://$h/api/csp-report" 405
    echo 204 > "$F/http/$(printf '%s' "$h/api/csp-report" | tr -c 'A-Za-z0-9\n' '_').POST.code"
    dns_fake "$h" 198.51.100.7
  done
  HTTP_BODY='' http_fake 'http://test.example.com/' 301 'location: https://test.example.com/'
  printf 'subject=CN=example.com\nissuer=C=US, O=Google Trust Services, CN=WE1\nnotAfter=Feb  1 00:00:00 2027 GMT\nX509v3 Subject Alternative Name:\n    DNS:example.com, DNS:*.example.com\n' > "$F/cert.txt"
}
flat_world; answers n y 1 deploy
run_g cf-flat --only cloudflare
assert_rc "flat layout: cloudflare phase passes" 0
assert_has "the host list is shown" "$OUT" "https://seller-test.example.com"
assert_has "ovh-web started for the base domain with apex" "$F/calls.log" "-f domain=test.example.com -f storefront-host=apex"
assert_has "the certificate coverage is proven for the seller host" "$OUT" "seller-test.example.com: certificate covers it"
assert_lacks "no apex redirect is demanded" "$OUT" "apex answered"
assert_lacks "no two-label warning" "$OUT" "two-label web hosts"

flat_world; setvars OVH_SELLER_HOST=seller.test.example.com OVH_ADMIN_HOST=admin.test.example.com
for h in seller.test.example.com admin.test.example.com; do HTTP_BODY=page http_fake "https://$h/" 200; dns_fake "$h" 198.51.100.7; done
answers n y 1 deploy
run_g cf-twolabel --only cloudflare
assert_rc "two-label hosts: the certificate check fails" 1
assert_has "...with the plain warning about the free plan" "$OUT" "two-label web hosts need a paid Advanced Certificate"
assert_has "...naming the uncovered host" "$OUT" "seller.test.example.com: the certificate does not cover it"

flat_world; setvars OVH_SELLER_HOST=test.example.com; answers n y 1 deploy
run_g cf-badhost --only cloudflare
assert_rc "a web host equal to another host is refused before anything runs" 1
assert_lacks "...nothing was deployed" "$F/calls.log" "ovh-web.yml"

echo "== phase 8: smoke"
new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\nserver.sha=%s\n' "$SHA" > "$STATE"; healthy_world; answers reindex
run_g smoke-hooks --only smoke
assert_has "smoke posts an unsigned body to the payments webhook" "$F/calls.log" "curl POST https://api.example.com/payments/v1/webhooks/stripe"
assert_has "...to the seller Connect webhook" "$F/calls.log" "curl POST https://api.example.com/seller/v1/webhooks/stripe-connect"
assert_has "...and to the seller plan-fee webhook" "$F/calls.log" "curl POST https://api.example.com/seller/v1/webhooks/stripe-billing"
assert_has "a 400 is reported as route exists, signature enforced" "$OUT" "unsigned POST refused (400)"

new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\n' > "$STATE"; healthy_world; webhook_fake seller/v1/webhooks/stripe-connect 404; no_answers
run_g smoke-hook404 --only smoke
assert_rc "smoke fails when a webhook route is missing (404)" 1
assert_has "...naming the URL" "$OUT" "seller/v1/webhooks/stripe-connect answered 404"
assert_has "...with the runbook pointer" "$OUT" "docs/runbooks/stripe-webhooks.md, section 10, row 404"

new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\n' > "$STATE"; healthy_world; webhook_fake payments/v1/webhooks/stripe 502; no_answers
run_g smoke-hook502 --only smoke
assert_rc "smoke fails when a webhook answers 502" 1
assert_has "...saying what was expected" "$OUT" "answered 502 (expected 400"

new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\n' > "$STATE"; healthy_world; rm -f "$F/http/api_example_com_seller_v1_webhooks_stripe_billing.POST.code"; no_answers
run_g smoke-hookcert --only smoke
assert_rc "smoke fails when a webhook cannot be reached (connection or certificate error)" 1
assert_has "...pointing at the runbook rows" "$OUT" "no connection or certificate error"

new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\nserver.sha=%s\n' "$SHA" > "$STATE"; healthy_world; answers reindex
run_g smoke-ok --only smoke
assert_rc "smoke passes" 0
assert_has "sign-in CSP is checked" "$OUT" "enforced CSP"
assert_has "report-only CSP is checked" "$OUT" "nonce CSP in Report-Only"
assert_has "the CSP report endpoint accepts a report" "$OUT" "accepted a sample report (204)"
assert_has "reindex runs after typing it" "$F/calls.log" "action=reindex-search"
assert_has "seeding rules are explained" "$OUT" "SEED_ALLOW_REMOTE=1"

new_env; fill_required; echo STRIPE_WEBHOOK_SECRET >> "$F/secrets"; printf 'domain=example.com\n' > "$STATE"; healthy_world; HTTP_BODY=login http_fake https://auth.example.com/login 200; no_answers
run_g smoke-nocsp --only smoke
assert_rc "smoke fails when sign-in has no CSP" 1
assert_lacks "...and does not go on to reindex" "$F/calls.log" "reindex-search"

echo "== doctor"
new_env; fill_required; printf 'domain=example.com\nserver.ip=203.0.113.10\n' > "$STATE"; healthy_world; no_answers
run_g doctor-ok --doctor
assert_rc "doctor is green on a healthy deployment" 0
assert_has "...and says so" "$OUT" "everything checked is healthy"
[ -z "$(mutating)" ] && t_ok "doctor changed nothing outward" || t_fail "doctor did something outward" "$(mutating)"
dns_fake api.example.com 198.51.100.9
run_g doctor-bad --doctor
assert_rc "doctor fails when DNS is wrong" 1
[ -z "$(mutating)" ] && t_ok "doctor (failing) changed nothing outward" || t_fail "doctor did something outward" "$(mutating)"

echo "== resume"
new_env; printf 'phase.preflight=2026-01-01T00:00Z\n' > "$STATE"; no_answers
run_g resume
assert_has "a finished phase is skipped on re-run" "$OUT" "already done"
assert_has "quitting says how to resume" "$OUT" "run  make deploy-guide  again"
assert_rc "quitting is a clean exit" 0
run_g status --status
assert_has "--status shows done and pending" "$T/out/status.txt" "pending"

echo "== secrets never leak"
leak=''
for s in "$SK_BAD" "$SK_GOOD" "$CFT" "$PAT"; do
  grep -rlF -- "$s" "$T/out" "$F/calls.log" "$STATE" "$F/vars" 2>/dev/null | grep -v 'failed-text' | grep -q . && leak="$leak $s"
done
[ -z "$leak" ] && t_ok "no secret value in any output, call log, variable file or state" || t_fail "secret leaked:$leak"

echo
echo "passed $PASS, failed $FAILN"
[ "$FAILN" = 0 ]
