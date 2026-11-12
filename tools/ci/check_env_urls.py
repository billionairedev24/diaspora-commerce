#!/usr/bin/env python3
"""Guard: environment-specific AfroTransact hosts must come from per-environment configuration.

Public hosts come from ONE map, infra/cloudflare/hosts.json (docs/engineering/environments.md): prod is afrotransact.com and
<role>.afrotransact.com, every other env E is <suffix>.afrotransact.com and <role>-<suffix>.afrotransact.com (dev, uat, test).
Cloudflare's FREE certificate covers the apex and ONE label below the zone, so a host more than one label below it
(api.uat.afrotransact.com) fails, and so does a host that the map does not derive. Code and shared defaults must not hard-code any
of them; each environment's own files may only name that environment's hosts.

File classes (anything else is not scanned):

  neutral  application.yml / application-local.yml, Go config/local.yaml, Helm values.yaml,
           infra/k8s/platform/values/*.yaml, .env / .env.local / .env.development / .env.example,
           and non-test source (.java .kt .go .ts .tsx .js .jsx .mjs .cjs .sql)
           -> NO AfroTransact host at all (use PUBLIC_WEB_URL, AUTH_ISSUER_URL, ... with a localhost default)
  dev/uat  application-<env>.yml, config/<env>.yaml, values-<env>.yaml, infra/argocd/envs/<env>/**,
           infra/k8s/platform/envs/<env>/**, infra/k8s/platform/<layer>/overlays/<env>/**
           -> only hosts of that environment (a prod or other-env host fails)
  prod     the same files for prod, .env.production -> no dev/uat host
  multi    files that hold every environment side by side: web wrangler.jsonc / wrangler.toml (vars per Worker
           env), mobile eas.json (EXPO_PUBLIC_* per build profile) -> any environment's host, but only real
           public hosts and the canonical problem-type base

Always allowed (identifiers, not environment hosts):
  - email addresses (support@afrotransact.com, no-reply@afrotransact.com, ...)
  - RFC 9457 problem types under https://developers.afrotransact.com/problems/ (one stable base everywhere)
  - Kubernetes label / annotation / taint keys (afrotransact.com/env, afrotransact.com/spot)
  - the documented exceptions in EXCEPTIONS below

Every host must be derived from the map and be at most one label below the zone; staff., sell., provider. ... fail anywhere.

Tests and fixtures are skipped (src/test/, *_test.go, *.test.*, *.spec.*, __tests__/, e2e/, tests/, testdata/,
fixtures/), as are generated clients (*/generated/*) and build output.

    python3 tools/ci/check_env_urls.py                     # whole repository
    python3 tools/ci/check_env_urls.py java go/pkg infra   # only these paths
    python3 tools/ci/check_env_urls.py --exclude infra/tofu  # skip a path prefix (repeatable)
    python3 tools/ci/check_env_urls.py --format github     # ::error annotations for GitHub Actions

Exit status: 0 clean, 1 violations found, 2 usage error.
"""
from __future__ import annotations

import argparse
import fnmatch
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ZONE = "afrotransact.com"
ENVS = ("dev", "uat", "trial", "prod")
SKIP_DIRS = {
    "node_modules", ".git", "design", "target", ".next", ".next-e2e", ".open-next", "dist", "build", "coverage",
    ".turbo", ".gradle", ".idea", ".vscode", "vendor", ".expo", ".wrangler", "storybook-static",
}
# Whole trees that are documentation or contracts, not configuration or runtime source.
SKIP_PREFIXES = ("docs/portal/", "contracts/", "design/")

SOURCE_EXT = {".java", ".kt", ".go", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".sql"}
TEST_PATTERNS = (
    "*/src/test/*", "*_test.go", "*.test.*", "*.spec.*", "*/__tests__/*", "*/e2e/*", "*/tests/*", "tests/*",
    "*/testdata/*", "*/fixtures/*", "*/__fixtures__/*", "*/test-utils/*",
)
GENERATED_PATTERNS = ("*/generated/*", "*.generated.*", "*.gen.go", "*.d.ts")

# A host in the zone: optional sub-domain labels, not part of an email address or a longer name.
HOST_RE = re.compile(r"(?<![A-Za-z0-9.@_-])((?:[a-z0-9*](?:[a-z0-9-]*[a-z0-9])?\.)*)" + re.escape(ZONE) + r"(?![A-Za-z0-9-])")
PROBLEM_TYPE_PREFIX = "https://developers." + ZONE + "/problems/"


