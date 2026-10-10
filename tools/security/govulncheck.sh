#!/usr/bin/env bash
# govulncheck for every Go module (go/pkg + go/services/*): reports only vulnerabilities whose vulnerable symbols
# are actually reachable from our code (call-graph analysis), so findings are actionable.
#
#   tools/security/govulncheck.sh                    # all modules, text output; exit 1 if any module is affected
#   tools/security/govulncheck.sh search cart        # some modules (service names; "pkg" for go/pkg)
#   GOVULNCHECK_FORMAT=sarif tools/security/govulncheck.sh search > govulncheck.sarif   # one module per SARIF file
#
# Needs the Go vulnerability database: vuln.go.dev by default; GOVULNDB=<url> picks another source, e.g. its
# backing bucket https://storage.googleapis.com/go-vulndb or a file:///path mirror. Install:
#   go install golang.org/x/vuln/cmd/govulncheck@latest
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GOVULNCHECK="${GOVULNCHECK:-$(command -v govulncheck || echo "$HOME/go/bin/govulncheck")}"
FORMAT="${GOVULNCHECK_FORMAT:-text}"
DB="${GOVULNDB:-https://vuln.go.dev}"

mods=()
if [[ $# -gt 0 ]]; then
  for m in "$@"; do [[ "$m" == "pkg" ]] && mods+=("$ROOT/go/pkg") || mods+=("$ROOT/go/services/$m"); done
else
  mods=("$ROOT/go/pkg")
  for d in "$ROOT"/go/services/*/; do [[ -f "$d/go.mod" ]] && mods+=("${d%/}"); done
fi

failed=()
for mod in "${mods[@]}"; do
  name="${mod#"$ROOT"/}"
  [[ "$FORMAT" == "text" ]] && echo "==> $name" >&2
  # Each module pins `toolchain go1.27.2`; GOTOOLCHAIN=auto lets `go` (used by govulncheck) switch to it.
  ( cd "$mod" && GOTOOLCHAIN="${GOTOOLCHAIN:-auto}" "$GOVULNCHECK" -db "$DB" -format "$FORMAT" ./... )
  rc=$?
  # text mode: exit 3 = vulnerabilities found; other non-zero = error.
  [[ $rc -ne 0 ]] && failed+=("$name (exit $rc)")
done

if [[ ${#failed[@]} -gt 0 ]]; then
  printf 'govulncheck: affected or failed: %s\n' "${failed[*]}" >&2
  exit 1
fi
[[ "$FORMAT" == "text" ]] && echo "govulncheck: no reachable vulnerabilities in ${#mods[@]} module(s)" >&2
exit 0
