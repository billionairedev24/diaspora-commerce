#!/usr/bin/env bash
# The OVHcloud API from the scripts (S-161), with the credentials the owner already has for Terraform: an application
# key set (OVH_APPLICATION_KEY / OVH_APPLICATION_SECRET / OVH_CONSUMER_KEY, signed requests) or an OAuth2 service
# account (OVH_CLIENT_ID / OVH_CLIENT_SECRET). Sourced after common.sh and ovh_location, never run. bash 3.2 compatible.
# No credential ever goes on a command line (curl reads its headers from stdin) or into the output.
#
#   ovh_api_init                      once per script, NOT in a subshell: the server clock offset or the OAuth2 token
#   ovh_api METHOD PATH [JSON]        → the response body; HTTP ≥ 300 → the API's message on stderr, status 1
#   state_keys                        the Terraform state's Public Cloud user and its S3 key, found or created:
#                                     STATE_USER_ID, STATE_ACCESS_KEY, STATE_SECRET_KEY
#   state_user_delete                 deletes that user (its S3 keys go with it)
#   project_find DESCRIPTION          → the id of the account's live Public Cloud project of that description, or nothing
#   state_bucket_find                 → the name of a afrotransact-tfstate-* bucket already in the project (one made by
#                                       hand is adopted), or nothing
#
# The state user is the project's user whose description is OVH_STATE_USER [terraform-state], role
# objectstore_operator. Re-runs find it by that description and reuse its first S3 key (the API hands an existing
# key's secret back: POST …/s3Credentials/{access}/secret), so no user or key piles up — on a Mac, a runner or both.

OVH_STATE_USER=${OVH_STATE_USER:-terraform-state}
OVH_API_BASE='' OVH_API_DELTA=0 OVH_API_TOKEN=''

cfg_q() { printf '%s' "$1" | sed 's/[\\"]/\\&/g'; }   # a value inside a curl config line's quotes

ovh_api_has_creds() { [ -n "${OVH_APPLICATION_KEY:-}" ] || [ -n "${OVH_CLIENT_ID:-}" ]; }

ovh_api_init() {
  [ -z "$OVH_API_BASE" ] || return 0
  need curl jq openssl
  OVH_API_BASE=$(loc_get api_url)
  if [ -n "${OVH_APPLICATION_KEY:-}" ]; then
    [ -n "${OVH_APPLICATION_SECRET:-}" ] && [ -n "${OVH_CONSUMER_KEY:-}" ] \
      || die "OVH_APPLICATION_KEY needs OVH_APPLICATION_SECRET and OVH_CONSUMER_KEY (the token page, docs/runbooks/ovh-vps.md, step 3)"
    local t
    t=$(curl -fsS --max-time 20 "$OVH_API_BASE/auth/time") || die "could not reach $OVH_API_BASE/auth/time"
    case "$t" in ('' | *[!0-9]*) die "unexpected answer from $OVH_API_BASE/auth/time" ;; esac
    OVH_API_DELTA=$((t - $(date +%s)))   # signatures carry the server's time
  elif [ -n "${OVH_CLIENT_ID:-}" ]; then
    [ -n "${OVH_CLIENT_SECRET:-}" ] || die "OVH_CLIENT_ID needs OVH_CLIENT_SECRET"
    OVH_API_TOKEN=$(printf 'data = "grant_type=client_credentials"\ndata = "scope=all"\ndata-urlencode = "client_id=%s"\ndata-urlencode = "client_secret=%s"\n' \
        "$(cfg_q "$OVH_CLIENT_ID")" "$(cfg_q "$OVH_CLIENT_SECRET")" \
      | curl -fsS --max-time 30 -K - "$(loc_get oauth2_token_url)" | jq -r '.access_token // empty') || true
    [ -n "$OVH_API_TOKEN" ] || die "the OAuth2 service account was refused at $(loc_get oauth2_token_url) (OVH_CLIENT_ID / OVH_CLIENT_SECRET of the $(loc_get site) account?)"
  else
    die "no OVHcloud API credentials: OVH_APPLICATION_KEY/SECRET + OVH_CONSUMER_KEY, or OVH_CLIENT_ID/SECRET (docs/runbooks/ovh-vps.md, step 3)"
  fi
}

