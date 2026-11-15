#!/usr/bin/env bash
# Provisions the Release 1 database layout on a PostgreSQL 17 server (Layerbase in production, the local container, a CI service):
# runs release-1-bootstrap.sql (roles, databases, schemas, grants, search_path, connection limits), sets each role's password, then
# runs release-1-verify.sql. Idempotent: run it again whenever a module is added.
#
#   PGHOST=... PGUSER=<admin> PGPASSWORD=... PGSSLMODE=require STATE_DIR=$HOME/.afrotransact-prod infra/ops/postgres/release-1-bootstrap.sh
#
# Passwords never reach stdout, argv, the SQL file or git:
#   PASSWORD_MODE=generated (default)  <STATE_DIR>/generated.env holds DBPW_<schema>=<value>, the file infra/ovh-vps/secrets.sh reads with
#                                      the same key names, so the Secrets rendered later carry the same values (the old per-service
#                                      roles <db>_app are the same roles). A missing value is generated there (mode 0600). Back that
#                                      directory up OFFLINE, separately from the database dumps (docs/runbooks/ovh-production.md section 6).
#   PASSWORD_MODE=name                 password = the schema name: LOCAL AND CI ONLY (infra/local, the CI Postgres service).
# Other variables: ROLE_SUFFIX (default _app; local and CI use "" or what their init uses), EXTENSIONS (default postgis,btree_gist),
#   VERIFY=false to skip the verification, plus the usual libpq variables (PGSSLROOTCERT, PGPORT ...).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sql="$here/release-1-bootstrap.sql"
mode="${PASSWORD_MODE:-generated}"
suffix="${ROLE_SUFFIX-_app}"
vars=(-v "role_suffix=$suffix")
[[ -z "${EXTENSIONS:-}" ]] || vars+=(-v "extensions=$EXTENSIONS")

die() { echo "release-1-bootstrap: $*" >&2; exit 1; }
command -v psql >/dev/null || die "psql is required"
case "$mode" in generated|name) ;; *) die "PASSWORD_MODE must be generated or name" ;; esac
if [[ "$mode" == generated ]]; then
  : "${STATE_DIR:?set STATE_DIR (the directory secrets.sh keeps generated.env in)}"
  install -d -m 700 "$STATE_DIR"; touch "$STATE_DIR/generated.env"; chmod 600 "$STATE_DIR/generated.env"
fi

# The module lists live in ONE place, the SQL file.
list() { sed -n "s/^\\\\set $1 '\\(.*\\)'\$/\\1/p" "$sql" | head -1 | tr ',' '\n' | cut -d: -f1; }
modules="$(list core_modules; list go_modules)"
[[ -n "$modules" ]] || die "cannot read the module lists from $sql"

echo "==> roles, databases, schemas (release-1-bootstrap.sql)"
psql -v ON_ERROR_STOP=1 -q -d "${PGDATABASE:-postgres}" "${vars[@]}" -f "$sql"

password_of() { # password_of NAME -> value (generated once, kept in generated.env)
  if [[ "$mode" == name ]]; then printf '%s' "$1"; return; fi
  local file="$STATE_DIR/generated.env" v
  v="$(grep -E "^DBPW_$1=" "$file" | head -1 | cut -d= -f2- || true)"
  if [[ -z "$v" ]]; then
    v="$(openssl rand -base64 27 | tr -d '/+=\n' | cut -c1-36)"
    printf 'DBPW_%s=%s\n' "$1" "$v" >> "$file"
  fi
  printf '%s' "$v"
}

echo "==> passwords ($mode)"
for m in $modules temporal; do
  role="${m}${suffix}"
  # The value goes in on stdin as a psql variable: not in argv, not in the process list, not in a log.
  psql -v ON_ERROR_STOP=1 -q -d "${PGDATABASE:-postgres}" -v "role=$role" -v "pw=$(password_of "$m")" -f - <<< "ALTER ROLE :\"role\" PASSWORD :'pw';"
  echo "  $role"
done

if [[ "${VERIFY:-true}" == true ]]; then
  echo "==> verify (release-1-verify.sql)"
  psql -v ON_ERROR_STOP=1 -q -d "${PGDATABASE:-postgres}" "${vars[@]}" -f "$here/release-1-verify.sql"
fi
