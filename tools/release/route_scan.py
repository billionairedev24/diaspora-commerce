#!/usr/bin/env python3
"""Static route scan for Release 1 (ADR 0011, S4): which contract routes have a Spring mapping in their module's source, and which
routes two modules map (the collisions core has to disambiguate). No server needed, so it also covers modules that cannot boot
where the check runs (fulfilment needs PostGIS).

  python3 tools/release/route_scan.py                      # every Java module that has a contract
  python3 tools/release/route_scan.py --modules orders,payments --json report.json

A route is METHOD + path with every {parameter} replaced by {}. Mappings are read from @RequestMapping / @GetMapping / @PostMapping /
@PutMapping / @DeleteMapping / @PatchMapping on the classes under java/services/<module>/src/main (class-level prefix + method path;
`path =` / `value =` forms and arrays). A mapping built from a constant is reported as `unresolved` instead of guessed.
Exit 1 when a contract route has no mapping.
"""
import argparse
import json
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
METHODS = {"get", "put", "post", "delete", "patch", "options", "head"}
MAPPING = re.compile(r"@(Request|Get|Post|Put|Delete|Patch)Mapping\b(\s*\((?P<args>(?:[^()]|\([^()]*\))*)\))?", re.S)
STRING = re.compile(r'"([^"]*)"')
CLASS = re.compile(r"\b(?:class|interface|record|enum)\s+\w+")


def norm(path):
    path = re.sub(r"\{[^}/]*\}", "{}", path)
    path = re.sub(r"/+", "/", "/" + path.strip("/"))
    return path if path == "/" else path.rstrip("/")


def paths_of(args):
    """The path strings of a mapping annotation's arguments ('' when it names none, None when it uses a constant)."""
    if args is None or not args.strip():
        return [""]
    m = re.search(r"\b(?:path|value)\s*=\s*(\{[^}]*\}|\"[^\"]*\"|[A-Za-z_.]+)", args)
    if m:
        body = m.group(1)
    elif re.match(r"\s*\w+\s*=", args):
        return [""]  # only other attributes (produces = ..., method = ...)
    else:
        body = re.split(r",\s*\w+\s*=", args)[0]
    found = STRING.findall(body)
    if not found and body.strip() and not body.strip().startswith(('"', "{")):
        return None
    return found or [""]


def verbs_of(kind, args):
    if kind != "Request":
        return [kind.upper()]
    m = re.search(r"method\s*=\s*(\{[^}]*\}|[A-Za-z_.]+)", args or "")
    return re.findall(r"RequestMethod\.(\w+)", m.group(1) if m else "") or ["ANY"]


def scan_file(text):
    """[(verb, path, unresolved)] of one Java source file."""
    out = []
    prefixes = [""]
    unresolved_prefix = False
    first_class = CLASS.search(text)
    head = text[: first_class.start()] if first_class else ""
    body_start = first_class.start() if first_class else 0
    # the class-level @RequestMapping sits between the imports and the class keyword (after the last blank line before it)
    for m in MAPPING.finditer(text[:body_start + 1]):
        if m.group(1) == "Request":
            ps = paths_of(m.group("args"))
            if ps is None:
                unresolved_prefix = True
            else:
                prefixes = ps
    for m in MAPPING.finditer(text[body_start:]):
        kind, args = m.group(1), m.group("args")
        if kind == "Request" and text[: body_start + m.start()].rstrip().endswith(("*/", ")")) and False:
            continue
        ps = paths_of(args)
        verbs = verbs_of(kind, args)
        # skip the class-level annotation itself (already consumed as prefix)
        if m.start() + body_start < body_start + 1:
            continue
        for verb in verbs:
            if ps is None:
                out.append((verb, None, True))
            else:
                for pre in prefixes:
                    for p in ps:
                        out.append((verb, norm(pre + "/" + p), unresolved_prefix))
    return out


def module_routes(module):
    src = ROOT / "java/services" / module / "src/main/java"
    routes = {}
    unresolved = []
    for f in src.rglob("*.java"):
        text = f.read_text()
        if "Mapping" not in text:
            continue
        for verb, path, unres in scan_file(text):
            if path is None:
                unresolved.append(f.name)
                continue
            routes.setdefault((verb, path), set()).add(f.name)
            if unres:
                unresolved.append(f.name)
    return routes, sorted(set(unresolved))


def contract_routes(module):
    doc = yaml.safe_load((ROOT / "contracts/openapi" / f"{module}.yaml").read_text())
    return {(m.upper(), norm(p)) for p, item in (doc.get("paths") or {}).items() for m in (item or {}) if m.lower() in METHODS}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--modules", help="comma separated; default every java/services/<m> with a contract")
    ap.add_argument("--json", help="write the report here")
    args = ap.parse_args()
    services = sorted(p.name for p in (ROOT / "java/services").iterdir() if (ROOT / "contracts/openapi" / f"{p.name}.yaml").exists())
    modules = [m for m in (args.modules or "").split(",") if m] or services
    report = {}
    mapped_by = {}
    bad = 0
    for m in modules:
        routes, unresolved = module_routes(m)
        contract = contract_routes(m)
        have = {(v, p) for (v, p) in routes}
        missing = sorted(r for r in contract if r not in have and (("ANY", r[1]) not in have))
        extra = sorted(r for r in have if r not in contract and r[0] != "ANY")
        report[m] = {"contract": len(contract), "mapped": len(have), "missing": [f"{v} {p}" for v, p in missing],
                     "not_in_contract": len(extra), "unresolved_files": unresolved}
        for r in contract:
            mapped_by.setdefault(r, set()).add(m)
        bad += len(missing)
        print(f"[{m}] contract {len(contract)} routes, mapped in source {len(have)}, missing {len(missing)}, "
              f"mapped but not in contract {len(extra)}" + (f", unresolved in {unresolved}" if unresolved else ""))
        for v, p in missing:
            print(f"    MISSING {v} {p}")
    collisions = sorted((f"{v} {p}", sorted(ms)) for (v, p), ms in mapped_by.items() if len(ms) > 1)
    report["collisions"] = {r: ms for r, ms in collisions}
    print(f"\nRoutes in the contracts of two or more modules ({len(collisions)}):")
    for r, ms in collisions:
        print(f"    {r}: {', '.join(ms)}")
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(report, indent=2))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
