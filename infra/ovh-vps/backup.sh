#!/usr/bin/env bash
# Backups of what cannot be rebuilt from git, to the private Object Storage bucket the pipeline made (BACKUP_BUCKET), encrypted
# with BACKUP_PASSPHRASE, nothing written to the server's disk (streamed):
#   - every database (pg_dump -Fc): the Postgres on this server or the external one
#   - /var/lib/afrotransact: the generated secrets and the identity signing key (without them a restored database is useless)
# Not backed up, on purpose: Valkey (cache, carts), Meilisearch (rebuilt from the catalog), Kafka (services publish through an
# outbox). Objects older than 14 days are deleted here and by the bucket's 30-day expiry.
#   backup.sh install        cron at 03:15 every night (deploy.sh runs it)
#   backup.sh run            one backup now
#   backup.sh list           what is in the bucket
#   backup.sh restore DB OBJECT   restore one database from an object name shown by `list`: the Postgres on this server
#                                 (DB_MODE=cluster) or the external one (DB_MODE=external, e.g. Layerbase) with the admin
#                                 credentials of config.env. The database is created (owner <db>_app, PostGIS / btree_gist
#                                 first) when missing and must be EMPTY otherwise: drop and recreate it first.
# The remote is configurable: by default the OVH bucket (BACKUP_BUCKET, BACKUP_S3_*), or ANY rclone remote with
# BACKUP_REMOTE=<remote>:<bucket> (define the remote with RCLONE_CONFIG_<NAME>_* variables, e.g. Cloudflare R2 or Backblaze B2).
# THIS script is the test box's backup (passphrase-encrypted, one server). PRODUCTION backups are the Kubernetes CronJob
# infra/k8s/platform/lean/backup (age-encrypted, a bucket at a vendor other than Layerbase) and infra/ops/backup/pg-restore.sh;
# see docs/runbooks/restore-drill.md. With PROFILE=prod this script refuses to run, so the two never overlap.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config
[[ "$PROFILE" != prod ]] || die "production is backed up by the CronJob infra/k8s/platform/lean/backup, not by this script"
: "${BACKUP_PASSPHRASE:?no BACKUP_PASSPHRASE}"
export BACKUP_PASSPHRASE
if [[ -n "${BACKUP_REMOTE:-}" ]]; then
  REMOTE="$BACKUP_REMOTE"          # any rclone remote, configured by the caller through RCLONE_CONFIG_<NAME>_* variables
else
  : "${BACKUP_BUCKET:?no backup bucket configured (the pipeline sets BACKUP_BUCKET, or set BACKUP_REMOTE)}"
  export RCLONE_CONFIG_OVH_TYPE=s3 RCLONE_CONFIG_OVH_PROVIDER=Other RCLONE_CONFIG_OVH_ENDPOINT="$BACKUP_S3_ENDPOINT" \
    RCLONE_CONFIG_OVH_REGION="$BACKUP_S3_REGION" RCLONE_CONFIG_OVH_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" \
    RCLONE_CONFIG_OVH_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" RCLONE_S3_NO_CHECK_BUCKET=true
  REMOTE="OVH:$BACKUP_BUCKET"
fi
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
enc() { openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_PASSPHRASE; }
dec() { openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_PASSPHRASE; }

pg_admin_password() { if [[ "$DB_MODE" == external ]]; then printf '%s' "$EXTERNAL_PG_ADMIN_PASSWORD"; else . "$STATE_DIR/generated.env" 2>/dev/null; printf '%s' "${POSTGRES_ADMIN_PASSWORD:-}"; fi; }

dump_db() { # dump_db DB  -> custom-format dump on stdout
  if [[ "$DB_MODE" == cluster ]]; then
    kubectl -n data exec postgres-0 -- env PGPASSWORD="$(pg_admin_password)" pg_dump -h 127.0.0.1 -U at_admin -Fc "$1"
  else
    PGPASSWORD="$(pg_admin_password)" PGSSLMODE="${EXTERNAL_PG_SSLMODE:-require}" pg_dump -h "$EXTERNAL_PG_HOST" -U "$EXTERNAL_PG_ADMIN_USER" -Fc "$1"
  fi
}

