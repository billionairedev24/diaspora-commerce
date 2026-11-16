#!/usr/bin/env python3
"""Contract route diff for Release 1 (ADR 0011, success criterion S4): every route in contracts/openapi/<module>.yaml of an enabled
module must be served by the running afrotransact-core, and the report names routes core serves that no contract lists.

  python3 tools/release/route_diff.py --live http://localhost:8080 --modules catalog,tax-returns
  python3 tools/release/route_diff.py --live http://localhost:8080 --modules-from java/apps/core/src/main/resources/application.yml
  python3 tools/release/route_diff.py --live-file api-docs.json --modules catalog          # offline (tests, no server)

Where the application exposes a group per module (/v3/api-docs/<module>) the diff is per group; until it does, the aggregate
/v3/api-docs is used (a route that a module's contract lists must exist in it). A path is compared as METHOD + path with every
{parameter} name replaced by {}, so a renamed path variable is not a difference. Exit 1 when a contract route is missing.
Standard library plus PyYAML (contracts are YAML).
"""
import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


def routes(doc):
    out = set()
    for path, item in (doc.get("paths") or {}).items():
        for method in item or {}:
            if method.lower() in METHODS:
                out.add((method.upper(), re.sub(r"\{[^}/]*\}", "{}", path)))
    return out


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as r:  # noqa: S310 (a local URL given on the command line)
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def modules_from_config(path):
    """Modules that are on by default in core: `<module>:` followed by `enabled: ${MODULE_X_ENABLED:true}` under at.modules."""
    text = pathlib.Path(path).read_text()
    return re.findall(r"^    ([a-z][a-z-]*):\n(?:      [^\n]*\n)*?      enabled: \$\{MODULE_[A-Z_]+_ENABLED:true\}", text, re.M)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--live", help="base URL of a running afrotransact-core")
    ap.add_argument("--live-file", help="a saved /v3/api-docs JSON (offline)")
    ap.add_argument("--modules", help="comma separated module names (contracts/openapi/<module>.yaml)")
    ap.add_argument("--modules-from", help="application.yml of core: the modules enabled by default")
    ap.add_argument("--contracts", default=str(ROOT / "contracts/openapi"))
    ap.add_argument("--json-out", help="write {\"missing\": [{module, method, path}]} here (read by tools/release/parity.py --route-report)")
    args = ap.parse_args()
    if not args.live and not args.live_file:
        ap.error("--live or --live-file")
    modules = [m for m in (args.modules or "").split(",") if m] or (modules_from_config(args.modules_from) if args.modules_from else [])
    if not modules:
        ap.error("no modules: pass --modules or --modules-from")
    aggregate = json.loads(pathlib.Path(args.live_file).read_text()) if args.live_file else fetch(args.live.rstrip("/") + "/v3/api-docs")
    if aggregate is None:
        sys.exit("FAIL: /v3/api-docs not served")
    all_live = routes(aggregate)
    bad = 0
    report = {"missing": []}
    for m in modules:
        contract_file = pathlib.Path(args.contracts) / f"{m}.yaml"
        if not contract_file.exists():
            print(f"[{m}] no contract file {contract_file.name}: skipped")
            continue
        contract = routes(yaml.safe_load(contract_file.read_text()))
        group = None if args.live_file else fetch(f"{args.live.rstrip('/')}/v3/api-docs/{m}")
        live = routes(group) if group else all_live
        scope = f"group /v3/api-docs/{m}" if group else "aggregate /v3/api-docs"
        missing = sorted(contract - live)
        extra = sorted(live - contract) if group else []
        print(f"[{m}] {len(contract)} contract routes, {len(live)} served ({scope}): {len(missing)} missing" + (f", {len(extra)} not in the contract" if group else ""))
        for method, path in missing:
            print(f"    MISSING  {method} {path}")
            report["missing"].append({"module": m, "method": method, "path": path})
        for method, path in extra:
            print(f"    EXTRA    {method} {path}")
        bad += len(missing)
    if args.json_out:
        pathlib.Path(args.json_out).write_text(json.dumps(report))
    print("route diff: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
