#!/usr/bin/env bash
# Create (or repair) the single `core` database with one schema and one role per Java module (schema-per-module mode, ADR 0011).
# Idempotent. Uses the Docker Postgres of infra/local when it is running, otherwise `psql` against localhost:5432 as a superuser.
set -euo pipefail
cd "$(dirname "$0")/../.."
SQL=infra/local/postgres/provision-core.sql
if docker compose -f infra/local/docker-compose.yml ps --status running --services 2>/dev/null | grep -qx postgres; then
  docker compose -f infra/local/docker-compose.yml exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 < "$SQL"
elif command -v psql >/dev/null; then
  PGPASSWORD="${CORE_DB_ADMIN_PASSWORD:-postgres}" psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
    -U "${CORE_DB_ADMIN_USER:-postgres}" -d postgres -v ON_ERROR_STOP=1 -f "$SQL"
else
  echo "core-db: neither the Docker postgres (make up-infra) nor psql is available" >&2
  exit 1
fi
echo "core-db: database 'core' provisioned (schemas and roles per module)"
