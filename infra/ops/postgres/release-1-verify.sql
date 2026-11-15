-- Read-only check of what release-1-bootstrap.sql built. Exits non-zero (psql ON_ERROR_STOP) with a message naming the first problem,
-- and prints a summary table. Run it after the bootstrap, after a restore (docs/runbooks/restore-drill.md) and before a deploy:
--   psql -v ON_ERROR_STOP=1 -h <host> -U <admin> -d postgres -f release-1-verify.sql
-- Same optional variables as the bootstrap (role_suffix, extensions, conn_limits); keep core_modules / go_modules equal to it.
\set ON_ERROR_STOP on
\if :{?role_suffix}
\else
  \set role_suffix '_app'
\endif
\if :{?extensions}
\else
  \set extensions 'postgis,btree_gist'
\endif
\if :{?extra_core_modules}
\else
  \set extra_core_modules ''
\endif
\if :{?conn_limits}
\else
  \set conn_limits 'true'
\endif
\set core_db 'core'
\set go_db 'go'
\set core_modules 'identity:4,catalog:8,offers:3,inventory:3,orders:6,payments:5,seller:3,fulfilment:3,bookings:3,trust_ops:2,tax_returns:4,content:2,ai_gateway:2'
\set go_modules 'cart:3,geo:3,media:3,notifications:3'
SELECT CASE WHEN :'extra_core_modules' = '' THEN :'core_modules' ELSE :'core_modules' || ',' || :'extra_core_modules' END AS core_modules \gset

\connect :core_db
\set cur_modules :core_modules
\ir release-1-verify-one.sql
\connect :go_db
\set cur_modules :go_modules
\ir release-1-verify-one.sql
\connect postgres
\echo release-1 verify: OK
