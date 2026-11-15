#!/usr/bin/env bash
# Restore one database from the nightly encrypted backups (infra/k8s/platform/lean/backup) into a Postgres server you point at
# with the usual libpq variables: the real Layerbase server after an incident, or a SCRATCH server/database for the restore
# drill (docs/runbooks/restore-drill.md). Runs on an operator machine; the age PRIVATE key never goes near the cluster.
#
#   pg-restore.sh list [DB]                         objects in the backup remote (all databases, or one)
#   pg-restore.sh restore DB [OBJECT|latest] [TARGET_DB]
#                                                   decrypt, verify, create TARGET_DB (default DB) if missing, restore into it
#   pg-restore.sh globals [OBJECT|latest]           print the roles/grants dump (psql < file) when it was captured
#   pg-restore.sh restore-schema DB SCHEMA|all [OBJECT|latest] [TARGET_DB]
#                                                   RELEASE 1 layout (ADR 0011): DB is core or go (one database per deployable, one
#                                                   schema and role per module). Restores ONE module's schema (or `all`) from the
#                                                   whole-database dump into a target that infra/ops/postgres/release-1-bootstrap.sh
#                                                   has already prepared (database, roles, EMPTY schemas), as that module's role,
#                                                   then re-runs the bootstrap SQL so ownership, grants and search_path are exact.
#
# Environment:
#   B2_KEY_ID B2_APPLICATION_KEY B2_BUCKET B2_ENDPOINT [B2_REGION]
#                                                   the Backblaze B2 bucket (same values as the Secret pg-backup; a key restricted to
#                                                   the bucket and Read-only is enough for a restore). Region = the <region> of
#                                                   s3.<region>.backblazeb2.com unless B2_REGION is set.
#   BACKUP_REMOTE=<rclone path>                     generic alternative (tests, another S3 vendor): define the remote with
#                                                   RCLONE_CONFIG_BACKUP_TYPE/_PROVIDER/_ENDPOINT/_ACCESS_KEY_ID/_SECRET_ACCESS_KEY/_REGION
#   AGE_IDENTITY=~/age-backup-key.txt               the age private key (offline copy)
#   PGHOST PGPORT PGUSER PGPASSWORD PGSSLMODE       an ADMIN of the target server (roles and databases are created with it)
#   RESTORE_ROLE_PASSWORD                           password for a role this script has to create (default: random, printed once)
# The dump was taken with --no-owner, so everything is owned by <db>_app (temporal_visibility: temporal_app). Extensions
# (postgis in geo and fulfilment, btree_gist in bookings) are created by this script as admin and skipped inside the dump,
# exactly as infra/tofu/scripts/postgres-bootstrap.sh does on a fresh server.
set -euo pipefail

if [[ -n "${B2_KEY_ID:-}" ]]; then
  : "${B2_APPLICATION_KEY:?}" "${B2_BUCKET:?}" "${B2_ENDPOINT:?}"
  b2_host="${B2_ENDPOINT#https://}"; b2_host="${b2_host%/}"
  b2_region="${B2_REGION:-$(sed -n 's/^s3\.\([^.]*\)\.backblazeb2\.com$/\1/p' <<< "$b2_host")}"
  [[ -n "$b2_region" ]] || { echo "error: cannot derive the region from B2_ENDPOINT=$B2_ENDPOINT; set B2_REGION" >&2; exit 1; }
  export RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=Other RCLONE_CONFIG_BACKUP_ENDPOINT="https://$b2_host" \
    RCLONE_CONFIG_BACKUP_REGION="$b2_region" RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID="$B2_KEY_ID" \
    RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY="$B2_APPLICATION_KEY"
  BACKUP_REMOTE="backup:$B2_BUCKET"
fi
: "${BACKUP_REMOTE:?set B2_KEY_ID, B2_APPLICATION_KEY, B2_BUCKET and B2_ENDPOINT (or BACKUP_REMOTE)}"
export PGSSLMODE="${PGSSLMODE:-require}" PGDATABASE="${PGDATABASE:-postgres}"

