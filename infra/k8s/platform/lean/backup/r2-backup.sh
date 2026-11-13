#!/bin/sh
# Nightly copy of the two Cloudflare R2 buckets (media, kyc-evidence) to the Backblaze B2 backup bucket (backlog S380, audit F12).
# Same tooling as pg-backup.sh: rclone over the S3 API, age with only the PUBLIC key in the cluster, the job fails (and the alerts in
# infra/observability/rules/r2-backup.yaml fire) when anything is wrong. Runs in the CronJob r2-backup (namespace ops).
#
# Nothing is ever deleted at the destination: a deletion or overwrite in R2 does not propagate (that is the point).
#   media (public images, already public on the CDN): rclone copy R2 -> B2 <prefix>/r2/media/current with --checksum; a file that
#     CHANGED in R2 has its previous copy moved to <prefix>/r2/media/changed/<stamp>/ (--backup-dir), never lost. Afterwards
#     `rclone check --one-way` compares every R2 object with its copy (checksums where both sides have them, else size).
#   kyc (identity documents, private): each new or changed object is downloaded, hashed (sha256), encrypted with age and stored as
#     <prefix>/r2/kyc/objects/<sha256(key)>.<sha256(content)[0:16]>.age, plus <sha256(key)>.path.age (the original key, encrypted):
#     B2 therefore holds no readable file name or content. The state is a plain manifest of  hash|size|modtime|content16  lines
#     (<prefix>/r2/kyc/_manifest/latest, a dated copy beside it); an object whose line is missing or different is copied again under
#     a NEW name (immutable names work with B2 Object Lock). A failed object keeps its old line and is retried the next night.
#     The age PRIVATE key stays offline (docs/runbooks/backup-restore-r2.md); without it the KYC copy cannot be read, by design.
#
# Environment (Secret r2-backup, namespace ops; infra/ovh-vps/secrets.sh):
#   R2_ACCOUNT_ID | R2_ENDPOINT      the Cloudflare account (https://<id>.r2.cloudflarestorage.com)
#   R2_MEDIA_BUCKET R2_KYC_BUCKET    afrotransact-<env>-media | afrotransact-<env>-kyc-evidence
#   R2_MEDIA_KEY_ID R2_MEDIA_SECRET  read-only (Object Read) R2 token scoped to the media bucket
#   R2_KYC_KEY_ID   R2_KYC_SECRET    read-only R2 token scoped to the kyc bucket
#   B2_KEY_ID B2_APPLICATION_KEY B2_BUCKET B2_ENDPOINT [B2_REGION]   the environment's backup bucket (write key, that bucket only)
#   B2_PREFIX                        optional folder inside the bucket, default none (objects land in <bucket>/r2/...)
#   AGE_RECIPIENT                    age1... public key
#   BACKUP_ENV PUSHGATEWAY_URL HEALTHCHECK_URL   as in pg-backup.sh
#   (generic form for tests and drills: MEDIA_SRC, KYC_SRC, DEST as rclone paths, e.g. a local directory or a MinIO remote)
#   WORK (default /work)  SKIP_APK=1
set -eu

BACKUP_ENV="${BACKUP_ENV:-prod}"
: "${AGE_RECIPIENT:?}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${WORK:-/work}"
mkdir -p "$WORK"

if [ -z "${MEDIA_SRC:-}" ]; then
  : "${R2_MEDIA_BUCKET:?}" "${R2_KYC_BUCKET:?}" "${R2_MEDIA_KEY_ID:?}" "${R2_MEDIA_SECRET:?}" "${R2_KYC_KEY_ID:?}" "${R2_KYC_SECRET:?}"
  r2_ep="${R2_ENDPOINT:-}"
  [ -n "$r2_ep" ] || { : "${R2_ACCOUNT_ID:?set R2_ACCOUNT_ID or R2_ENDPOINT}"; r2_ep="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com"; }
  # Two remotes, one read-only token each: neither token can read the other bucket.
  export RCLONE_CONFIG_R2MEDIA_TYPE=s3 RCLONE_CONFIG_R2MEDIA_PROVIDER=Cloudflare RCLONE_CONFIG_R2MEDIA_ENDPOINT="$r2_ep" \
    RCLONE_CONFIG_R2MEDIA_ACCESS_KEY_ID="$R2_MEDIA_KEY_ID" RCLONE_CONFIG_R2MEDIA_SECRET_ACCESS_KEY="$R2_MEDIA_SECRET" RCLONE_CONFIG_R2MEDIA_REGION=auto
  export RCLONE_CONFIG_R2KYC_TYPE=s3 RCLONE_CONFIG_R2KYC_PROVIDER=Cloudflare RCLONE_CONFIG_R2KYC_ENDPOINT="$r2_ep" \
    RCLONE_CONFIG_R2KYC_ACCESS_KEY_ID="$R2_KYC_KEY_ID" RCLONE_CONFIG_R2KYC_SECRET_ACCESS_KEY="$R2_KYC_SECRET" RCLONE_CONFIG_R2KYC_REGION=auto
  MEDIA_SRC="r2media:$R2_MEDIA_BUCKET"; KYC_SRC="r2kyc:$R2_KYC_BUCKET"
  : "${B2_KEY_ID:?}" "${B2_APPLICATION_KEY:?}" "${B2_BUCKET:?}" "${B2_ENDPOINT:?}"
  b2_host="${B2_ENDPOINT#https://}"; b2_host="${b2_host%/}"
  b2_region="${B2_REGION:-$(printf '%s' "$b2_host" | sed -n 's/^s3\.\([^.]*\)\.backblazeb2\.com$/\1/p')}"
  [ -n "$b2_region" ] || { echo "error: cannot derive the region from B2_ENDPOINT; set B2_REGION" >&2; exit 2; }
  export RCLONE_CONFIG_B2DEST_TYPE=s3 RCLONE_CONFIG_B2DEST_PROVIDER=Other RCLONE_CONFIG_B2DEST_ENDPOINT="https://$b2_host" \
    RCLONE_CONFIG_B2DEST_REGION="$b2_region" RCLONE_CONFIG_B2DEST_ACCESS_KEY_ID="$B2_KEY_ID" RCLONE_CONFIG_B2DEST_SECRET_ACCESS_KEY="$B2_APPLICATION_KEY"
  DEST="b2dest:$B2_BUCKET${B2_PREFIX:+/$B2_PREFIX}"
