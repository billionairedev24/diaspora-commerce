#!/usr/bin/env bash
# Renders the Kubernetes Secrets of an environment (there is no External Secrets Operator or cloud secret store):
#   secrets.sh platform   postgres / valkey / meilisearch / temporal / openfga, each in its namespace
#   secrets.sh databases  one Postgres role + database per service (idempotent)
#   secrets.sh services   <service>-secrets in $NS, the keys listed in each chart's "Secret keys expected" comment
# Generated values are kept in /var/lib/afrotransact/generated.env (root only), so re-running changes nothing.
# Third-party keys come from secrets.local.env. Back up generated.env: without it the Secrets cannot be rebuilt.
#
# PROFILE=prod (production secrets, run on an OPERATOR machine, never on a shared server):
#   PROFILE=prod STATE_DIR=$HOME/.afrotransact-prod SECRETS_OUT=./out DB_MODE=external ./secrets.sh services|platform
#   - the launch set only (services.launch.conf), APP_ENV=prod, no OpenFGA (FGA_ENABLED=false in prod), namespace afrotransact-prod
#   - PROFILE=uat is the same launch set with APP_ENV=uat and Stripe TEST keys: start from secrets.uat.env.example (or
#     tools/release/uat-env.sh split). LINK_SIGNING_KEY and every other internal secret is generated here (never typed); only
#     third-party values come from secrets.local.env
#   - SECRETS_OUT writes the Secrets as YAML files for SOPS instead of applying them (infra/ops/secrets/README.md)
#   - STATE_DIR holds the generated values (IDENTITY_DATA_KEY, signing key ...): back it up OFFLINE, separately from the database
#   - `databases` is for a self-hosted Postgres only in dev; for Layerbase run infra/tofu/scripts/postgres-bootstrap.sh
#
# DB_LAYOUT=schema (RELEASE 1, ADR 0011; default `database` keeps main's one-database-per-service layout):
#   - databases: ONE database `core` for the Java modules and ONE `go` for the Go modules, one schema and one role per module, built by
#     infra/ops/postgres/release-1-bootstrap.sh (needs DB_MODE=external; the role passwords are the same DBPW_<schema> values kept in
#     $STATE_DIR/generated.env, so nothing is typed or printed)
#   - services: the per-service Secrets as before (their DB_URL now points at the module's schema, so the old Applications that are not
#     moved yet, and a rollback after a cutover, read and write the same tables), PLUS afrotransact-core-secrets and
#     afrotransact-go-secrets: the union of the modules' keys, with CORE_DB_URL and <MODULE>_DB_USER / <MODULE>_DB_PASSWORD for Java and
#     <MODULE>_DB_URL for Go (docs/runbooks/release-1-deploy.md)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

PG_HOST="postgres.data.svc.cluster.local"; PG_SSL="disable"; PG_ADMIN="at_admin"
PG_SSLROOTCERT=""   # path of the CA bundle INSIDE the pods; only set when the sslmode verifies the server (F11)
if [[ "$DB_MODE" == external ]]; then PG_HOST="$EXTERNAL_PG_HOST"; PG_SSL="${EXTERNAL_PG_SSLMODE:-require}"; PG_ADMIN="$EXTERNAL_PG_ADMIN_USER"; fi
# F11: verify-ca / verify-full need the provider's CA bundle: EXTERNAL_PG_CA_FILE (operator side, PEM) becomes the Secret pg-ca
# (key ca.crt), which the at-service chart mounts at the directory of EXTERNAL_PG_SSLROOTCERT (chart value postgresCA.enabled).
case "$PG_SSL" in
  verify-ca|verify-full)
    PG_SSLROOTCERT="${EXTERNAL_PG_SSLROOTCERT:-/etc/pg-ca/ca.crt}"
    [[ "$(basename "$PG_SSLROOTCERT")" == ca.crt ]] || die "EXTERNAL_PG_SSLROOTCERT must end in /ca.crt (the key of the Secret pg-ca)"
    [[ -r "${EXTERNAL_PG_CA_FILE:-}" ]] || die "EXTERNAL_PG_SSLMODE=$PG_SSL needs EXTERNAL_PG_CA_FILE (readable PEM of the Postgres provider's CA)"
    grep -q 'BEGIN CERTIFICATE' "$EXTERNAL_PG_CA_FILE" || die "EXTERNAL_PG_CA_FILE is not a PEM certificate bundle" ;;
  disable|allow|prefer|require) ;;
  *) die "EXTERNAL_PG_SSLMODE must be disable, require, verify-ca or verify-full (got '$PG_SSL')" ;;
