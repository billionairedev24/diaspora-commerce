#!/usr/bin/env bash
# The value catalogue (tools/deploy/catalogue.conf): reading it, validating entries, live checks, and what is set in the GitHub
# Environment. Sourced by tools/deploy/guide.sh after ui.sh. bash 3.2. A secret's value only ever lives in a shell variable of
# this process and in the stdin of gh/curl: never in a file, the state, the logs or a command line.

CAT_FILE=${CAT_FILE:-}
GUIDE_CF_TOKEN=''   # a verified Cloudflare token of THIS run, kept in memory for follow-up checks only

cat_names()       { awk -F'|' '/^#/ || NF < 11 { next } { print $1 }' "$CAT_FILE"; }
cat_names_phase() { awk -F'|' -v p="$1" '/^#/ || NF < 11 { next } $5 == p { print $1 }' "$CAT_FILE"; }
cat_load() { # cat_load NAME -> C_NAME C_KIND C_NEED C_DEFAULT C_PHASE C_GATE C_VAL C_LIVE C_WHAT C_WHY C_HOWTO
  local line
  line=$(awk -F'|' -v n="$1" '!/^#/ && $1 == n { print; exit }' "$CAT_FILE")
  C_NAME='' C_KIND='' C_NEED='' C_DEFAULT='' C_PHASE='' C_GATE='' C_VAL='' C_LIVE='' C_WHAT='' C_WHY='' C_HOWTO=''
  IFS='|' read -r C_NAME C_KIND C_NEED C_DEFAULT C_PHASE C_GATE C_VAL C_LIVE C_WHAT C_WHY C_HOWTO <<EOF3
$line
EOF3
  [ -n "$C_NAME" ]
}

# ---- what is stored in GitHub (names only: a secret's value cannot be read back, and we never ask) ----------------------------
GH_SECRETS='' GH_VARS='' GH_LISTED=0
gh_refresh() {
  GH_SECRETS=$(gh api "repos/$REPO/environments/$ENVN/secrets" --paginate 2>/dev/null | jq -r '.secrets[]?.name' 2>/dev/null)
  GH_VARS=$(gh api "repos/$REPO/environments/$ENVN/variables" --paginate 2>/dev/null | jq -r '.variables[]?.name' 2>/dev/null)
  DB_MODE_CACHE=$(gh_var DB_MODE)
  GH_LISTED=1
}
is_set() { printf '%s\n%s\n' "$GH_SECRETS" "$GH_VARS" | grep -qx "$1"; }
gh_var() { gh variable get "$1" --env "$ENVN" --repo "$REPO" 2>/dev/null || true; }
gh_put() { # gh_put secret|var NAME VALUE (the value goes through stdin or --body of gh, never to our output)
  if [ "$1" = secret ]; then printf '%s' "$3" | gh secret set "$2" --env "$ENVN" --repo "$REPO" >/dev/null 2>&1 || return 1
    GH_SECRETS="$GH_SECRETS
$2"
  else gh variable set "$2" --env "$ENVN" --repo "$REPO" --body "$3" >/dev/null 2>&1 || return 1
    GH_VARS="$GH_VARS
$2"
    [ "$2" != DB_MODE ] || DB_MODE_CACHE=$3
  fi
}

# ---- needs ------------------------------------------------------------------------------------------------------------------
# needed_now NAME: is the value required in this setup (DB_MODE aware)?
needed_now() {
  cat_load "$1" || return 1
  case "$C_NEED" in
    (required | managed) return 0 ;;
    (external) [ "$(db_mode_now)" = external ] ;;
    (*) return 1 ;;
  esac
}
DB_MODE_CACHE=''
db_mode_now() { echo "${DB_MODE_CACHE:-cluster}"; }

# missing_for <server|web>: names that are required for the gated step and not set.
missing_for() {
  local n out=''
  for n in $(cat_names); do
    cat_load "$n"
    needed_now "$n" || continue
    case "$1" in (server) [ "$C_GATE" = server ] || continue ;; (web) [ "$C_GATE" = server ] || [ "$C_GATE" = web ] || continue ;; esac
    is_set "$n" || out="$out $n"
  done
  printf '%s' "${out# }"
}

# checklist [all]: aligned table of every value: set / MISSING / skipped. Never a value.
checklist() {
  local n st col shown=0
  printf '  %s%-30s %-7s %-10s %s%s\n' "$C_BOLD" NAME KIND NEED STATUS "$C_RESET"
  for n in $(cat_names); do
    cat_load "$n"
    if is_set "$n"; then st='set'; col=$C_GREEN
    elif needed_now "$n"; then st='MISSING'; col=$C_RED
    elif [ "$C_NEED" = external ]; then st='n/a (cluster db)'; col=$C_DIM
    else st='optional, skipped'; col=$C_YELLOW; fi
    if [ "${1:-all}" != all ] && [ "$st" = set ]; then continue; fi
    printf '  %-30s %-7s %-10s %s%s%s\n' "$C_NAME" "$C_KIND" "$C_NEED" "$col" "$st" "$C_RESET"; shown=$((shown + 1))
  done
  [ "$shown" -gt 0 ] || say "  (nothing to show)"
}

