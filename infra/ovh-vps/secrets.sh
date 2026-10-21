#!/usr/bin/env bash
# Creates the Kubernetes Secrets this single server needs (there is no External Secrets Operator or cloud secret store):
#   secrets.sh platform   postgres / valkey / meilisearch / temporal / openfga, each in its namespace
#   secrets.sh databases  one Postgres role + database per service (idempotent)
#   secrets.sh services   <service>-secrets in $NS, the keys listed in each chart's "Secret keys expected" comment
# Generated values are kept in /var/lib/afrotransact/generated.env (root only), so re-running changes nothing.
# Third-party keys come from secrets.local.env. Back up generated.env: without it the Secrets cannot be rebuilt.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config

PG_HOST="postgres.data.svc.cluster.local"; PG_SSL="disable"; PG_ADMIN="at_admin"
if [[ "$DB_MODE" == external ]]; then PG_HOST="$EXTERNAL_PG_HOST"; PG_SSL="${EXTERNAL_PG_SSLMODE:-require}"; PG_ADMIN="$EXTERNAL_PG_ADMIN_USER"; fi
KAFKA="kafka-0.kafka.kafka.svc.cluster.local:9092"
VALKEY_PW="$(gen VALKEY_PASSWORD)"
REDIS_URL="redis://:${VALKEY_PW}@valkey.data.svc.cluster.local:6379"
MEILI_KEY="$(gen MEILI_MASTER_KEY)"
FGA_TOKEN="$(gen FGA_API_TOKEN)"
TEMPORAL_TARGET="temporal.temporal.svc.cluster.local:7233"

pg_admin_password() { if [[ "$DB_MODE" == external ]]; then printf '%s' "$EXTERNAL_PG_ADMIN_PASSWORD"; else gen POSTGRES_ADMIN_PASSWORD; fi; }

psql_admin() { # psql_admin DB SQL...   through the Postgres pod (cluster) or from this server (external)
  local db="$1"; shift
  if [[ "$DB_MODE" == cluster ]]; then
    kubectl -n data exec postgres-0 -- env PGPASSWORD="$(pg_admin_password)" psql -h 127.0.0.1 -U "$PG_ADMIN" -d "$db" -v ON_ERROR_STOP=1 -qtA "$@"
  else
    PGPASSWORD="$(pg_admin_password)" PGSSLMODE="$PG_SSL" psql -h "$PG_HOST" -U "$PG_ADMIN" -d "$db" -v ON_ERROR_STOP=1 -qtA "$@"
  fi
}

is_java() { services | awk -v s="$1" '$1==s && $2=="java"{f=1} END{exit !f}'; }
role_of() { printf '%s_app' "$1"; }
db_password() { gen "DBPW_$1" 27; }
jdbc_url() { printf 'jdbc:postgresql://%s:5432/%s?sslmode=%s' "$PG_HOST" "$1" "$PG_SSL"; }
go_url() { printf 'postgres://%s:%s@%s:5432/%s?sslmode=%s' "$(role_of "$1")" "$(db_password "$1")" "$PG_HOST" "$1" "$PG_SSL"; }

ensure_namespaces() {
  for n in data kafka temporal openfga ingress cert-manager "$NS"; do kubectl get ns "$n" >/dev/null 2>&1 || kubectl create ns "$n" >/dev/null; done
  # NetworkPolicies of the data stores admit namespaces carrying this label.
  kubectl label ns "$NS" app.kubernetes.io/part-of=afrotransact --overwrite >/dev/null
  kubectl label ns data kafka temporal openfga app.kubernetes.io/part-of=afrotransact-platform --overwrite >/dev/null
}

platform() {
  log "platform secrets"
  ensure_namespaces
  [[ "$DB_MODE" == cluster ]] && secret data postgres "POSTGRES_PASSWORD=$(pg_admin_password)"
  secret data valkey "VALKEY_PASSWORD=$VALKEY_PW"
  secret data meilisearch "MEILI_MASTER_KEY=$MEILI_KEY"
  local tdb_pw ofga_pw
  tdb_pw="$(db_password temporal)"; ofga_pw="$(db_password openfga)"
  secret temporal temporal "POSTGRES_SEEDS=$PG_HOST" "DB_PORT=5432" "POSTGRES_USER=$(role_of temporal)" "POSTGRES_PWD=$tdb_pw"
  secret openfga openfga \
    "OPENFGA_DATASTORE_URI=postgres://$(role_of openfga):${ofga_pw}@${PG_HOST}:5432/openfga?sslmode=${PG_SSL}" \
    "OPENFGA_AUTHN_PRESHARED_KEYS=$FGA_TOKEN"
}

