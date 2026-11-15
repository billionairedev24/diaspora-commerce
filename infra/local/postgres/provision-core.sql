-- afrotransact-core, schema-per-module mode (ADR 0011): ONE database `core` with ONE SCHEMA and ONE ROLE per Java module.
-- Each role owns only its schema (search_path pinned on the role and on every pooled connection), so a module cannot read
-- another module's tables. LOCAL passwords = role name; production roles get real passwords from the secret store.
-- Run as a superuser, any database, safe to repeat:
--   psql -h localhost -U postgres -f infra/local/postgres/provision-core.sql        (make core-db)
-- Temporal keeps its own databases (init-databases.sh). Extensions live in `public` so every module's search_path finds them.
\set ON_ERROR_STOP on
SELECT 'CREATE DATABASE core' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'core') \gexec
\connect core
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

DO $do$
DECLARE
  m text;
BEGIN
  FOREACH m IN ARRAY ARRAY['identity','catalog','offers','inventory','orders','payments','seller','retail','fulfilment',
                           'bookings','trust_ops','tax_returns','content','ai_gateway','delivery_runs'] LOOP
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = m) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', m, m);
    END IF;
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', m, m);
    EXECUTE format('ALTER ROLE %I SET search_path = %I, public', m, m);
    EXECUTE format('GRANT CONNECT ON DATABASE core TO %I', m);
  END LOOP;
END
$do$;
