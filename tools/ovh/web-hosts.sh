#!/usr/bin/env bash
# Computes and validates the public hostnames of the OVH test environment (docs/engineering/environments.md, "Free certificate
# layout (flat web hosts)"). One place, used by the ovh-vps and ovh-web workflows, render-config.sh and tools/deploy/guide.sh.
#
#   tools/ovh/web-hosts.sh            prints KEY=value lines (storefront_host, seller_host, admin_host, *_url), warnings on stderr
#
# Inputs (environment variables):
#   OVH_DOMAIN           base domain, required: the backend is api./auth./grafana./media.<OVH_DOMAIN>
#   OVH_STOREFRONT_HOST  www (default) | apex
#   OVH_SELLER_HOST      optional FULL hostname of the Seller Center & Provider Hub, default seller.<OVH_DOMAIN>
#   OVH_ADMIN_HOST       optional FULL hostname of the staff console, default admin.<OVH_DOMAIN>
#   CLOUDFLARE_ZONE      optional, enables the zone checks (an explicit host must sit under the zone) and the certificate warning
# Exit 1 with `error:` on stderr when a value is invalid. Defaults reproduce the layout of every other environment exactly.
set -euo pipefail

DOMAIN="${OVH_DOMAIN:-}"; DOMAIN="${DOMAIN,,}"
SFH="${OVH_STOREFRONT_HOST:-www}"
SELLER_IN="${OVH_SELLER_HOST:-}"; SELLER_IN="${SELLER_IN,,}"
ADMIN_IN="${OVH_ADMIN_HOST:-}"; ADMIN_IN="${ADMIN_IN,,}"
ZONE="${CLOUDFLARE_ZONE:-}"; ZONE="${ZONE,,}"

# GitHub Actions turns ::warning:: / ::error:: into annotations; elsewhere plain text.
if [[ -n "${GITHUB_ACTIONS:-}" ]]; then warn() { echo "::warning::$*" >&2; }; else warn() { echo "warning: $*" >&2; }; fi
die() { if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::error::$*" >&2; else echo "error: $*" >&2; fi; exit 1; }

HOST_RE='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$'
valid_host() { [[ "$1" =~ $HOST_RE ]] && [[ ${#1} -le 253 ]]; }
labels() { local s="${1//[^.]/}"; echo $(( ${#s} + 1 )); }

[[ -n "$DOMAIN" ]] || die "OVH_DOMAIN is not set"
valid_host "$DOMAIN" || die "OVH_DOMAIN '$DOMAIN' must be a bare lower-case DNS name such as test.example.com (no scheme, port or path)"
case "$SFH" in www|apex) ;; *) die "OVH_STOREFRONT_HOST must be www or apex (got '$SFH')" ;; esac
if [[ -n "$ZONE" ]]; then
  valid_host "$ZONE" || die "CLOUDFLARE_ZONE '$ZONE' is not a valid DNS name"
  [[ "$DOMAIN" == "$ZONE" || "$DOMAIN" == *".$ZONE" ]] || die "OVH_DOMAIN '$DOMAIN' is not the Cloudflare zone '$ZONE' or a name under it"
fi

storefront="$DOMAIN"; [[ "$SFH" == www ]] && storefront="www.$DOMAIN"
seller="${SELLER_IN:-seller.$DOMAIN}"
admin="${ADMIN_IN:-admin.$DOMAIN}"

check_explicit() { # NAME VALUE: only for hosts the owner chose
  local name="$1" h="$2"
  [[ -n "$h" ]] || return 0
  valid_host "$h" || die "$name '$h' must be a full lower-case hostname such as seller-test.example.com (no scheme, port, path or wildcard)"
  if [[ -n "$ZONE" ]]; then
    [[ "$h" == *".$ZONE" ]] || die "$name '$h' must sit under the Cloudflare zone '$ZONE' (a Worker custom domain needs a DNS record in that zone)"
  fi
  case "$h" in
    api."$DOMAIN"|auth."$DOMAIN"|grafana."$DOMAIN"|media."$DOMAIN"|developers."$DOMAIN")
      die "$name '$h' is a backend host of this environment" ;;
  esac
}
check_explicit OVH_SELLER_HOST "$SELLER_IN"
check_explicit OVH_ADMIN_HOST "$ADMIN_IN"

[[ "$seller" != "$admin" ]] || die "the Seller Center and the staff console cannot share the host '$seller'"
[[ "$seller" != "$storefront" ]] || die "the Seller Center cannot share the storefront host '$storefront'"
[[ "$admin" != "$storefront" ]] || die "the staff console cannot share the storefront host '$storefront'"

# Cloudflare's free Universal certificate covers the zone apex and ONE label under it.
if [[ -n "$ZONE" ]]; then
  for pair in "storefront:$storefront" "seller:$seller" "admin:$admin"; do
    h="${pair#*:}"
    [[ "$h" == "$ZONE" ]] && continue
    depth=$(( $(labels "$h") - $(labels "$ZONE") ))
    if (( depth > 1 )); then
      warn "the ${pair%%:*} host $h is $depth labels under the zone $ZONE: Cloudflare's free Universal certificate (apex + *.$ZONE) does not cover it, browsers will show a certificate error unless you buy an Advanced Certificate. Free layout: OVH_SELLER_HOST=seller-<name>.$ZONE OVH_ADMIN_HOST=admin-<name>.$ZONE (docs/runbooks/cloudflare-setup.md)."
    fi
  done
fi

cat <<OUT
domain=$DOMAIN
storefront_host=$storefront
seller_host=$seller
admin_host=$admin
seller_host_explicit=$SELLER_IN
admin_host_explicit=$ADMIN_IN
storefront_url=https://$storefront
seller_url=https://$seller
admin_url=https://$admin
api_url=https://api.$DOMAIN/bff
media_url=https://media.$DOMAIN
OUT
