-- Included by release-1-verify.sql for the CURRENT database (variables: cur_modules, role_suffix, extensions). Not standalone.
-- psql does not substitute :variables inside $$ bodies, so the DO block reads them from settings.
SELECT set_config('r1.suffix', :'role_suffix', false) AS a, set_config('r1.modules', :'cur_modules', false) AS b,
       set_config('r1.extensions', :'extensions', false) AS c \gset
DO $verify$
DECLARE
  m record; other record; ext text; n int;
BEGIN
  FOR m IN SELECT split_part(x, ':', 1) AS s, split_part(x, ':', 1) || current_setting('r1.suffix') AS r, 2 * split_part(x, ':', 2)::int + 2 AS lim
             FROM unnest(string_to_array(current_setting('r1.modules'), ',')) AS x LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = m.r AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole) THEN
      RAISE EXCEPTION 'role % is missing or too powerful (needs LOGIN, no SUPERUSER/CREATEDB/CREATEROLE)', m.r;
    END IF;
    IF (SELECT rolconnlimit FROM pg_roles WHERE rolname = m.r) <> m.lim THEN
      RAISE EXCEPTION 'role % has CONNECTION LIMIT %, expected % (2 x pool + 2)', m.r, (SELECT rolconnlimit FROM pg_roles WHERE rolname = m.r), m.lim;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles o ON o.oid = n.nspowner WHERE n.nspname = m.s AND o.rolname = m.r) THEN
      RAISE EXCEPTION 'schema % in % is missing or not owned by %', m.s, current_database(), m.r;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_db_role_setting rs JOIN pg_roles r ON r.oid = rs.setrole JOIN pg_database d ON d.oid = rs.setdatabase
                    WHERE r.rolname = m.r AND d.datname = current_database() AND rs.setconfig::text LIKE '%search_path=' || m.s || ', public%') THEN
      RAISE EXCEPTION 'role % has no search_path = %, public in database %', m.r, m.s, current_database();
    END IF;
    IF NOT has_database_privilege(m.r, current_database(), 'CONNECT') THEN
      RAISE EXCEPTION 'role % cannot connect to %', m.r, current_database();
    END IF;
    -- Isolation: no module role can even see another module's schema.
    FOR other IN SELECT split_part(y, ':', 1) AS s FROM unnest(string_to_array(current_setting('r1.modules'), ',')) AS y WHERE split_part(y, ':', 1) <> m.s LOOP
      IF has_schema_privilege(m.r, other.s, 'USAGE') OR has_schema_privilege(m.r, other.s, 'CREATE') THEN
        RAISE EXCEPTION 'role % has a right on schema % (modules must not read each other''s tables)', m.r, other.s;
      END IF;
    END LOOP;
    IF has_schema_privilege(m.r, 'public', 'CREATE') THEN
      RAISE EXCEPTION 'role % can create objects in public', m.r;
    END IF;
  END LOOP;
  FOREACH ext IN ARRAY string_to_array(current_setting('r1.extensions'), ',') LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = ext) THEN
      RAISE EXCEPTION 'extension % is not installed in %', ext, current_database();
    END IF;
  END LOOP;
  SELECT count(*) INTO n FROM unnest(string_to_array(current_setting('r1.modules'), ','));
  RAISE NOTICE '% : % module schemas verified (owner, connection limit, search_path, isolation, extensions)', current_database(), n;
END
$verify$;
