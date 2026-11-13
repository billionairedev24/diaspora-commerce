#!/usr/bin/env bash
# S380 / F12: the R2 -> B2 copy and its restore drill, against a LOCAL MinIO pair (one plays R2 with a media and a kyc bucket, the other
# plays B2). Runs infra/k8s/platform/lean/backup/r2-backup.sh and infra/ops/backup/r2-restore{,-drill}.sh for real inside an alpine
# container (apk adds rclone and age, so it needs network access to the Alpine mirrors). Needs docker. No cloud credentials.
#   bash tools/ops/test/r2-backup.test.sh
# Variables: MINIO_IMAGE (default pgsty/minio:latest, any MinIO image works), ALPINE_IMAGE (default alpine:3.22).
set -u
REAL=$(cd "$(dirname "$0")/../../.." && pwd)
MINIO_IMAGE="${MINIO_IMAGE:-pgsty/minio:latest}"
ALPINE_IMAGE="${ALPINE_IMAGE:-alpine:3.22}"
command -v docker >/dev/null && docker info >/dev/null 2>&1 || { echo "skip: docker is not available"; exit 0; }
N="r2bk$$"
cleanup() { docker rm -f "$N-r2" "$N-b2" >/dev/null 2>&1; docker network rm "$N" >/dev/null 2>&1; }
trap cleanup EXIT
docker network create "$N" >/dev/null || exit 1
for side in r2 b2; do
  docker run -d --name "$N-$side" --network "$N" -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio-secret-1 \
    "$MINIO_IMAGE" server /data >/dev/null || { echo "FAIL cannot start $MINIO_IMAGE"; exit 1; }
done

INNER=$(cat <<'EOS'
set -eu
apk add --no-cache rclone age >/dev/null
export RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Minio RCLONE_CONFIG_R2_ENDPOINT=http://R2HOST:9000 \
  RCLONE_CONFIG_R2_ACCESS_KEY_ID=minio RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=minio-secret-1
export RCLONE_CONFIG_B2_TYPE=s3 RCLONE_CONFIG_B2_PROVIDER=Minio RCLONE_CONFIG_B2_ENDPOINT=http://B2HOST:9000 \
  RCLONE_CONFIG_B2_ACCESS_KEY_ID=minio RCLONE_CONFIG_B2_SECRET_ACCESS_KEY=minio-secret-1
i=0; until rclone mkdir r2:media-bucket 2>/dev/null && rclone mkdir b2:backups 2>/dev/null; do i=$((i+1)); [ $i -lt 30 ] || { echo "FAIL minio not ready"; exit 1; }; sleep 1; done
rclone mkdir r2:kyc-bucket
age-keygen -o /tmp/age.key 2>/dev/null
export AGE_RECIPIENT="$(age-keygen -y /tmp/age.key)" AGE_IDENTITY_FILE=/tmp/age.key
export MEDIA_SRC=r2:media-bucket KYC_SRC=r2:kyc-bucket DEST=b2:backups SEED=1
sh /repo/infra/ops/backup/r2-restore-drill.sh
# More properties of the copy itself.
export WORK=/tmp/w; mkdir -p $WORK; SKIP_APK=1 sh /repo/infra/k8s/platform/lean/backup/r2-backup.sh >/dev/null
echo hello > /tmp/doc.txt
rclone copyto /tmp/doc.txt r2:kyc-bucket/kyc-document/orgX/passport.pdf
SKIP_APK=1 sh /repo/infra/k8s/platform/lean/backup/r2-backup.sh | grep -q 'kyc_new=1' && echo "ok   an incremental run copies only the new kyc object" || { echo "FAIL incremental kyc copy"; exit 1; }
SKIP_APK=1 sh /repo/infra/k8s/platform/lean/backup/r2-backup.sh | grep -q 'kyc_new=0' && echo "ok   an unchanged bucket copies nothing" || { echo "FAIL second run copied again"; exit 1; }
rclone lsf -R b2:backups/r2/kyc | grep -q 'passport' && { echo "FAIL a kyc file name is readable in B2"; exit 1; } || echo "ok   no kyc file name appears in B2"
rclone cat b2:backups/r2/kyc/objects/$(ls /dev/null >/dev/null; printf 'kyc-document/orgX/passport.pdf' | sha256sum | cut -d' ' -f1).path.age | grep -q orgX && { echo "FAIL path not encrypted"; exit 1; } || echo "ok   the stored key is encrypted"
# Deleting in the source must not delete the copy.
rclone deletefile r2:kyc-bucket/kyc-document/orgX/passport.pdf
SKIP_APK=1 sh /repo/infra/k8s/platform/lean/backup/r2-backup.sh >/dev/null
sh /repo/infra/ops/backup/r2-restore.sh kyc kyc-document/orgX/passport.pdf /tmp/passport.out >/dev/null
[ "$(cat /tmp/passport.out)" = hello ] && echo "ok   a kyc object deleted from the source is still restorable" || { echo "FAIL deleted object not restorable"; exit 1; }
# A corrupted copy fails the checksum.
dh=$(printf 'kyc-document/org1/2026/11/drill-id.pdf' | sha256sum | cut -d' ' -f1)
f=$(rclone lsf b2:backups/r2/kyc/objects | grep "^$dh\.[0-9a-f]\{16\}\.age$")
head -c 10 /dev/urandom > /tmp/junk; rclone copyto /tmp/junk b2:backups/r2/kyc/objects/$f
if sh /repo/infra/ops/backup/r2-restore.sh kyc kyc-document/org1/2026/11/drill-id.pdf /tmp/x.out >/dev/null 2>&1; then echo "FAIL corrupted copy accepted"; exit 1; else echo "ok   a corrupted copy is refused"; fi
# A missing object in B2 makes the next night fail (and so alert).
rclone deletefile b2:backups/r2/kyc/objects/$f
if SKIP_APK=1 sh /repo/infra/k8s/platform/lean/backup/r2-backup.sh >/tmp/missing.log 2>&1; then
  echo "FAIL a missing B2 object went unnoticed"; grep -v NOTICE /tmp/missing.log | tail -5; rclone cat b2:backups/r2/kyc/_manifest/latest | cut -c1-70; exit 1
else echo "ok   a missing B2 object fails the run"; fi
echo "ALL PASSED"
EOS
)
INNER=${INNER//R2HOST/$N-r2}; INNER=${INNER//B2HOST/$N-b2}
docker run --rm --network "$N" -v "$REAL:/repo:ro" "$ALPINE_IMAGE" sh -c "$INNER" 2>&1 | tee /tmp/r2-backup-test.$$ | sed 's/^/  /'
grep -q "ALL PASSED" /tmp/r2-backup-test.$$; rc=$?
rm -f /tmp/r2-backup-test.$$
exit $rc