fi
: "${KYC_SRC:?}" "${DEST:?}"
RC="--s3-no-check-bucket"
[ "${RCLONE_NO_S3_FLAGS:-0}" = 0 ] || RC=""   # local-directory remotes in unit tests do not know S3 flags

[ "${SKIP_APK:-0}" = 1 ] || apk add --no-cache rclone age curl >/dev/null

ping_hc() { [ -n "${HEALTHCHECK_URL:-}" ] || return 0; curl -fsS -m 15 --retry 3 "${HEALTHCHECK_URL}${1:-}" >/dev/null 2>&1 || echo "warning: healthcheck ping failed" >&2; }
ping_hc /start
failed=0
media_ok=0; kyc_new=0; kyc_total=0; kyc_failed=0; run_bytes=0

# ---- media: copy, keep overwritten versions, then compare ----------------------------------------------------------------------
# shellcheck disable=SC2086
if rclone copy "$MEDIA_SRC" "$DEST/r2/media/current" --backup-dir "$DEST/r2/media/changed/$STAMP" --checksum --transfers 4 $RC; then
  # shellcheck disable=SC2086
  if rclone check "$MEDIA_SRC" "$DEST/r2/media/current" --one-way --checksum $RC >"$WORK/media-check.log" 2>&1; then
    media_ok=1
  else
    echo "FAILED media check: $(tail -n 5 "$WORK/media-check.log" | tr '\n' ' ')" >&2; failed=1
  fi
else
  echo "FAILED media copy" >&2; failed=1
fi

# ---- kyc: encrypted, incremental, nothing deleted -----------------------------------------------------------------------------
: > "$WORK/prev.mf"
# shellcheck disable=SC2086
rclone cat "$DEST/r2/kyc/_manifest/latest" $RC > "$WORK/prev.mf" 2>/dev/null || : > "$WORK/prev.mf"
: > "$WORK/new.mf"; : > "$WORK/curh"
# shellcheck disable=SC2086
if ! rclone lsf -R --files-only --format "pst" --separator "|" "$KYC_SRC" $RC > "$WORK/cur.lst" 2>"$WORK/lsf.err"; then
  echo "FAILED to list the kyc bucket: $(head -c 200 "$WORK/lsf.err")" >&2; failed=1; : > "$WORK/cur.lst"
  cp "$WORK/prev.mf" "$WORK/new.mf"   # keep the state as it was
