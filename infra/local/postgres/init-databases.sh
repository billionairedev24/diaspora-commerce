#!/usr/bin/env bash
# Creates one database and one login role per service (password = service name — LOCAL ONLY).
# Runs once, on the first start of an empty postgres volume (docker-entrypoint-initdb.d).
# To add a service: append its database name to SERVICES below and run `make reset && make up`.
set -euo pipefail

SERVICES=(
  identity catalog offers inventory orders payments seller retail fulfilment bookings
  trustops taxreturns content ai cart geo media notifications carriers deliveryruns
  temporal openfga
)

for svc in "${SERVICES[@]}"; do
  echo "creating role + database: ${svc}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE ROLE "${svc}" LOGIN PASSWORD '${svc}';
    CREATE DATABASE "${svc}" OWNER "${svc}";
    REVOKE ALL ON DATABASE "${svc}" FROM PUBLIC;
SQL
  # Services own their public schema so Flyway can create objects.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${svc}" \
    -c "ALTER SCHEMA public OWNER TO \"${svc}\";"
done

# Temporal keeps visibility records in a second database owned by the same role.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
  CREATE DATABASE temporal_visibility OWNER temporal;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname temporal_visibility \
  -c "ALTER SCHEMA public OWNER TO temporal;"

# PostGIS for the geo service (address points, delivery zones) and pg_trgm for fuzzy lookups.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname geo <<-SQL
  CREATE EXTENSION IF NOT EXISTS postgis;
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL

# Read-only monitoring role used by postgres-exporter.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
  CREATE ROLE monitoring LOGIN PASSWORD 'monitoring';
  GRANT pg_monitor TO monitoring;
SQL
echo "all service databases created"