# ---- http helper (every outside request of the wizard goes through it) ---------------------------------------------------
HTTP_CODE=000
http() { # http URL [curl args]: HTTP_CODE; body in $GUIDE_TMP/body, headers in $GUIDE_TMP/headers
  local url=$1; shift
  : > "$GUIDE_TMP/body"; : > "$GUIDE_TMP/headers"
  HTTP_CODE=$(curl -sS --max-time "${HTTP_TIMEOUT:-20}" -o "$GUIDE_TMP/body" -D "$GUIDE_TMP/headers" -w '%{http_code}' "$@" "$url" 2>/dev/null) || HTTP_CODE=000
  [ -n "$HTTP_CODE" ] || HTTP_CODE=000
}
hdr() { grep -i "^$1:" "$GUIDE_TMP/headers" 2>/dev/null | tail -1 | sed 's/^[^:]*: *//' | tr -d '\r'; }
body() { cat "$GUIDE_TMP/body"; }
bearer_get() { # bearer_get URL TOKEN: the token travels on curl's stdin, not its command line
  http "$1" -K - <<EOF6
header = "Authorization: Bearer $2"
EOF6
}

# ---- validators ---------------------------------------------------------------------------------------------------------------
V_MSG=''
validate() { # validate VALIDATOR VALUE: 0 = fine; else V_MSG says what is wrong and what a good value looks like
  local v=$1 x=$2
  V_MSG=''
  case "$v" in
    (hex32) printf '%s' "$x" | grep -Eq '^[0-9a-f]{32}$' || V_MSG='must be exactly 32 lower-case hexadecimal characters (0-9, a-f), for example 0123456789abcdef0123456789abcdef' ;;
    (bucket) printf '%s' "$x" | grep -Eq '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$' || V_MSG='3 to 63 characters: lower-case letters, digits and hyphens, not starting or ending with a hyphen' ;;
    (domain) printf '%s' "$x" | grep -Eq '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$' || V_MSG='a lower-case domain such as example.com (no https://, no path, no trailing dot)' ;;
    (email) printf '%s' "$x" | grep -Eq '^[^@ ]+@[^@ ]+\.[^@ ]+$' || V_MSG='an email address such as you@example.com' ;;
    (phone) printf '%s' "$x" | grep -Eq '^\+[1-9][0-9]{6,14}$' || V_MSG='international format: + then country code and number, digits only, for example +15555550100' ;;
    (sk_test) printf '%s' "$x" | grep -Eq '^sk_test_[A-Za-z0-9]{10,}$' || V_MSG='a Stripe TEST secret key starting with sk_test_ (a live key sk_live_ is refused on purpose)' ;;
    (pk_test) printf '%s' "$x" | grep -Eq '^pk_test_[A-Za-z0-9]{10,}$' || V_MSG='a Stripe TEST publishable key starting with pk_test_' ;;
    (whsec) printf '%s' "$x" | grep -Eq '^whsec_[A-Za-z0-9+/=_-]{10,}$' || V_MSG='a Stripe signing secret starting with whsec_' ;;
    (resend) printf '%s' "$x" | grep -Eq '^re_[A-Za-z0-9_-]{10,}$' || V_MSG='a Resend key starting with re_' ;;
    (cftoken) printf '%s' "$x" | grep -Eq '^[A-Za-z0-9_-]{30,80}$' || V_MSG='a Cloudflare API token: 30 to 80 letters, digits, - or _ (not the Global API Key)' ;;
    (ghpat) printf '%s' "$x" | grep -Eq '^ghp_[A-Za-z0-9]{30,}$' || V_MSG='a classic GitHub token starting with ghp_ (Settings > Developer settings > Tokens (classic))' ;;
    (ovhkey) printf '%s' "$x" | grep -Eq '^[A-Za-z0-9]{12,64}$' || V_MSG='letters and digits only, 12 to 64 characters, as shown on the OVHcloud token page' ;;
    (location) [ -f "$OVH_LOCATIONS_FILE" ] && jq -e --arg l "$x" 'has($l)' "$OVH_LOCATIONS_FILE" >/dev/null 2>&1 || V_MSG="one of: $(jq -r 'keys | join(", ")' "$OVH_LOCATIONS_FILE" 2>/dev/null)" ;;
    (flavor) printf '%s' "$x" | grep -Eq '^[a-z0-9][a-z0-9.-]{1,30}$' || V_MSG='a flavor name such as b3-32 (lower-case, as listed in the console)' ;;
    (host) printf '%s' "$x" | grep -Eq '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$' || V_MSG='a host name such as db.example.com (no scheme, no port)' ;;
    (user) printf '%s' "$x" | grep -Eq '^[A-Za-z0-9_.@-]{1,64}$' || V_MSG='letters, digits and _ . @ - only' ;;
    (minlen12) [ "${#x}" -ge 12 ] || V_MSG='at least 12 characters' ;;
    (nonempty) [ -n "$x" ] || V_MSG='cannot be empty' ;;
    (choice:*) case ",${v#choice:}," in (*",$x,"*) ;; (*) V_MSG="one of: ${v#choice:}" ;; esac ;;
    (*) ;;
  esac
  [ -z "$V_MSG" ]
}

