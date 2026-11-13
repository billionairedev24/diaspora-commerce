#!/usr/bin/env python3
"""Fail when a deployable file turns authentication off (security audit F5).

Java services honour AT_SECURITY_ENABLED / at.security.enabled, Go services AUTH_ENABLED / auth.enabled. Outside the local
stack none of them may be set to false: values files, Argo CD, deploy compose files, workflows, Dockerfiles and the
dev/uat/prod Spring profiles and Go configs. infra/local (docker compose for `make up`) and tests are excluded.
The services also refuse to start in that state (platform-security SecurityEnabledGuard, go/pkg/config RequireAuthOutsideLocal);
this check catches it before a deploy.

  python3 tools/ci/check_security_off.py [--format github]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_DIRS = ["infra", "java", "go", ".github", "docs/runbooks"]
SUFFIXES = {".yaml", ".yml", ".env", ".sh", ".tpl", ".json", ".example", ".tf", ".tfvars", ""}
EXCLUDE_PARTS = {"node_modules", "target", ".git", "test", "tests", "testdata", "src/test"}
EXCLUDE_PREFIXES = ("infra/local/",)
# local-only files by name
LOCAL_NAMES = {"application-local.yml", "local.yaml", "application-test.yml"}

FALSE = r"""["']?(?i:false|0|off|no)["']?"""
ENV_RE = re.compile(r"""(?<![\w])(?:AT_SECURITY_ENABLED|AUTH_ENABLED)\b["']?\s*(?:[:=]\s*|\n\s*value:\s*)""" + FALSE + r"(?![\w-])")
LOCAL_STEP_RE = re.compile(r"(?:APP_ENV|SPRING_PROFILES_ACTIVE)\W+local\b")
SPRING_RE = re.compile(r"^\s*security:\s*\n(?:\s+#.*\n)*\s+enabled:\s*" + FALSE + r"\s*$", re.I | re.M)
GO_AUTH_RE = re.compile(r"^auth:\s*\n(?:\s+#.*\n)*\s+enabled:\s*" + FALSE + r"\s*$", re.I | re.M)


def candidates():
    for d in SCAN_DIRS:
        base = ROOT / d
        if not base.exists():
            continue
        for p in base.rglob("*"):
            if not p.is_file() or p.suffix not in SUFFIXES:
                continue
            rel = p.relative_to(ROOT).as_posix()
            if rel.startswith(EXCLUDE_PREFIXES) or p.name in LOCAL_NAMES or p.name == Path(__file__).name:
                continue
            if any(part in EXCLUDE_PARTS for part in p.relative_to(ROOT).parts):
                continue
            if "src/test/" in rel or "/charts/" in rel:
                continue
            yield p, rel


def scan(text: str, rel: str):
    out = []
    for m in ENV_RE.finditer(text):
        # A CI step that also pins APP_ENV=local (a sandbox run of one service) is the local stack, not a deployment.
        window = text[max(0, m.start() - 1200):m.end() + 400]
        if rel.startswith(".github/") and LOCAL_STEP_RE.search(window):
            continue
        out.append((text.count("\n", 0, m.start()) + 1, m.group(0).strip()))
    if rel.endswith((".yml", ".yaml")):
        spring_profile = "/src/main/resources/application" in rel
        go_cfg = rel.startswith("go/services/") and "/config/" in rel
        if spring_profile:
            for m in SPRING_RE.finditer(text):
                out.append((text.count("\n", 0, m.start()) + 1, "at.security.enabled: false"))
        if go_cfg:
            for m in GO_AUTH_RE.finditer(text):
                out.append((text.count("\n", 0, m.start()) + 1, "auth.enabled: false"))
    return out


def main(argv: list[str]) -> int:
    github = "--format" in argv and "github" in argv
    bad = 0
    for p, rel in candidates():
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        # docs may describe the dangerous value; only enforce in docs/runbooks lines that set it as a command
        for line, what in scan(text, rel):
            bad += 1
            msg = f"{rel}:{line}: {what} - authentication must never be off outside the local stack"
            print(f"::error file={rel},line={line}::{msg}" if github else msg)
    if bad:
        print(f"\n{bad} security-off setting(s) found", file=sys.stderr)
        return 1
    print("ok: no deployable file turns authentication off")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