esac
if [[ "$DB_MODE" == external && "$PROFILE" == prod && "$PG_SSL" != verify-full ]]; then
  [[ "${EXTERNAL_PG_REQUIRE_VERIFY:-false}" != true ]] || die "EXTERNAL_PG_REQUIRE_VERIFY=true: prod must use EXTERNAL_PG_SSLMODE=verify-full (got '$PG_SSL')"
  printf '\033[33mwarning:\033[0m prod database TLS is sslmode=%s: encrypted, but the server is not verified (audit F11). Switch to verify-full: docs/runbooks/postgres-tls.md. EXTERNAL_PG_REQUIRE_VERIFY=true turns this into an error.\n' "$PG_SSL" >&2
fi
KAFKA="kafka-0.kafka.kafka.svc.cluster.local:9092"
VALKEY_PW="$(gen VALKEY_PASSWORD)"
# Release 1 go-live: Valkey is a MANAGED service on Layerbase, not a pod. EXTERNAL_REDIS_URL (rediss://<user>:<password>@<host>:<port>, a secret VALUE
# that stays in secrets.local.env) becomes REDIS_URL of every service that uses it. Unset = an in-cluster Valkey (dev profile only).
REDIS_URL="${EXTERNAL_REDIS_URL:-redis://:${VALKEY_PW}@valkey.data.svc.cluster.local:6379}"
MEILI_KEY="$(gen MEILI_MASTER_KEY)"
FGA_TOKEN=""; [[ "$PROD_LIKE" == true ]] || FGA_TOKEN="$(gen FGA_API_TOKEN)"   # prod: OpenFGA is not deployed (FGA_ENABLED=false)
TEMPORAL_TARGET="temporal.temporal.svc.cluster.local:7233"

pg_admin_password() { if [[ "$DB_MODE" == external ]]; then printf '%s' "$EXTERNAL_PG_ADMIN_PASSWORD"; else gen POSTGRES_ADMIN_PASSWORD; fi; }

psql_admin() { # psql_admin DB SQL...   through the Postgres pod (cluster) or from this server (external)
  local db="$1"; shift
  if [[ "$DB_MODE" == cluster ]]; then
    kubectl -n data exec postgres-0 -- env PGPASSWORD="$(pg_admin_password)" psql -h 127.0.0.1 -U "$PG_ADMIN" -d "$db" -v ON_ERROR_STOP=1 -qtA "$@"
  else
    env PGPASSWORD="$(pg_admin_password)" PGSSLMODE="$PG_SSL" ${EXTERNAL_PG_CA_FILE:+PGSSLROOTCERT="$EXTERNAL_PG_CA_FILE"} psql -h "$PG_HOST" -U "$PG_ADMIN" -d "$db" -v ON_ERROR_STOP=1 -qtA "$@"
  fi
}

DB_LAYOUT="${DB_LAYOUT:-database}"
case "$DB_LAYOUT" in database|schema) ;; *) die "DB_LAYOUT must be database or schema" ;; esac
is_java() { services | awk -v s="$1" '$1==s && $2=="java"{f=1} END{exit !f}'; }
role_of() { printf '%s_app' "$1"; }
db_password() { gen "DBPW_$1" 27; }
# Release 1 (DB_LAYOUT=schema): a service's data is the schema named after its module ('-' as '_', as module-core derives it) in the
# database `core` (Java) or `go` (Go); its role is <schema>_app and its password DBPW_<schema>. In the database layout all three are the
# old per-service database name.
schema_of() { printf '%s' "${1//-/_}"; }
layout_db() { # layout_db SERVICE DB
  if [[ "$DB_LAYOUT" == schema ]]; then if is_java "$1"; then printf core; else printf go; fi; else printf '%s' "$2"; fi
}
layout_name() { if [[ "$DB_LAYOUT" == schema ]]; then schema_of "$1"; else printf '%s' "$2"; fi; }   # the role / password stem
pg_ssl_params() { printf 'sslmode=%s' "$PG_SSL"; [[ -z "$PG_SSLROOTCERT" ]] || printf '&sslrootcert=%s' "$PG_SSLROOTCERT"; }
jdbc_url() { printf 'jdbc:postgresql://%s:5432/%s?%s' "$PG_HOST" "$1" "$(pg_ssl_params)"; }
go_url() { printf 'postgres://%s:%s@%s:5432/%s?%s' "$(role_of "$1")" "$(db_password "$1")" "$PG_HOST" "${2:-$1}" "$(pg_ssl_params)"; }

ensure_namespaces() {
  [[ -z "${SECRETS_OUT:-}" ]] || return 0   # render-only mode: no cluster
  for n in data kafka temporal openfga ingress cert-manager "$NS"; do kubectl get ns "$n" >/dev/null 2>&1 || kubectl create ns "$n" >/dev/null; done
  # NetworkPolicies of the data stores admit namespaces carrying this label.
  kubectl label ns "$NS" app.kubernetes.io/part-of=afrotransact --overwrite >/dev/null
  kubectl label ns data kafka temporal openfga app.kubernetes.io/part-of=afrotransact-platform --overwrite >/dev/null
}

