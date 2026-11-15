#!/bin/sh
# Release 1 layout on the local Postgres (docker-entrypoint-initdb.d, after 10-init-databases.sh): databases core and go with one
# schema and role per module, from the SAME SQL production runs (infra/ops/postgres, mounted at /release-1).
# POSIX sh on purpose: the local image is Alpine and has no bash. Roles are named after the module and the password is the name
# (LOCAL ONLY), no connection limits. The per-service databases stay, so both ways of running keep working. Existing volumes do not
# re-run this: use `make db-release-1`. The verification step of the production wrapper is skipped here (it needs bash).
set -eu
sql=/release-1/release-1-bootstrap.sql
extra='retail:3,delivery_runs:3'
export PGUSER="${POSTGRES_USER:-postgres}"
psql -v ON_ERROR_STOP=1 -q -d postgres -v role_suffix= -v conn_limits=false -v extra_core_modules="$extra" -f "$sql"
list() { sed -n "s/^\\\\set $1 '\\(.*\\)'\$/\\1/p" "$sql" | head -1 | tr ',' '\n' | cut -d: -f1; }
modules="$(list core_modules; list go_modules; printf '%s\n' "$extra" | tr ',' '\n' | cut -d: -f1; echo temporal)"
for m in $modules; do
  psql -v ON_ERROR_STOP=1 -q -d postgres -v "role=$m" -v "pw=$m" -f - <<SQL
ALTER ROLE :"role" PASSWORD :'pw';
SQL
done
echo "release-1: databases core and go provisioned"
