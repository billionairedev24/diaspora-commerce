-- Release 1 database layout (ADR 0011; owner decision: schema per module). Run ONCE as the server admin before the first deploy and
-- again whenever a module is added; it is idempotent and holds NO secret (roles are created without a password: release-1-bootstrap.sh
-- sets them, so nothing sensitive is ever in a file or in git).
--
--   afrotransact_core   ONE database for the Java deployable: one schema AND one login role per module (13 with a database; mcp-server has none)
--   afrotransact_go     ONE database for the Go deployable: cart, geo, media, notifications
--   temporal, temporal_visibility   separate, owned by temporal_app (Temporal uses one credential for both)
--
-- Isolation is what separate databases gave: a module role owns its schema and nothing else (USAGE on no other module's schema, REVOKE
-- from PUBLIC), has CONNECTION LIMIT 2 x its pool + 2 (the Helm budget, see below) and a role-level search_path "<schema>, public" so
-- Flyway and the application create and read their objects in their own schema without a prefix. `public` only carries the extensions
-- (postgis, btree_gist, created here as the admin because a managed server does not let the service roles) and is read-only for the
-- module roles. The same roles work for the old per-service build (it only needs its DB_URL to point at afrotransact_core with
-- currentSchema=<schema>), which is what makes the rollback in docs/runbooks/release-1-deploy.md possible.
--
-- Run:   psql -v ON_ERROR_STOP=1 -h <host> -U <admin> -d postgres -f release-1-bootstrap.sql
-- Variables (psql -v name=value), all optional:
--   role_suffix   '_app' (default; Layerbase/prod). The local stack uses '' because its roles are named after the module.
--   extensions    'postgis,btree_gist' (default). A server without PostGIS: -v extensions=btree_gist (fulfilment and geo then fail to migrate).
-- To add a module: add `schema:pool` to core_modules or go_modules below, re-run, then run release-1-bootstrap.sh to set its password.
-- The pools MUST equal modules.<name>.pool in java/apps/core/deploy/helm/values.yaml and go/apps/afrotransact-go/deploy/helm/values.yaml:
-- `python3 tools/release/check_delivery.py` fails when they drift.
-- Verify with release-1-verify.sql. Admin needs CREATEROLE and CREATEDB (Layerbase: UNVERIFIED, docs/runbooks/ovh-production.md section 14).

\set ON_ERROR_STOP on
\if :{?role_suffix}
\else
  \set role_suffix '_app'
\endif
\if :{?extensions}
\else
  \set extensions 'postgis,btree_gist'
\endif

-- schema:pool (the pool is the Hikari / pgx maximum of ONE pod; a rolling update runs two pods, hence the 2x in the role limit).
\set core_db 'afrotransact_core'
\set go_db 'afrotransact_go'
\set core_modules 'identity:4,catalog:8,offers:3,inventory:3,orders:6,payments:5,seller:3,fulfilment:3,bookings:3,trustops:2,taxreturns:4,content:2,ai:2'
\set go_modules 'cart:5,geo:5,media:5,notifications:5'

-- 1. Roles (no password: infrastructure code sets it) --------------------------------------------------------------------------
CREATE TEMP TABLE r1_roles AS
  SELECT split_part(x, ':', 1) || :'role_suffix' AS role, split_part(x, ':', 1) AS schema_name, :'core_db' AS db,
         2 * split_part(x, ':', 2)::int + 2 AS conn_limit
    FROM unnest(string_to_array(:'core_modules', ',')) AS x
  UNION ALL
  SELECT split_part(x, ':', 1) || :'role_suffix', split_part(x, ':', 1), :'go_db', 2 * split_part(x, ':', 2)::int + 2
    FROM unnest(string_to_array(:'go_modules', ',')) AS x;

SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT', role)
  FROM r1_roles WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r1_roles.role) \gexec