@dataclass(frozen=True)
class Exception_:
    path_glob: str
    pattern: str  # regex that must match the line
    reason: str


# Documented exceptions: identifiers that look like hosts. Keep this list short and explain every entry.
EXCEPTIONS: tuple[Exception_, ...] = (
    Exception_("infra/argocd/envs/*/platform-values.yaml", r"^\s*domain: afrotransact\.com\s*$",
               "global.domain is the ZONE in every environment (PLATFORM_DOMAIN, the WebAuthn rp id), not a host"),
    Exception_("java/services/identity/src/main/resources/application.yml",
               r"IDENTITY_STAFF_EMAIL_DOMAINS:afrotransact\.com\}",
               "staff email domain (an email domain, not a host); overridable with IDENTITY_STAFF_EMAIL_DOMAINS"),
    Exception_("go/services/gateway-bff/internal/domain/account.go",
               r'StaffEmailDomain = "afrotransact\.com"',
               "staff email domain (an email domain, not a host): staff work emails are read-only in My account"),
    Exception_("web/apps/staff-console/src/lib/config.ts",
               r"https://developers\.afrotransact\.com",
               "public developer portal (canonical in every environment); DOCS_PORTAL_URL overrides"),
    Exception_("java/libs/platform-web/src/main/java/com/afrotransact/platform/web/docs/ApiDocsProperties.java",
               r'DEVELOPER_PORTAL_URL = "https://developers\.afrotransact\.com"',
               "public developer portal (canonical in every environment; home of the problem types); "
               "at.api-docs.contact-url overrides"),
)


@dataclass(frozen=True)
class Violation:
    path: str
    line: int
    host: str
    message: str


# Public host names per environment, from infra/cloudflare/hosts.json (the single source of truth; tools/ci/env_hosts.py).
import json  # noqa: E402

_MAP = json.loads((ROOT / "infra/cloudflare/hosts.json").read_text())
HOST_ENV: dict[str, str] = {}
for _env, _cfg in _MAP["envs"].items():
    _sfx = _cfg["suffix"]
    for _role in ["storefront", *_MAP["roles"], *_MAP["other_roles"]]:
        if _role == "storefront":
            _h = f"{_sfx}.{ZONE}" if _sfx else ZONE
        else:
            _h = f"{_role}-{_sfx}.{ZONE}" if _sfx else f"{_role}.{ZONE}"
        HOST_ENV[_h] = _env


def labels_below(host: str) -> int:
    return 0 if host == ZONE else host[: -len(ZONE) - 1].count(".") + 1


def host_env(host: str) -> str:
    return HOST_ENV.get(host, "")


def classify(rel: str) -> str | None:
    """'neutral', 'dev', 'uat', 'prod', 'multi' or None (not scanned)."""
    name = rel.rsplit("/", 1)[-1]
    if name in ("wrangler.jsonc", "wrangler.json", "wrangler.toml", "eas.json"):
        return "multi"
    m = re.fullmatch(r"application(?:-(\w+))?\.(?:ya?ml|properties)", name)
    if m:
        profile = m.group(1)
        if profile in (None, "local"):
            return "neutral"
        return profile if profile in ENVS else None
    m = re.search(r"(?:^|/)config/(local|dev|uat|prod)\.ya?ml$", rel)
    if m:
        return "neutral" if m.group(1) == "local" else m.group(1)
    if name in ("values.yaml", "values.yml"):
        return "neutral"
    m = re.fullmatch(r"values-(\w+)\.ya?ml", name)
    if m:
        return m.group(1) if m.group(1) in ENVS else None
    m = re.match(r"infra/argocd/envs/(dev|uat|trial|prod)/", rel) or \
        re.match(r"infra/k8s/platform/(?:envs|[\w-]+/overlays)/(dev|uat|trial|prod)/", rel)
    if m and rel.endswith((".yaml", ".yml")):
        return m.group(1)
    if rel.startswith("infra/k8s/platform/values/") and rel.endswith((".yaml", ".yml")):
        return "neutral"
    if name in (".env", ".env.local", ".env.development", ".env.example", ".env.local.example"):
        return "neutral"
    if name == ".env.production":
        return "prod"
    if os.path.splitext(name)[1] in SOURCE_EXT:
        probe = "/" + rel
        if any(fnmatch.fnmatch(probe, p) or fnmatch.fnmatch(rel, p) for p in TEST_PATTERNS + GENERATED_PATTERNS):
            return None
        return "neutral"
    return None


