#!/usr/bin/env bash
# Terraform/OpenTofu state of the single-server environment in an OVHcloud Object Storage bucket (S3 backend), so the pipeline
# (.github/workflows/ovh-vps.yml) and a laptop share one state and none ever sits in the repository, an artifact or a
# runner's disk after the job. Nothing is made by hand: the state's S3 user and key come from the OVHcloud API credentials
# (tools/ovh/api.sh), the bucket from those keys.
#
#   tools/ovh/tf-state.sh keys       the state's S3 keys (TF_STATE_ACCESS_KEY/SECRET_KEY when they work, else the Public Cloud
#                                    user terraform-state, role ObjectStore operator, found or created through the API). In the
#                                    pipeline they go masked into $GITHUB_ENV; on a laptop as export lines for eval "$(...)"
#   tools/ovh/tf-state.sh ensure     create the state bucket once (idempotent; versioning on) - needs the aws CLI
#   tools/ovh/tf-state.sh init       tofu init of infra/ovh-vps/terraform on that bucket
#   tools/ovh/tf-state.sh teardown   after destroy: the state (every version), the bucket, then the state user - last
#
# Environment: TF_STATE_BUCKET, TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY (or AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY),
# OVH_LOCATION [us-east-va], OVH_SERVICE_NAME and the API credentials for the fallbacks. No state locking (OVHcloud's support
# for S3 conditional writes is unverified): the workflow's concurrency group is the lock; do not run tofu from a laptop while a
# pipeline run is going.
set -euo pipefail
. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/api.sh"

cmd=${1:-}
[ -n "${OVH_LOCATION:-}" ] || OVH_LOCATION=$(env_get OVH_LOCATION)
[ -n "${OVH_SERVICE_NAME:-}" ] || OVH_SERVICE_NAME=$(env_get OVH_SERVICE_NAME)
[ -n "${TF_STATE_BUCKET:-}" ] || TF_STATE_BUCKET=$(env_get TF_STATE_BUCKET)
ovh_location "${OVH_LOCATION:-}"
TF_STATE_ENDPOINT=${TF_STATE_ENDPOINT:-$(loc_get s3_endpoint)}
TF_STATE_REGION=${TF_STATE_REGION:-$(loc_get s3_region)}
STATE_KEY="ovh/vps/terraform.tfstate"
if [ -n "${TF_STATE_ACCESS_KEY:-}" ]; then export AWS_ACCESS_KEY_ID="$TF_STATE_ACCESS_KEY"; fi
if [ -n "${TF_STATE_SECRET_KEY:-}" ]; then export AWS_SECRET_ACCESS_KEY="$TF_STATE_SECRET_KEY"; fi
# Third-party S3: send checksums only when an operation requires them (newer AWS CLIs add them by default).
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

have_keys() { [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ]; }

# from_api — the state user's keys through the OVHcloud API (found or created; tools/ovh/api.sh) into AWS_*.
from_api() {
  ovh_api_has_creds || return 1
  [ -n "${OVH_SERVICE_NAME:-}" ] || die "OVH_SERVICE_NAME (the Public Cloud project id) is needed to find the state user"
  echo "state user, from the OVHcloud API ($(loc_get site) account, project $OVH_SERVICE_NAME):" >&2
  ovh_api_init
  state_keys
  if [ -n "${GITHUB_ENV:-}" ]; then echo "::add-mask::$STATE_SECRET_KEY"; fi
  export AWS_ACCESS_KEY_ID="$STATE_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$STATE_SECRET_KEY"
}

need_keys() {
  have_keys || from_api \
    || die "no keys for the state bucket: TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY, or the OVHcloud API credentials that make them (docs/runbooks/ovh-vps.md, step 4)"
}

s3() { aws --endpoint-url "$TF_STATE_ENDPOINT" --region "$TF_STATE_REGION" "$@"; }

keys() {
  if have_keys; then
    # Keys from the environment that no longer work (the state user was deleted by a destroy, or replaced) are
    # replaced by the API's when the API credentials are there.
    if ovh_api_has_creds && command -v aws >/dev/null 2>&1 && ! s3 s3api list-buckets >/dev/null 2>&1; then
      echo "warning: TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY are refused by $TF_STATE_ENDPOINT — using the state user's keys from the API instead (re-run make ovh-gh-setup to store them)" >&2
      unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
      from_api
    else
      echo "state keys: from TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY" >&2
    fi
  else
    need_keys
  fi
  if [ -n "${GITHUB_ENV:-}" ]; then
    printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY" >>"$GITHUB_ENV"
    echo "state keys: in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY for the next steps of this job (masked)" >&2
  elif [ -t 1 ]; then
    die "keys prints the secret key: use it as  eval \"\$(tools/ovh/tf-state.sh keys)\""
  else
    printf 'export AWS_ACCESS_KEY_ID=%q AWS_SECRET_ACCESS_KEY=%q\n' "$AWS_ACCESS_KEY_ID" "$AWS_SECRET_ACCESS_KEY"
  fi
}