ovh_api() {
  local method=$1 path=$2 body=${3:-} url out code ts sig
  url="$OVH_API_BASE$path"
  local data=()
  [ -z "$body" ] || data=(--data-binary "$body")
  out=$(
    if [ -n "$OVH_API_TOKEN" ]; then
      printf 'header = "Authorization: Bearer %s"\n' "$(cfg_q "$OVH_API_TOKEN")"
    else
      ts=$(($(date +%s) + OVH_API_DELTA))
      sig=$(printf '%s+%s+%s+%s+%s+%s' "$OVH_APPLICATION_SECRET" "$OVH_CONSUMER_KEY" "$method" "$url" "$body" "$ts" \
        | openssl sha1 | awk '{ print $NF }')
      # shellcheck disable=SC2016 # "$1$" is the signature's literal prefix
      printf 'header = "X-Ovh-Application: %s"\nheader = "X-Ovh-Consumer: %s"\nheader = "X-Ovh-Timestamp: %s"\nheader = "X-Ovh-Signature: $1$%s"\n' \
        "$(cfg_q "$OVH_APPLICATION_KEY")" "$(cfg_q "$OVH_CONSUMER_KEY")" "$ts" "$sig"
    fi | curl -sS --max-time 60 -K - -X "$method" -H 'Content-Type: application/json' ${data[@]+"${data[@]}"} \
      -w '\n%{http_code}' "$url"
  ) || { echo "OVHcloud API $method $path: no answer" >&2; return 1; }
  code=${out##*$'\n'}
  out=${out%$'\n'*}
  case "$code" in
    (2??) printf '%s\n' "$out" ;;
    (*)
      echo "OVHcloud API $method $path: HTTP $code — $(printf '%s' "$out" | jq -r '.message // empty' 2>/dev/null)" >&2
      return 1 ;;
  esac
}

# state_user_id → the id of the oldest live user described $OVH_STATE_USER, or nothing.
state_user_id() {
  local users
  users=$(ovh_api GET "/cloud/project/$OVH_SERVICE_NAME/user") || return 1
  printf '%s' "$users" | jq -r --arg d "$OVH_STATE_USER" \
    '[.[] | select(.description == $d and .status != "deleting" and .status != "deleted")] | sort_by(.id) | .[0].id // empty'
}

state_keys() {
  : "${OVH_SERVICE_NAME:?OVH_SERVICE_NAME: the Public Cloud project id}"
  local p="/cloud/project/$OVH_SERVICE_NAME/user" r status i=0
  STATE_USER_ID=$(state_user_id) || die "could not list the project's users (OVH_SERVICE_NAME, and the token's GET /cloud/* right?)"
  if [ -z "$STATE_USER_ID" ]; then
    r=$(ovh_api POST "$p" "$(jq -cn --arg d "$OVH_STATE_USER" '{description: $d, role: "objectstore_operator"}')") \
      || die "could not create the Public Cloud user $OVH_STATE_USER (the token's POST /cloud/* right?)"
    STATE_USER_ID=$(printf '%s' "$r" | jq -r '.id // empty')
    [ -n "$STATE_USER_ID" ] || die "the API created no user id for $OVH_STATE_USER"
    echo "   Public Cloud user $OVH_STATE_USER created (id $STATE_USER_ID, role ObjectStore operator)" >&2
  else
    echo "   Public Cloud user $OVH_STATE_USER exists (id $STATE_USER_ID): reused" >&2
  fi
  # A new user is "creating" for a few seconds; S3 keys need it "ok".
  while :; do
    r=$(ovh_api GET "$p/$STATE_USER_ID") || die "could not read the user $STATE_USER_ID"
    status=$(printf '%s' "$r" | jq -r '.status // empty')
    [ "$status" != ok ] || break
    i=$((i + 1))
    [ "$i" -le "${OVH_API_WAIT_TRIES:-60}" ] || die "the user $OVH_STATE_USER is still '$status' after 5 minutes (Public Cloud › Users & Roles)"
    sleep "${OVH_API_WAIT_SECONDS:-5}"
  done
  printf '%s' "$r" | jq -e '[.roles[]?.name] | index("objectstore_operator")' >/dev/null \
    || echo "warning: the user $OVH_STATE_USER has no ObjectStore operator role — give it one (Public Cloud › Users & Roles › … › Edit roles)" >&2
  r=$(ovh_api GET "$p/$STATE_USER_ID/s3Credentials") || die "could not list the S3 keys of $OVH_STATE_USER"
  STATE_ACCESS_KEY=$(printf '%s' "$r" | jq -r 'sort_by(.access) | .[0].access // empty')
  if [ -n "$STATE_ACCESS_KEY" ]; then
    r=$(ovh_api POST "$p/$STATE_USER_ID/s3Credentials/$STATE_ACCESS_KEY/secret") || die "could not read the secret of the S3 key of $OVH_STATE_USER"
    STATE_SECRET_KEY=$(printf '%s' "$r" | jq -r '.secret // empty')
    echo "   its S3 key …${STATE_ACCESS_KEY: -4}: reused" >&2
  else
    r=$(ovh_api POST "$p/$STATE_USER_ID/s3Credentials") || die "could not create an S3 key for $OVH_STATE_USER"
    STATE_ACCESS_KEY=$(printf '%s' "$r" | jq -r '.access // empty')
    STATE_SECRET_KEY=$(printf '%s' "$r" | jq -r '.secret // empty')
    echo "   its S3 key …${STATE_ACCESS_KEY: -4}: created" >&2
  fi
  [ -n "$STATE_ACCESS_KEY" ] && [ -n "$STATE_SECRET_KEY" ] || die "the API returned no S3 key for $OVH_STATE_USER"
}

state_user_delete() {
  : "${OVH_SERVICE_NAME:?OVH_SERVICE_NAME: the Public Cloud project id}"
  local uid
  uid=$(state_user_id) || return 1
  if [ -z "$uid" ]; then echo "no Public Cloud user $OVH_STATE_USER"; return 0; fi
  ovh_api DELETE "/cloud/project/$OVH_SERVICE_NAME/user/$uid" >/dev/null || return 1
  echo "Public Cloud user $OVH_STATE_USER (id $uid) and its S3 keys deleted"
}

project_find() {
  local ids id r
  ids=$(ovh_api GET /cloud/project | jq -r '.[]') || return 1
  for id in $ids; do
    r=$(ovh_api GET "/cloud/project/$id") || continue
    if printf '%s' "$r" | jq -e --arg d "$1" '.description == $d and (.status == "ok" or .status == "creating")' >/dev/null; then
      printf '%s\n' "$id"
      return 0
    fi
  done
}

state_bucket_find() {
  ovh_api GET "/cloud/project/$OVH_SERVICE_NAME/region/$(loc_get storage_region)/storage" 2>/dev/null \
    | jq -r '[.[]?.name | select(startswith("afrotransact-tfstate-"))] | sort | .[0] // empty' 2>/dev/null || true
}