platform() {
  log "platform secrets"
  ensure_namespaces
  [[ "$DB_MODE" == cluster ]] && secret data postgres "POSTGRES_PASSWORD=$(pg_admin_password)"
  [[ -n "${EXTERNAL_REDIS_URL:-}" ]] || secret data valkey "VALKEY_PASSWORD=$VALKEY_PW"   # managed Valkey: no in-cluster pod, no Secret
  secret data meilisearch "MEILI_MASTER_KEY=$MEILI_KEY"
  local tdb_pw ofga_pw
  tdb_pw="$(db_password temporal)"
  secret temporal temporal "POSTGRES_SEEDS=$PG_HOST" "DB_PORT=5432" "POSTGRES_USER=$(role_of temporal)" "POSTGRES_PWD=$tdb_pw"
  if [[ "$PROD_LIKE" == true ]]; then
    # Production extras (no OpenFGA). Values come from secrets.local.env; an empty value is a mistake the apply step refuses.
    secret ingress cloudflared "token=${CLOUDFLARED_TUNNEL_TOKEN:-}"
    secret observability grafana-cloud-otlp "GRAFANA_OTLP_ENDPOINT=${GRAFANA_OTLP_ENDPOINT:-}" "GRAFANA_INSTANCE_ID=${GRAFANA_INSTANCE_ID:-}" "GRAFANA_API_TOKEN=${GRAFANA_API_TOKEN:-}"
    # Grafana Alloy (metrics + logs, infra/k8s/platform/lean/alloy). ONE Grafana Cloud stack serves uat and prod: GRAFANA_ENV
    # (uat | prod, default = the profile) becomes the external label `env` on every series and log line; use a separate
    # access-policy token per env. VALKEY_PASSWORD and MEILI_MASTER_KEY let Alloy scrape those two (same values as their own Secrets).
    secret observability grafana-cloud-alloy "CLUSTER_ENV=${GRAFANA_ENV:-$PROFILE}" \
      "GRAFANA_PROM_URL=${GRAFANA_PROM_URL:-}" "GRAFANA_PROM_USER=${GRAFANA_PROM_USER:-}" \
      "GRAFANA_LOKI_URL=${GRAFANA_LOKI_URL:-}" "GRAFANA_LOKI_USER=${GRAFANA_LOKI_USER:-}" \
      "GRAFANA_TOKEN=${GRAFANA_ALLOY_TOKEN:-}" "VALKEY_PASSWORD=$VALKEY_PW" "MEILI_MASTER_KEY=$MEILI_KEY"
    # Nightly backup job (infra/k8s/platform/lean/backup): admin read access, the PUBLIC age key and this environment's Backblaze B2
    # bucket with its bucket-scoped key (pg-backup.sh builds the rclone remote from B2_*; docs/runbooks/backups-backblaze.md).
    secret ops pg-backup "PGHOST=$PG_HOST" "PGPORT=5432" "PGUSER=$PG_ADMIN" "PGPASSWORD=$(pg_admin_password)" "PGSSLMODE=$PG_SSL" \
      "AGE_RECIPIENT=${BACKUP_AGE_RECIPIENT:-}" "B2_KEY_ID=${BACKUP_B2_KEY_ID:-}" "B2_APPLICATION_KEY=${BACKUP_B2_APPLICATION_KEY:-}" \
      "B2_BUCKET=${BACKUP_B2_BUCKET:-afrotransact-${PROFILE}-backups}" "B2_ENDPOINT=${BACKUP_B2_ENDPOINT:-}" \
      "RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}" "RETENTION_MODE=${BACKUP_RETENTION_MODE:-sweep}" "BACKUP_ENV=${GRAFANA_ENV:-$PROFILE}" \
      "PUSHGATEWAY_URL=${BACKUP_PUSHGATEWAY_URL:-}" "HEALTHCHECK_URL=${BACKUP_HEALTHCHECK_URL:-}"
    # Nightly R2 -> B2 copy (infra/k8s/platform/lean/backup/r2-cronjob.yaml, S380): two READ-ONLY R2 tokens (one per bucket, not the ones media
    # writes with), the B2 key (R2COPY_B2_* or, by default, the database backup's) and the same public age key. docs/runbooks/backup-restore-r2.md.
    secret ops r2-backup "R2_ACCOUNT_ID=${R2_ACCOUNT_ID:-}" "R2_MEDIA_BUCKET=${R2_PUBLIC_BUCKET:-afrotransact-${PROFILE}-media}" \
      "R2_KYC_BUCKET=${R2_PRIVATE_BUCKET:-afrotransact-${PROFILE}-kyc-evidence}" \
      "R2_MEDIA_KEY_ID=${R2COPY_R2_MEDIA_KEY_ID:-}" "R2_MEDIA_SECRET=${R2COPY_R2_MEDIA_SECRET:-}" \
      "R2_KYC_KEY_ID=${R2COPY_R2_KYC_KEY_ID:-}" "R2_KYC_SECRET=${R2COPY_R2_KYC_SECRET:-}" \
      "AGE_RECIPIENT=${BACKUP_AGE_RECIPIENT:-}" "B2_KEY_ID=${R2COPY_B2_KEY_ID:-${BACKUP_B2_KEY_ID:-}}" \
      "B2_APPLICATION_KEY=${R2COPY_B2_APPLICATION_KEY:-${BACKUP_B2_APPLICATION_KEY:-}}" \
      "B2_BUCKET=${R2COPY_B2_BUCKET:-${BACKUP_B2_BUCKET:-afrotransact-${PROFILE}-backups}}" "B2_ENDPOINT=${BACKUP_B2_ENDPOINT:-}" \
      "BACKUP_ENV=${GRAFANA_ENV:-$PROFILE}" "PUSHGATEWAY_URL=${BACKUP_PUSHGATEWAY_URL:-}" "HEALTHCHECK_URL=${R2COPY_HEALTHCHECK_URL:-}"
    return 0
  fi
  ofga_pw="$(db_password openfga)"
  secret openfga openfga \
    "OPENFGA_DATASTORE_URI=postgres://$(role_of openfga):${ofga_pw}@${PG_HOST}:5432/openfga?sslmode=${PG_SSL}" \
    "OPENFGA_AUTHN_PRESHARED_KEYS=$FGA_TOKEN"
}

