#!/usr/bin/env python3
"""Load the written legal documents (docs/legal/<key>.<locale>.md) into the content service as a Flyway migration.

    python3 tools/legal/build_seed.py            # write java/services/content/src/main/resources/db/migration/V<n>__legal_documents_<hash>.sql
    python3 tools/legal/build_seed.py --check    # CI: fail when docs/legal changed but the migration did not
    python3 tools/legal/build_seed.py --list     # documents found, versions, and what the content service already seeds
    python3 tools/legal/build_seed.py --rewrite  # replace the newest generated migration (only while it is unreleased)

Each document is `docs/legal/<key>.en.md` (English, binding, required) plus optional `<key>.fr.md`, `.es.md`, `.ar.md` and
`.yo.md` translations. A file starts with YAML front matter and then the Markdown body:

    ---
    key: gift-card-terms
    title: Gift Card Terms
    version: "1.1"            # major.minor[.patch]; must be higher than the version the content service has now
    effective: 2026-11-01
    change_summary: Adds the dormant balance rules
    applies_to: [customers]
    draft: true               # true shows "Draft for legal counsel review" until counsel signs off in the staff console
    ---
    # Gift Card Terms

    ## Scope
    ...

The generated migration is idempotent and follows the content service's version rules (LegalDocument.raise): the new
version becomes CURRENT, an older CURRENT version is ARCHIVED (kept and retrievable), a newer CURRENT version is never
touched, and a version that is already CURRENT is refreshed in place only while it is untouched since it was inserted by
a seed or by this loader (counsel's sign-off or translations edited in the staff console are never overwritten).
The file name carries a hash of every document, so the name is stable for the same documents; see
docs/engineering/legal-documents.md.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs", "legal")
MIGRATIONS = os.path.join(ROOT, "java", "services", "content", "src", "main", "resources", "db", "migration")
ENUM = os.path.join(ROOT, "java", "services", "content", "src", "main", "java", "com", "afrotransact", "content",
                    "domain", "legal", "LegalDocumentType.java")

LOCALES = ("en", "fr", "es", "ar", "yo")
BINDING = "en"
# {token}s the content service fills from the site config (LegalServiceImpl.placeholders + the contact emails).
PLACEHOLDERS = {"phone", "phoneE164", "address", "smsProgramName", "support", "privacy", "accessibility", "legal", "press",
                "partners", "careers"}
GENERATOR_VERSION = "1"
NAMESPACE = uuid.UUID("427750c4-de3c-5039-b398-ffe1b3e09178")  # the V5 seed's namespace: ids stay deterministic
LOADER = "legal-loader"
FILE_RE = re.compile(r"^([a-z]+(?:-[a-z]+)*)\.([a-z]{2})\.md$")
VERSION_RE = re.compile(r"^(\d{1,3})\.(\d{1,3})(?:\.(\d{1,3}))?$")
GENERATED_RE = re.compile(r"^V(\d+)__legal_documents_([0-9a-f]{12})\.sql$")
MIGRATION_RE = re.compile(r"^V(\d+)__")
SEED_ROW = re.compile(r"values \('[^']+', '([a-z-]+)', '[0-9.]+', (\d+), (\d+), (\d+), '[0-9-]+', '(CURRENT|ARCHIVED)'")


class Problem(Exception):
    pass


def known_keys():
    """Document keys the content service accepts (LegalDocumentType)."""
    with open(ENUM, encoding="utf-8") as f:
        return re.findall(r'^\s+[A-Z_]+\("([a-z-]+)",', f.read(), re.M)


def seeded_current_versions(migrations_dir):
    """Highest version per key already inserted by non-generated migrations (seeds the loader must not go below)."""
    best = {}
    for name in sorted(os.listdir(migrations_dir)):
        if not MIGRATION_RE.match(name) or GENERATED_RE.match(name):
            continue
        with open(os.path.join(migrations_dir, name), encoding="utf-8") as f:
            for key, ma, mi, pa, status in SEED_ROW.findall(f.read()):
                if status == "CURRENT":
                    best[key] = max(best.get(key, (0, 0, 0)), (int(ma), int(mi), int(pa)))
    return best


def parse_version(raw, where):
    m = VERSION_RE.match(str(raw).strip())
    if not m:
        raise Problem(f"{where}: version {raw!r} must look like 1.2 or 1.2.1")
    return int(m.group(1)), int(m.group(2)), int(m.group(3) or 0)


def display_version(v):
    return f"{v[0]}.{v[1]}" if v[2] == 0 else f"{v[0]}.{v[1]}.{v[2]}"


def split_front_matter(text, where):
    text = text.replace("\r\n", "\n").lstrip("﻿")
    if not text.startswith("---\n"):
        raise Problem(f"{where}: starts without YAML front matter (--- ... ---)")
    end = text.find("\n---\n", 4)
    if end < 0:
        raise Problem(f"{where}: front matter is not closed with ---")
    return text[4:end], text[end + 5:].strip("\n")


def load_front_matter(raw, where):
    try:
        import yaml
    except ImportError as e:  # pragma: no cover
        raise Problem("PyYAML is required: pip install pyyaml") from e
    try:
        data = yaml.safe_load(raw) or {}
    except yaml.YAMLError as e:
        raise Problem(f"{where}: invalid front matter: {e}") from e
    if not isinstance(data, dict):
        raise Problem(f"{where}: front matter must be a mapping")
    return data


def load_documents(docs_dir, keys):
    """{key: {'version': tuple, 'effective': str, 'draft': bool, 'summary': str|None, 'applies_to': ..., 'bodies': {loc: md}}}"""
    docs = {}
    if not os.path.isdir(docs_dir):
        return docs
    problems = []
    for name in sorted(os.listdir(docs_dir)):
        if not name.endswith(".md"):
            continue
        m = FILE_RE.match(name)
        where = os.path.relpath(os.path.join(docs_dir, name), ROOT) if docs_dir.startswith(ROOT) else name
        if not m:
            problems.append(f"{where}: file names are <key>.<locale>.md (for example gift-card-terms.en.md)")
            continue
        key, loc = m.groups()
        try:
            with open(os.path.join(docs_dir, name), encoding="utf-8") as f:
                front_raw, body = split_front_matter(f.read(), where)
            front = load_front_matter(front_raw, where)
            if key not in keys:
                raise Problem(f"{where}: {key!r} is not a legal document key (content LegalDocumentType): {', '.join(keys)}")
            if loc not in LOCALES:
                raise Problem(f"{where}: locale {loc!r} must be one of {', '.join(LOCALES)}")
            if front.get("key") != key:
                raise Problem(f"{where}: front matter key {front.get('key')!r} must equal the file name key {key!r}")
            # Read the version as text: YAML would turn an unquoted 1.10 into the number 1.1.
            vm = re.search(r"^version:\s*[\"']?([0-9][0-9.]*)[\"']?\s*(?:#.*)?$", front_raw, re.M)
            version = parse_version(vm.group(1) if vm else front.get("version"), where)
            eff = front.get("effective")
            if isinstance(eff, datetime.date):
                eff = eff.isoformat()
            try:
                datetime.date.fromisoformat(str(eff))
            except ValueError as e:
                raise Problem(f"{where}: effective {eff!r} must be a date like 2026-11-01") from e
            draft = front.get("draft", True)
            if not isinstance(draft, bool):
                raise Problem(f"{where}: draft must be true or false")
            summary = front.get("change_summary")
            if summary is not None:
                summary = " ".join(str(summary).split()) or None
            if not re.match(r"^# \S", body):
                raise Problem(f"{where}: the body must start with a '# Title' line")
            if not re.search(r"^## \S", body, re.M):
                raise Problem(f"{where}: the body needs at least one '## Section' heading (they become 'On this page')")
            if "${" in body:
                raise Problem(f"{where}: '${{' would be treated as a Flyway placeholder; rephrase")
            if len(body) > 200_000:
                raise Problem(f"{where}: more than 200,000 characters")
            for token in sorted(set(re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", body))):
                if token not in PLACEHOLDERS:
                    raise Problem(f"{where}: unknown placeholder {{{token}}}; supported: {', '.join(sorted(PLACEHOLDERS))}")
            entry = docs.setdefault(key, {"bodies": {}, "meta": {}})
            entry["bodies"][loc] = body
            entry["meta"][loc] = {"version": version, "effective": str(eff), "draft": draft, "summary": summary,
                                  "applies_to": front.get("applies_to"), "title": front.get("title")}
        except Problem as e:
            problems.append(str(e))
    for key, entry in docs.items():
        if BINDING not in entry["bodies"]:
            problems.append(f"docs/legal/{key}.{BINDING}.md is missing: English is the binding text and is required")
            continue
        en = entry["meta"][BINDING]
        for loc, meta in entry["meta"].items():
            if loc != BINDING and meta["version"] != en["version"]:
                problems.append(f"{key}.{loc}.md: version {display_version(meta['version'])} differs from the English "
                                f"{display_version(en['version'])}; translations carry the same version")
        entry["version"], entry["effective"], entry["draft"], entry["summary"] = (en["version"], en["effective"], en["draft"],
                                                                                 en["summary"])
        entry["applies_to"] = en["applies_to"]
    if problems:
        raise Problem("\n".join(problems))
    return {k: v for k, v in sorted(docs.items(), key=lambda kv: keys.index(kv[0]))}


def check_against_seeds(docs, seeded):
    problems = []
    for key, d in docs.items():
        have = seeded.get(key)
        if have and d["version"] < have:
            problems.append(f"{key}: version {display_version(d['version'])} is lower than the {display_version(have)} the "
                            f"content service already seeds; raise it above {display_version(have)} (or use {display_version(have)} to "
                            f"replace the draft text in place)")
    if problems:
        raise Problem("\n".join(problems))


def content_hash(docs):
    h = hashlib.sha256()
    h.update(f"legal-seed:{GENERATOR_VERSION}\n".encode())
    for key, d in docs.items():
        h.update(json.dumps({"key": key, "version": d["version"], "effective": d["effective"], "draft": d["draft"],
                             "summary": d["summary"], "bodies": {loc: d["bodies"][loc] for loc in sorted(d["bodies"])}},
                            sort_keys=True, ensure_ascii=False).encode())
        h.update(b"\n")
    return h.hexdigest()[:12]


def lit(text):
    return "'" + text.replace("'", "''") + "'"


def render(docs, digest):
    out = [
        f"-- GENERATED by tools/legal/build_seed.py from docs/legal/*.md (hash {digest}); do not edit.",
        "-- Raises each document to the written version the way LegalDocument.raise does: the new version becomes CURRENT,",
        "-- an older CURRENT version is ARCHIVED, a newer CURRENT version is left alone, and a version that is already",
        "-- CURRENT is refreshed in place only while no one has edited it since a seed or this loader inserted it.",
        "-- Run: python3 tools/legal/build_seed.py   (CI: python3 tools/legal/build_seed.py --check)",
        "",
    ]
    for key, d in docs.items():
        ma, mi, pa = d["version"]
        version = display_version(d["version"])
        vid = uuid.uuid5(NAMESPACE, f"{key}:{version}")
        bodies = json.dumps({loc: d["bodies"][loc] for loc in sorted(d["bodies"])}, ensure_ascii=False)
        tup = f"({ma}, {mi}, {pa})"
        summary = lit(d["summary"]) if d["summary"] else "null"
        locales = ", ".join(sorted(d["bodies"]))
        out += [
            f"-- {key} {version} (effective {d['effective']}; locales: {locales}; draft banner {'on' if d['draft'] else 'off'})",
            f"update legal_version set status = 'ARCHIVED', archived_at = now(), updated_at = now()",
            f"    where doc_type = {lit(key)} and status = 'CURRENT' and (major, minor, patch) < {tup};",
            "insert into legal_version (id, doc_type, version, major, minor, patch, effective_date, status, draft_banner, bodies,",
            "                           change_summary, created_at, created_by, updated_at, archived_at)",
            f"select {lit(str(vid))}::uuid, {lit(key)}, {lit(version)}, {ma}, {mi}, {pa}, {lit(d['effective'])}::date, 'CURRENT', "
            f"{'true' if d['draft'] else 'false'},",
            f"       {lit(bodies)}::jsonb, {summary}, now(), {lit(LOADER)}, now(), null",
            f" where not exists (select 1 from legal_version where doc_type = {lit(key)} and (major, minor, patch) >= {tup});",
            "update legal_version set effective_date = " + f"{lit(d['effective'])}::date, draft_banner = {'true' if d['draft'] else 'false'},",
            f"       bodies = {lit(bodies)}::jsonb, change_summary = {summary}, created_at = now(), updated_at = now()",
            f"    where doc_type = {lit(key)} and (major, minor, patch) = {tup} and status = 'CURRENT'",
            f"      and created_by in ('seed', {lit(LOADER)}) and updated_at = created_at;",
            "",
        ]
    return "\n".join(out)


def generated_migrations(migrations_dir):
    found = []
    for name in os.listdir(migrations_dir):
        m = GENERATED_RE.match(name)
        if m:
            found.append((int(m.group(1)), m.group(2), name))
    return sorted(found)


def next_version(migrations_dir):
    versions = [int(m.group(1)) for n in os.listdir(migrations_dir) if (m := MIGRATION_RE.match(n))]
    return max(versions, default=0) + 1


def run(docs_dir, migrations_dir, check=False, rewrite=False, listing=False, out=sys.stdout):
    keys = known_keys()
    docs = load_documents(docs_dir, keys)
    seeded = seeded_current_versions(migrations_dir)
    check_against_seeds(docs, seeded)
    if listing:
        for key in keys:
            d = docs.get(key)
            seeded_v = display_version(seeded[key]) if key in seeded else "-"
            written = f"{display_version(d['version'])} ({','.join(sorted(d['bodies']))})" if d else "not written"
            print(f"{key:28} seeded {seeded_v:6} written {written}", file=out)
        return 0
    if not docs:
        print("docs/legal has no documents: nothing to load (the content service keeps its seeded drafts)", file=out)
        return 0
    digest = content_hash(docs)
    sql = render(docs, digest)
    existing = generated_migrations(migrations_dir)
    latest = existing[-1] if existing else None
    if check:
        if latest is None:
            print(f"docs/legal has {len(docs)} document(s) but there is no V<n>__legal_documents_<hash>.sql migration; "
                  f"run: python3 tools/legal/build_seed.py", file=sys.stderr)
            return 1
        if latest[1] != digest:
            print(f"docs/legal changed (hash {digest}) but the newest legal migration {latest[2]} was generated from "
                  f"{latest[1]}; run: python3 tools/legal/build_seed.py", file=sys.stderr)
            return 1
        with open(os.path.join(migrations_dir, latest[2]), encoding="utf-8") as f:
            if f.read() != sql:
                print(f"{latest[2]} was edited by hand or generated by another version of the tool; "
                      f"run: python3 tools/legal/build_seed.py --rewrite", file=sys.stderr)
                return 1
        print(f"legal documents are up to date ({len(docs)} documents, {latest[2]})", file=out)
        return 0
    if latest is not None and latest[1] == digest:
        print(f"up to date: {latest[2]}", file=out)
        return 0
    if rewrite and latest is not None:
        number = latest[0]
        os.remove(os.path.join(migrations_dir, latest[2]))
    else:
        number = next_version(migrations_dir)
    name = f"V{number}__legal_documents_{digest}.sql"
    with open(os.path.join(migrations_dir, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(sql)
    print(f"wrote {name} ({len(docs)} documents)", file=out)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="fail when docs/legal changed but the migration did not")
    ap.add_argument("--rewrite", action="store_true", help="replace the newest generated migration instead of adding one")
    ap.add_argument("--list", action="store_true", dest="listing", help="show documents, seeded and written versions")
    ap.add_argument("--docs", default=DOCS, help="documents directory (default docs/legal)")
    ap.add_argument("--out", default=MIGRATIONS, help="content migrations directory")
    args = ap.parse_args(argv)
    try:
        return run(args.docs, args.out, args.check, args.rewrite, args.listing)
    except Problem as e:
        print(f"legal documents: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
