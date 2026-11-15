#!/usr/bin/env bash
# Create (or repair) the single `core` database with one schema and one role per Java module (schema-per-module mode, ADR 0011).
# Idempotent. It uses the SAME Postgres as `make up` (tools/dev/local.sh: your own Postgres on localhost:5432 when something other
# than this stack's container listens there, else the Docker `postgres` service), with the same superuser detection
# (PG_SUPERUSER / PGPASSWORD, your login over the socket, sudo -u postgres) and PG_EXTERNAL=0|1 to force one. It prints the
# CORE_DB_URL core will use, and says exactly what to run when it cannot provision. `make up` does this step by itself.
set -euo pipefail
exec "$(dirname "$0")/../dev/local.sh" core-db "$@"