databases() {
  if [[ "$DB_LAYOUT" == schema ]]; then
    [[ "$DB_MODE" == external ]] || die "DB_LAYOUT=schema needs DB_MODE=external (the release-1 bootstrap runs against a managed Postgres)"
    log "release-1 databases: core, go, temporal, temporal_visibility (schema and role per module)"
    PGHOST="$PG_HOST" PGUSER="$PG_ADMIN" PGPASSWORD="$(pg_admin_password)" PGSSLMODE="$PG_SSL" ${EXTERNAL_PG_CA_FILE:+PGSSLROOTCERT="$EXTERNAL_PG_CA_FILE"} \
      STATE_DIR="$STATE_DIR" "$HERE/../ops/postgres/release-1-bootstrap.sh"
    return 0
  fi
  log "databases and roles"
  local db role pw
  local names=(temporal temporal_visibility)
  [[ "$PROD_LIKE" == true ]] || names+=(openfga)
  while read -r _ _ db _; do [[ "$db" != "-" ]] && names+=("$db"); done < <(services)
  for db in "${names[@]}"; do
    # temporal_visibility belongs to the temporal role
    if [[ "$db" == temporal_visibility ]]; then role="$(role_of temporal)"; pw="$(db_password temporal)"; else role="$(role_of "$db")"; pw="$(db_password "$db")"; fi
    psql_admin postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$role') THEN CREATE ROLE \"$role\" LOGIN PASSWORD '$pw'; END IF; END \$\$;" >/dev/null
    # Managed servers: the admin is not a superuser, so it must be a member of the role to create a database owned by it.
    psql_admin postgres -c "GRANT \"$role\" TO CURRENT_USER" >/dev/null 2>&1 || true
    [[ -n "$(psql_admin postgres -c "SELECT 1 FROM pg_database WHERE datname='$db'")" ]] || psql_admin postgres -c "CREATE DATABASE \"$db\" OWNER \"$role\"" >/dev/null
    psql_admin postgres -c "REVOKE ALL ON DATABASE \"$db\" FROM PUBLIC; GRANT ALL ON DATABASE \"$db\" TO \"$role\"" >/dev/null
    psql_admin "$db" -c "ALTER SCHEMA public OWNER TO \"$role\"" >/dev/null
    # Extensions are created HERE, as admin, because the service roles cannot on a managed server: fulfilment's V3 migration and
    # geo's 001 migration run `create extension if not exists postgis` as the service role, which only works once it exists.
    # (bookings: btree_gist, a trusted extension, pre-created too.) Same list as infra/tofu/scripts/postgres-bootstrap.sh.
    case "$db" in
      geo|fulfilment) psql_admin "$db" -c "CREATE EXTENSION IF NOT EXISTS postgis" >/dev/null || die "cannot create postgis in $db (ask the provider; docs/runbooks/release-1-go-live.md)" ;;
      bookings) psql_admin "$db" -c "CREATE EXTENSION IF NOT EXISTS btree_gist" >/dev/null || die "cannot create btree_gist in $db" ;;
    esac
  done
}

