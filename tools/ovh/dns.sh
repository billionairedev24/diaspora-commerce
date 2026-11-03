#!/usr/bin/env bash
# Optional: creates or updates the A records api./auth./mcp./grafana.<domain> at Cloudflare (DNS only, not proxied, so Let's Encrypt's
# HTTP-01 check reaches the server). Needs CLOUDFLARE_API_TOKEN (Zone: DNS edit on the zone) and CLOUDFLARE_ZONE (the zone's
# apex, e.g. afrotransact.com). Without them it prints the records to create by hand and exits 0.
#   tools/ovh/dns.sh <domain> <ipv4>
set -euo pipefail
domain=${1:?domain}; ip=${2:?ipv4}
hosts="api auth mcp grafana"
if [ -z "${CLOUDFLARE_API_TOKEN:-}" ] || [ -z "${CLOUDFLARE_ZONE:-}" ]; then
  echo "No Cloudflare token or zone set: create these DNS records yourself (type A, DNS only / grey cloud):"
  for h in $hosts; do echo "   $h.$domain  ->  $ip"; done
  exit 0
fi
cf() { # cf METHOD PATH [JSON]
  local data=()
  [ -z "${3:-}" ] || data=(--data "$3")
  printf 'header = "Authorization: Bearer %s"\n' "$CLOUDFLARE_API_TOKEN" \
    | curl -fsS --max-time 30 -K - -X "$1" -H 'Content-Type: application/json' ${data[@]+"${data[@]}"} "https://api.cloudflare.com/client/v4$2"
}
zone=$(cf GET "/zones?name=$CLOUDFLARE_ZONE" | jq -r '.result[0].id // empty')
[ -n "$zone" ] || { echo "Cloudflare zone $CLOUDFLARE_ZONE not found for this token" >&2; exit 1; }
for h in $hosts; do
  name="$h.$domain"
  body=$(jq -cn --arg n "$name" --arg ip "$ip" '{type:"A", name:$n, content:$ip, ttl:120, proxied:false}')
  id=$(cf GET "/zones/$zone/dns_records?type=A&name=$name" | jq -r '.result[0].id // empty')
  if [ -n "$id" ]; then cf PUT "/zones/$zone/dns_records/$id" "$body" >/dev/null; echo "   updated $name -> $ip"
  else cf POST "/zones/$zone/dns_records" "$body" >/dev/null; echo "   created $name -> $ip"; fi
done