# ---- live checks (cheap, read-only, safe): 0 = PASS, 1 = FAIL (the message says the fix) -------------------------------------------
live_check() { # live_check KIND VALUE NAME
  local k=$1 v=$2
  case "$k" in
    (cf_token)
      bearer_get https://api.cloudflare.com/client/v4/user/tokens/verify "$v"
      if [ "$HTTP_CODE" = 200 ] && [ "$(body | jq -r '.result.status // empty')" = active ]; then
        verdict_pass "Cloudflare accepted the token (status active)"; GUIDE_CF_TOKEN=$v; return 0
      fi
      verdict_fail "Cloudflare refused the token (HTTP $HTTP_CODE). Re-create it at My Profile > API Tokens, or you copied it incompletely."; return 1 ;;
    (cf_account)
      [ -n "$GUIDE_CF_TOKEN" ] || { hint "not checked: no verified token in this run"; return 0; }
      bearer_get https://api.cloudflare.com/client/v4/accounts "$GUIDE_CF_TOKEN"
      if body | jq -e --arg a "$v" '[.result[]?.id] | index($a)' >/dev/null 2>&1; then verdict_pass "the token can see account $v"; return 0; fi
      verdict_fail "the token cannot see an account with that id. Accounts it can see: $(body | jq -r '[.result[]? | .name + " = " + .id] | join(", ")' 2>/dev/null)"; return 1 ;;
    (cf_zone)
      if [ -n "$GUIDE_CF_TOKEN" ]; then
        bearer_get "https://api.cloudflare.com/client/v4/zones?name=$v" "$GUIDE_CF_TOKEN"
        local st; st=$(body | jq -r '.result[0].status // empty')
        if [ "$st" = active ]; then verdict_pass "zone $v is Active on Cloudflare"; return 0; fi
        if [ -n "$st" ]; then verdict_fail "zone $v is '$st', not active: move the nameservers at your registrar to the two Cloudflare shows (cloudflare-setup.md step 2)"; return 1; fi
        verdict_fail "the token does not see a zone named $v (wrong name, or Zone Resources of the token exclude it)"; return 1
      fi
      local ns; ns=$(ns_lookup "$v")
      if printf '%s' "$ns" | grep -qi cloudflare; then verdict_pass "nameservers of $v point at Cloudflare"; return 0; fi
      verdict_fail "nameservers of $v are not Cloudflare's yet ($(printf '%s' "$ns" | tr '\n' ' ')). Change them at your registrar; it can take hours."; return 1 ;;
    (stripe)
      bearer_get https://api.stripe.com/v1/account "$v"
      if [ "$HTTP_CODE" = 200 ]; then verdict_pass "Stripe accepted the key (account $(body | jq -r '.id // "?"'))"; return 0; fi
      verdict_fail "Stripe refused the key (HTTP $HTTP_CODE): copy it again from Developers > API keys in Test mode"; return 1 ;;
    (gh_pull)
      bearer_get https://api.github.com/user "$v"
      if [ "$HTTP_CODE" != 200 ]; then verdict_fail "GitHub refused the token (HTTP $HTTP_CODE): create a new classic token with read:packages"; return 1; fi
      local sc; sc=$(hdr x-oauth-scopes)
      case "$sc" in
        ('read:packages') verdict_pass "token accepted, scope exactly read:packages" ;;
        (*read:packages*) verdict_warn "token works but has more scopes than needed ($sc): make a new one with only read:packages" ;;
        (*) verdict_fail "token has no read:packages scope (scopes: ${sc:-none})"; return 1 ;;
      esac
      return 0 ;;
    (*) return 0 ;;
  esac
}

# ns_lookup ZONE: its nameservers, one per line (dig if installed, else DNS over HTTPS through curl).
ns_lookup() {
  if command -v dig >/dev/null 2>&1; then dig +short NS "$1" 2>/dev/null
  else http "https://cloudflare-dns.com/dns-query?name=$1&type=NS" -H 'accept: application/dns-json'; body | jq -r '.Answer[]?.data' 2>/dev/null
  fi
}
a_lookup() { # a_lookup HOST: A records, one per line
  if command -v dig >/dev/null 2>&1; then dig +short A "$1" 2>/dev/null | grep -E '^[0-9.]+$'
  else http "https://cloudflare-dns.com/dns-query?name=$1&type=A" -H 'accept: application/dns-json'; body | jq -r '.Answer[]? | select(.type == 1) | .data' 2>/dev/null
  fi
}