role_of() { case "$1" in temporal_visibility) echo temporal_app ;; *) echo "${1}_app" ;; esac; }
ext_of() { case "$1" in geo|fulfilment) echo postgis ;; bookings) echo btree_gist ;; esac; }
psql_admin() { psql -v ON_ERROR_STOP=1 -qtA "$@"; }

latest() { # latest PREFIX -> newest object path below it
  rclone lsf "$BACKUP_REMOTE/$1" --files-only --s3-no-check-bucket | sort | tail -1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  list)
    rclone lsf "$BACKUP_REMOTE/postgres/${1:-}" -R --files-only --s3-no-check-bucket | sort
    ;;
  globals)
    obj="${1:-latest}"
    [[ "$obj" == latest ]] && obj="postgres/_globals/$(latest postgres/_globals)"
    : "${AGE_IDENTITY:?set AGE_IDENTITY}"
    rclone cat "$BACKUP_REMOTE/$obj" --s3-no-check-bucket | age -d -i "$AGE_IDENTITY"
    ;;
  restore-schema)
    db="${1:?usage: restore-schema DB SCHEMA|all [OBJECT|latest] [TARGET_DB]}"; schema="${2:?usage: restore-schema DB SCHEMA|all [OBJECT|latest] [TARGET_DB]}"
    obj="${3:-latest}"; target="${4:-$db}"; suffix="${ROLE_SUFFIX-_app}"
    case "$db" in core|go) ;; *) echo "error: restore-schema is for the databases core and go; use restore for temporal" >&2; exit 1 ;; esac
    [[ "$schema" =~ ^[a-z_]+$ ]] || { echo "error: bad schema name" >&2; exit 1; }
    : "${AGE_IDENTITY:?set AGE_IDENTITY}"
    [[ "$obj" == latest ]] && obj="postgres/$db/$(latest "postgres/$db")"
    [[ "$obj" == postgres/* ]] || obj="postgres/$db/$obj"
    work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
    rclone cat "$BACKUP_REMOTE/$obj" --s3-no-check-bucket | age -d -i "$AGE_IDENTITY" > "$work/db.dump"
    pg_restore --list "$work/db.dump" > "$work/full.list" || { echo "error: dump is unreadable (wrong key or damaged object)" >&2; exit 1; }
    if [[ "$schema" == all ]]; then
      schemas="$(sed -n 's/^[0-9]*; [0-9]* [0-9]* SCHEMA - \([a-z_]*\) .*/\1/p' "$work/full.list" | grep -vx public || true)"
    else
      schemas="$schema"
    fi
    [[ -n "$schemas" ]] || { echo "error: no module schema in $obj" >&2; exit 1; }
    [[ -n "$(psql_admin -d postgres -c "SELECT 1 FROM pg_database WHERE datname = '$target'")" ]] \
      || { echo "error: database $target does not exist: run infra/ops/postgres/release-1-bootstrap.sh first" >&2; exit 1; }
    # The schema objects only: no extension, no SCHEMA entry (the bootstrap made the schema, empty, owned by the module's role).
    grep -v -E ' EXTENSION |spatial_ref_sys| SCHEMA - ' "$work/full.list" > "$work/restore.list"
    for sc in $schemas; do
      role="${sc}${suffix}"
      [[ -n "$(psql_admin -d "$target" -c "SELECT 1 FROM pg_namespace WHERE nspname = '$sc'")" ]] \
        || { echo "error: schema $sc does not exist in $target: run infra/ops/postgres/release-1-bootstrap.sh first" >&2; exit 1; }
      n="$(psql_admin -d "$target" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$sc'")"
      (( n == 0 )) || { echo "error: schema $sc in $target is not empty ($n tables). Drop it and re-run the bootstrap first." >&2; exit 1; }
      echo "restoring $sc from $obj into $target as $role"
      psql_admin -d postgres -c "GRANT \"$role\" TO CURRENT_USER" >/dev/null 2>&1 || true
      pg_restore --exit-on-error --no-owner --role="$role" -n "$sc" -L "$work/restore.list" -d "$target" "$work/db.dump"
      echo "restored $sc: $(psql_admin -d "$target" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = '$sc'") tables"
    done
    # Re-assert ownership, grants and search_path exactly as in production (idempotent), then prove it.
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../postgres"
    psql -v ON_ERROR_STOP=1 -q -d postgres -v "role_suffix=$suffix" -f "$here/release-1-bootstrap.sql" >/dev/null
    psql -v ON_ERROR_STOP=1 -q -d postgres -v "role_suffix=$suffix" -f "$here/release-1-verify.sql"
    ;;
  restore)
    db="${1:?usage: restore DB [OBJECT|latest] [TARGET_DB]}"; obj="${2:-latest}"; target="${3:-$db}"
    case "$db" in core|go) echo "error: $db is a schema-per-module database (Release 1): use restore-schema" >&2; exit 1 ;; esac
    : "${AGE_IDENTITY:?set AGE_IDENTITY}"
    [[ "$db" =~ ^[a-z_]+$ ]] || { echo "error: bad database name" >&2; exit 1; }
    [[ "$obj" == latest ]] && obj="postgres/$db/$(latest "postgres/$db")"
    [[ "$obj" == postgres/* ]] || obj="postgres/$db/$obj"
    role="$(role_of "$db")"
    work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
    echo "restoring $obj into database $target as $role"

    rclone cat "$BACKUP_REMOTE/$obj" --s3-no-check-bucket | age -d -i "$AGE_IDENTITY" > "$work/db.dump"
    pg_restore --list "$work/db.dump" > "$work/full.list" || { echo "error: dump is unreadable (wrong key or damaged object)" >&2; exit 1; }
    # Skip what the admin created already: the extension itself and the extension's own configuration table data.
    grep -v -E ' EXTENSION |spatial_ref_sys' "$work/full.list" > "$work/restore.list"

    if [[ -z "$(psql_admin -c "SELECT 1 FROM pg_roles WHERE rolname = '$role'")" ]]; then
      pw="${RESTORE_ROLE_PASSWORD:-$(openssl rand -base64 36 | tr -d '/+=' | cut -c1-40)}"
      psql_admin -c "CREATE ROLE \"$role\" LOGIN PASSWORD '$pw'"
      echo "created role $role (password: ${RESTORE_ROLE_PASSWORD:+<RESTORE_ROLE_PASSWORD>}${RESTORE_ROLE_PASSWORD:-$pw}; put it in the service Secret)"
    fi
    psql_admin -c "GRANT \"$role\" TO CURRENT_USER" >/dev/null 2>&1 || true
    if [[ -z "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$target'")" ]]; then
      psql_admin -c "CREATE DATABASE \"$target\" OWNER \"$role\""
    else
      n="$(psql_admin -d "$target" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")"
      # PostGIS adds spatial_ref_sys; anything beyond that means the database is not empty
      (( n <= 1 )) || { echo "error: $target is not empty ($n tables). Drop and recreate it first." >&2; exit 1; }
    fi
    psql_admin -c "ALTER DATABASE \"$target\" OWNER TO \"$role\"; REVOKE ALL ON DATABASE \"$target\" FROM PUBLIC; GRANT CONNECT, TEMPORARY ON DATABASE \"$target\" TO \"$role\""
    ext="$(ext_of "$db")"
    [[ -z "$ext" ]] || psql_admin -d "$target" -c "CREATE EXTENSION IF NOT EXISTS $ext"

    pg_restore --exit-on-error --no-owner --role="$role" -L "$work/restore.list" -d "$target" "$work/db.dump"
    psql_admin -d "$target" -c "ALTER SCHEMA public OWNER TO \"$role\""
    echo "restored $db into $target: $(psql_admin -d "$target" -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'") tables in schema public"
    ;;
  *)
    echo "usage: pg-restore.sh list [DB] | restore DB [OBJECT|latest] [TARGET_DB] | restore-schema DB SCHEMA|all [OBJECT|latest] [TARGET_DB] | globals [OBJECT|latest]" >&2; exit 2 ;;
esac
