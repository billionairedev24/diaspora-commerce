#!/usr/bin/env python3
"""Load the written legal documents (docs/legal/<key>.<locale>.md) into the content service as a Flyway migration.

    python3 tools/legal/build_seed.py            # write java/services/content/src/main/resources/db/migration/V<n>__legal_documents_<hash>.sql
    python3 tools/legal/build_seed.py --check    # CI: fail when docs/legal changed but the migration did not
    python3 tools/legal/build_seed.py --list     # documents found, versions, and what the content service already seeds
    python3 tools/legal/build_seed.py --rewrite  # replace the newest generated migration (only while it is unreleased)

Each document is `docs/legal/<key>.en.md` (English, binding, required) plus optional `<key>.fr.md`, `.es.md`, `.ar.md` and
`.yo.md` translations. Other files in the folder (README.md, notes) are ignored. A file starts with front matter (simple
`key: value` lines) and then the Markdown body:

    ---
    key: gift-card-terms
    title: Gift Card Terms
    version: 1.1              # major.minor[.patch]; translations carry the same version
    effective: 2026-11-01     # a date, or TBD-at-launch (then --launch-date / LEGAL_LAUNCH_DATE, default 2026-12-01)
    change_summary: Adds the dormant balance rules
    applies_to: Anyone who buys or redeems a gift card
    draft: true               # true shows "Draft for legal counsel review" until counsel signs off in the staff console
    ---
    # Gift Card Terms

    ## Scope
    ...

The generated migration is idempotent and follows the content service's version rules (LegalDocument.raise): the new
version becomes CURRENT, an older CURRENT version is ARCHIVED (kept and retrievable), a newer CURRENT version is never
touched, and a version that is already CURRENT is refreshed in place only while it is untouched since it was inserted by a
seed or by this loader (counsel's sign-off or translations edited in the staff console are never overwritten). The skeleton
texts V5/V13 seed are placeholders with an invented history: a written version that is *lower* than a still-untouched seeded
one (the first written Conditions of Use is 1.0, the seed says 3.2) replaces the placeholder and its invented history.
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
# Documents say "effective: TBD-at-launch" until the launch date is fixed; the staff console can still amend a date.
DEFAULT_LAUNCH_DATE = "2026-12-01"
GENERATOR_VERSION = "2"
NAMESPACE = uuid.UUID("427750c4-de3c-5039-b398-ffe1b3e09178")  # the V5 seed's namespace: ids stay deterministic
LOADER = "legal-loader"
FILE_RE = re.compile(r"^([a-z]+(?:-[a-z]+)*)\.([a-z]{2})\.md$")
VERSION_RE = re.compile(r"^(\d{1,3})\.(\d{1,3})(?:\.(\d{1,3}))?$")
GENERATED_RE = re.compile(r"^V(\d+)__legal_documents_([0-9a-f]{12})\.sql$")
MIGRATION_RE = re.compile(r"^V(\d+)__")
SEED_ROW = re.compile(r"values \('[^']+', '([a-z-]+)', '[0-9.]+', (\d+), (\d+), (\d+), '[0-9-]+', '(CURRENT|ARCHIVED)'")
FRONT_LINE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*):\s*(.*)$")
COUNSEL_MARK = re.compile(r"\[\[LEGAL")


class Problem(Exception):
    pass


def known_keys():
    """Document keys the content service accepts (LegalDocumentType)."""
    with open(ENUM, encoding="utf-8") as f:
        return re.findall(r'^\s+[A-Z_]+\("([a-z-]+)",', f.read(), re.M)


def seeded_current_versions(migrations_dir):
    """Highest version per key already inserted by non-generated migrations (the seeded placeholder texts)."""
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
        raise Problem(f"{where}: starts without front matter (--- ... ---)")
    end = text.find("\n---\n", 4)
    if end < 0:
        raise Problem(f"{where}: front matter is not closed with ---")
    return text[4:end], text[end + 5:].strip("\n")


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def load_front_matter(raw, where):
    """`key: value` lines; an indented line continues the previous value. Deliberately not YAML: values such as
    'applies_to: Sellers: all of them' are plain text."""
    data = {}
    key = None
    for line in raw.split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = FRONT_LINE.match(line)
        if m and not line[0].isspace():
            key = m.group(1)
            data[key] = m.group(2).strip()
        elif key is not None and line[0].isspace():
            data[key] = f"{data[key]} {line.strip()}".strip()
        else:
            raise Problem(f"{where}: cannot read front matter line {line!r}; use 'key: value'")
    return data


def scalar(data, key):
    """A single-word front matter value without quotes or a trailing comment."""
    v = data.get(key)
    if v is None:
        return None
    v = v.strip()
    if v[:1] in "\"'":
        return unquote(re.sub(r"\s+#.*$", "", v))
    return re.sub(r"\s+#.*$", "", v).strip()


def load_documents(docs_dir, keys, launch_date):
    """{key: {'version', 'effective', 'draft', 'summary', 'applies_to', 'bodies': {loc: md}, 'markers': n}} in legal-center order."""
    docs = {}
    if not os.path.isdir(docs_dir):
        return docs
    problems = []
    for name in sorted(os.listdir(docs_dir)):
        m = FILE_RE.match(name)
        if not m:
            continue  # README.md, open-questions-*.md and other notes are not documents
        key, loc = m.groups()
        where = os.path.relpath(os.path.join(docs_dir, name), ROOT) if docs_dir.startswith(ROOT) else name
        try:
            with open(os.path.join(docs_dir, name), encoding="utf-8") as f:
                front_raw, body = split_front_matter(f.read(), where)
            front = load_front_matter(front_raw, where)
            if key not in keys:
                raise Problem(f"{where}: {key!r} is not a legal document key (content LegalDocumentType): {', '.join(keys)}")
            if loc not in LOCALES:
                raise Problem(f"{where}: locale {loc!r} must be one of {', '.join(LOCALES)}")
            if scalar(front, "key") != key:
                raise Problem(f"{where}: front matter key {front.get('key')!r} must equal the file name key {key!r}")
            version = parse_version(scalar(front, "version"), where)
            eff = scalar(front, "effective") or ""
            try:
                eff = datetime.date.fromisoformat(eff).isoformat()
            except ValueError:
                eff = launch_date  # TBD-at-launch and the like
            draft_raw = (scalar(front, "draft") or "true").lower()
            if draft_raw not in ("true", "false", "yes", "no"):
                raise Problem(f"{where}: draft must be true or false")
            draft = draft_raw in ("true", "yes")
            summary = " ".join(front.get("change_summary", "").split()) or None
            if summary is not None and len(summary) > 1000:
                summary = summary[:997] + "..."
            if not re.match(r"^# \S", body):
                raise Problem(f"{where}: the body must start with a '# Title' line")
            if not re.search(r"^## \S", body, re.M):
                raise Problem(f"{where}: the body needs at least one '## Section' heading (they become 'On this page')")
            if "${" in body:
                raise Problem(f"{where}: '${{' would be treated as a Flyway placeholder; rephrase")
            if len(body) > 200_000:
                raise Problem(f"{where}: more than 200,000 characters")
            markers = len(COUNSEL_MARK.findall(body))
            if markers and not draft:
                raise Problem(f"{where}: {markers} [[LEGAL ...]] counsel marker(s) are still in the text; resolve them before draft: false")
            for token in sorted(set(re.findall(r"\{([A-Za-z][A-Za-z0-9]*)\}", body))):
                if token not in PLACEHOLDERS:
                    raise Problem(f"{where}: unknown placeholder {{{token}}}; supported: {', '.join(sorted(PLACEHOLDERS))}")
            entry = docs.setdefault(key, {"bodies": {}, "meta": {}})
            entry["bodies"][loc] = body
            entry["meta"][loc] = {"version": version, "effective": eff, "draft": draft, "summary": summary,
                                  "applies_to": " ".join(front.get("applies_to", "").split()) or None, "markers": markers}
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
        entry["applies_to"], entry["markers"] = en["applies_to"], en["markers"]
    if problems:
        raise Problem("\n".join(problems))
    return {k: v for k, v in sorted(docs.items(), key=lambda kv: keys.index(kv[0]))}


def seed_replacements(docs, seeded):
    """Notes for documents whose written version is lower than the placeholder the content service seeds."""
    notes = []
    for key, d in docs.items():
        have = seeded.get(key)
        if have and d["version"] < have:
            notes.append(f"{key}: written {display_version(d['version'])} replaces the seeded placeholder "
                         f"{display_version(have)} and its invented history (while the placeholder is untouched)")
    return notes


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
        "-- A written version lower than a still-untouched seeded placeholder replaces the placeholder and its invented history.",
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
        banner = "true" if d["draft"] else "false"
        out += [
            f"-- {key} {version} (effective {d['effective']}; locales: {locales}; draft banner {'on' if d['draft'] else 'off'})",
            f"delete from legal_version where doc_type = {lit(key)} and created_by = 'seed'",
            f"    and exists (select 1 from legal_version c where c.doc_type = {lit(key)} and c.status = 'CURRENT'",
            f"                and c.created_by = 'seed' and c.updated_at = c.created_at and (c.major, c.minor, c.patch) > {tup});",
            "update legal_version set status = 'ARCHIVED', archived_at = now(), updated_at = now()",
            f"    where doc_type = {lit(key)} and status = 'CURRENT' and (major, minor, patch) < {tup};",
            "insert into legal_version (id, doc_type, version, major, minor, patch, effective_date, status, draft_banner, bodies,",
            "                           change_summary, created_at, created_by, updated_at, archived_at)",
            f"select {lit(str(vid))}::uuid, {lit(key)}, {lit(version)}, {ma}, {mi}, {pa}, {lit(d['effective'])}::date, 'CURRENT', {banner},",
            f"       {lit(bodies)}::jsonb, {summary}, now(), {lit(LOADER)}, now(), null",
            f" where not exists (select 1 from legal_version where doc_type = {lit(key)} and (major, minor, patch) >= {tup});",
            f"update legal_version set effective_date = {lit(d['effective'])}::date, draft_banner = {banner},",
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


def run(docs_dir, migrations_dir, check=False, rewrite=False, listing=False, launch_date=None, out=sys.stdout):
    launch = launch_date or os.environ.get("LEGAL_LAUNCH_DATE") or DEFAULT_LAUNCH_DATE
    try:
        datetime.date.fromisoformat(launch)
    except ValueError as e:
        raise Problem(f"launch date {launch!r} must be a date like 2026-12-01") from e
    keys = known_keys()
    docs = load_documents(docs_dir, keys, launch)
    seeded = seeded_current_versions(migrations_dir)
    notes = seed_replacements(docs, seeded)
    if listing:
        for key in keys:
            d = docs.get(key)
            seeded_v = display_version(seeded[key]) if key in seeded else "-"
            if d:
                written = (f"{display_version(d['version'])} {','.join(sorted(d['bodies']))} effective {d['effective']}"
                           f"{' draft' if d['draft'] else ''}{f' [{d['markers']} counsel marker(s)]' if d['markers'] else ''}")
            else:
                written = "not written"
            print(f"{key:26} seeded {seeded_v:5} written {written}", file=out)
        for n in notes:
            print(f"note: {n}", file=out)
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
    for n in notes:
        print(f"note: {n}", file=out)
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
    ap.add_argument("--launch-date", default=None, help=f"effective date for 'TBD-at-launch' documents (default {DEFAULT_LAUNCH_DATE}, or $LEGAL_LAUNCH_DATE)")
    args = ap.parse_args(argv)
    try:
        return run(args.docs, args.out, args.check, args.rewrite, args.listing, args.launch_date)
    except Problem as e:
        print(f"legal documents: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
