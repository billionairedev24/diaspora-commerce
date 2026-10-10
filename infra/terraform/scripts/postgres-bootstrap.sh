#!/usr/bin/env bash
# Per-service database bootstrap - identical on Aurora, Cloud SQL and Azure Flexible Server.
#
# The platform runs ONE managed PostgreSQL 17 server per environment and ONE database per service
# (PORTS.md). This script makes that true and idempotent:
#   - CREATE DATABASE <db> (skipped if it exists; Cloud SQL/Azure already created it natively)
#   - CREATE ROLE <db>_app LOGIN with a random password; owner of its database; no access to others
#   - REVOKE CONNECT ... FROM PUBLIC on every service database (services cannot cross-read)
#   - enable postgis in `geo`
#   - prints DB_URL/DB_USER/DB_PASSWORD per service as JSON lines so the operator (or the Job wrapper)
#     can merge them into <prefix>/<env>/<service> in the cloud secret store
#
# Run it as a Kubernetes Job (Argo CD PreSync hook in infra/k8s) or from a bastion:
#   PGHOST=... PGUSER=at_admin PGPASSWORD=... PGSSLMODE=require \
#   DATABASES="identity catalog ..." ROTATE=false ./postgres-bootstrap.sh
# The admin credential comes from `tofu output postgres` -> admin_secret_ref.
set -euo pipefail
: "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${DATABASES:?space-separated database names}"
export PGSSLMODE="${PGSSLMODE:-require}" PGDATABASE="${PGDATABASE:-postgres}"
ROTATE="${ROTATE:-false}"   # true = reset existing role passwords too

psql_admin() { psql -v ON_ERROR_STOP=1 -qtA "$@"; }

for db in $DATABASES; do
  role="${db}_app"
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

  # Cloud SQL / Azure admins are not superusers: grant the role to the admin so ownership can be set.
  psql_admin -c "GRANT \"${role}\" TO CURRENT_USER" >/dev/null 2>&1 || true
  psql_admin -c "ALTER DATABASE \"${db}\" OWNER TO \"${role}\""
  psql_admin -c "REVOKE ALL ON DATABASE \"${db}\" FROM PUBLIC"
  psql_admin -c "GRANT CONNECT, TEMPORARY ON DATABASE \"${db}\" TO \"${role}\""
  psql_admin -d "$db" -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC; ALTER SCHEMA public OWNER TO \"${role}\""

  if [[ "$db" == "geo" ]]; then
    psql_admin -d "$db" -c "CREATE EXTENSION IF NOT EXISTS postgis"
  fi

  if [[ -n "$password" ]]; then
    printf '{"database":"%s","DB_URL":"jdbc:postgresql://%s:5432/%s?sslmode=require","DB_USER":"%s","DB_PASSWORD":"%s"}\n' \
      "$db" "$PGHOST" "$db" "$role" "$password"
  else
    printf '{"database":"%s","status":"unchanged"}\n' "$db"
  fi
done