databases() {
  log "databases and roles"
  local db role pw
  local names=(temporal temporal_visibility openfga)
  while read -r _ _ db _; do [[ "$db" != "-" ]] && names+=("$db"); done < <(services)
  for db in "${names[@]}"; do
    # temporal_visibility belongs to the temporal role
    if [[ "$db" == temporal_visibility ]]; then role="$(role_of temporal)"; pw="$(db_password temporal)"; else role="$(role_of "$db")"; pw="$(db_password "$db")"; fi
    psql_admin postgres -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$role') THEN CREATE ROLE \"$role\" LOGIN PASSWORD '$pw'; END IF; END \$\$;" >/dev/null
    [[ -n "$(psql_admin postgres -c "SELECT 1 FROM pg_database WHERE datname='$db'")" ]] || psql_admin postgres -c "CREATE DATABASE \"$db\" OWNER \"$role\"" >/dev/null
    psql_admin postgres -c "REVOKE ALL ON DATABASE \"$db\" FROM PUBLIC; GRANT ALL ON DATABASE \"$db\" TO \"$role\"" >/dev/null
    psql_admin "$db" -c "ALTER SCHEMA public OWNER TO \"$role\"" >/dev/null
    [[ "$db" == geo ]] && psql_admin geo -c "CREATE EXTENSION IF NOT EXISTS postgis" >/dev/null || true
  done
}

