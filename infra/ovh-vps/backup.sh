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
#   backup.sh restore DB OBJECT   restore one database from an object name shown by `list` (Postgres on this server; the
#                                 database must exist and be empty: drop and recreate it first)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
load_config
: "${BACKUP_BUCKET:?no backup bucket configured (the pipeline sets BACKUP_BUCKET)}" "${BACKUP_PASSPHRASE:?no BACKUP_PASSPHRASE}"
export BACKUP_PASSPHRASE
export RCLONE_CONFIG_OVH_TYPE=s3 RCLONE_CONFIG_OVH_PROVIDER=Other RCLONE_CONFIG_OVH_ENDPOINT="$BACKUP_S3_ENDPOINT" \
  RCLONE_CONFIG_OVH_REGION="$BACKUP_S3_REGION" RCLONE_CONFIG_OVH_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY" \
  RCLONE_CONFIG_OVH_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_KEY" RCLONE_S3_NO_CHECK_BUCKET=true
REMOTE="OVH:$BACKUP_BUCKET"
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
  local dbs=(temporal temporal_visibility openfga)
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
    [[ "$DB_MODE" == cluster ]] || die "restore into the in-cluster Postgres only; for an external server use its own restore tools"
    rclone cat "$REMOTE/$3" | dec | kubectl -n data exec -i postgres-0 -- env PGPASSWORD="$(pg_admin_password)" pg_restore -h 127.0.0.1 -U at_admin -d "$2" --no-owner --role="${2}_app"
    echo "restored $2" ;;
  *) die "usage: backup.sh install|run|list|restore DB OBJECT" ;;
esac
