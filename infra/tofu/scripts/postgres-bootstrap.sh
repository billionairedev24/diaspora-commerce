#!/usr/bin/env bash
# Per-service database bootstrap for a managed PostgreSQL 17 server (Layerbase on the OVH launch path; also Aurora, Cloud SQL
# and Azure Flexible Server). Run it ONCE as the admin BEFORE the first deploy, and again whenever a database is added.
#
# The platform runs ONE server per environment and ONE database per service (PORTS.md). This script makes that true and idempotent:
#   - CREATE DATABASE <db> (skipped if it exists; Cloud SQL/Azure already created it natively)
#   - CREATE ROLE <db>_app LOGIN with a random password; owner of its database; no access to others
#   - REVOKE CONNECT ... FROM PUBLIC on every service database (services cannot cross-read)
#   - temporal_visibility belongs to the temporal_app role (Temporal uses ONE credential for both of its databases)
#   - CREATE EXTENSION as the ADMIN, because the service roles cannot on a managed server:
#       postgis    in geo (go/services/geo/migrations/001_zones.sql) and fulfilment (V3__nearest_driver.sql, driver_position)
#       btree_gist in bookings (V1__bookings_schema.sql, slot exclusion constraints)
#     The services' own migrations say `create extension if not exists ...`, which is a no-op once it exists. Without this
#     step the fulfilment (and geo, bookings) migration runs as the service role and can fail on first deploy.
#   - prints DB_URL (JDBC) / PG_URL (Go) / DB_USER / DB_PASSWORD per database as JSON lines so the operator can put them
#     into the service Secrets (infra/ops/secrets) or the cloud secret store
#
# Launch set = 19 databases (default below): 12 Java (identity catalog offers inventory orders payments seller fulfilment bookings
# trustops taxreturns content), ai-gateway's `ai`, 4 Go (cart geo media notifications) and the two Temporal databases.
# Earlier plans counted 18: that list forgot `ai`, but ai-gateway is in the launch set and its chart expects DB_URL/DB_USER/
# DB_PASSWORD. mcp-server, gateway-bff, search and indexer have no database. NOT created: retail, deliveryruns, carriers,
# openfga (those services are not deployed).
#
# Run it as a Kubernetes Job or from a bastion / your laptop (the Layerbase admin endpoint must accept your IP):
#   PGHOST=... PGUSER=<admin> PGPASSWORD=... PGSSLMODE=require ROTATE=false ./postgres-bootstrap.sh
# The admin credential comes from `tofu output postgres` -> admin_secret_ref (cloud-managed) or from the Layerbase console.
# libpq variables (PGSSLROOTCERT, PGSSLCERT, PGSSLKEY, PGPORT) work as usual if Layerbase requires verify-full / mTLS.
set -euo pipefail

# The launch databases. Keep in step with: infra/ops (backup CronJob DATABASES), infra/ovh-vps/services.launch.conf,
# docs/runbooks/ovh-production.md, and infra/tofu/envs/*/main.tf `services`.
LAUNCH_DATABASES="identity catalog offers inventory orders payments seller fulfilment bookings trustops taxreturns content ai cart geo media notifications temporal temporal_visibility"

: "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}"
DATABASES="${DATABASES:-$LAUNCH_DATABASES}"
export PGSSLMODE="${PGSSLMODE:-require}" PGDATABASE="${PGDATABASE:-postgres}"
PGPORT="${PGPORT:-5432}"; export PGPORT
ROTATE="${ROTATE:-false}"   # true = reset existing role passwords too
# Extensions the ADMIN must create per database (see the header). Space separated "db:extension".
EXTENSIONS="${EXTENSIONS:-geo:postgis fulfilment:postgis bookings:btree_gist}"

psql_admin() { psql -v ON_ERROR_STOP=1 -qtA "$@"; }

role_of() { # temporal_visibility shares the temporal role
  case "$1" in temporal_visibility) echo "temporal_app" ;; *) echo "${1}_app" ;; esac
}

for db in $DATABASES; do
  role="$(role_of "$db")"
  exists_db="$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '${db}'")"
  if [[ -z "$exists_db" ]]; then
    psql_admin -c "CREATE DATABASE \"${db}\""
  fi

  exists_role="$(psql_admin -c "SELECT 1 FROM pg_roles WHERE rolname = '${role}'")"
  password=""
  if [[ -z "$exists_role" ]]; then
    password="$(openssl rand -base64 36 | tr -d '/+=' | cut -c1-40)"
    psql_admin -c "CREATE ROLE \"${role}\" LOGIN PASSWORD '${password}'"
  elif [[ "$ROTATE" == "true" ]]; then
    password="$(openssl rand -base64 36 | tr -d '/+=' | cut -c1-40)"
    psql_admin -c "ALTER ROLE \"${role}\" PASSWORD '${password}'"
  fi

  # Cloud SQL / Azure / managed admins are not always superusers: grant the role to the admin so ownership can be set.
  psql_admin -c "GRANT \"${role}\" TO CURRENT_USER" >/dev/null 2>&1 || true
  psql_admin -c "ALTER DATABASE \"${db}\" OWNER TO \"${role}\""
  psql_admin -c "REVOKE ALL ON DATABASE \"${db}\" FROM PUBLIC"
  psql_admin -c "GRANT CONNECT, TEMPORARY ON DATABASE \"${db}\" TO \"${role}\""
  psql_admin -d "$db" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO \"${role}\""

  # Extensions: created here, as admin, in the database's public schema.
  for pair in $EXTENSIONS; do
    if [[ "${pair%%:*}" == "$db" ]]; then
      ext="${pair#*:}"
      if ! psql_admin -d "$db" -c "CREATE EXTENSION IF NOT EXISTS ${ext}"; then
        echo "FATAL: cannot create extension ${ext} in ${db}. Ask the provider to enable it for this server or to grant the admin the right (docs/runbooks/ovh-production.md, Layerbase questions)." >&2
        exit 1
      fi
      # Prove it is really there (a no-op `IF NOT EXISTS` on a different schema would be a silent failure).
      [[ "$(psql_admin -d "$db" -c "SELECT 1 FROM pg_extension WHERE extname = '${ext}'")" == "1" ]] \
        || { echo "FATAL: extension ${ext} is not installed in ${db}" >&2; exit 1; }
      echo "extension ${ext} present in ${db}" >&2
    fi
  done

  if [[ -n "$password" ]]; then
    printf '{"database":"%s","DB_URL":"jdbc:postgresql://%s:%s/%s?sslmode=%s","PG_URL":"postgres://%s:%s@%s:%s/%s?sslmode=%s","DB_USER":"%s","DB_PASSWORD":"%s"}\n' \
      "$db" "$PGHOST" "$PGPORT" "$db" "$PGSSLMODE" "$role" "$password" "$PGHOST" "$PGPORT" "$db" "$PGSSLMODE" "$role" "$password"
  else
    printf '{"database":"%s","status":"unchanged"}\n' "$db"
  fi
done