else
  while IFS='|' read -r p s t; do
    [ -n "$p" ] || continue
    kyc_total=$((kyc_total + 1))
    h="$(printf '%s' "$p" | sha256sum | cut -d' ' -f1)"
    printf '%s\n' "$h" >> "$WORK/curh"
    if prev="$(grep -F "$h|$s|$t|" "$WORK/prev.mf" | head -n 1)" && [ -n "$prev" ]; then printf '%s\n' "$prev" >> "$WORK/new.mf"; continue; fi
    old="$(grep -F "$h|" "$WORK/prev.mf" | head -n 1 || true)"   # a changed object keeps its old line until the new copy is in
    obj="$WORK/obj"; rm -f "$obj" "$obj.age"
    # shellcheck disable=SC2086
    if ! rclone copyto "$KYC_SRC/$p" "$obj" $RC || [ "$(wc -c < "$obj")" != "$s" ]; then
      echo "FAILED download of a kyc object ($h)" >&2; kyc_failed=$((kyc_failed + 1)); [ -z "$old" ] || printf '%s\n' "$old" >> "$WORK/new.mf"; continue
    fi
    c="$(sha256sum "$obj" | cut -c1-16)"
    if ! age -r "$AGE_RECIPIENT" -o "$obj.age" "$obj"; then
      echo "FAILED encrypt ($h)" >&2; kyc_failed=$((kyc_failed + 1)); [ -z "$old" ] || printf '%s\n' "$old" >> "$WORK/new.mf"; rm -f "$obj"; continue
    fi
    enc="$(wc -c < "$obj.age")"
    printf '%s' "$p" | age -r "$AGE_RECIPIENT" -o "$WORK/path.age"
    # shellcheck disable=SC2086
    if rclone copyto "$obj.age" "$DEST/r2/kyc/objects/$h.$c.age" $RC && rclone copyto "$WORK/path.age" "$DEST/r2/kyc/objects/$h.path.age" $RC; then
      printf '%s|%s|%s|%s\n' "$h" "$s" "$t" "$c" >> "$WORK/new.mf"; kyc_new=$((kyc_new + 1)); run_bytes=$((run_bytes + enc))
    else
      echo "FAILED upload of a kyc object ($h)" >&2; kyc_failed=$((kyc_failed + 1)); [ -z "$old" ] || printf '%s\n' "$old" >> "$WORK/new.mf"
    fi
    rm -f "$obj" "$obj.age" "$WORK/path.age"
  done < "$WORK/cur.lst"
  [ "$kyc_failed" -eq 0 ] || failed=1
  # Objects gone from R2 (deleted or overwritten by mistake: the case this copy exists for) keep their manifest line forever, so the
  # verification below keeps checking that their encrypted copy is still in B2. Nothing is ever deleted from B2 by this job.
  awk -F'|' 'FILENAME == ARGV[1] {seen[$1] = 1; next} !($1 in seen)' "$WORK/curh" "$WORK/prev.mf" >> "$WORK/new.mf"
fi
sort -u "$WORK/new.mf" -o "$WORK/new.mf"

# Every manifest line must have its encrypted object in B2: list once, compare.
if [ -s "$WORK/new.mf" ]; then
  # shellcheck disable=SC2086
  if rclone lsf --files-only "$DEST/r2/kyc/objects" $RC > "$WORK/b2.lst" 2>/dev/null; then
    missing=0
    while IFS='|' read -r h _ _ c; do
      grep -Fxq "$h.$c.age" "$WORK/b2.lst" || { missing=$((missing + 1)); echo "MISSING in B2: kyc object $h.$c.age" >&2; }
    done < "$WORK/new.mf"
    [ "$missing" -eq 0 ] || failed=1
  else
    echo "FAILED to list the kyc copy in B2" >&2; failed=1
  fi
fi
# Publish the new manifest only when it is a superset of what R2 has now, plus the dated copy.
# shellcheck disable=SC2086
rclone copyto "$WORK/new.mf" "$DEST/r2/kyc/_manifest/latest" $RC && rclone copyto "$WORK/new.mf" "$DEST/r2/kyc/_manifest/$STAMP" $RC \
  || { echo "FAILED to write the kyc manifest" >&2; failed=1; }
kyc_in_manifest="$(wc -l < "$WORK/new.mf" | tr -d ' ')"

# ---- report ------------------------------------------------------------------------------------------------------------------
now="$(date -u +%s)"
echo "r2_backup_metrics env=$BACKUP_ENV stamp=$STAMP media_ok=$media_ok kyc_objects=$kyc_total kyc_new=$kyc_new kyc_failed=$kyc_failed kyc_in_manifest=$kyc_in_manifest run_bytes=$run_bytes failed=$failed"
if [ -n "${PUSHGATEWAY_URL:-}" ]; then
  {
    echo "# TYPE afrotransact_r2_backup_run_bytes gauge"; echo "afrotransact_r2_backup_run_bytes $run_bytes"
    echo "# TYPE afrotransact_r2_backup_kyc_objects gauge"; echo "afrotransact_r2_backup_kyc_objects $kyc_total"
    echo "# TYPE afrotransact_r2_backup_last_run_failed gauge"; echo "afrotransact_r2_backup_last_run_failed $failed"
    echo "# TYPE afrotransact_r2_backup_last_run_timestamp_seconds gauge"; echo "afrotransact_r2_backup_last_run_timestamp_seconds $now"
    [ "$failed" -ne 0 ] || { echo "# TYPE afrotransact_r2_backup_last_success_timestamp_seconds gauge"; echo "afrotransact_r2_backup_last_success_timestamp_seconds $now"; }
  } | curl -fsS -m 15 --retry 2 --data-binary @- "${PUSHGATEWAY_URL%/}/metrics/job/r2_backup/env/$BACKUP_ENV" >/dev/null 2>&1 \
    || echo "warning: pushgateway push failed" >&2
fi
if [ "$failed" -ne 0 ]; then ping_hc /fail; echo "r2 backup finished WITH FAILURES" >&2; exit 1; fi
ping_hc
echo "r2 backup complete at $STAMP: media compared, $kyc_new new or changed kyc objects"