ensure() {
  [ -n "${TF_STATE_BUCKET:-}" ] || { echo "TF_STATE_BUCKET empty: local Terraform state"; return 0; }
  need aws
  need_keys
  if s3 s3api head-bucket --bucket "$TF_STATE_BUCKET" >/dev/null 2>&1; then
    echo "state bucket $TF_STATE_BUCKET exists"
  else
    s3 s3api create-bucket --bucket "$TF_STATE_BUCKET" \
      --create-bucket-configuration "LocationConstraint=$TF_STATE_REGION" >/dev/null \
      || die "could not create $TF_STATE_BUCKET (taken by someone else? pick another TF_STATE_BUCKET)"
    echo "state bucket $TF_STATE_BUCKET created in $OVH_LOCATION ($TF_STATE_ENDPOINT)"
  fi
  # Versioning: an earlier state can be restored after a bad apply (Object Storage › the bucket › versions).
  s3 s3api put-bucket-versioning --bucket "$TF_STATE_BUCKET" --versioning-configuration Status=Enabled >/dev/null \
    || echo "warning: could not turn versioning on for $TF_STATE_BUCKET (turn it on in the console)" >&2
}

init() {
  local TF override flags
  TF=$(tf_bin)
  override="$OVH_TF/backend_override.tf"; flags=(-input=false)
  [ -n "${TF_STATE_BUCKET:-}" ] || die "TF_STATE_BUCKET is empty (the bucket name for the state)"
  need_keys
  printf '%s\n' '# Written by tools/ovh/tf-state.sh (git-ignored): the S3 backend on OVHcloud Object Storage.' \
    'terraform {' '  backend "s3" {}' '}' >"$override"
  conf=$(mktemp); trap 'rm -f "${conf:-}"' EXIT
  # No secret in here: the keys come from AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
  cat >"$conf" <<EOT
bucket                      = "$TF_STATE_BUCKET"
key                         = "$STATE_KEY"
region                      = "$TF_STATE_REGION"
endpoints                   = { s3 = "$TF_STATE_ENDPOINT" }
skip_credentials_validation = true
skip_region_validation      = true
skip_requesting_account_id  = true
skip_metadata_api_check     = true
skip_s3_checksum            = true
use_path_style              = true
EOT
  "$TF" -chdir="$OVH_TF" init "${flags[@]}" -reconfigure -backend-config="$conf"
  echo "state: s3://$TF_STATE_BUCKET/$STATE_KEY"
}

# delete_versions <prefix> — every version and delete marker under the prefix (the bucket is versioned).
delete_versions() {
  local list batch
  while :; do
    list=$(s3 s3api list-object-versions --bucket "$TF_STATE_BUCKET" --prefix "$1" --max-items 1000 --output json) || return 1
    [ -n "$list" ] || return 0
    batch=$(printf '%s' "$list" | jq -c '{Objects: [(.Versions // [])[], (.DeleteMarkers // [])[] | {Key, VersionId}], Quiet: true}')
    [ "$(printf '%s' "$batch" | jq '.Objects | length')" -gt 0 ] || return 0
    s3 s3api delete-objects --bucket "$TF_STATE_BUCKET" --delete "$batch" >/dev/null || return 1
  done
}

teardown() {
  [ -n "${TF_STATE_BUCKET:-}" ] || { echo "TF_STATE_BUCKET empty: nothing to remove"; return 0; }
  need aws jq
  need_keys
  local gone=0
  s3 s3api head-bucket --bucket "$TF_STATE_BUCKET" >/dev/null 2>&1 || { echo "state bucket $TF_STATE_BUCKET is gone already"; gone=1; }
  if [ "$gone" = 0 ]; then
    delete_versions "ovh/" || die "could not delete the state in s3://$TF_STATE_BUCKET (Object Storage > the bucket)"
    echo "state deleted (every version)"
    s3 s3api delete-bucket --bucket "$TF_STATE_BUCKET" || die "could not delete the bucket $TF_STATE_BUCKET"
    echo "state bucket $TF_STATE_BUCKET deleted"
  fi
  # Last: the user whose keys opened the bucket.
  if ovh_api_has_creds && [ -n "${OVH_SERVICE_NAME:-}" ]; then
    ovh_api_init
    state_user_delete || die "could not delete the Public Cloud user $OVH_STATE_USER (Public Cloud > Users & Roles)"
    echo "the environment's TF_STATE_ACCESS_KEY / TF_STATE_SECRET_KEY are now stale: re-run make ovh-gh-setup before the next apply"
  else
    echo "no OVHcloud API credentials: delete the Public Cloud user $OVH_STATE_USER in the console (Users & Roles)"
  fi
}

case "$cmd" in
  keys) keys ;;
  ensure) ensure ;;
  init) init ;;
  teardown) teardown ;;
  *) die "usage: $0 keys | ensure | init | teardown" ;;
esac
