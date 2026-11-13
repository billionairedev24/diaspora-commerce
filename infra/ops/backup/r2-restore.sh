#!/bin/sh
# Restore objects copied by the nightly R2 -> B2 job (infra/k8s/platform/lean/backup/r2-backup.sh) to a local file or any rclone
# path (for example back into an R2 bucket). Run on an OPERATOR machine with rclone (and age for kyc); runbook:
# docs/runbooks/backup-restore-r2.md, drill: infra/ops/backup/r2-restore-drill.sh.
#
#   r2-restore.sh media <object-key> <target>        newest copy of a media object (current/, else the latest changed/ version)
#   r2-restore.sh kyc   <object-key> <target>        newest copy of a KYC object, decrypted with AGE_IDENTITY_FILE and verified
#   r2-restore.sh kyc-list                           decrypted keys of every KYC object in the copy (needs the private key)
#
# Environment: DEST (rclone path of the backup bucket, e.g. b2dest:afrotransact-prod-backups) or B2_KEY_ID B2_APPLICATION_KEY B2_BUCKET
#   B2_ENDPOINT [B2_REGION B2_PREFIX]; AGE_IDENTITY_FILE (the age private key file, kept OFFLINE: copy it here only for the restore and
#   delete the copy afterwards). Nothing is written to the bucket.
set -eu

if [ -z "${DEST:-}" ]; then
  : "${B2_KEY_ID:?}" "${B2_APPLICATION_KEY:?}" "${B2_BUCKET:?}" "${B2_ENDPOINT:?}"
  b2_host="${B2_ENDPOINT#https://}"; b2_host="${b2_host%/}"
  b2_region="${B2_REGION:-$(printf '%s' "$b2_host" | sed -n 's/^s3\.\([^.]*\)\.backblazeb2\.com$/\1/p')}"
  export RCLONE_CONFIG_B2DEST_TYPE=s3 RCLONE_CONFIG_B2DEST_PROVIDER=Other RCLONE_CONFIG_B2DEST_ENDPOINT="https://$b2_host" \
    RCLONE_CONFIG_B2DEST_REGION="$b2_region" RCLONE_CONFIG_B2DEST_ACCESS_KEY_ID="$B2_KEY_ID" RCLONE_CONFIG_B2DEST_SECRET_ACCESS_KEY="$B2_APPLICATION_KEY"
  DEST="b2dest:$B2_BUCKET${B2_PREFIX:+/$B2_PREFIX}"
fi
RC="--s3-no-check-bucket"
[ "${RCLONE_NO_S3_FLAGS:-0}" = 0 ] || RC=""
cmd="${1:-}"

hash_of() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

case "$cmd" in
  media)
    key="${2:?object key}"; target="${3:?target}"
    # shellcheck disable=SC2086
    if rclone copyto "$DEST/r2/media/current/$key" "$target" $RC 2>/dev/null; then echo "restored media object from current/"; exit 0; fi
    # shellcheck disable=SC2086
    last="$(rclone lsf -R --files-only "$DEST/r2/media/changed" $RC 2>/dev/null | grep -F "/$key" | sort | tail -n 1 || true)"
    [ -n "$last" ] || { echo "error: $key is in neither current/ nor changed/" >&2; exit 1; }
    # shellcheck disable=SC2086
    rclone copyto "$DEST/r2/media/changed/$last" "$target" $RC && echo "restored media object from changed/$last"
    ;;
  kyc)
    key="${2:?object key}"; target="${3:?target}"; : "${AGE_IDENTITY_FILE:?the age private key file}"
    h="$(hash_of "$key")"
    # shellcheck disable=SC2086
    c="$(rclone cat "$DEST/r2/kyc/_manifest/latest" $RC | grep -F "$h|" | tail -n 1 | cut -d'|' -f4)"
    if [ -z "$c" ]; then
      # Not in the manifest (the object was deleted from R2 before the manifest was rewritten): newest copy by name.
      # shellcheck disable=SC2086
      c="$(rclone lsf --files-only "$DEST/r2/kyc/objects" $RC | grep "^$h\.[0-9a-f]\{16\}\.age$" | tail -n 1 | cut -d. -f2)"
    fi
    [ -n "$c" ] || { echo "error: no copy of that KYC object" >&2; exit 1; }
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    # shellcheck disable=SC2086
    rclone cat "$DEST/r2/kyc/objects/$h.$c.age" $RC | age -d -i "$AGE_IDENTITY_FILE" > "$tmp"
    got="$(sha256sum "$tmp" | cut -c1-16)"
    [ "$got" = "$c" ] || { echo "error: checksum mismatch after decryption (expected $c, got $got)" >&2; exit 1; }
    case "$target" in *:*) rclone copyto "$tmp" "$target" $RC ;; *) cp "$tmp" "$target" ;; esac
    echo "restored kyc object, sha256 prefix $c verified"
    ;;
  kyc-list)
    : "${AGE_IDENTITY_FILE:?the age private key file}"
    # shellcheck disable=SC2086
    for f in $(rclone lsf --files-only "$DEST/r2/kyc/objects" $RC | grep '\.path\.age$'); do
      rclone cat "$DEST/r2/kyc/objects/$f" $RC | age -d -i "$AGE_IDENTITY_FILE"; printf '\n'
    done
    ;;
  *) echo "usage: r2-restore.sh media <key> <target> | kyc <key> <target> | kyc-list" >&2; exit 2 ;;
esac
