#!/usr/bin/env bash
# osv-scanner gate (F7, F9): fails on any HIGH or CRITICAL advisory (CVSS >= 7) and on advisories with no severity at all.
# Accepted risks live in tools/security/osv-scanner.toml (reason, owner, expiry: tools/security/check_ignores.py).
#
#   tools/security/osv-gate.sh -r .                          # every lockfile in the repo (security.yml)
#   tools/security/osv-gate.sh --lockfile pnpm-lock.yaml     # the web workspace (_web-app.yml)
#
# Arguments are passed to `osv-scanner scan source`. Writes osv.json (and osv.sarif unless OSV_SARIF=0) in the cwd.
# Never swallows a scanner failure: exit code 1 from osv-scanner means "findings" and is judged by the threshold;
# any other non-zero exit (network, parse) fails the job.
#
# The binary is downloaded from the pinned release and verified against a SHA-256 kept in this file, so a tampered or
# re-uploaded release asset fails the job. OSV_SCANNER=<path> uses a binary you already have (local runs; no checksum).
set -euo pipefail

OSV_SCANNER_VERSION="${OSV_SCANNER_VERSION:-v2.6.0}"
# sha256 of osv-scanner_linux_amd64 for v2.6.0 (from the release's osv-scanner_SHA256SUMS, cross-checked against the
# asset). Bump both together.
OSV_SCANNER_LINUX_AMD64_SHA256="ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
THRESHOLD="${OSV_FAIL_CVSS:-7}"

bin="${OSV_SCANNER:-}"
if [[ -z "$bin" ]]; then
  tmp="${RUNNER_TEMP:-$(mktemp -d)}"
  bin="$tmp/osv-scanner"
  if [[ "$OSV_SCANNER_VERSION" != "v2.6.0" ]]; then
    echo "osv-gate: bump OSV_SCANNER_LINUX_AMD64_SHA256 together with OSV_SCANNER_VERSION" >&2
    exit 2
  fi
  curl -sSfL --retry 5 --retry-all-errors -o "$bin" \
    "https://github.com/google/osv-scanner/releases/download/${OSV_SCANNER_VERSION}/osv-scanner_linux_amd64"
  echo "${OSV_SCANNER_LINUX_AMD64_SHA256}  ${bin}" | sha256sum -c -
  chmod +x "$bin"
fi

cfg="$ROOT/tools/security/osv-scanner.toml"
set +e
if [[ "${OSV_SARIF:-1}" != "0" ]]; then
  "$bin" scan source --config "$cfg" --format sarif --output-file osv.sarif "$@"
  rc=$?
  [[ $rc -le 1 ]] || { echo "osv-gate: osv-scanner failed (exit $rc)" >&2; exit "$rc"; }
fi
"$bin" scan source --config "$cfg" --format json --output-file osv.json "$@"
rc=$?
set -e
[[ $rc -le 1 ]] || { echo "osv-gate: osv-scanner failed (exit $rc)" >&2; exit "$rc"; }
[[ -s osv.json ]] || { echo "osv-gate: no osv.json produced" >&2; exit 2; }

rows=$(jq -r '.results[]?.packages[]? as $p | $p.groups[]? | "\(.max_severity // "")\t\($p.package.name)@\($p.package.version)\t\(.ids | join(","))"' osv.json | sort -rn)
if [[ -n "$rows" ]]; then
  echo "osv-scanner findings (max CVSS, package, advisories):"
  echo "$rows" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
blocking=$(jq --argjson t "$THRESHOLD" '[.results[]?.packages[]?.groups[]? | select(((.max_severity // "") | tonumber? // -1) >= $t or ((.max_severity // "") == ""))] | length' osv.json)
if [[ "$blocking" -gt 0 ]]; then
  echo "osv-gate: $blocking advisory group(s) at CVSS >= $THRESHOLD or with no severity. Upgrade, add a pnpm/maven override," >&2
  echo "          or record a time-boxed ignore (reason, Owner, expiry) in tools/security/osv-scanner.toml." >&2
  exit 1
fi
echo "osv-gate: no advisories at CVSS >= $THRESHOLD"