# KEY=VALUE records for one service, each terminated by a NUL byte (values may be empty when a third-party key was not given, and may
# span lines: SIGNING_KEY_PEM is a PEM). Read them with `mapfile -d '' -t` or `read -r -d ''`, never line by line.
service_keys() {
  local svc="$1" db="$2"
  local common=("APP_ENV=$PROFILE" "KAFKA_BOOTSTRAP=$KAFKA")
  local out=()
  if [[ "$db" != "-" ]]; then
    local stem; stem="$(layout_name "$svc" "$db")"
    if is_java "$svc"; then
      out+=("DB_NAME=$stem" "DB_URL=$(jdbc_url "$(layout_db "$svc" "$db")")" "DB_USER=$(role_of "$stem")" "DB_PASSWORD=$(db_password "$stem")")
    else
      out+=("DB_NAME=$stem" "DB_URL=$(go_url "$stem" "$(layout_db "$svc" "$db")")")
    fi
  fi
  case "$svc" in
    ai-gateway)       out+=("${common[@]}" "OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}") ;;
    authz-sync)       out+=("KAFKA_BOOTSTRAP=$KAFKA" "OPENFGA_API_TOKEN=$FGA_TOKEN") ;;
    bookings)         out+=("${common[@]}" "BOOKINGS_CLIENT_SECRET=$(gen BOOKINGS_CLIENT_SECRET)") ;;
    carrier-gateway)  out+=("REDIS_URL=$REDIS_URL" "USPS_CLIENT_ID=${USPS_CLIENT_ID:-}" "USPS_CLIENT_SECRET=${USPS_CLIENT_SECRET:-}" "UPS_CLIENT_ID=${UPS_CLIENT_ID:-}" "UPS_CLIENT_SECRET=${UPS_CLIENT_SECRET:-}" "FEDEX_CLIENT_ID=${FEDEX_CLIENT_ID:-}" "FEDEX_CLIENT_SECRET=${FEDEX_CLIENT_SECRET:-}"
                      # Optional carrier credentials (documented in the chart): rendered only when given in secrets.local.env
                      ${USPS_PAYMENT_TOKEN:+"USPS_PAYMENT_TOKEN=$USPS_PAYMENT_TOKEN"} ${UPS_ACCOUNT:+"UPS_ACCOUNT=$UPS_ACCOUNT"}
                      ${UPS_WEBHOOK_CREDENTIAL:+"UPS_WEBHOOK_CREDENTIAL=$UPS_WEBHOOK_CREDENTIAL"} ${FEDEX_ACCOUNT:+"FEDEX_ACCOUNT=$FEDEX_ACCOUNT"}
                      ${FEDEX_WEBHOOK_SECRET:+"FEDEX_WEBHOOK_SECRET=$FEDEX_WEBHOOK_SECRET"} ${DHL_USERNAME:+"DHL_USERNAME=$DHL_USERNAME"}
                      ${DHL_PASSWORD:+"DHL_PASSWORD=$DHL_PASSWORD"} ${DHL_ACCOUNT:+"DHL_ACCOUNT=$DHL_ACCOUNT"}
                      ${DHL_WEBHOOK_SECRET:+"DHL_WEBHOOK_SECRET=$DHL_WEBHOOK_SECRET"} ${S3_ACCESS_KEY_ID:+"S3_ACCESS_KEY_ID=$S3_ACCESS_KEY_ID"}
                      ${S3_SECRET_ACCESS_KEY:+"S3_SECRET_ACCESS_KEY=$S3_SECRET_ACCESS_KEY"}) ;;
    cart)             out+=("REDIS_URL=$REDIS_URL" "CART_CLIENT_SECRET=$(gen CART_CLIENT_SECRET)") ;;
    catalog)          out+=("${common[@]}" ${FGA_TOKEN:+"FGA_API_TOKEN=$FGA_TOKEN"} "CATALOG_CLIENT_SECRET=$(gen CATALOG_CLIENT_SECRET)" "GS1_API_KEY=") ;;
    content)          out+=("${common[@]}" "NEWSLETTER_TOKEN_KEY=$(gen NEWSLETTER_TOKEN_KEY)") ;;
    delivery-runs)    out+=("${common[@]}" "DELIVERY_RUNS_CLIENT_SECRET=$(gen DELIVERY_RUNS_CLIENT_SECRET)") ;;
    fulfilment)       out+=("${common[@]}" "FULFILMENT_CLIENT_SECRET=$(gen FULFILMENT_CLIENT_SECRET)") ;;
    gateway-bff)      out+=("REDIS_URL=$REDIS_URL" "OIDC_CLIENT_SECRET=$(gen GATEWAY_BFF_CLIENT_SECRET)" "SESSION_SIGNING_KEY=$(gen SESSION_SIGNING_KEY)" "STRIPE_PUBLISHABLE_KEY=${STRIPE_PUBLISHABLE_KEY:-}") ;;
    geo)              out+=("GOOGLE_PLACES_API_KEY=${GOOGLE_PLACES_API_KEY:-}" "GOOGLE_MAPS_API_KEY=${GOOGLE_MAPS_API_KEY:-}"
                      ${OSRM_URL:+"OSRM_URL=$OSRM_URL"}) ;;     # OSRM_URL: optional routing engine, rendered only when set
    identity)
      out+=("${common[@]}" "SIGNING_KEY_PEM=$(signing_key)" "IDENTITY_DATA_KEY=$(gen IDENTITY_DATA_KEY)" "IDENTITY_DATA_KEY_ID=k1" "IDENTITY_DATA_KEYS_PREVIOUS="
        "BOOTSTRAP_ADMIN_EMAIL=${ADMIN_EMAIL:-admin@example.com}" "BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PASSWORD:-}" "BOOTSTRAP_ADMIN_PHONE=${ADMIN_PHONE:-}"
        "GOOGLE_CLIENT_ID=" "GOOGLE_CLIENT_SECRET=" "APPLE_CLIENT_ID=" "APPLE_CLIENT_SECRET=" "FACEBOOK_CLIENT_ID=" "FACEBOOK_CLIENT_SECRET=")
      local c
      for c in BFF SELLER_SERVICE ORDERS BOOKINGS FULFILMENT PAYMENTS NOTIFICATIONS SEARCH RETAIL GATEWAY_BFF OFFERS INDEXER TRUSTOPS TAX_RETURNS INVENTORY CATALOG DELIVERY_RUNS CART MEDIA; do
        out+=("${c}_CLIENT_SECRET=$(gen "${c}_CLIENT_SECRET")")
      done ;;
    indexer)          out+=("KAFKA_BOOTSTRAP=$KAFKA" "SEARCH_PROVIDER=meilisearch" "MEILISEARCH_URL=http://meilisearch.data.svc.cluster.local:7700" "MEILISEARCH_API_KEY=$MEILI_KEY" "ES_URLS=http://elasticsearch.invalid:9200" "ES_API_KEY=" "INDEXER_CLIENT_SECRET=$(gen INDEXER_CLIENT_SECRET)") ;;
    inventory)        out+=("${common[@]}" "INVENTORY_CLIENT_SECRET=$(gen INVENTORY_CLIENT_SECRET)") ;;
    mcp-server)       out+=("KAFKA_BOOTSTRAP=$KAFKA" "REDIS_URL=$REDIS_URL") ;;
    media)
      # Production media is Cloudflare R2 only (docs/runbooks/r2-buckets.md): one token per bucket. The dev box keeps UploadThing.
      if [[ "$PROD_LIKE" == true ]]; then
        out+=("LINK_SIGNING_KEY=$(gen LINK_SIGNING_KEY)" "LINK_SIGNING_KEYS_PREVIOUS=" "MEDIA_CLIENT_SECRET=$(gen MEDIA_CLIENT_SECRET)" "R2_ACCOUNT_ID=${R2_ACCOUNT_ID:-}"
          "R2_ACCESS_KEY_ID=${R2_ACCESS_KEY_ID:-}" "R2_SECRET_ACCESS_KEY=${R2_SECRET_ACCESS_KEY:-}"
          "R2_PRIVATE_ACCESS_KEY_ID=${R2_PRIVATE_ACCESS_KEY_ID:-}" "R2_PRIVATE_SECRET_ACCESS_KEY=${R2_PRIVATE_SECRET_ACCESS_KEY:-}")
      else
        out+=("UPLOADTHING_API_KEY=${UPLOADTHING_API_KEY:-}" "LINK_SIGNING_KEY=$(gen LINK_SIGNING_KEY)" "LINK_SIGNING_KEYS_PREVIOUS=" "MEDIA_CLIENT_SECRET=$(gen MEDIA_CLIENT_SECRET)" "R2_ACCESS_KEY_ID=${R2_ACCESS_KEY_ID:-}" "R2_SECRET_ACCESS_KEY=${R2_SECRET_ACCESS_KEY:-}" "AZURE_STORAGE_KEY=")
      fi ;;
    notifications)    out+=("RESEND_API_KEY=${RESEND_API_KEY:-}" "RESEND_WEBHOOK_SECRET=${RESEND_WEBHOOK_SECRET:-}" "UNSUBSCRIBE_SIGNING_KEY=$(gen UNSUBSCRIBE_SIGNING_KEY)" "NOTIFICATIONS_CLIENT_SECRET=$(gen NOTIFICATIONS_CLIENT_SECRET)"
                      # Optional channels (documented in the chart): rendered only when given in secrets.local.env
                      ${SENDGRID_API_KEY:+"SENDGRID_API_KEY=$SENDGRID_API_KEY"} ${SENDGRID_WEBHOOK_PUBLIC_KEY:+"SENDGRID_WEBHOOK_PUBLIC_KEY=$SENDGRID_WEBHOOK_PUBLIC_KEY"}
                      ${TWILIO_ACCOUNT_SID:+"TWILIO_ACCOUNT_SID=$TWILIO_ACCOUNT_SID"} ${TWILIO_AUTH_TOKEN:+"TWILIO_AUTH_TOKEN=$TWILIO_AUTH_TOKEN"}
                      ${VONAGE_API_KEY:+"VONAGE_API_KEY=$VONAGE_API_KEY"} ${VONAGE_API_SECRET:+"VONAGE_API_SECRET=$VONAGE_API_SECRET"}
                      ${FCM_SERVICE_ACCOUNT_JSON:+"FCM_SERVICE_ACCOUNT_JSON=$FCM_SERVICE_ACCOUNT_JSON"} ${APNS_KEY_P8:+"APNS_KEY_P8=$APNS_KEY_P8"}
                      ${WHATSAPP_TOKEN:+"WHATSAPP_TOKEN=$WHATSAPP_TOKEN"}) ;;
    offers)           out+=("${common[@]}" "OFFERS_CLIENT_SECRET=$(gen OFFERS_CLIENT_SECRET)" ${FGA_TOKEN:+"FGA_API_TOKEN=$FGA_TOKEN"}) ;;
    orders)           out+=("${common[@]}" "ORDERS_CLIENT_SECRET=$(gen ORDERS_CLIENT_SECRET)" "TEMPORAL_TARGET=$TEMPORAL_TARGET" "TEMPORAL_NAMESPACE=afrotransact" "TEMPORAL_TLS_CERT=" "TEMPORAL_TLS_KEY=" "HANDOVER_CODE_KEY=$(gen HANDOVER_CODE_KEY)" "PAY_LINK_KEY=$(gen PAY_LINK_KEY)" ${FGA_TOKEN:+"FGA_API_TOKEN=$FGA_TOKEN"}) ;;
    # Stripe webhook secrets (one whsec_ per Stripe endpoint; docs/runbooks/stripe-webhooks.md). A changed value reaches the pod only
    # after the services action AND a restart: kubectl -n $NS rollout restart deploy/payments (STRIPE_WEBHOOK_SECRET) or deploy/seller
    # (STRIPE_CONNECT_WEBHOOK_SECRET: required for sellers). Two Stripe endpoints only: no Stripe Billing, plan fees are card-only.
    payments)         out+=("${common[@]}" "STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY:-}" "STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET:-}" ${FGA_TOKEN:+"FGA_API_TOKEN=$FGA_TOKEN"} "PAYMENTS_CLIENT_SECRET=$(gen PAYMENTS_CLIENT_SECRET)") ;;
    promise)          out+=("REDIS_URL=$REDIS_URL") ;;
    retail)           out+=("${common[@]}" "RETAIL_CLIENT_SECRET=$(gen RETAIL_CLIENT_SECRET)") ;;
    search)           out+=("KAFKA_BOOTSTRAP=$KAFKA" "SEARCH_PROVIDER=meilisearch" "MEILISEARCH_URL=http://meilisearch.data.svc.cluster.local:7700" "MEILISEARCH_API_KEY=$MEILI_KEY" "ES_URLS=http://elasticsearch.invalid:9200" "ES_API_KEY=" "ADS_TOKEN_KEY=$(gen ADS_TOKEN_KEY)" "SEARCH_CLIENT_SECRET=$(gen SEARCH_CLIENT_SECRET)") ;;
    seller)           out+=("${common[@]}" "STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY:-}" "STRIPE_CONNECT_WEBHOOK_SECRET=${STRIPE_CONNECT_WEBHOOK_SECRET:-}" "FIELD_ENCRYPTION_KEY=$(gen FIELD_ENCRYPTION_KEY)") ;;
    tax-returns)      out+=("${common[@]}" "TAX_RETURNS_CLIENT_SECRET=$(gen TAX_RETURNS_CLIENT_SECRET)") ;;
    trust-ops)        out+=("${common[@]}" "TRUSTOPS_CLIENT_SECRET=$(gen TRUSTOPS_CLIENT_SECRET)") ;;
    *)                die "no secret keys defined for $svc (add it to secrets.sh)" ;;
  esac
  # later entries win (e.g. KAFKA_BOOTSTRAP listed twice), first position kept; NUL-terminated so a multi-line value stays one record
  local -A seen=(); local -a order=(); local kv key
  for kv in "${out[@]}"; do
    key="${kv%%=*}"
    [[ -v seen[$key] ]] || order+=("$key")
    seen[$key]="${kv#*=}"
  done
  for key in "${order[@]}"; do printf '%s=%s\0' "$key" "${seen[$key]}"; done
}

