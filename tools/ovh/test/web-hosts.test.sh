#!/usr/bin/env bash
# Tests of tools/ovh/web-hosts.sh (host computation and validation of the OVH test environment) and, when helm is installed, of the
# at-service library chart's global.sellerHost / global.adminHost override. No network.
#   bash tools/ovh/test/web-hosts.test.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REAL=$(cd "$HERE/../../.." && pwd)
S=$REAL/tools/ovh/web-hosts.sh
PASS=0 FAILN=0
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() { FAILN=$((FAILN + 1)); printf '  FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/       | /'; }

# run NAME VAR=value...: OUT (stdout), ERR (stderr), RC; only the given variables are set
run() { local name=$1; shift; ERR=$(env -i PATH="$PATH" "$@" bash "$S" 2>&1 >"${TMPDIR:-/tmp}/wh.$$"); RC=$?; OUT=$(cat "${TMPDIR:-/tmp}/wh.$$"); rm -f "${TMPDIR:-/tmp}/wh.$$"; }
val() { printf '%s\n' "$OUT" | sed -n "s/^$1=//p"; }
expect() { [ "$(val "$2")" = "$3" ] && ok "$1" || ko "$1 ($2: expected '$3', got '$(val "$2")')"; }
fails() { [ "$RC" != 0 ] && printf '%s' "$ERR" | grep -qF -- "$3" && ok "$1" || ko "$1 (rc $RC, expected an error containing: $3)" "$ERR"; }
quiet() { [ -z "$ERR" ] && ok "$1" || ko "$1 (unexpected output)" "$ERR"; }
warns() { [ "$RC" = 0 ] && printf '%s' "$ERR" | grep -qF -- "$3" && ok "$1" || ko "$1 (rc $RC, expected a warning containing: $3)" "$ERR"; }

echo "== defaults reproduce today's layout"
run d OVH_DOMAIN=example.com
expect "storefront is www.<domain>" storefront_host www.example.com
expect "seller is seller.<domain>" seller_host seller.example.com
expect "admin is admin.<domain>" admin_host admin.example.com
expect "media is media.<domain>" media_url https://media.example.com
expect "no explicit seller host" seller_host_explicit ""
run d OVH_DOMAIN=test.example.com OVH_STOREFRONT_HOST=apex
expect "apex storefront is the base domain" storefront_host test.example.com
expect "default seller below the base domain" seller_url https://seller.test.example.com
run d OVH_DOMAIN=Example.COM
expect "the domain is lower-cased" storefront_host www.example.com
run d OVH_DOMAIN=example.com CLOUDFLARE_ZONE=example.com
quiet "defaults at the zone apex: one label, no warning"

echo "== the flat layout for test.afrotransact.com"
FLAT="OVH_DOMAIN=test.afrotransact.com CLOUDFLARE_ZONE=afrotransact.com OVH_STOREFRONT_HOST=apex OVH_SELLER_HOST=seller-test.afrotransact.com OVH_ADMIN_HOST=admin-test.afrotransact.com"
# shellcheck disable=SC2086
run flat $FLAT
expect "storefront" storefront_url https://test.afrotransact.com
expect "seller" seller_url https://seller-test.afrotransact.com
expect "admin" admin_url https://admin-test.afrotransact.com
expect "api stays below the base" api_url https://api.test.afrotransact.com/bff
expect "media stays below the base" media_url https://media.test.afrotransact.com
quiet "all three web hosts are one label under the zone: no warning"
# shellcheck disable=SC2086
run flat $FLAT OVH_SELLER_HOST=SELLER-Test.afrotransact.com
expect "an explicit host is lower-cased" seller_host seller-test.afrotransact.com

echo "== certificate warning for hosts deeper than one label under the zone"
run deep OVH_DOMAIN=test.afrotransact.com CLOUDFLARE_ZONE=afrotransact.com
warns "default hosts below a subdomain warn" "$RC" "free Universal certificate"
run deep OVH_DOMAIN=test.afrotransact.com CLOUDFLARE_ZONE=afrotransact.com OVH_STOREFRONT_HOST=apex OVH_SELLER_HOST=seller.test.afrotransact.com OVH_ADMIN_HOST=admin-test.afrotransact.com
warns "an explicit two-label seller host warns" "$RC" "the seller host seller.test.afrotransact.com is 2 labels"
printf '%s' "$ERR" | grep -q "admin host" && ko "the one-label admin host must not warn" "$ERR" || ok "...and only the deep host is named"
run deep OVH_DOMAIN=test.afrotransact.com OVH_STOREFRONT_HOST=apex
quiet "without a zone nothing can be said about certificates"

echo "== validation"
run v OVH_DOMAIN=example.com OVH_SELLER_HOST=https://seller-test.example.com
fails "a scheme is refused" "$RC" "OVH_SELLER_HOST"
run v OVH_DOMAIN=example.com OVH_SELLER_HOST='*.example.com'
fails "a wildcard is refused" "$RC" "OVH_SELLER_HOST"
run v OVH_DOMAIN=example.com OVH_ADMIN_HOST=admin.example.com:8443
fails "a port is refused" "$RC" "OVH_ADMIN_HOST"
run v OVH_DOMAIN=example.com OVH_ADMIN_HOST=admin_test.example.com
fails "an underscore is refused" "$RC" "OVH_ADMIN_HOST"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_SELLER_HOST=seller.other.org
fails "a host outside the zone is refused" "$RC" "must sit under the Cloudflare zone"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_SELLER_HOST=example.com
fails "the zone apex itself is not a valid seller host" "$RC" "must sit under"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_SELLER_HOST=x-test.example.com OVH_ADMIN_HOST=x-test.example.com
fails "seller and admin cannot share a host" "$RC" "cannot share the host"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_STOREFRONT_HOST=apex OVH_SELLER_HOST=test.example.com
fails "the seller host cannot equal the storefront host" "$RC" "storefront host"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_ADMIN_HOST=api.test.example.com
fails "a web host cannot be a backend host" "$RC" "backend host"
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=example.com OVH_STOREFRONT_HOST=apex OVH_ADMIN_HOST=www.test.example.com
expect "www.<domain> as the admin host is allowed when the storefront is the apex" admin_host www.test.example.com
run v OVH_DOMAIN=test.example.com CLOUDFLARE_ZONE=other.org
fails "the domain must be the zone or below it" "$RC" "is not the Cloudflare zone"
run v OVH_DOMAIN=test.example.com OVH_STOREFRONT_HOST=blog
fails "the storefront host stays www or apex" "$RC" "www or apex"
run v OVH_STOREFRONT_HOST=www
fails "the domain is required" "$RC" "OVH_DOMAIN"

