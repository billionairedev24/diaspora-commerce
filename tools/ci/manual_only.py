#!/usr/bin/env python3
"""Switch every GitHub Actions workflow between automatic triggers and manual-only (workflow_dispatch).

    python3 tools/ci/manual_only.py disable   # push/pull_request/schedule/... off; run workflows by hand (Actions > Run workflow, or gh workflow run)
    python3 tools/ci/manual_only.py enable    # restore the original triggers
    python3 tools/ci/manual_only.py status

`disable` keeps `workflow_dispatch` (added when missing) and `workflow_call` (reusable workflows still work when a manual
run calls them). Reusable-only files (`_*.yml`, workflow_call without workflow_dispatch) are not touched. The original `on:`
blocks are saved in .github/ci-triggers.original.json and the marker file .github/ci-manual-only is written, so
tools/ci/gen_workflows.py keeps generating manual-only workflows while the marker exists.
Dependabot PRs no longer start any CI; pause Dependabot too if you do not want the PRs (.github/dependabot.yml).
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
STORE = ROOT / ".github" / "ci-triggers.original.json"
MARKER = ROOT / ".github" / "ci-manual-only"
NOTE = "# MANUAL ONLY: automatic triggers are off (tools/ci/manual_only.py enable restores them).\n"
KEEP = ("workflow_dispatch", "workflow_call")


def split_on(text: str):
    """(before, on_block, after) around the top-level `on:` mapping; the block runs to the next top-level key."""
    lines = text.splitlines(keepends=True)
    start = next((i for i, l in enumerate(lines) if re.match(r"^on:\s*$", l)), None)
    if start is None:
        return None
    end = len(lines)
    for j in range(start + 1, len(lines)):
        l = lines[j]
        if l.strip() and not l.startswith((" ", "\t", "#")):
            end = j
            break
    # Trailing blank lines / comments that belong to the next section stay out of the block.
    while end > start + 1 and (not lines[end - 1].strip() or lines[end - 1].lstrip().startswith("#") and not lines[end - 1].startswith(" ")):
        end -= 1
    return "".join(lines[:start]), "".join(lines[start:end]), "".join(lines[end:])


def children(block: str) -> dict[str, str]:
    """Top-level children of the on: mapping (2-space indent) -> their full text."""
    lines = block.splitlines(keepends=True)[1:]
    out: dict[str, str] = {}
    key = None
    for l in lines:
        m = re.match(r"^  ([A-Za-z_]+):", l)
        if m:
            key = m.group(1)
            out[key] = l
        elif key is not None:
            out[key] += l
    return out


def transform(text: str) -> str:
    parts = split_on(text)
    if parts is None:
        return text
    before, block, after = parts
    kids = children(block)
    if "workflow_call" in kids and "workflow_dispatch" not in kids:
        return text  # reusable-only
    if set(kids) <= set(KEEP) and NOTE in before:
        return text
    kept = [kids[k] for k in KEEP if k in kids]
    if "workflow_dispatch" not in kids:
        kept.insert(0, "  workflow_dispatch:\n")
    return before.rstrip("\n") + "\n" + NOTE + "on:\n" + "".join(kept) + ("\n" if after and not after.startswith("\n") else "") + after


def disable() -> int:
    store = json.loads(STORE.read_text()) if STORE.exists() else {}
    changed = 0
    for path in sorted(WORKFLOWS.glob("*.yml")):
        text = path.read_text(encoding="utf-8")
        parts = split_on(text)
        if parts is None:
            continue
        new = transform(text)
        if new == text:
            continue
        store.setdefault(path.name, parts[1])
        path.write_text(new, encoding="utf-8")
        changed += 1
    STORE.write_text(json.dumps(store, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    MARKER.write_text("Workflows run only when triggered by hand. Restore: python3 tools/ci/manual_only.py enable\n")
    print(f"manual only: {changed} workflows changed; originals in {STORE.relative_to(ROOT)}")
    return 0


def enable() -> int:
    store = json.loads(STORE.read_text()) if STORE.exists() else {}
    changed = 0
    for path in sorted(WORKFLOWS.glob("*.yml")):
        if path.name not in store:
            continue
        text = path.read_text(encoding="utf-8")
        parts = split_on(text)
        if parts is None:
            continue
        before, _, after = parts
        before = before.replace(NOTE, "")
        path.write_text(before + store[path.name] + after, encoding="utf-8")
        changed += 1
    STORE.unlink(missing_ok=True)
    MARKER.unlink(missing_ok=True)
    print(f"automatic triggers restored on {changed} workflows")
    return 0


def status() -> int:
    auto = []
    for path in sorted(WORKFLOWS.glob("*.yml")):
        parts = split_on(path.read_text(encoding="utf-8"))
        if parts is None:
            continue
        kids = children(parts[1])
        if set(kids) - set(KEEP):
            auto.append(path.name)
    print("manual only" if not auto else "automatic triggers still on: " + ", ".join(auto))
    return 0 if not auto else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    sys.exit({"disable": disable, "enable": enable, "status": status}.get(cmd, status)())