signing_key() { # an RSA 3072 PKCS#8 key (identity signs RS256 and refuses any other key type), kept so tokens survive re-runs
  local f="$STATE_DIR/identity-signing-key.pem"
  # An earlier version of this script generated an EC key, which identity cannot load (it fails at start-up): replace it.
  if [[ -f "$f" ]] && ! openssl pkey -in "$f" -noout -text 2>/dev/null | grep -q '^modulus:'; then
    mv "$f" "$f.not-rsa.$(date +%s)"
  fi
  [[ -f "$f" ]] || { install -d -m 700 "$STATE_DIR"; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$f"; chmod 600 "$f"; }
  cat "$f"
}

# Release 1: afrotransact-core-secrets and afrotransact-go-secrets, the union of the modules' keys (a later module wins on a duplicate name;
# the shared ones, KAFKA_BOOTSTRAP, REDIS_URL, FGA_API_TOKEN, have one value). Database credentials are renamed per module: Java gets
# CORE_DB_URL once and <MODULE>_DB_USER / <MODULE>_DB_PASSWORD (MODULE = the module name upper-cased, '-' as '_', what the chart's
# MODULE_<NAME>_ENABLED uses); Go gets <MODULE>_DB_URL. Values are never printed.
deployable_secrets() {
  local svc rt db line k v stem
  local -A core_kv go_kv
  local core_order=() go_order=()
  while read -r svc rt db wave; do
    stem="$(printf '%s' "${svc//-/_}" | tr '[:lower:]' '[:upper:]')"
    while IFS= read -r -d '' line; do
      [[ -n "$line" ]] || continue
      k="${line%%=*}"; v="${line#*=}"
      if [[ "$rt" == java ]]; then
        case "$k" in
          DB_NAME) continue ;;
          DB_URL) k="CORE_DB_URL" ;;
          DB_USER) k="${stem}_DB_USER" ;;
          DB_PASSWORD) k="${stem}_DB_PASSWORD" ;;
        esac
        [[ -v core_kv[$k] ]] || core_order+=("$k"); core_kv[$k]="$v"
      else
        case "$k" in
          DB_NAME) continue ;;
          DB_URL) k="${stem}_DB_URL" ;;
        esac
        [[ -v go_kv[$k] ]] || go_order+=("$k"); go_kv[$k]="$v"
      fi
    done < <(service_keys "$svc" "$db")
  done < <(services)
  local core_args=() go_args=()
  for k in "${core_order[@]}"; do core_args+=("$k=${core_kv[$k]}"); done
  for k in "${go_order[@]}"; do go_args+=("$k=${go_kv[$k]}"); done
  secret "$NS" afrotransact-core-secrets "${core_args[@]}"
  secret "$NS" afrotransact-go-secrets "${go_args[@]}"
  echo "  afrotransact-core: ${#core_args[@]} keys, afrotransact-go: ${#go_args[@]} keys"
}

