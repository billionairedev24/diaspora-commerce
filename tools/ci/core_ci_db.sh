#!/usr/bin/env bash
# Prepares the Postgres 17 service container of the core / afrotransact-go workflows (CI ONLY: passwords = names).
#   - the Release 1 layout: afrotransact_core + afrotransact_go with one schema and role per module (infra/ops/postgres), roles named
#     after the module (role_suffix ""), the same SQL production runs;
#   - transitionally, one database per module owned by its role (what core's context tests used before the schema layout, CORE_IT_DB):
#     `catalog`, `taxreturns`, ... so both layouts work while the tests move.
# Usage: PGHOST=localhost PGUSER=postgres PGPASSWORD=postgres tools/ci/core_ci_db.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PASSWORD_MODE=name ROLE_SUFFIX='' infra/ops/postgres/release-1-bootstrap.sh
for m in identity catalog offers inventory orders payments seller fulfilment bookings trustops taxreturns content ai cart geo media notifications; do
  if [[ -z "$(psql -qtA -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$m'")" ]]; then
    psql -v ON_ERROR_STOP=1 -q -d postgres -c "CREATE DATABASE \"$m\" OWNER \"$m\""
  fi
done
# fulfilment and bookings migrations create extensions as the service role; pre-create them as the admin (as production does).
psql -v ON_ERROR_STOP=1 -q -d fulfilment -c "CREATE EXTENSION IF NOT EXISTS postgis"
psql -v ON_ERROR_STOP=1 -q -d geo -c "CREATE EXTENSION IF NOT EXISTS postgis"
psql -v ON_ERROR_STOP=1 -q -d bookings -c "CREATE EXTENSION IF NOT EXISTS btree_gist"
echo "ci databases ready"
