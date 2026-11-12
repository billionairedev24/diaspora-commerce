#!/bin/sh
# Nightly logical backup of every service database on the external PostgreSQL server (Layerbase) to an object bucket at a
# DIFFERENT vendor, encrypted with age so the cluster only ever holds the PUBLIC key. Runs in the CronJob pg-backup
# (postgres:17-alpine; apk adds rclone, age and curl, so the cluster needs outbound HTTPS to the Alpine mirrors).
#
# Per database: pg_dump -Fc -> check it (pg_restore --list) -> age -> rclone copyto <remote>/postgres/<db>/<stamp>.dump.age.
# A failed database does not stop the others; the job exits 1 if ANY failed, so the alert fires. 14-day retention is applied
# to the postgres/ prefix after the uploads. Restore: infra/ops/backup/pg-restore.sh, docs/runbooks/restore-drill.md.
#
# Environment (Secret pg-backup, namespace ops; infra/ops/secrets/apply.sh):
#   PGHOST PGPORT PGUSER PGPASSWORD PGSSLMODE   admin (or any role that can read every database), plus PGSSLROOTCERT etc. if needed
#   AGE_RECIPIENT         age1... public key; the private key lives offline (password manager + paper), never in the cluster
#   BACKUP_REMOTE         rclone path, e.g. backup:afrotransact-pg-backups   (the remote "backup" is defined by RCLONE_CONFIG_BACKUP_* env)
#   RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=... _ENDPOINT _ACCESS_KEY_ID _SECRET_ACCESS_KEY _REGION   (any S3 vendor)
#   DATABASES             optional, space separated; default = the 19 launch databases below
#   RETENTION_DAYS        optional, default 14
#   HEALTHCHECK_URL       optional dead-man's-switch ping (healthchecks.io style): GET on success, GET <url>/fail on failure
set -eu

DEFAULT_DATABASES="identity catalog offers inventory orders payments seller fulfilment bookings trustops taxreturns content ai cart geo media notifications temporal temporal_visibility"
DATABASES="${DATABASES:-$DEFAULT_DATABASES}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${AGE_RECIPIENT:?}" "${BACKUP_REMOTE:?}"
export PGSSLMODE="${PGSSLMODE:-require}" PGCONNECT_TIMEOUT=20
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${WORK:-/work}"
mkdir -p "$WORK"

apk add --no-cache rclone age curl >/dev/null

ping_hc() { # ping_hc [suffix]
  [ -n "${HEALTHCHECK_URL:-}" ] || return 0
  curl -fsS -m 15 --retry 3 "${HEALTHCHECK_URL}${1:-}" >/dev/null 2>&1 || echo "warning: healthcheck ping failed" >&2
}
ping_hc /start

failed=0
for db in $DATABASES; do
  f="$WORK/$db.dump"
  if ! pg_dump -Fc --no-owner "$db" > "$f"; then
    echo "FAILED dump: $db" >&2; failed=1; rm -f "$f"; continue
  fi
  # A truncated or empty dump must not be uploaded as if it were a backup.
  if ! pg_restore --list "$f" >/dev/null 2>&1 || [ ! -s "$f" ]; then
    echo "FAILED verify: $db (dump is not readable)" >&2; failed=1; rm -f "$f"; continue
  fi
  if ! age -r "$AGE_RECIPIENT" -o "$f.age" "$f"; then
    echo "FAILED encrypt: $db" >&2; failed=1; rm -f "$f" "$f.age"; continue
  fi
  size="$(wc -c < "$f")"
  if rclone copyto "$f.age" "$BACKUP_REMOTE/postgres/$db/$STAMP.dump.age" --s3-no-check-bucket; then
    echo "backed up $db ($size bytes plain)"
  else
    echo "FAILED upload: $db" >&2; failed=1
  fi
  rm -f "$f" "$f.age"
done

# Roles and their grants: a restored cluster needs the <db>_app roles. Managed servers often refuse pg_dumpall --globals-only for
# a non-superuser; that is only a warning because infra/tofu/scripts/postgres-bootstrap.sh recreates the roles (new passwords).
if pg_dumpall --globals-only --no-role-passwords > "$WORK/globals.sql" 2>"$WORK/globals.err"; then
  age -r "$AGE_RECIPIENT" -o "$WORK/globals.sql.age" "$WORK/globals.sql" \
    && rclone copyto "$WORK/globals.sql.age" "$BACKUP_REMOTE/postgres/_globals/$STAMP.sql.age" --s3-no-check-bucket \
    || { echo "FAILED globals upload" >&2; failed=1; }
else
  echo "warning: pg_dumpall --globals-only not permitted for this role: $(head -c 200 "$WORK/globals.err")" >&2
fi
rm -f "$WORK"/globals.*

# Retention: delete this job's objects older than RETENTION_DAYS (the bucket should ALSO carry a lifecycle rule, a few days longer).
rclone delete "$BACKUP_REMOTE/postgres" --min-age "${RETENTION_DAYS}d" --s3-no-check-bucket \
  || { echo "warning: retention sweep failed" >&2; }

if [ "$failed" -ne 0 ]; then
  ping_hc /fail
  echo "backup finished WITH FAILURES" >&2
  exit 1
fi
ping_hc
echo "backup complete: $(echo $DATABASES | wc -w) databases at $STAMP"
