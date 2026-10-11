-- AfroTransact: local databases on YOUR OWN Postgres (Homebrew, Postgres.app, DBeaver-managed, …).
-- Creates one login role + one database per service (password = name, LOCAL ONLY), Temporal's visibility database,
-- and the extensions the services need (PostGIS + pg_trgm in geo, PostGIS in fulfilment, btree_gist in bookings).
-- Same result as infra/local/postgres/init-databases.sh in the Docker container. Safe to run again.
--
-- How to run it in DBeaver:
--   1. Connect to your server's `postgres` database as a superuser (Homebrew: your macOS user, no password).
--   2. Open this file in an SQL editor and run it as a script (Alt+X / "Execute SQL Script"), auto-commit on.
--   3. If your server needs a password for local connections, put it in line [A] below first.
-- Needs PostGIS installed on the server (Homebrew: brew install postgis). From a terminal instead:
--   psql -h localhost -d postgres -f infra/local/postgres/create-local-databases.sql
-- tools/dev/local.sh (make dev-up) does the same automatically when it finds your Postgres on localhost:5432.

-- [A] Password dblink uses to connect back to this server as you: put it between the quotes of '' if your server
--     asks for one on local connections (leave '' for trust/peer authentication, e.g. Homebrew). A value preset with
--     PGOPTIONS='-c afrotransact.su_password=…' (as tools/dev/local.sh does) is kept.
select set_config('afrotransact.su_password',
  coalesce(nullif('', ''), current_setting('afrotransact.su_password', true), ''), false);

-- dblink lets one script create databases (CREATE DATABASE can't run inside a block) and enter each database.
create extension if not exists dblink;

-- 1. Roles (own statement: committed before the databases below are created over dblink, which runs separately).
do $$
declare
  r text;
begin
  if not (select rolsuper from pg_roles where rolname = current_user) then
    raise exception 'Run this as a superuser (connected as %).', current_user;
  end if;
  if not exists (select from pg_available_extensions where name = 'postgis') then
    raise exception 'PostGIS is not installed on this server (Homebrew: brew install postgis), needed by geo and fulfilment.';
  end if;
  foreach r in array array['identity', 'catalog', 'offers', 'inventory', 'orders', 'payments', 'seller', 'retail',
    'fulfilment', 'bookings', 'trustops', 'taxreturns', 'content', 'ai', 'cart', 'geo', 'storefront', 'media',
    'notifications', 'carriers', 'deliveryruns', 'temporal', 'openfga'] loop
    if not exists (select from pg_roles where rolname = r) then
      execute format('create role %I login password %L', r, r);
      raise notice 'role %: created', r;
    end if;
  end loop;
end
$$;

-- 2. Databases, schema ownership and extensions.
do $$
declare
  services text[] := array['identity', 'catalog', 'offers', 'inventory', 'orders', 'payments', 'seller', 'retail',
    'fulfilment', 'bookings', 'trustops', 'taxreturns', 'content', 'ai', 'cart', 'geo', 'storefront', 'media',
    'notifications', 'carriers', 'deliveryruns', 'temporal', 'openfga'];
  pw text := current_setting('afrotransact.su_password', true);
  conn text;
  db text;
  owner text;
begin
  foreach db in array services || array['temporal_visibility'] loop
    owner := case when db = 'temporal_visibility' then 'temporal' else db end;
    if not exists (select from pg_database where datname = db) then
      conn := format('dbname=postgres user=%s', current_user) || coalesce(nullif(' password=' || pw, ' password='), '');
      perform dblink_exec(conn, format('create database %I owner %I', db, owner));
      perform dblink_exec(conn, format('revoke all on database %I from public', db));
      raise notice 'database %: created (owner %)', db, owner;
    end if;
    -- The service owns its public schema so Flyway / migrations can create objects.
    conn := format('dbname=%s user=%s', db, current_user) || coalesce(nullif(' password=' || pw, ' password='), '');
    perform dblink_exec(conn, format('alter schema public owner to %I', owner));
  end loop;

  conn := 'user=' || current_user || coalesce(nullif(' password=' || pw, ' password='), '');
  perform dblink_exec('dbname=geo ' || conn, 'create extension if not exists postgis');
  perform dblink_exec('dbname=geo ' || conn, 'create extension if not exists pg_trgm');
  perform dblink_exec('dbname=fulfilment ' || conn, 'create extension if not exists postgis');
  perform dblink_exec('dbname=bookings ' || conn, 'create extension if not exists btree_gist');
  raise notice 'AfroTransact local databases ready (% services).', array_length(services, 1);
end
$$;

-- What you should now see (24 rows: 23 services + temporal_visibility):
select d.datname as database, pg_get_userbyid(d.datdba) as owner
from pg_database d
where d.datname in ('identity', 'catalog', 'offers', 'inventory', 'orders', 'payments', 'seller', 'retail',
  'fulfilment', 'bookings', 'trustops', 'taxreturns', 'content', 'ai', 'cart', 'geo', 'storefront', 'media',
  'notifications', 'carriers', 'deliveryruns', 'temporal', 'openfga', 'temporal_visibility')
order by 1;
