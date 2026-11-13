#!/usr/bin/env python3
"""Fail when the pinned Go toolchain disagrees between the places that must agree (F1, docs/engineering/security-audit.md).

The single source of truth is `toolchain goX.Y.Z` in go/go.work. Every other pin must equal it:
  - `toolchain` line of every go.mod (go/pkg, go/services/*, tools/**)
  - Makefile GO_TOOLCHAIN
  - the Go row of the CLAUDE.md toolchain table
  - `FROM golang:X.Y.Z` in every Dockerfile
  - GO_VERSION / go-version in every workflow that sets up Go

The `go X.Y.Z` language directive is intentionally NOT compared (a patch release needs no language bump).

  python3 tools/ci/check_go_pins.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SKIP = {"node_modules", ".git", "design", "target", ".next", ".open-next", ".claude"}


def files(pattern):
    for p in ROOT.rglob(pattern):
        if not SKIP.intersection(p.relative_to(ROOT).parts):
            yield p


def rel(p):
    return str(p.relative_to(ROOT))


def main():
    work = (ROOT / "go/go.work").read_text()
    m = re.search(r"^toolchain go(\d+\.\d+\.\d+)\s*$", work, re.M)
    if not m:
        print("go/go.work has no `toolchain goX.Y.Z` line")
        return 1
    want = m.group(1)
    bad = []

    def expect(where, got):
        if got != want:
            bad.append(f"{where}: {got} (go/go.work says {want})")

    for p in files("go.mod"):
        t = re.search(r"^toolchain go(\d+\.\d+\.\d+)\s*$", p.read_text(), re.M)
        expect(rel(p), t.group(1) if t else "<no toolchain line>")

    mk = re.search(r"^GO_TOOLCHAIN\s*:?=\s*go(\S+)", (ROOT / "Makefile").read_text(), re.M)
    expect("Makefile GO_TOOLCHAIN", mk.group(1) if mk else "<missing>")

    claude = (ROOT / "CLAUDE.md").read_text()
    cl = re.search(r"^\| Go \| (\d+\.\d+\.\d+) toolchain", claude, re.M)
    expect("CLAUDE.md toolchain table", cl.group(1) if cl else "<missing>")
    cl2 = re.search(r"`toolchain go(\d+\.\d+\.\d+)`", claude)
    expect("CLAUDE.md toolchain directive", cl2.group(1) if cl2 else "<missing>")

    for p in files("Dockerfile*"):
        for tag in re.findall(r"^FROM\s+(?:--platform=\S+\s+)?golang:(\d+\.\d+\.\d+)", p.read_text(), re.M):
            expect(rel(p), tag)

    for p in (ROOT / ".github/workflows").glob("*.yml"):
        txt = p.read_text()
        for v in re.findall(r"^\s*GO_VERSION:\s*\"?(\d+\.\d+\.\d+)\"?", txt, re.M):
            expect(f"{rel(p)} GO_VERSION", v)
        for v in re.findall(r"^\s*go-version:\s*\"?(\d+\.\d+\.\d+)\"?\s*$", txt, re.M):
            expect(f"{rel(p)} go-version", v)

    if bad:
        print(f"Go toolchain pins disagree (expected go{want}):")
        for b in bad:
            print("  " + b)
        return 1
    print(f"Go toolchain pins consistent: go{want}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
