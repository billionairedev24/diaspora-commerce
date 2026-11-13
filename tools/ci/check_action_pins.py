#!/usr/bin/env python3
"""Fail when a third-party GitHub Action is not pinned to a full commit SHA (F9, docs/engineering/security-audit.md).

A mutable tag (`@v4`) on a third-party action lets its maintainer, or whoever compromises them, run code with our
deploy and registry credentials. Rule for every `uses:` in .github/workflows and .github/actions:
  - local (`./...`) and GitHub-owned (`actions/*`, `github/*`) references may use a tag;
  - everything else must be `owner/repo[/path]@<40 hex chars> # <version>` (the comment keeps it reviewable;
    Dependabot `github-actions` updates both).

Resolve a SHA (dereferencing annotated tags):
  gh api repos/<owner>/<repo>/git/ref/tags/<tag>        # object.type == "tag" -> gh api repos/<owner>/<repo>/git/tags/<sha>

  python3 tools/ci/check_action_pins.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
USES = re.compile(r"^\s*-?\s*uses:\s*(?P<ref>\S+)(?P<rest>.*)$")
SHA = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
FIRST_PARTY_OWNERS = {"actions", "github"}


def main():
    files = sorted((ROOT / ".github/workflows").glob("*.y*ml")) + sorted((ROOT / ".github/actions").rglob("*.y*ml"))
    bad = []
    for p in files:
        for no, line in enumerate(p.read_text().splitlines(), 1):
            m = USES.match(line)
            if not m:
                continue
            ref = m.group("ref").strip("\"'")
            if ref.startswith("./") or ref.startswith("docker://"):
                if ref.startswith("docker://") and "@sha256:" not in ref:
                    bad.append(f"{p.relative_to(ROOT)}:{no}: {ref} (docker:// reference needs @sha256:<digest>)")
                continue
            if ref.split("/")[0] in FIRST_PARTY_OWNERS:
                continue
            if not SHA.match(ref):
                bad.append(f"{p.relative_to(ROOT)}:{no}: {ref} (pin to a full commit SHA)")
            elif "#" not in m.group("rest"):
                bad.append(f"{p.relative_to(ROOT)}:{no}: {ref} (add the version as a trailing comment, `# v1.2.3`)")
    if bad:
        print("Unpinned third-party GitHub Actions:")
        for b in bad:
            print("  " + b)
        return 1
    print(f"All third-party actions are SHA-pinned ({len(files)} workflow/action files checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