def allowed(rel: str, line: str, start: int, end: int, host: str) -> bool:
    before = line[:start]
    after = line[end:]
    # Kubernetes label / annotation / taint key: bare zone followed by "/", not a URL.
    if host == ZONE and after.startswith("/") and not before.endswith("://"):
        return True
    # RFC 9457 problem types: one stable identifier base for every environment.
    if (before.endswith("https://") and (host + after).startswith(PROBLEM_TYPE_PREFIX[len("https://"):])):
        return True
    for ex in EXCEPTIONS:
        if fnmatch.fnmatch(rel, ex.path_glob) and re.search(ex.pattern, line):
            return True
    return False


def before_is_url(line: str, start: int) -> bool:
    """True when the host at `start` is the authority of a URL (preceded by a scheme), not a bare domain setting."""
    return line[:start].endswith("://")


def scan_file(path: Path, rel: str, cls: str) -> list[Violation]:
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    if ZONE not in text:
        return []
    out: list[Violation] = []
    for no, line in enumerate(text.splitlines(), 1):
        if ZONE not in line:
            continue
        for m in HOST_RE.finditer(line):
            host = m.group(0)
            if allowed(rel, line, m.start(), m.end(), host):
                continue
            env = host_env(host)
            if host.startswith("errors."):
                out.append(Violation(rel, no, host, "non-canonical RFC 9457 problem type base: use "
                                     f"{PROBLEM_TYPE_PREFIX}<code> (go/pkg/problem.TypeURI, ProblemTypes.of)"))
            elif labels_below(host) > 1:
                out.append(Violation(rel, no, host, "more than one label below the zone: Cloudflare's free certificate covers only "
                                     "the apex and one label (api-uat.<zone>, not api.uat.<zone>); hosts come from "
                                     "infra/cloudflare/hosts.json"))
            elif not env:
                out.append(Violation(rel, no, host, "not a host of the map infra/cloudflare/hosts.json (prod: <role>.<zone>; other "
                                     "envs: <role>-<suffix>.<zone>, storefront <suffix>.<zone>)"))
            elif cls == "multi":
                continue
            elif cls == "neutral":
                out.append(Violation(rel, no, host, f"{env} host in a shared default / source file: read it from a "
                                     "per-environment setting (PUBLIC_WEB_URL, AUTH_ISSUER_URL, ...) with a "
                                     "localhost default"))
            elif env != cls:
                out.append(Violation(rel, no, host, f"{env} host in a {cls} file"))
    return out


def iter_files(root: Path, targets: list[Path], excludes: list[str]):
    for target in targets:
        if target.is_file():
            yield target
            continue
        for dirpath, dirnames, filenames in os.walk(target):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".next-"))
            for f in sorted(filenames):
                yield Path(dirpath) / f


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("paths", nargs="*", help="files or directories to scan (default: the repository root)")
    ap.add_argument("--exclude", action="append", default=[], metavar="PATH",
                    help="repository-relative path prefix to skip (repeatable)")
    ap.add_argument("--format", choices=("text", "github"), default="text")
    args = ap.parse_args(argv)

    root = ROOT
    targets = [Path(p).resolve() for p in args.paths] or [root]
    for t in targets:
        if not t.exists():
            print(f"check_env_urls: no such path: {t}", file=sys.stderr)
            return 2
    excludes = [e.strip("/") + "/" for e in args.exclude]

    violations: list[Violation] = []
    scanned = 0
    seen: set[str] = set()
    for path in iter_files(root, targets, excludes):
        try:
            rel = path.resolve().relative_to(root).as_posix()
        except ValueError:
            rel = path.as_posix()
        if rel in seen:
            continue
        seen.add(rel)
        if rel.startswith(SKIP_PREFIXES) or any((rel + "/").startswith(e) for e in excludes):
            continue
        if any(part in SKIP_DIRS or part.startswith(".next-") for part in rel.split("/")[:-1]):
            continue
        cls = classify(rel)
        if cls is None:
            continue
        scanned += 1
        violations.extend(scan_file(path, rel, cls))

    for v in violations:
        if args.format == "github":
            print(f"::error file={v.path},line={v.line},title=env-specific host::{v.host}: {v.message}")
        else:
            print(f"{v.path}:{v.line}: {v.host}: {v.message}")
    status = "FAIL" if violations else "OK"
    print(f"check_env_urls: {status} - {len(violations)} violation(s) in {scanned} scanned file(s)", file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
