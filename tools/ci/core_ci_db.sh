#!/usr/bin/env bash
# Prepares the Postgres 17 service container of the core / afrotransact-go workflows (CI ONLY: passwords = names).
#   - the Release 1 layout: databases core and go with one schema and role per module (infra/ops/postgres), roles named after the
#     module (ROLE_SUFFIX ""), the same SQL production runs;
#   - transitionally, the per-module databases `catalog` and `taxreturns` (roles catalog / taxreturns) that core's context tests use
#     (CORE_IT_DB) while they move to the schema layout.
# Usage: PGHOST=localhost PGUSER=postgres PGPASSWORD=postgres tools/ci/core_ci_db.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PASSWORD_MODE=name ROLE_SUFFIX='' EXTRA_CORE_MODULES='retail:3,delivery_runs:3' infra/ops/postgres/release-1-bootstrap.sh
psql -v ON_ERROR_STOP=1 -q -d postgres <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', 'taxreturns', 'taxreturns') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'taxreturns') \gexec
SELECT 'CREATE DATABASE catalog OWNER catalog' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'catalog') \gexec
SELECT 'CREATE DATABASE taxreturns OWNER taxreturns' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'taxreturns') \gexec
SQL
echo "ci databases ready"
