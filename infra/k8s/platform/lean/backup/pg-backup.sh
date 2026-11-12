#!/bin/sh
# Nightly logical backup of every service database on the external PostgreSQL server (Layerbase) to a Backblaze B2 bucket (an
# S3-compatible API, reached with rclone; a vendor other than Layerbase, OVH and Cloudflare), encrypted with age so the cluster only
# ever holds the PUBLIC key. Runs in the CronJob pg-backup (postgres:17-alpine; apk adds rclone, age and curl, so the cluster needs
# outbound HTTPS to the Alpine mirrors). Cloudflare R2 is deliberately NOT used for backups (independence from the Cloudflare account).
#
# Per database: pg_dump -Fc -> check it (pg_restore --list) -> age -> rclone copyto <remote>/postgres/<db>/<stamp>.dump.age.
# A failed database does not stop the others; the job exits 1 if ANY failed, so the alert fires. Retention (RETENTION_DAYS, default 14)
# is applied to the postgres/ prefix after the uploads, only when every database succeeded. Then ONE log line, backup_metrics, reports
# the bytes uploaded and the bucket total (and the same numbers go to a Pushgateway when PUSHGATEWAY_URL is set):
# infra/observability/rules/backup.yaml alerts on them. Restore: infra/ops/backup/pg-restore.sh. Setup, restore, key rotation:
# docs/runbooks/backups-backblaze.md; drill: docs/runbooks/restore-drill.md.
#
# Environment (Secret pg-backup, namespace ops; infra/ops/secrets/apply.sh):
#   PGHOST PGPORT PGUSER PGPASSWORD PGSSLMODE   admin (or any role that can read every database), plus PGSSLROOTCERT etc. if needed
#   AGE_RECIPIENT         age1... public key; the private key lives offline (password manager + paper), never in the cluster
#   B2_KEY_ID B2_APPLICATION_KEY   the application key of THIS environment's bucket only (Read and Write, restricted to that bucket)
#   B2_BUCKET             afrotransact-test-backups | afrotransact-prod-backups
#   B2_ENDPOINT           the bucket's S3 endpoint from the B2 console, s3.<region>.backblazeb2.com (https:// is added when missing)
#   B2_REGION             optional; default = the <region> part of B2_ENDPOINT
#   (generic alternative, used by tests and for another S3 vendor: BACKUP_REMOTE=<rclone path> plus RCLONE_CONFIG_BACKUP_* variables)
#   DATABASES             optional, space separated; default = the 19 launch databases below
#   RETENTION_DAYS        days of backups kept, default 14, accepted 3..365 (the owner may set 7)
#   RETENTION_MODE        sweep (default: the job deletes objects older than RETENTION_DAYS) | lifecycle (the bucket's lifecycle rule
#                         does it, the job deletes nothing) | off. On B2 a delete only HIDES the object: the bucket also needs the
#                         lifecycle rule in docs/runbooks/backups-backblaze.md, or hidden versions pile up and are billed.
#   RETENTION_MAX_DELETE  safety cap on objects one sweep may delete, default 400
#   BACKUP_ENV            label of the metrics, default prod (test | prod)
#   PUSHGATEWAY_URL       optional, e.g. http://pushgateway.observability:9091 : the metrics are pushed there as well as logged
#   HEALTHCHECK_URL       optional dead-man's-switch ping (healthchecks.io style): GET on success, GET <url>/fail on failure
#   SKIP_APK=1            do not run apk add (tests with a stub environment)
set -eu

DEFAULT_DATABASES="identity catalog offers inventory orders payments seller fulfilment bookings trustops taxreturns content ai cart geo media notifications temporal temporal_visibility"
DATABASES="${DATABASES:-$DEFAULT_DATABASES}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
RETENTION_MODE="${RETENTION_MODE:-sweep}"
RETENTION_MAX_DELETE="${RETENTION_MAX_DELETE:-400}"
BACKUP_ENV="${BACKUP_ENV:-prod}"
: "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${AGE_RECIPIENT:?}"
case "$RETENTION_DAYS" in ''|*[!0-9]*) echo "error: RETENTION_DAYS must be a whole number of days" >&2; exit 2 ;; esac
if [ "$RETENTION_DAYS" -lt 3 ] || [ "$RETENTION_DAYS" -gt 365 ]; then echo "error: RETENTION_DAYS must be 3..365 (got $RETENTION_DAYS)" >&2; exit 2; fi
case "$RETENTION_MODE" in sweep|lifecycle|off) ;; *) echo "error: RETENTION_MODE must be sweep, lifecycle or off" >&2; exit 2 ;; esac

# The off-vendor remote. Backblaze B2 through its S3-compatible API is the configured default; no region is hard-coded.
if [ -n "${B2_KEY_ID:-}" ]; then
  : "${B2_APPLICATION_KEY:?}" "${B2_BUCKET:?}" "${B2_ENDPOINT:?}"
  b2_host="${B2_ENDPOINT#https://}"; b2_host="${b2_host%/}"
  b2_region="${B2_REGION:-$(printf '%s' "$b2_host" | sed -n 's/^s3\.\([^.]*\)\.backblazeb2\.com$/\1/p')}"
  [ -n "$b2_region" ] || { echo "error: cannot derive the region from B2_ENDPOINT=$B2_ENDPOINT; set B2_REGION" >&2; exit 2; }
  export RCLONE_CONFIG_BACKUP_TYPE=s3 RCLONE_CONFIG_BACKUP_PROVIDER=Other RCLONE_CONFIG_BACKUP_ENDPOINT="https://$b2_host" \
    RCLONE_CONFIG_BACKUP_REGION="$b2_region" RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID="$B2_KEY_ID" \
    RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY="$B2_APPLICATION_KEY"
  BACKUP_REMOTE="backup:$B2_BUCKET"
