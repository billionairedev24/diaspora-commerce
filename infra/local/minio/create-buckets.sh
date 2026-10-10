#!/usr/bin/env bash
# Creates the local S3 buckets. kyc-evidence has object lock (WORM) with a default GOVERNANCE retention.
set -euo pipefail
mc alias set local http://minio:9000 "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
for b in media labels exports; do
  mc mb --ignore-existing "local/${b}"
done
mc mb --ignore-existing --with-lock local/kyc-evidence
mc retention set --default GOVERNANCE 30d local/kyc-evidence
# Public read for rendered media (local only); everything else stays private.
mc anonymous set download local/media
mc ls local
