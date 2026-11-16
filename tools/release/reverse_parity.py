#!/usr/bin/env python3
"""Reverse parity: what release-1 has that main (or develop) lacks (CLAUDE.md, "One product, three branches").

`parity.py` asks "does the monolith have everything main has". This asks the other way round: main, develop and release-1 are functionally
identical, so a Flyway migration, an API contract, a permission seed or a translation key that exists on release-1 only is a functional
change that has not been ported back (tools/release/port-back.sh). It is a REPORT: it exits 0 unless --strict, because a difference the other
way (main ahead, waiting for the next sync) is normal.

  python3 tools/release/parity.py --reverse --main origin/main            (same thing, through parity.py)
  python3 tools/release/reverse_parity.py --main origin/main [--head HEAD] [--format text|markdown|json] [--strict]

Compared, file by file or key by key, between --head (default HEAD, the release-1 line) and --main:
  migrations   */db/migration/*.sql of every service (the sets of files and their contents must be identical)
  contracts    contracts/** (OpenAPI, AsyncAPI, schemas)
  permissions  *Permissions.java, identity's domain/Permission.java, the tenant-isolation matrices (permission seeds and who may call what)
  i18n         the keys of every i18n/*.json (web and mobile), flattened: a key present on one side only

Each difference says who changed it since the merge base of the two refs:
  port-back     release-1 added or changed it, main did not: PORT IT to main and develop (the gap this report exists for)
  sync-pending  main added or changed it, release-1 did not: the next release-1-sync brings it (not a gap)
  both          both changed it: a conflict for the sync or a hand merge
Paths that differ on purpose are listed under `reverse_skip` in tools/release/parity.yaml (glob: reason; an entry without a reason does not count).
Standard library plus PyYAML (only for parity.yaml).
"""
import argparse
import fnmatch
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]

CATEGORIES = [
    ("migrations", ["*/db/migration/*.sql"], ["*/src/test/*"]),
    ("contracts", ["contracts/*"], []),
    ("permissions", ["java/services/*/src/main/java/*Permissions.java", "java/services/identity/src/main/java/*/domain/Permission.java",
                     "java/services/*/src/test/resources/tenant-isolation-matrix.txt",
                     "go/services/*/*tenant_isolation_matrix.txt"], []),
    ("i18n", ["*/i18n/*.json"], ["*/node_modules/*"]),
]


class Diff:
    def __init__(self, category, subject, what, direction):
        self.category, self.subject, self.what, self.direction = category, subject, what, direction

    def row(self):
        return [self.category, self.subject, self.what, self.direction]


def git(root, *args):
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True)


def tree(root, ref):
    """path -> blob id for every file of a ref (empty when the ref does not exist)."""
    r = git(root, "ls-tree", "-r", ref)
    out = {}
    for line in r.stdout.splitlines():
        meta, path = line.split("\t", 1)
        out[path] = meta.split()[2]
    return out


def blob(root, ref, path):
    r = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{path}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def flatten(obj, prefix=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from flatten(v, f"{prefix}.{k}" if prefix else str(k))
    else:
        yield prefix


def keys_of(data):
    try:
        return set(flatten(json.loads(data.decode("utf-8")))) if data is not None else None
    except (ValueError, UnicodeDecodeError):
        return None


def direction(changed_head, changed_main):
    if changed_head and changed_main:
        return "both"
    return "port-back" if changed_head else "sync-pending"


def load_skips(root):
    p = pathlib.Path(root) / "tools/release/parity.yaml"
    if not p.exists():
        return {}
    import yaml  # PyYAML is already a dependency of parity.py
    cfg = yaml.safe_load(p.read_text()) or {}
    return {k: v for k, v in (cfg.get("reverse_skip") or {}).items() if v}


def matches(path, includes, excludes):
    return any(fnmatch.fnmatch(path, g) for g in includes) and not any(fnmatch.fnmatch(path, g) for g in excludes)


def collect(root, main, head="HEAD", skips=None):
    root = pathlib.Path(root)
    skips = load_skips(root) if skips is None else skips
    th, tm = tree(root, head), tree(root, main)
    if not tm:
        raise SystemExit(f"reverse parity: cannot read {main} (git fetch origin main?)")
    mb = git(root, "merge-base", head, main).stdout.strip()
    tb = tree(root, mb) if mb else {}
    diffs = []
    for cat, includes, excludes in CATEGORIES:
        paths = sorted({p for p in list(th) + list(tm) if matches(p, includes, excludes)})
        for p in paths:
            if any(fnmatch.fnmatch(p, g) for g in skips):
                continue
            h, m, b = th.get(p), tm.get(p), tb.get(p)
            if h == m:
                continue
            d = direction(h != b, m != b)
            if cat == "i18n" and h and m:
                kh, km, kb = keys_of(blob(root, head, p)), keys_of(blob(root, main, p)), keys_of(blob(root, mb, p) if b else b"{}")
                if kh is not None and km is not None:
                    kb = kb if kb is not None else set()
                    for k in sorted(kh - km):
                        diffs.append(Diff(cat, f"{p}:{k}", "key only on release-1", direction(k not in kb, k in kb)))
                    for k in sorted(km - kh):
                        diffs.append(Diff(cat, f"{p}:{k}", "key only on main", direction(k in kb, k not in kb)))
                    continue
            if h is None:
                what = "file only on main"
            elif m is None:
                what = "file only on release-1"
            else:
                what = "content differs"
            diffs.append(Diff(cat, p, what, d))
    return diffs


def render(diffs, fmt, main, head):
    if fmt == "json":
        return json.dumps([dict(zip(("category", "subject", "what", "direction"), d.row())) for d in diffs])
    back = [d for d in diffs if d.direction != "sync-pending"]
    lines = []
    if fmt == "markdown":
        lines += ["| Category | Subject | Difference | Direction |", "|---|---|---|---|"]
        esc = lambda s: str(s).replace("|", "\\|")
        for d in sorted(diffs, key=lambda d: (d.direction == "sync-pending", d.category, d.subject)):
            lines.append(f"| {d.category} | `{esc(d.subject)}` | {d.what} | {d.direction} |")
        if not diffs:
            lines.append("| | | none | |")
        lines.append("")
    else:
        for d in sorted(diffs, key=lambda d: (d.direction == "sync-pending", d.category, d.subject)):
            lines.append(f"{d.direction:<13} {d.category:<12} {d.subject}  ({d.what})")
    lines.append(f"reverse parity {head} vs {main}: {len(back)} difference(s) to port back or merge, "
                 f"{len(diffs) - len(back)} waiting for the next sync" + ("" if back else " (nothing to port back)"))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--main", default="origin/main", help="git ref of main or develop")
    ap.add_argument("--head", default="HEAD", help="git ref of the release-1 line")
    ap.add_argument("--format", choices=["text", "markdown", "json"], default="text")
    ap.add_argument("--strict", action="store_true", help="exit 1 when something has to be ported back (default: report only)")
    args = ap.parse_args(argv)
    diffs = collect(args.root, args.main, args.head)
    print(render(diffs, args.format, args.main, args.head))
    return 1 if args.strict and any(d.direction != "sync-pending" for d in diffs) else 0


if __name__ == "__main__":
    sys.exit(main())
