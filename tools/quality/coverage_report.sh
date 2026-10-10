#!/usr/bin/env bash
# Coverage summary + gate for every Java (JaCoCo XML) and Go (cover profile) service.
#
#   tools/quality/coverage_report.sh                     # summarise reports that already exist, gate on thresholds
#   tools/quality/coverage_report.sh --run-go            # first run `go test -short -coverprofile` for each Go module
#   tools/quality/coverage_report.sh --service catalog   # one service (repeatable)
#   tools/quality/coverage_report.sh --no-fail           # report only (exit 0)
#
# Inputs
#   Java: java/{services,libs}/*/target/site/jacoco/jacoco.xml   (written by `./mvnw verify`; includes *IT via failsafe)
#   Go:   $GO_COVER_DIR/<module>.out (default go/.coverage/)      (written by --run-go or by CI: see docs/engineering/testing.md)
#         cmd/ (main wiring) and internal/testkit (test helpers) are excluded from Go totals.
#
# Thresholds (line coverage %, first match wins): env > tools/quality/coverage-thresholds.conf > built-in defaults
#   COVERAGE_MIN_JAVA      total line coverage per Java service                (default 75)
#   COVERAGE_MIN_GO        total statement coverage per Go module              (default 60)
#   COVERAGE_MIN_DOMAIN    Java domain packages (…/domain)                     (default 80)
#   COVERAGE_MIN_DOMAIN_GO Go domain packages (internal/domain)                (default 80)
#   Per-service overrides live in tools/quality/coverage-thresholds.conf as `<service>.<metric>=<pct>`.
#
# Output: a Markdown table on stdout (also appended to $GITHUB_STEP_SUMMARY when set) and coverage-summary.json in
# $COVERAGE_OUT (default: build/coverage). Exit 1 when any service is under its threshold, 2 on usage errors.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONF="${COVERAGE_CONF:-$ROOT/tools/quality/coverage-thresholds.conf}"
GO_COVER_DIR="${GO_COVER_DIR:-$ROOT/go/.coverage}"
OUT_DIR="${COVERAGE_OUT:-$ROOT/build/coverage}"
RUN_GO=0
FAIL=1
SERVICES=()

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-go) RUN_GO=1 ;;
    --no-fail) FAIL=0 ;;
    --service) SERVICES+=("${2:?--service needs a name}"); shift ;;
    --go-cover-dir) GO_COVER_DIR="${2:?}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