services_secrets() {
  log "service secrets in namespace $NS"
  if [[ "$PROD_LIKE" == true ]]; then
    # Identity refuses to start in prod with a weak or published bootstrap admin (StartupProvisioning): fail here, not at 3 am.
    local pw="${ADMIN_PASSWORD:-}"
    [[ "${#pw}" -ge 16 && "$pw" != change-me-please-12 ]] || die "ADMIN_PASSWORD must be a real password of at least 16 characters for PROFILE=$PROFILE"
    case "${ADMIN_EMAIL:-}" in ""|admin@example.com|*.test|*@example.com) die "ADMIN_EMAIL must be a real address for PROFILE=$PROFILE" ;; esac
  fi
  ensure_namespaces
  # F11: the CA bundle every service mounts (chart value postgresCA.enabled=true in infra/argocd/envs/<env>/platform-values.yaml).
  [[ -z "$PG_SSLROOTCERT" ]] || secret "$NS" pg-ca "ca.crt=$(cat "$EXTERNAL_PG_CA_FILE")"
  local svc rt db wave kv
  while read -r svc rt db _; do
    mapfile -d '' -t kv < <(service_keys "$svc" "$db")
    secret "$NS" "${svc}-secrets" "${kv[@]}"
    echo "  $svc: ${#kv[@]} keys"
  done < <(services)
  if [[ "$DB_LAYOUT" == schema ]]; then deployable_secrets; fi
  if [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]]; then
    if [[ -n "${SECRETS_OUT:-}" ]]; then
      install -d -m 700 "$SECRETS_OUT"
      ( umask 077; kubectl -n "$NS" create secret docker-registry ghcr --docker-server=ghcr.io --docker-username="$GHCR_USER" --docker-password="$GHCR_TOKEN" --dry-run=client -o yaml > "$SECRETS_OUT/$NS.ghcr.yaml" )
    else
      kubectl -n "$NS" create secret docker-registry ghcr --docker-server=ghcr.io --docker-username="$GHCR_USER" --docker-password="$GHCR_TOKEN" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    fi
  fi
}

case "${1:-}" in
  platform) platform ;;
  databases) databases ;;
  services) services_secrets ;;
  all) platform; databases; services_secrets ;;
  *) die "usage: secrets.sh platform|databases|services|all" ;;
esac