# KEY=VALUE lines for one service (values may be empty when a third-party key was not given).
service_keys() {
  local svc="$1" db="$2"
  local common=("APP_ENV=dev" "KAFKA_BOOTSTRAP=$KAFKA")
  local out=()
  if [[ "$db" != "-" ]]; then
    if is_java "$svc"; then
      out+=("DB_NAME=$db" "DB_URL=$(jdbc_url "$db")" "DB_USER=$(role_of "$db")" "DB_PASSWORD=$(db_password "$db")")
    else
      out+=("DB_NAME=$db" "DB_URL=$(go_url "$db")")
    fi
  fi
  case "$svc" in
    ai-gateway)       out+=("${common[@]}" "OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}") ;;
    authz-sync)       out+=("KAFKA_BOOTSTRAP=$KAFKA" "OPENFGA_API_TOKEN=$FGA_TOKEN") ;;
    bookings)         out+=("${common[@]}" "BOOKINGS_CLIENT_SECRET=$(gen BOOKINGS_CLIENT_SECRET)") ;;
    carrier-gateway)  out+=("REDIS_URL=$REDIS_URL" "USPS_CLIENT_ID=${USPS_CLIENT_ID:-}" "USPS_CLIENT_SECRET=${USPS_CLIENT_SECRET:-}" "UPS_CLIENT_ID=${UPS_CLIENT_ID:-}" "UPS_CLIENT_SECRET=${UPS_CLIENT_SECRET:-}" "FEDEX_CLIENT_ID=${FEDEX_CLIENT_ID:-}" "FEDEX_CLIENT_SECRET=${FEDEX_CLIENT_SECRET:-}") ;;
    cart)             out+=("REDIS_URL=$REDIS_URL" "CART_CLIENT_SECRET=$(gen CART_CLIENT_SECRET)") ;;
    catalog)          out+=("${common[@]}" "FGA_API_TOKEN=$FGA_TOKEN" "CATALOG_CLIENT_SECRET=$(gen CATALOG_CLIENT_SECRET)" "GS1_API_KEY=") ;;
    content)          out+=("${common[@]}" "NEWSLETTER_TOKEN_KEY=$(gen NEWSLETTER_TOKEN_KEY)") ;;
    delivery-runs)    out+=("${common[@]}" "DELIVERY_RUNS_CLIENT_SECRET=$(gen DELIVERY_RUNS_CLIENT_SECRET)") ;;
    fulfilment)       out+=("${common[@]}" "FULFILMENT_CLIENT_SECRET=$(gen FULFILMENT_CLIENT_SECRET)") ;;
    gateway-bff)      out+=("REDIS_URL=$REDIS_URL" "OIDC_CLIENT_SECRET=$(gen GATEWAY_BFF_CLIENT_SECRET)" "SESSION_SIGNING_KEY=$(gen SESSION_SIGNING_KEY)" "STRIPE_PUBLISHABLE_KEY=${STRIPE_PUBLISHABLE_KEY:-}") ;;
    geo)              out+=("GOOGLE_PLACES_API_KEY=${GOOGLE_PLACES_API_KEY:-}" "GOOGLE_MAPS_API_KEY=${GOOGLE_MAPS_API_KEY:-}" "SMARTY_AUTH_ID=${SMARTY_AUTH_ID:-}" "SMARTY_AUTH_TOKEN=${SMARTY_AUTH_TOKEN:-}") ;;
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
    media)            out+=("UPLOADTHING_API_KEY=${UPLOADTHING_API_KEY:-}" "LINK_SIGNING_KEY=$(gen LINK_SIGNING_KEY)" "LINK_SIGNING_KEYS_PREVIOUS=" "MEDIA_CLIENT_SECRET=$(gen MEDIA_CLIENT_SECRET)" "R2_ACCESS_KEY_ID=${R2_ACCESS_KEY_ID:-}" "R2_SECRET_ACCESS_KEY=${R2_SECRET_ACCESS_KEY:-}" "AZURE_STORAGE_KEY=") ;;
    notifications)    out+=("RESEND_API_KEY=${RESEND_API_KEY:-}" "UNSUBSCRIBE_SIGNING_KEY=$(gen UNSUBSCRIBE_SIGNING_KEY)" "NOTIFICATIONS_CLIENT_SECRET=$(gen NOTIFICATIONS_CLIENT_SECRET)") ;;
    offers)           out+=("${common[@]}" "OFFERS_CLIENT_SECRET=$(gen OFFERS_CLIENT_SECRET)" "FGA_API_TOKEN=$FGA_TOKEN") ;;
    orders)           out+=("${common[@]}" "ORDERS_CLIENT_SECRET=$(gen ORDERS_CLIENT_SECRET)" "TEMPORAL_TARGET=$TEMPORAL_TARGET" "TEMPORAL_NAMESPACE=afrotransact" "TEMPORAL_TLS_CERT=" "TEMPORAL_TLS_KEY=" "HANDOVER_CODE_KEY=$(gen HANDOVER_CODE_KEY)" "PAY_LINK_KEY=$(gen PAY_LINK_KEY)" "FGA_API_TOKEN=$FGA_TOKEN") ;;
    payments)         out+=("${common[@]}" "STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY:-}" "STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET:-}" "FGA_API_TOKEN=$FGA_TOKEN" "PAYMENTS_CLIENT_SECRET=$(gen PAYMENTS_CLIENT_SECRET)") ;;
    promise)          out+=("REDIS_URL=$REDIS_URL") ;;
    retail)           out+=("${common[@]}" "RETAIL_CLIENT_SECRET=$(gen RETAIL_CLIENT_SECRET)") ;;
    search)           out+=("KAFKA_BOOTSTRAP=$KAFKA" "SEARCH_PROVIDER=meilisearch" "MEILISEARCH_URL=http://meilisearch.data.svc.cluster.local:7700" "MEILISEARCH_API_KEY=$MEILI_KEY" "ES_URLS=http://elasticsearch.invalid:9200" "ES_API_KEY=" "ADS_TOKEN_KEY=$(gen ADS_TOKEN_KEY)" "SEARCH_CLIENT_SECRET=$(gen SEARCH_CLIENT_SECRET)") ;;
    seller)           out+=("${common[@]}" "STRIPE_SECRET_KEY=${STRIPE_SECRET_KEY:-}" "STRIPE_CONNECT_WEBHOOK_SECRET=${STRIPE_CONNECT_WEBHOOK_SECRET:-}" "STRIPE_BILLING_WEBHOOK_SECRET=${STRIPE_BILLING_WEBHOOK_SECRET:-}" "FIELD_ENCRYPTION_KEY=$(gen FIELD_ENCRYPTION_KEY)") ;;
    tax-returns)      out+=("${common[@]}" "TAX_RETURNS_CLIENT_SECRET=$(gen TAX_RETURNS_CLIENT_SECRET)") ;;
    trust-ops)        out+=("${common[@]}" "TRUSTOPS_CLIENT_SECRET=$(gen TRUSTOPS_CLIENT_SECRET)") ;;
    *)                die "no secret keys defined for $svc (add it to secrets.sh)" ;;
  esac
  # later entries win (e.g. KAFKA_BOOTSTRAP listed twice); print KEY=VALUE, one per line
  printf '%s\n' "${out[@]}" | awk -F= '{k=$1; v=substr($0,length(k)+2); a[k]=v; if(!(k in s)){s[k]=++n; o[n]=k}} END{for(i=1;i<=n;i++) print o[i] "=" a[o[i]]}'
}

signing_key() { # an EC P-256 PKCS#8 key, kept so tokens survive re-runs
  local f="$STATE_DIR/identity-signing-key.pem"
  [[ -f "$f" ]] || { install -d -m 700 "$STATE_DIR"; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$f"; chmod 600 "$f"; }
  cat "$f"
}

services_secrets() {
  log "service secrets in namespace $NS"
  ensure_namespaces
  local svc rt db wave kv
  while read -r svc rt db wave; do
    mapfile -t kv < <(service_keys "$svc" "$db")
    secret "$NS" "${svc}-secrets" "${kv[@]}"
    echo "  $svc: ${#kv[@]} keys"
  done < <(services)
  if [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]]; then
    kubectl -n "$NS" create secret docker-registry ghcr --docker-server=ghcr.io --docker-username="$GHCR_USER" --docker-password="$GHCR_TOKEN" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  fi
}

case "${1:-}" in
  platform) platform ;;
  databases) databases ;;
  services) services_secrets ;;
  all) platform; databases; services_secrets ;;
  *) die "usage: secrets.sh platform|databases|services|all" ;;
esac
