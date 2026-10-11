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
--   psql -h localhost -d postgres -f infra/local/postgres/create-local-databases.sql      (macOS / Homebrew)
--   sudo -u postgres psql < infra/local/postgres/create-local-databases.sql              (Ubuntu / Debian packages)
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

-- dblink connects back to this server inside the server process. Try your role first, then (Ubuntu/Debian peer
-- authentication: the server runs as the OS user `postgres`) the postgres role, then TCP with the password from [A].
create or replace function pg_temp.at_exec(db text, cmd text) returns void language plpgsql as $f$
declare
  pw text := coalesce(current_setting('afrotransact.su_password', true), '');
  tries text[] := array[format('dbname=%s user=%s', db, current_user)];
  conn text;
  last_error text;
begin
  if exists (select from pg_roles where rolname = 'postgres' and rolsuper) and current_user <> 'postgres' then
    tries := tries || format('dbname=%s user=postgres', db);
  end if;
  if pw <> '' then
    tries := tries || format('host=localhost port=%s dbname=%s user=%s password=%s', current_setting('port'), db,
      current_user, pw);
  end if;
  foreach conn in array tries loop
    begin
      perform dblink_exec(conn, cmd);
      return;
    exception when sqlclient_unable_to_establish_sqlconnection or invalid_authorization_specification
        or invalid_password then
      last_error := sqlerrm;
    end;
  end loop;
  raise exception 'dblink could not connect to database % (%). Set the password in line [A], or run this script as the postgres user (Ubuntu: sudo -u postgres psql -f …).', db, last_error;
end
$f$;

-- 2. Databases, schema ownership and extensions.
do $$
declare
  services text[] := array['identity', 'catalog', 'offers', 'inventory', 'orders', 'payments', 'seller', 'retail',
    'fulfilment', 'bookings', 'trustops', 'taxreturns', 'content', 'ai', 'cart', 'geo', 'storefront', 'media',
    'notifications', 'carriers', 'deliveryruns', 'temporal', 'openfga'];
  db text;
  owner text;
begin
  foreach db in array services || array['temporal_visibility'] loop
    owner := case when db = 'temporal_visibility' then 'temporal' else db end;
    if not exists (select from pg_database where datname = db) then
      perform pg_temp.at_exec('postgres', format('create database %I owner %I', db, owner));
      perform pg_temp.at_exec('postgres', format('revoke all on database %I from public', db));
      raise notice 'database %: created (owner %)', db, owner;
    end if;
    -- The service owns its public schema so Flyway / migrations can create objects.
    perform pg_temp.at_exec(db, format('alter schema public owner to %I', owner));
  end loop;
  perform pg_temp.at_exec('geo', 'create extension if not exists postgis');
  perform pg_temp.at_exec('geo', 'create extension if not exists pg_trgm');
  perform pg_temp.at_exec('fulfilment', 'create extension if not exists postgis');
  perform pg_temp.at_exec('bookings', 'create extension if not exists btree_gist');
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