fi
: "${BACKUP_REMOTE:?set B2_KEY_ID, B2_APPLICATION_KEY, B2_BUCKET and B2_ENDPOINT (or BACKUP_REMOTE)}"
export PGSSLMODE="${PGSSLMODE:-require}" PGCONNECT_TIMEOUT=20
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${WORK:-/work}"
mkdir -p "$WORK"

[ "${SKIP_APK:-0}" = 1 ] || apk add --no-cache rclone age curl >/dev/null
run_bytes=0
ok_dbs=0

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
  size="$(wc -c < "$f")"; enc_size="$(wc -c < "$f.age")"
  if rclone copyto "$f.age" "$BACKUP_REMOTE/postgres/$db/$STAMP.dump.age" --s3-no-check-bucket; then
    run_bytes=$((run_bytes + enc_size)); ok_dbs=$((ok_dbs + 1))
    echo "backed up $db ($size bytes plain, $enc_size bytes encrypted)"
  else
    echo "FAILED upload: $db" >&2; failed=1
  fi
  rm -f "$f" "$f.age"
done

# Roles and their grants: a restored cluster needs the <db>_app roles. Managed servers often refuse pg_dumpall --globals-only for
# a non-superuser; that is only a warning because infra/tofu/scripts/postgres-bootstrap.sh recreates the roles (new passwords).
if pg_dumpall --globals-only --no-role-passwords > "$WORK/globals.sql" 2>"$WORK/globals.err"; then
  if age -r "$AGE_RECIPIENT" -o "$WORK/globals.sql.age" "$WORK/globals.sql" \
    && rclone copyto "$WORK/globals.sql.age" "$BACKUP_REMOTE/postgres/_globals/$STAMP.sql.age" --s3-no-check-bucket; then
    run_bytes=$((run_bytes + $(wc -c < "$WORK/globals.sql.age")))
  else
    echo "FAILED globals upload" >&2; failed=1
  fi
else
  echo "warning: pg_dumpall --globals-only not permitted for this role: $(head -c 200 "$WORK/globals.err")" >&2
fi
rm -f "$WORK"/globals.*

# Retention. Only after a fully successful run (a broken night must never also eat the history), only below postgres/, capped by
# --max-delete. On B2 (versioned buckets) a delete through the S3 API only HIDES the object; the bucket's lifecycle rule "delete
# hidden versions after 1 day" removes the bytes (docs/runbooks/backups-backblaze.md; UNVERIFIED from memory). With Object Lock the
# hide still works but the locked versions stay until the lock ends: keep the lock period at or below RETENTION_DAYS.
if [ "$RETENTION_MODE" = sweep ] && [ "$failed" -eq 0 ]; then
  rclone delete "$BACKUP_REMOTE/postgres" --min-age "${RETENTION_DAYS}d" --max-delete "$RETENTION_MAX_DELETE" --s3-no-check-bucket \
    || echo "warning: retention sweep failed" >&2
  rclone cleanup "$BACKUP_REMOTE" --s3-no-check-bucket >/dev/null 2>&1 || true   # abandoned multipart uploads, best effort
elif [ "$RETENTION_MODE" = sweep ]; then
  echo "warning: retention sweep skipped because this run had failures" >&2
fi

# Size report: one log line (Alloy -> Loki) and, when PUSHGATEWAY_URL is set, Prometheus metrics. The bucket total is what rclone
# lists (current versions); hidden versions that B2 still holds are not in it (UNVERIFIED), so check the B2 console now and then.
bucket_bytes="$(rclone size --json "$BACKUP_REMOTE" --s3-no-check-bucket 2>/dev/null | tr -d ' \n' | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')"
bucket_bytes="${bucket_bytes:-0}"
echo "backup_metrics env=$BACKUP_ENV stamp=$STAMP run_bytes=$run_bytes bucket_bytes=$bucket_bytes databases_ok=$ok_dbs failed=$failed retention_days=$RETENTION_DAYS retention_mode=$RETENTION_MODE"
if [ -n "${PUSHGATEWAY_URL:-}" ]; then
  {
    echo "# TYPE afrotransact_backup_run_bytes gauge"; echo "afrotransact_backup_run_bytes $run_bytes"
    echo "# TYPE afrotransact_backup_bucket_bytes gauge"; echo "afrotransact_backup_bucket_bytes $bucket_bytes"
    echo "# TYPE afrotransact_backup_databases_ok gauge"; echo "afrotransact_backup_databases_ok $ok_dbs"
    echo "# TYPE afrotransact_backup_last_run_failed gauge"; echo "afrotransact_backup_last_run_failed $failed"
    echo "# TYPE afrotransact_backup_last_run_timestamp_seconds gauge"; echo "afrotransact_backup_last_run_timestamp_seconds $(date -u +%s)"
    [ "$failed" -ne 0 ] || { echo "# TYPE afrotransact_backup_last_success_timestamp_seconds gauge"; echo "afrotransact_backup_last_success_timestamp_seconds $(date -u +%s)"; }
  } | curl -fsS -m 15 --retry 2 --data-binary @- "${PUSHGATEWAY_URL%/}/metrics/job/pg_backup/env/$BACKUP_ENV" >/dev/null 2>&1 \
    || echo "warning: pushgateway push failed" >&2
fi

if [ "$failed" -ne 0 ]; then
  ping_hc /fail
  echo "backup finished WITH FAILURES" >&2
  exit 1
fi
ping_hc
echo "backup complete: $ok_dbs databases at $STAMP"
