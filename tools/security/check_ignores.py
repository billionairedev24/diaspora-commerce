#!/usr/bin/env python3
"""Every accepted-risk ignore must carry a reason, an owner and an expiry date, and CI fails on expired ones (F7).

Files checked (all under tools/security/):
  trivyignore.yaml                 entries under `vulnerabilities:` / `misconfigurations:` / `secrets:`
                                   need `expired_at: YYYY-MM-DD` and a `statement:` containing "Owner: <team>"
  osv-scanner.toml                 [[IgnoredVulns]]: `ignoreUntil`, `reason` containing "Owner: <team>"
                                   [[PackageOverrides]]: `effectiveUntil`, `reason` containing "Owner: <team>"
  dependency-check-suppressions.xml  <suppress until="YYYY-MM-DD..."> with <notes> containing "Owner: <team>"

An expiry more than MAX_DAYS away is also rejected: accepted risk is time-boxed, not permanent.

  python3 tools/security/check_ignores.py            # exit 1 on any problem
  python3 tools/security/check_ignores.py --today 2027-01-01   # simulate a date (used by the self test)
"""
import argparse
import datetime
import pathlib
import re
import sys
import tomllib

HERE = pathlib.Path(__file__).resolve().parent
MAX_DAYS = 400
OWNER = re.compile(r"\bOwner:\s*\S+")


def parse_date(s):
    m = re.match(r"(\d{4})-(\d{2})-(\d{2})", str(s))
    return datetime.date(int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def check_date(where, raw, today, problems):
    d = parse_date(raw) if raw is not None else None
    if d is None:
        problems.append(f"{where}: missing or unparseable expiry ({raw!r})")
    elif d < today:
        problems.append(f"{where}: EXPIRED on {d} (re-review it, fix the dependency, or extend with a new reason)")
    elif (d - today).days > MAX_DAYS:
        problems.append(f"{where}: expiry {d} is more than {MAX_DAYS} days away; accepted risk must be time-boxed")


def check_trivy(path, today, problems):
    if not path.exists():
        return 0
    n = 0
    blocks = re.split(r"^\s{2}- (?=id:)", path.read_text(), flags=re.M)[1:]
    for b in blocks:
        n += 1
        ident = re.match(r"id:\s*(\S+)", b)
        where = f"{path.name}:{ident.group(1) if ident else '?'}"
        exp = re.search(r"^\s*expired_at:\s*(\S+)", b, re.M)
        check_date(where, exp.group(1) if exp else None, today, problems)
        if not re.search(r"^\s*statement:", b, re.M):
            problems.append(f"{where}: no `statement:` (reason)")
        elif not OWNER.search(b):
            problems.append(f"{where}: statement has no `Owner: <team>`")
    return n


def check_osv(path, today, problems):
    if not path.exists():
        return 0
    data = tomllib.loads(path.read_text())
    n = 0
    for e in data.get("IgnoredVulns", []):
        n += 1
        where = f"{path.name}:{e.get('id', '?')}"
        check_date(where, e.get("ignoreUntil"), today, problems)
        reason = e.get("reason", "")
        if not reason:
            problems.append(f"{where}: no `reason`")
        elif not OWNER.search(reason):
            problems.append(f"{where}: reason has no `Owner: <team>`")
    for e in data.get("PackageOverrides", []):
        n += 1
        pkg = e.get("name", "?")
        where = f"{path.name}:{pkg}"
        check_date(where, e.get("effectiveUntil"), today, problems)
        reason = e.get("reason", "")
        if not reason:
            problems.append(f"{where}: no `reason`")
        elif not OWNER.search(reason):
            problems.append(f"{where}: reason has no `Owner: <team>`")
    return n


def check_depcheck(path, today, problems):
    if not path.exists():
        return 0
    text = re.sub(r"<!--.*?-->", "", path.read_text(), flags=re.S)
    n = 0
    for m in re.finditer(r"<suppress\b([^>]*)>(.*?)</suppress>", text, flags=re.S):
        n += 1
        attrs, body = m.group(1), m.group(2)
        ident = re.search(r"<(?:cve|vulnerabilityName|packageUrl)[^>]*>([^<]*)<", body)
        where = f"{path.name}:{(ident.group(1) if ident else '?')[:60]}"
        until = re.search(r'until="([^"]+)"', attrs)
        check_date(where, until.group(1) if until else None, today, problems)
        notes = re.search(r"<notes>(.*?)</notes>", body, flags=re.S)
        if not notes or not notes.group(1).strip():
            problems.append(f"{where}: no <notes> (reason)")
        elif not OWNER.search(notes.group(1)):
            problems.append(f"{where}: notes have no `Owner: <team>`")
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--today", help="YYYY-MM-DD (default: today, UTC)")
    args = ap.parse_args()
    today = parse_date(args.today) if args.today else datetime.datetime.now(datetime.timezone.utc).date()
    problems = []
    n = check_trivy(HERE / "trivyignore.yaml", today, problems)
    n += check_osv(HERE / "osv-scanner.toml", today, problems)
    n += check_depcheck(HERE / "dependency-check-suppressions.xml", today, problems)
    if problems:
        print("Security ignore problems:")
        for p in problems:
            print("  " + p)
        return 1
    print(f"{n} accepted-risk ignore(s), all with reason, owner and a future expiry (as of {today})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
