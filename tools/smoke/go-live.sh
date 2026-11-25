#!/usr/bin/env bash
# Read-only smoke test of a DEPLOYED environment from the internet (no login, no write, no demo data needed, no secret read or printed).
#   ENV=uat tools/smoke/go-live.sh        ENV=prod tools/smoke/go-live.sh
# Hosts follow infra/cloudflare/hosts.json (prod: afrotransact.com, api.; other env E: E.afrotransact.com, api-E.). Override ZONE=...
# Exit code = number of FAILed checks. In-cluster checks (pods, readiness, at_module_up) are in docs/runbooks/release-1-go-live.md, step 9.
set -uo pipefail
ENV="${ENV:?set ENV=uat or ENV=prod}"; ZONE="${ZONE:-afrotransact.com}"
case "$ENV" in
  prod) WEB="$ZONE"; SELLER="seller.$ZONE"; ADMIN="admin.$ZONE"; API="api.$ZONE"; AUTH="auth.$ZONE"; MEDIA="media.$ZONE" ;;
  uat|dev) s="$ENV"
    WEB="$s.$ZONE"; SELLER="seller-$s.$ZONE"; ADMIN="admin-$s.$ZONE"; API="api-$s.$ZONE"; AUTH="auth-$s.$ZONE"; MEDIA="media-$s.$ZONE" ;;
  *) echo "ENV must be uat or prod" >&2; exit 2 ;;
esac
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
fail=0
code() { curl -s -o /dev/null -m 20 -w '%{http_code}' "$@" 2>/dev/null || true; }
expect() { # expect "name" "space separated acceptable codes" actual
  if [[ " $2 " == *" $3 "* ]]; then printf 'PASS  %-58s %s\n' "$1" "$3"; else printf 'FAIL  %-58s got %s, want %s\n' "$1" "$3" "$2"; fail=$((fail+1)); fi
}
echo "== $ENV: web, edge, TLS"
expect "media host resolves https://$MEDIA/"        "200 301 302 307 400 403 404" "$(code "https://$MEDIA/")"
expect "storefront https://$WEB/"                     "200 301 302 307" "$(code "https://$WEB/")"
expect "seller center https://$SELLER/"               "200 301 302 307" "$(code "https://$SELLER/")"
expect "staff console https://$ADMIN/"                "200 301 302 307 401 403" "$(code "https://$ADMIN/")"
expect "plain http redirects or is refused ($WEB)"    "301 302 308 403 000" "$(code "http://$WEB/")"
echo "== $ENV: api and auth through the tunnel"
expect "bff ready https://$API/bff/readyz"            "200" "$(code "https://$API/bff/readyz")"
expect "openid configuration https://$AUTH"           "200" "$(code "https://$AUTH/.well-known/openid-configuration")"
expect "jwks https://$AUTH/oauth2/jwks"               "200" "$(code "https://$AUTH/oauth2/jwks")"
echo "== $ENV: webhook routes exist and enforce the signature (unsigned POST = 400)"
for p in payments/v1/webhooks/stripe seller/v1/webhooks/stripe-connect seller/v1/webhooks/stripe-billing; do
  expect "POST /$p" "400" "$(code -X POST "https://$API/$p" -H 'Content-Type: application/json' -d '{}')"
done
echo "== $ENV: operational paths are NOT public (edge denies, expect 404)"
for h in "$API" "$AUTH"; do
  for p in modules/identity/actuator/health modules mcp v1/tools .well-known/oauth-protected-resource; do
    expect "https://$h/$p" "404" "$(code "https://$h/$p")"
  done
done
for p in bookings/modules fulfilment/modules identity/modules payments/modules/payments identity/mcp; do
  expect "https://$API/$p" "404" "$(code "https://$API/$p")"
done
expect "actuator is not public https://$API/actuator/prometheus" "404 401 403" "$(code "https://$API/actuator/prometheus")"
echo "== $ENV: CORS preflight from the storefront origin only"
o="$(curl -s -o /dev/null -m 20 -D - -X OPTIONS "https://$API/bff/v1/home" -H "Origin: https://$WEB" -H 'Access-Control-Request-Method: GET' 2>/dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')"
if [[ "$o" == "https://$WEB" || -z "$o" ]]; then printf 'PASS  %-58s %s\n' "allowed origin is exact or the BFF is same-origin" "${o:-none}"; else printf 'FAIL  %-58s %s\n' "allowed origin" "$o"; fail=$((fail+1)); fi
b="$(curl -s -o /dev/null -m 20 -D - -X OPTIONS "https://$API/bff/v1/home" -H "Origin: https://evil.example" -H 'Access-Control-Request-Method: GET' 2>/dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')"
if [[ -z "$b" ]]; then printf 'PASS  %-58s none\n' "a foreign origin gets no CORS grant"; else printf 'FAIL  %-58s %s\n' "foreign origin granted" "$b"; fail=$((fail+1)); fi
if [[ "$ENV" != prod ]]; then
  r="$(curl -s -m 20 -D - -o /dev/null "https://$WEB/" 2>/dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="x-robots-tag"{print $2}')"
  case "$r" in *noindex*) printf 'PASS  %-58s %s\n' "non-prod storefront is noindex" "$r" ;; *) printf 'FAIL  %-58s %s\n' "non-prod storefront must be noindex" "${r:-no header}"; fail=$((fail+1)) ;; esac
fi
echo; echo "$fail check(s) failed"; exit "$fail"
