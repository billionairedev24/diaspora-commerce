#!/usr/bin/env bash
# Release 1 layout on the local Postgres (docker-entrypoint-initdb.d, after 10-init-databases.sh): databases afrotransact_core and
# afrotransact_go with one schema and role per module, from the SAME SQL production runs (infra/ops/postgres, mounted at /release-1).
# Roles are named after the module and the password is the name (LOCAL ONLY), no connection limits (the per-service containers and
# core share this server). The per-service databases stay, so both ways of running keep working. Existing volumes do not re-run this:
# use `make db-release-1`.
set -euo pipefail
PASSWORD_MODE=name ROLE_SUFFIX='' CONN_LIMITS=false PGUSER="${POSTGRES_USER:-postgres}" bash /release-1/release-1-bootstrap.sh