run() {
  command -v rclone >/dev/null || die "rclone is not installed (backup.sh install)"
  local failed=0 db
  local dbs=(temporal temporal_visibility)
  [[ "$PROFILE" == prod ]] || dbs+=(openfga)
  while read -r _ _ db _; do [[ "$db" != "-" ]] && dbs+=("$db"); done < <(services)
  for db in "${dbs[@]}"; do
    if dump_db "$db" | enc | rclone rcat "$REMOTE/postgres/$db/$STAMP.dump.enc"; then echo "backed up $db"; else echo "FAILED: $db" >&2; failed=1; fi
  done
  tar -C / -czf - "${STATE_DIR#/}" | enc | rclone rcat "$REMOTE/secrets/$STAMP.tgz.enc" && echo "backed up the generated secrets" || { echo "FAILED: secrets" >&2; failed=1; }
  rclone delete "$REMOTE" --min-age 14d >/dev/null 2>&1 || true
  [[ $failed == 0 ]] || exit 1
}

install() {
  if ! command -v rclone >/dev/null; then apt-get install -y -qq rclone >/dev/null; fi
  printf '15 3 * * * root %s run >> /var/log/afrotransact-backup.log 2>&1\n' "$HERE/backup.sh" > /etc/cron.d/afrotransact-backup
  chmod 644 /etc/cron.d/afrotransact-backup
  echo "nightly backup installed (03:15 UTC) to $REMOTE"
}

case "${1:-}" in
  install) install ;;
  run) run ;;
  list) rclone ls "$REMOTE" ;;
  restore)
    [[ -n "${2:-}" && -n "${3:-}" ]] || die "usage: backup.sh restore DB OBJECT   (OBJECT as in 'backup.sh list', e.g. postgres/orders/20261021T031500Z.dump.enc)"
    db="$2"; role="${db}_app"; [[ "$db" == temporal_visibility ]] && role="temporal_app"
    ext=""; case "$db" in geo|fulfilment) ext=postgis ;; bookings) ext=btree_gist ;; esac
    if [[ "$DB_MODE" == cluster ]]; then
      rclone cat "$REMOTE/$3" | dec | kubectl -n data exec -i postgres-0 -- env PGPASSWORD="$(pg_admin_password)" pg_restore -h 127.0.0.1 -U at_admin -d "$db" --no-owner --role="$role"
    else
      # External server (Layerbase ...): admin credentials from config.env. Same steps as infra/ops/backup/pg-restore.sh.
      export PGHOST="$EXTERNAL_PG_HOST" PGUSER="$EXTERNAL_PG_ADMIN_USER" PGPASSWORD="$EXTERNAL_PG_ADMIN_PASSWORD" PGSSLMODE="${EXTERNAL_PG_SSLMODE:-require}"
      adm() { psql -h "$PGHOST" -U "$PGUSER" -v ON_ERROR_STOP=1 -qtA "$@"; }
      [[ -n "$(adm -d postgres -c "SELECT 1 FROM pg_roles WHERE rolname='$role'")" ]] || die "role $role does not exist: run secrets.sh databases (or postgres-bootstrap.sh) first"
      adm -d postgres -c "GRANT \"$role\" TO CURRENT_USER" >/dev/null 2>&1 || true
      [[ -n "$(adm -d postgres -c "SELECT 1 FROM pg_database WHERE datname='$db'")" ]] || adm -d postgres -c "CREATE DATABASE \"$db\" OWNER \"$role\""
      [[ -z "$ext" ]] || adm -d "$db" -c "CREATE EXTENSION IF NOT EXISTS $ext"
      tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
      rclone cat "$REMOTE/$3" | dec > "$tmp/db.dump"
      pg_restore --list "$tmp/db.dump" | grep -v -E ' EXTENSION |spatial_ref_sys' > "$tmp/restore.list"   # the extension already exists
      pg_restore --exit-on-error --no-owner --role="$role" -L "$tmp/restore.list" -h "$PGHOST" -U "$PGUSER" -d "$db" "$tmp/db.dump"
    fi
    echo "restored $db" ;;
  *) die "usage: backup.sh install|run|list|restore DB OBJECT" ;;
esac