SELECT format('ALTER ROLE %I CONNECTION LIMIT %s', role, conn_limit) FROM r1_roles \gexec
-- Temporal: one role for both of its databases, its own pools (Temporal's SQL persistence config; 10 connections planned).
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', 'temporal' || :'role_suffix')
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'temporal' || :'role_suffix') \gexec
-- A non-superuser admin (Layerbase) must be able to SET ROLE to create schemas owned by the module roles (PostgreSQL 16+ syntax).
SELECT format('GRANT %I TO CURRENT_USER WITH SET TRUE, INHERIT FALSE', role) FROM r1_roles
  WHERE NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) \gexec
SELECT format('GRANT %I TO CURRENT_USER WITH SET TRUE, INHERIT FALSE', 'temporal' || :'role_suffix')
  WHERE NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) \gexec

-- 2. Databases -----------------------------------------------------------------------------------------------------------------
SELECT format('CREATE DATABASE %I', d) FROM unnest(ARRAY[:'core_db', :'go_db']) AS d
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d) \gexec
SELECT format('CREATE DATABASE %I OWNER %I', d, 'temporal' || :'role_suffix') FROM unnest(ARRAY['temporal', 'temporal_visibility']) AS d
  WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = d) \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', d) FROM unnest(ARRAY[:'core_db', :'go_db', 'temporal', 'temporal_visibility']) AS d \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', db, role) FROM r1_roles \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', d, 'temporal' || :'role_suffix') FROM unnest(ARRAY['temporal', 'temporal_visibility']) AS d \gexec
-- The nightly dump (pg_dump of the whole database) must read every schema: pg_read_all_data where the admin is not a superuser.
DO $$
BEGIN
  IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
    BEGIN
      EXECUTE format('GRANT pg_read_all_data TO %I', current_user);
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'could not grant pg_read_all_data to % (%): the backup role must read every schema, ask the provider', current_user, SQLERRM;
    END;
  END IF;
END $$;

-- 3. Per deployable database: extensions, one schema per module owned by its role, nobody else has a right on it -----------------
-- Extensions are created by the admin in public; module roles only use them (public is USAGE-only for them).
\connect :core_db
SELECT format('CREATE EXTENSION IF NOT EXISTS %I', e) FROM unnest(string_to_array(:'extensions', ',')) AS e \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO PUBLIC;
CREATE TEMP TABLE r1_here AS
  SELECT split_part(x, ':', 1) || :'role_suffix' AS role, split_part(x, ':', 1) AS schema_name
    FROM unnest(string_to_array(:'core_modules', ',')) AS x;
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', schema_name, role) FROM r1_here \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', schema_name, role) FROM r1_here \gexec
SELECT format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', schema_name) FROM r1_here \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', schema_name, role) FROM r1_here \gexec
-- search_path: the module's schema first, then public (extension functions and types). Role-level, so it holds for Flyway, the
-- application, psql and any pool; the JDBC URL also carries currentSchema as a second line of defence.
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', role, current_database(), schema_name) FROM r1_here \gexec

\connect :go_db
SELECT format('CREATE EXTENSION IF NOT EXISTS %I', e) FROM unnest(string_to_array(:'extensions', ',')) AS e \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO PUBLIC;
CREATE TEMP TABLE r1_here AS
  SELECT split_part(x, ':', 1) || :'role_suffix' AS role, split_part(x, ':', 1) AS schema_name
    FROM unnest(string_to_array(:'go_modules', ',')) AS x;
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', schema_name, role) FROM r1_here \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', schema_name, role) FROM r1_here \gexec
SELECT format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', schema_name) FROM r1_here \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', schema_name, role) FROM r1_here \gexec
-- search_path: the module's schema first, then public (extension functions and types). Role-level, so it holds for Flyway, the
-- application, psql and any pool; the JDBC URL also carries currentSchema as a second line of defence.
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I, public', role, current_database(), schema_name) FROM r1_here \gexec

-- 4. Temporal: the schemas stay public (its own migrations), owned by temporal_app, as in the per-database layout -------------------
\connect temporal
SELECT format('ALTER SCHEMA public OWNER TO %I', 'temporal' || :'role_suffix') \gexec
\connect temporal_visibility
SELECT format('ALTER SCHEMA public OWNER TO %I', 'temporal' || :'role_suffix') \gexec
\connect postgres
\echo release-1 bootstrap done: databases afrotransact_core, afrotransact_go, temporal, temporal_visibility
