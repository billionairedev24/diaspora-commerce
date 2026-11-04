#!/usr/bin/env bash
# S268: pin every Dockerfile base image by digest.
#
#   tools/security/pin-base-images.sh           rewrite  FROM image:tag  ->  FROM image:tag@sha256:<index digest> (skips pinned lines)
#   tools/security/pin-base-images.sh --update  also re-resolve lines that are already pinned (bump to the tag's current digest)
#   tools/security/pin-base-images.sh --check   exit 1 if any FROM in java/ or go/services/ is not digest-pinned
#
# The digest is the multi-arch image index digest, resolved from the registry (Docker Hub, gcr.io, ghcr.io) with an
# anonymous token.
# Needs curl and python3 (JSON). Renovate/Dependabot keep the digests fresh afterwards (the tag stays for readability).
set -euo pipefail
cd "$(dirname "$0")/../.."

mode=pin
[ "${1:-}" = "--check" ] && mode=check
[ "${1:-}" = "--update" ] && mode=update

ACCEPT='application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json'

# split_image <image> -> sets reg and repo (Docker Hub official images live under library/)
split_image() {
  local first="${1%%/*}"
  case "$first" in
    *.*|*:*) reg=$first; repo=${1#*/} ;;
    *) reg=registry-1.docker.io; if [[ "$1" == */* ]]; then repo=$1; else repo="library/$1"; fi ;;
  esac
}

digest_of() { # image tag
  local reg repo token="" auth_url="" hdr
  split_image "$1"
  case "$reg" in
    registry-1.docker.io) auth_url="https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull" ;;
    gcr.io)               auth_url="https://gcr.io/v2/token?service=gcr.io&scope=repository:${repo}:pull" ;;
    ghcr.io)              auth_url="https://ghcr.io/token?scope=repository:${repo}:pull" ;;
  esac
  if [ -n "$auth_url" ]; then
    token=$(curl -fsS --retry 6 --retry-delay 10 --retry-all-errors "$auth_url" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("token") or d.get("access_token") or "")')
  fi
  hdr=$(curl -fsSI --retry 6 --retry-delay 10 --retry-all-errors -H "Accept: ${ACCEPT}" ${token:+-H "Authorization: Bearer ${token}"} "https://${reg}/v2/${repo}/manifests/$2" \
        | tr -d '\r' | awk 'tolower($1)=="docker-content-digest:" {print $2}')
  [ -n "$hdr" ] || { echo "no digest for $1:$2" >&2; return 1; }
  echo "$hdr"
}

status=0
CACHE=$(mktemp -d); trap 'rm -rf "$CACHE"' EXIT # one lookup per image:tag keeps Docker Hub's anonymous rate limit happy
re='^FROM[[:space:]]+([^[:space:]@]+)(@sha256:[0-9a-f]{64})?([[:space:]].*)?$'
while IFS= read -r file; do
  tmp=$(mktemp)
  while IFS= read -r line; do
    if [[ "$line" =~ $re ]]; then
      ref="${BASH_REMATCH[1]}"; pinned="${BASH_REMATCH[2]}"; rest="${BASH_REMATCH[3]:-}"
      case "$ref" in scratch|\$*|*'${'*) printf '%s\n' "$line" >>"$tmp"; continue ;; esac
      if [ "$mode" = check ]; then
        if [ -z "$pinned" ]; then echo "unpinned: $file: $ref" >&2; status=1; fi
        printf '%s\n' "$line" >>"$tmp"; continue
      fi
      if [ -n "$pinned" ] && [ "$mode" = pin ]; then printf '%s\n' "$line" >>"$tmp"; continue; fi
      image="${ref%:*}"; tag="${ref##*:}"
      if [ "$image" = "$ref" ] || [[ "$tag" == */* ]]; then image="$ref"; tag=latest; fi
      key="${CACHE}/$(printf '%s' "${image}:${tag}" | tr '/:' '__')"
      if [ -s "$key" ]; then d=$(cat "$key"); else d=$(digest_of "$image" "$tag") || { status=1; printf '%s\n' "$line" >>"$tmp"; continue; }; printf '%s' "$d" >"$key"; fi
      printf 'FROM %s:%s@%s%s\n' "$image" "$tag" "$d" "$rest" >>"$tmp"
    else
      printf '%s\n' "$line" >>"$tmp"
    fi
  done <"$file"
  if [ "$mode" != check ]; then cat "$tmp" >"$file"; fi
  rm -f "$tmp"
done < <(find java go/services -maxdepth 2 -name Dockerfile | sort)
exit $status
