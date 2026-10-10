#!/usr/bin/env bash
# Run Semgrep with the AfroTransact rules (tools/security/semgrep.yml) over the source tree.
#
#   tools/security/run_semgrep.sh                   # our rules only (works offline)
#   tools/security/run_semgrep.sh --registry        # + registry packs p/java p/golang p/typescript p/react p/secrets (network)
#   tools/security/run_semgrep.sh --sarif out.sarif # also write SARIF (GitHub code scanning)
#   tools/security/run_semgrep.sh --self-test       # check every rule against tools/security/semgrep-tests fixtures
#   tools/security/run_semgrep.sh -- java/services/catalog go/services/search   # limit to paths
#
# Exit 1 when any ERROR-severity finding exists; WARNING findings are printed but do not fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SEMGREP="${SEMGREP:-semgrep}"
RULES="$ROOT/tools/security/semgrep.yml"
configs=(--config "$RULES")
sarif=()
paths=()
self_test=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --registry) configs+=(--config p/java --config p/golang --config p/typescript --config p/react --config p/secrets) ;;
    --sarif) sarif=(--sarif-output "${2:?--sarif needs a file}"); shift ;;
    --self-test) self_test=1 ;;
    --) shift; paths=("$@"); break ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
common=(--metrics=off --disable-version-check --quiet)

if [[ $self_test -eq 1 ]]; then
  # Fixtures annotate the line below with `ruleid: <id>` (must match) or `ok: <id>` (must not match).
  cd "$ROOT/tools/security/semgrep-tests"
  "$SEMGREP" scan "${common[@]}" --config "$RULES" --json --output "$tmp/out.json" . >/dev/null
  python3 - "$tmp/out.json" <<'PY'
import glob, json, re, sys
d = json.load(open(sys.argv[1]))
if d["errors"]:
    sys.exit(f"semgrep errors: {d['errors']}")
got = {(r["path"].split("/")[-1], r["start"]["line"], ".".join(r["check_id"].split(".")[-3:])) for r in d["results"]}
want, ok = set(), set()
for f in glob.glob("*.*"):
    for i, line in enumerate(open(f).read().splitlines()):
        m = re.search(r"(ruleid|ok): (\S+)", line)
        if m:
            (want if m.group(1) == "ruleid" else ok).add((f, i + 2, m.group(2)))
missed, false_pos = sorted(want - got), sorted(got & ok)
for x in missed: print("MISSED     ", *x)
for x in false_pos: print("FALSE POS  ", *x)
print(f"semgrep self-test: {len(want)} expected matches, {len(ok)} expected non-matches, {len(missed)} missed, {len(false_pos)} false positives")
sys.exit(1 if missed or false_pos else 0)
PY
  exit $?
fi

[[ ${#paths[@]} -eq 0 ]] && paths=(java go web mobile tests tools)
cd "$ROOT"
set +e
"$SEMGREP" scan "${common[@]}" "${configs[@]}" --text --json-output "$tmp/out.json" "${sarif[@]}" \
  --exclude node_modules --exclude .next --exclude '.next-*' --exclude .open-next --exclude target --exclude dist \
  --exclude storybook-static --exclude src/generated --exclude '*.min.js' --exclude semgrep-tests \
  "${paths[@]}"
rc=$?
set -e
[[ $rc -ne 0 && $rc -ne 1 ]] && exit "$rc"
python3 - "$tmp/out.json" <<'PY'
import collections, json, sys
d = json.load(open(sys.argv[1]))
sev = collections.Counter(r["extra"]["severity"] for r in d["results"])
print(f"semgrep: {sev.get('ERROR', 0)} error(s), {sev.get('WARNING', 0)} warning(s), {sev.get('INFO', 0)} info; {len(d['errors'])} scan error(s)")
sys.exit(1 if sev.get("ERROR") else 0)
PY
