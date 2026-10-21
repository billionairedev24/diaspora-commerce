#!/usr/bin/env bash
# Writes config.env and secrets.local.env (what infra/ovh-vps/*.sh read on the server) from environment variables, so the
# pipeline never keeps them in the repository and a value with special characters survives (printf %q).
#   tools/ovh/render-config.sh <output directory>
# Every value comes from the GitHub Environment `ovh-vps` (tools/ovh/gh-setup.sh) through the workflow's env block.
set -euo pipefail
out=${1:?output directory}
mkdir -p "$out"
umask 077

kv() { printf '%s=%q\n' "$1" "${2-}"; }

{
  kv DOMAIN "${OVH_DOMAIN:?OVH_DOMAIN (variable) is missing}"
  kv ACME_EMAIL "${ACME_EMAIL:?ACME_EMAIL (variable) is missing}"
  kv IMAGE_REGISTRY "${IMAGE_REGISTRY:?}"
  kv IMAGE_TAG "${IMAGE_TAG:?}"
  kv GHCR_USER "${GHCR_USER:-}"
  kv GHCR_TOKEN "${GHCR_TOKEN:-}"
  kv DB_MODE "${DB_MODE:-cluster}"
  kv EXTERNAL_PG_HOST "${EXTERNAL_PG_HOST:-}"
  kv EXTERNAL_PG_ADMIN_USER "${EXTERNAL_PG_ADMIN_USER:-}"
  kv EXTERNAL_PG_ADMIN_PASSWORD "${EXTERNAL_PG_ADMIN_PASSWORD:-}"
  kv EXTERNAL_PG_SSLMODE "${EXTERNAL_PG_SSLMODE:-require}"
  kv ADMIN_EMAIL "${ADMIN_EMAIL:-}"
  kv ADMIN_PASSWORD "${ADMIN_PASSWORD:-}"
  kv ADMIN_PHONE "${ADMIN_PHONE:-}"
} > "$out/config.env"

: > "$out/secrets.local.env"
for k in STRIPE_SECRET_KEY STRIPE_PUBLISHABLE_KEY STRIPE_WEBHOOK_SECRET STRIPE_CONNECT_WEBHOOK_SECRET STRIPE_BILLING_WEBHOOK_SECRET \
         RESEND_API_KEY OPENROUTER_API_KEY GOOGLE_PLACES_API_KEY GOOGLE_MAPS_API_KEY SMARTY_AUTH_ID SMARTY_AUTH_TOKEN \
         UPLOADTHING_API_KEY R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY USPS_CLIENT_ID USPS_CLIENT_SECRET UPS_CLIENT_ID \
         UPS_CLIENT_SECRET FEDEX_CLIENT_ID FEDEX_CLIENT_SECRET; do
  v="${!k-}"
  [ -z "$v" ] || kv "$k" "$v" >> "$out/secrets.local.env"
done
