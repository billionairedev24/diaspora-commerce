#!/bin/sh
# Restore drill for the R2 -> B2 copy (S380). Runs the real r2-backup.sh and r2-restore.sh against any pair of S3-compatible
# endpoints through rclone remotes: in tests a local MinIO pair (tools/ops/test/r2-backup.test.sh), in the real drill the live buckets
# via a scratch copy (docs/runbooks/backup-restore-r2.md). It
#   1. seeds one media object and one KYC object in the source (when SEED=1, tests only),
#   2. runs the nightly copy,
#   3. DELETES both objects from the source (the disaster),
#   4. restores each from the copy, byte-compares, and prints how long each restore took (the number the story asks for),
#   5. overwrites the media object and checks the earlier version is kept in changed/.
# Environment: MEDIA_SRC KYC_SRC DEST (rclone paths), AGE_RECIPIENT, AGE_IDENTITY_FILE, SEED (1 = create and delete the sample objects),
#   MEDIA_KEY KYC_KEY (object keys; defaults for the seeded ones), SCRIPTS (dir of r2-backup.sh and r2-restore.sh).
set -eu
: "${MEDIA_SRC:?}" "${KYC_SRC:?}" "${DEST:?}" "${AGE_RECIPIENT:?}" "${AGE_IDENTITY_FILE:?}"
HERE="$(cd "$(dirname "$0")" && pwd)"
BACKUP="${BACKUP_SCRIPT:-$HERE/../../k8s/platform/lean/backup/r2-backup.sh}"
RESTORE="${RESTORE_SCRIPT:-$HERE/r2-restore.sh}"
MEDIA_KEY="${MEDIA_KEY:-product-image/org1/2026/11/drill.jpg}"
KYC_KEY="${KYC_KEY:-kyc-document/org1/2026/11/drill-id.pdf}"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
RC="--s3-no-check-bucket"; [ "${RCLONE_NO_S3_FLAGS:-0}" = 0 ] || RC=""
# milliseconds from the kernel uptime (centisecond resolution; busybox date has no %N), else whole seconds
now_ms() {
  if [ -r /proc/uptime ]; then awk '{printf "%d", $1 * 1000}' /proc/uptime; else echo $(($(date +%s) * 1000)); fi
}
ok() { printf 'ok   %s\n' "$1"; }
die() { printf 'FAIL %s\n' "$1" >&2; exit 1; }

if [ "${SEED:-0}" = 1 ]; then
  head -c 20000 /dev/urandom > "$W/media.bin"; head -c 30000 /dev/urandom > "$W/kyc.bin"
  # shellcheck disable=SC2086
  rclone copyto "$W/media.bin" "$MEDIA_SRC/$MEDIA_KEY" $RC; rclone copyto "$W/kyc.bin" "$KYC_SRC/$KYC_KEY" $RC
else
  # shellcheck disable=SC2086
  rclone copyto "$MEDIA_SRC/$MEDIA_KEY" "$W/media.bin" $RC; rclone copyto "$KYC_SRC/$KYC_KEY" "$W/kyc.bin" $RC
fi
export WORK="$W/work"; mkdir -p "$WORK"
SKIP_APK=1 MEDIA_SRC="$MEDIA_SRC" KYC_SRC="$KYC_SRC" DEST="$DEST" AGE_RECIPIENT="$AGE_RECIPIENT" sh "$BACKUP" >"$W/backup.log" 2>&1 \
  || { cat "$W/backup.log"; die "nightly copy failed"; }
ok "nightly copy ran"

if [ "${SEED:-0}" = 1 ]; then
  # shellcheck disable=SC2086
  rclone deletefile "$MEDIA_SRC/$MEDIA_KEY" $RC; rclone deletefile "$KYC_SRC/$KYC_KEY" $RC
  ok "both objects deleted from the source (simulated disaster)"
fi

t0="$(now_ms)"
DEST="$DEST" sh "$RESTORE" media "$MEDIA_KEY" "$W/media.restored" >/dev/null || die "media restore failed"
t1="$(now_ms)"
cmp -s "$W/media.bin" "$W/media.restored" || die "restored media object differs"
ok "media object restored byte-identical in $((t1 - t0)) ms"
t0="$(now_ms)"
DEST="$DEST" AGE_IDENTITY_FILE="$AGE_IDENTITY_FILE" sh "$RESTORE" kyc "$KYC_KEY" "$W/kyc.restored" >/dev/null || die "kyc restore failed"
t1="$(now_ms)"
cmp -s "$W/kyc.bin" "$W/kyc.restored" || die "restored kyc object differs"
ok "kyc object restored (decrypted, verified) byte-identical in $((t1 - t0)) ms"
DEST="$DEST" AGE_IDENTITY_FILE="$AGE_IDENTITY_FILE" sh "$RESTORE" kyc-list 2>/dev/null | grep -Fxq "$KYC_KEY" || die "kyc-list does not show the key"
ok "kyc-list recovers the original key with the private key"

if [ "${SEED:-0}" = 1 ]; then
  # shellcheck disable=SC2086
  head -c 5000 /dev/urandom > "$W/media2.bin"; rclone copyto "$W/media2.bin" "$MEDIA_SRC/$MEDIA_KEY" $RC
  sleep 1; touch "$W/media2.bin"
  SKIP_APK=1 MEDIA_SRC="$MEDIA_SRC" KYC_SRC="$KYC_SRC" DEST="$DEST" AGE_RECIPIENT="$AGE_RECIPIENT" sh "$BACKUP" >"$W/backup2.log" 2>&1 \
    || { cat "$W/backup2.log"; die "second nightly copy failed"; }
  # shellcheck disable=SC2086
  rclone lsf -R --files-only "$DEST/r2/media/changed" $RC | grep -Fq "$MEDIA_KEY" || die "the overwritten version was not kept in changed/"
  ok "an overwritten media object keeps its previous version in changed/"
fi
echo "drill passed"