echo "== other consumers of the same variables"
if grep -q 'tools/ovh/web-hosts.sh' "$REAL/.github/workflows/ovh-web.yml" && grep -q 'tools/ovh/web-hosts.sh' "$REAL/.github/workflows/ovh-vps.yml"; then ok "both workflows call web-hosts.sh"; else ko "a workflow does not call web-hosts.sh"; fi
T=$(mktemp -d "${TMPDIR:-/tmp}/render.XXXXXX"); trap 'rm -rf "$T" "$T.h"' EXIT
OVH_DOMAIN=test.example.com OVH_STOREFRONT_HOST=apex OVH_SELLER_HOST=seller-test.example.com OVH_ADMIN_HOST=admin-test.example.com OVH_MEDIA_PUBLIC_URL=https://media-test.example.com \
  ACME_EMAIL=a@example.com IMAGE_REGISTRY=r IMAGE_TAG=t bash "$REAL/tools/ovh/render-config.sh" "$T" >/dev/null 2>&1
for kv in SELLER_HOST=seller-test.example.com ADMIN_HOST=admin-test.example.com MEDIA_PUBLIC_URL=https://media-test.example.com STOREFRONT_HOST=apex; do
  grep -qx "$kv" "$T/config.env" && ok "render-config.sh writes $kv" || ko "render-config.sh does not write $kv" "$(cat "$T/config.env")"
done
OVH_DOMAIN=example.com ACME_EMAIL=a@example.com IMAGE_REGISTRY=r IMAGE_TAG=t bash "$REAL/tools/ovh/render-config.sh" "$T" >/dev/null 2>&1
grep -qx "SELLER_HOST=''" "$T/config.env" && grep -qx "ADMIN_HOST=''" "$T/config.env" && ok "unset variables render empty (defaults)" || ko "defaults not empty" "$(cat "$T/config.env")"

echo "== at-service chart (needs helm)"
if command -v helm >/dev/null 2>&1; then
  C=$T.h; mkdir -p "$C/templates"
  printf 'apiVersion: v2\nname: probe\nversion: 0.1.0\ndependencies:\n  - name: at-service\n    version: 0.1.0\n    repository: "file://%s/infra/helm/library/at-service"\n' "$REAL" > "$C/Chart.yaml"
  helm dependency update "$C" >/dev/null 2>&1
  render() { printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: p\ndata:\n  x: |\n{{ include "at-service.platformEnvVars" . | indent 4 }}\n' > "$C/templates/p.yaml"; helm template p "$C" --set env=dev --set-string global.domain=test.example.com "$@" 2>&1; }
  pv() { printf '%s\n' "$1" | grep -A1 "name: $2\$" | sed -n 's/.*value: "\(.*\)"/\1/p'; }
  R=$(render)
  [ "$(pv "$R" SELLER_CENTER_URL)" = https://seller.test.example.com ] && [ "$(pv "$R" STAFF_CONSOLE_URL)" = https://admin.test.example.com ] && [ "$(pv "$R" PUBLIC_WEB_URL)" = https://www.test.example.com ] && ok "chart defaults unchanged" || ko "chart defaults changed" "$R"
  R=$(render --set-string global.storefrontHost=apex --set-string global.sellerHost=seller-test.example.com --set-string global.adminHost=admin-test.example.com)
  [ "$(pv "$R" SELLER_CENTER_URL)" = https://seller-test.example.com ] && [ "$(pv "$R" STAFF_CONSOLE_URL)" = https://admin-test.example.com ] && [ "$(pv "$R" PUBLIC_WEB_URL)" = https://test.example.com ] && [ "$(pv "$R" PLATFORM_DOMAIN)" = test.example.com ] && ok "explicit hosts replace seller./admin.; PLATFORM_DOMAIN stays the base" || ko "explicit hosts not applied" "$R"
  R=$(render --set-string global.sellerHost=seller-test.example.com --set-string global.urls.SELLER_CENTER_URL=https://override.example.com)
  [ "$(pv "$R" SELLER_CENTER_URL)" = https://override.example.com ] && ok "global.urls still wins over the explicit host" || ko "precedence" "$R"
  R=$(render --set-string global.sellerHost=https://seller-test.example.com); printf '%s' "$R" | grep -q "full lower-case host name" && ok "a URL as sellerHost is refused" || ko "sellerHost accepted a URL" "$R"
  R=$(render --set-string 'global.adminHost=*.example.com'); printf '%s' "$R" | grep -q "full lower-case host name" && ok "a wildcard adminHost is refused" || ko "adminHost accepted a wildcard" "$R"
else
  printf '  skip helm is not installed (CI runs this test with helm)\n'
fi

printf '\npassed %s, failed %s\n' "$PASS" "$FAILN"
[ "$FAILN" = 0 ]