wanted() { # $1 = service name
  [[ ${#SERVICES[@]} -eq 0 ]] && return 0
  local s; for s in "${SERVICES[@]}"; do [[ "$s" == "$1" ]] && return 0; done
  return 1
}

if [[ $RUN_GO -eq 1 ]]; then
  command -v go >/dev/null || { echo "go not on PATH" >&2; exit 2; }
  mkdir -p "$GO_COVER_DIR"
  for mod in "$ROOT"/go/pkg "$ROOT"/go/services/*; do
    [[ -f "$mod/go.mod" ]] || continue
    name="$(basename "$mod")"; [[ "$name" == "pkg" ]] && name="go-pkg"
    wanted "$name" || continue
    echo "==> go test -short -coverpkg=./... -coverprofile ($name)" >&2
    # -short skips the Testcontainers *_integration_test.go suites; CI's integration job runs them without -short.
    ( cd "$mod" && go test -short -count=1 -covermode=atomic -coverpkg=./... -coverprofile="$GO_COVER_DIR/$name.out" ./... >&2 ) \
      || { echo "go test failed in $name" >&2; exit 1; }
  done
fi

mkdir -p "$OUT_DIR"
SERVICES_CSV="$(IFS=,; echo "${SERVICES[*]:-}")"

ROOT="$ROOT" CONF="$CONF" GO_COVER_DIR="$GO_COVER_DIR" OUT_DIR="$OUT_DIR" FAIL="$FAIL" SERVICES_CSV="$SERVICES_CSV" \
python3 - <<'PY'
import glob, json, os, re, sys
import xml.etree.ElementTree as ET

root, conf_path = os.environ["ROOT"], os.environ["CONF"]
go_dir, out_dir = os.environ["GO_COVER_DIR"], os.environ["OUT_DIR"]
fail = os.environ["FAIL"] == "1"
only = {s for s in os.environ["SERVICES_CSV"].split(",") if s}

defaults = {"java": 75.0, "go": 60.0, "domain": 80.0, "domain_go": 80.0}
conf = {}
if os.path.exists(conf_path):
    for line in open(conf_path):
        line = line.split("#", 1)[0].strip()
        if line and "=" in line:
            k, v = (x.strip() for x in line.split("=", 1))
            conf[k] = float(v)
env = {"java": "COVERAGE_MIN_JAVA", "go": "COVERAGE_MIN_GO", "domain": "COVERAGE_MIN_DOMAIN", "domain_go": "COVERAGE_MIN_DOMAIN_GO"}

def threshold(service, metric):
    if os.environ.get(env[metric]):
        return float(os.environ[env[metric]])
    return conf.get(f"{service}.{metric}", conf.get(f"default.{metric}", defaults[metric]))

def pct(cov, total):
    return None if total == 0 else 100.0 * cov / total

rows = []

# ---- Java: JaCoCo XML (LINE counters; report-level total + packages whose name ends in /domain)
for xml in sorted(glob.glob(os.path.join(root, "java", "*", "*", "target", "site", "jacoco", "jacoco.xml"))):
    svc = xml.split(os.sep)[-5]
    if only and svc not in only:
        continue
    rep = ET.parse(xml).getroot()
    def line_counter(el):
        for c in el.findall("counter"):
            if c.get("type") == "LINE":
                return int(c.get("covered")), int(c.get("missed"))
        return 0, 0
    cov, mis = line_counter(rep)
    dcov = dmis = 0
    for p in rep.iter("package"):
        if re.search(r"/domain(/|$)", p.get("name", "")):
            c, m = line_counter(p); dcov += c; dmis += m
    rows.append({"service": svc, "runtime": "java", "total": pct(cov, cov + mis), "lines": cov + mis,
                 "domain": pct(dcov, dcov + dmis), "min": threshold(svc, "java"), "min_domain": threshold(svc, "domain"),
                 "source": os.path.relpath(xml, root)})

# ---- Go: cover profiles (statements; a block counts once even if several test binaries report it)
for prof in sorted(glob.glob(os.path.join(go_dir, "*.out"))):
    svc = os.path.splitext(os.path.basename(prof))[0]
    if only and svc not in only:
        continue
    blocks = {}
    with open(prof) as fh:
        for line in fh:
            if line.startswith("mode:") or not line.strip():
                continue
            loc, stmts, count = line.rsplit(" ", 2)
            if "/cmd/" in loc or "/testkit/" in loc:  # main wiring (exercised by smoke/E2E) and test helpers
                continue
            prev = blocks.get(loc)
            blocks[loc] = (int(stmts), max(int(count), prev[1] if prev else 0))
    tot = sum(s for s, _ in blocks.values()); cov = sum(s for s, c in blocks.values() if c > 0)
    dt = sum(s for loc, (s, _) in blocks.items() if "/internal/domain/" in loc)
    dc = sum(s for loc, (s, c) in blocks.items() if "/internal/domain/" in loc and c > 0)
    rows.append({"service": svc, "runtime": "go", "total": pct(cov, tot), "lines": tot, "domain": pct(dc, dt),
                 "min": threshold(svc, "go"), "min_domain": threshold(svc, "domain_go"), "source": os.path.relpath(prof, root)})

failures = []
for r in rows:
    r["ok"] = True
    if r["total"] is None:
        r["ok"] = None  # empty profile/report: the test run failed or nothing is instrumented — the test job reports it
        failures.append(f'{r["service"]} ({r["runtime"]}): no coverage data in {r["source"]}')
        continue
    if r["total"] is not None and r["total"] < r["min"]:
        r["ok"] = False; failures.append(f'{r["service"]} ({r["runtime"]}): total {r["total"]:.1f}% < {r["min"]:.0f}%')
    if r["domain"] is not None and r["domain"] < r["min_domain"]:
        r["ok"] = False; failures.append(f'{r["service"]} ({r["runtime"]}): domain {r["domain"]:.1f}% < {r["min_domain"]:.0f}%')

fmt = lambda v: "—" if v is None else f"{v:.1f}%"
md = ["| Service | Runtime | Total | Min | Domain | Min (domain) | Lines/stmts | Status |", "|---|---|---:|---:|---:|---:|---:|---|"]
for r in sorted(rows, key=lambda r: (r["runtime"], r["service"])):
    md.append(f'| {r["service"]} | {r["runtime"]} | {fmt(r["total"])} | {r["min"]:.0f}% | {fmt(r["domain"])} | {r["min_domain"]:.0f}% | {r["lines"]} | {"⚠️ no data" if r["ok"] is None else "✅" if r["ok"] else "❌"} |')
if not rows:
    md.append("| _no coverage reports found — run `./mvnw verify` or pass --run-go_ | | | | | | | |")
text = "## Coverage\n\n" + "\n".join(md) + "\n"
if failures:
    text += "\n**Below threshold:**\n\n" + "\n".join(f"- {f}" for f in failures) + "\n"
print(text)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
        fh.write(text)
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, "coverage-summary.json"), "w") as fh:
    json.dump({"services": rows, "failures": failures}, fh, indent=2)
sys.exit(1 if failures and fail else 0)
PY
