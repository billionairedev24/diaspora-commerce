#!/usr/bin/env python3
"""Three-way merge of docs/backlog/backlog.csv by Issue Id (used by tools/release/sync-main.sh; never by hand-editing conflict markers).

A line-based git merge of this file corrupts it: the rows have multi-line quoted fields, so two sides that both append rows interleave
the lines of one row with another (that is what happened to S494 on release-1). This tool merges ROWS:

  * a row only one side added or changed is taken as is; a row both sides added identically is taken once;
  * a row changed on both sides is merged FIELD by FIELD; two different values for the same field are a CONFLICT (reported, never guessed);
  * a row one side removed and the other left alone is removed; removed on one side and changed on the other is a CONFLICT;
  * release-1 stories live in the block S600-S699 (epic E37). When an id that release-1 added collides with a different row main added,
    the RELEASE-1 row is renumbered (a story to the next free id in S600-S699, an epic to the next free E<n> above both sides) and the
    Parent Id of its children follows; the renumbering is reported. Main's ids never move;
  * output order: rows in main's order, then the rows only release-1 has (its block), each in their own order; the file keeps main's
    line ending (CRLF) and QUOTE_ALL, so a merge that changes nothing is byte-identical.

The result is validated like the `backlog` workflow does (header, unique ids, parents are epics, statuses, types). A file that is already
corrupt on either side is refused with the offending ids (repair it from main's copy plus the release-1 rows first).

  python3 tools/release/merge_backlog.py --git origin/main            merge main into the work tree's file (base = merge-base)
  python3 tools/release/merge_backlog.py --base b.csv --ours o.csv --theirs t.csv --out merged.csv
Exit 0 merged (the file is written), 2 conflicts or a corrupt input (nothing is written; the report names them).
Standard library only.
"""
import argparse
import csv
import io
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
REL = "docs/backlog/backlog.csv"
HEADER = ["Issue Id", "Issue Type", "Summary", "Description", "Acceptance Criteria", "Parent Id", "Epic Name", "Priority", "Story Points",
          "Labels", "Component", "Status"]
STATUSES = {"To Do", "In Progress", "In Review", "Done"}
TYPES = {"Epic", "Story", "Task", "Bug", "Sub-task"}
PRIORITIES = {"Highest", "High", "Medium", "Low", "Lowest"}
RELEASE_BLOCK = range(600, 700)


class Refused(Exception):
    def __init__(self, lines):
        super().__init__("\n".join(lines))
        self.lines = lines


def parse(text, label):
    rows = list(csv.reader(io.StringIO(text, newline="")))
    if not rows or rows[0] != HEADER:
        raise Refused([f"{label}: header is not the Jira import header"])
    problems = validate_rows(rows[1:], label)
    if problems:
        raise Refused(problems)
    return rows[1:]


def validate_rows(rows, label):
    problems, seen = [], set()
    epics = {r[0] for r in rows if len(r) == len(HEADER) and r[1] == "Epic"}
    for r in rows:
        if len(r) != len(HEADER):
            problems.append(f"{label}: a row has {len(r)} fields instead of {len(HEADER)} (starts with {r[0][:40]!r})")
            continue
        if not re.fullmatch(r"[ES]\d+", r[0]):
            problems.append(f"{label}: {r[0][:40]!r} is not an Issue Id (a corrupt multi-line row, see the header of merge_backlog.py)")
        if r[0] in seen:
            problems.append(f"{label}: duplicate id {r[0]}")
        seen.add(r[0])
        if r[1] not in TYPES:
            problems.append(f"{label}: {r[0]} has type {r[1]!r}")
        if r[11] not in STATUSES:
            problems.append(f"{label}: {r[0]} has status {r[11]!r}")
        if r[7] and r[7] not in PRIORITIES:
            problems.append(f"{label}: {r[0]} has priority {r[7]!r}")
        if r[8] and not r[8].isdigit():
            problems.append(f"{label}: {r[0]} has story points {r[8]!r}")
        if not r[2].strip():
            problems.append(f"{label}: {r[0]} has an empty summary")
        if r[1] != "Epic" and r[5] and r[5] not in epics:
            problems.append(f"{label}: {r[0]} has parent {r[5]} which is not an epic")
    return problems


def serialize(rows, crlf=True):
    out = io.StringIO(newline="")
    csv.writer(out, quoting=csv.QUOTE_ALL, lineterminator="\r\n" if crlf else "\n").writerows([HEADER] + rows)
    return out.getvalue()


def num(i):
    return int(i[1:])


def merge(base_text, ours_text, theirs_text):
    """Return (merged_text, report_lines). Raises Refused on a corrupt input or an unresolvable conflict."""
    base = {r[0]: r for r in parse(base_text, "base")} if base_text else {}
    ours_rows, theirs_rows = parse(ours_text, "release-1"), parse(theirs_text, "main")
    ours, theirs = {r[0]: r for r in ours_rows}, {r[0]: r for r in theirs_rows}
    crlf = '"\r\n' in theirs_text
    report, conflicts = [], []
    result = {}
    renames = {}

    for i, t in theirs.items():
        o, b = ours.get(i), base.get(i)
        if o is None:
            if b is not None and b != t:
                conflicts.append(f"{i}: removed on release-1, changed on main")
            elif b is not None:
                report.append(f"{i}: removed on release-1, kept removed")
            else:
                result[i] = t
        elif o == t:
            result[i] = t
        elif b is None:
            # both added the id with different content: the release-1 row moves (renumbered below), main's stays
            result[i] = t
            renames[i] = o
        elif o == b:
            result[i] = t                      # only main changed it
        elif t == b:
            result[i] = o                      # only release-1 changed it
        else:
            merged, clash = list(b), []
            for k, (bv, ov, tv) in enumerate(zip(b, o, t)):
                if ov == tv:
                    merged[k] = ov
                elif ov == bv:
                    merged[k] = tv
                elif tv == bv:
                    merged[k] = ov
                else:
                    clash.append(HEADER[k])
            if clash:
                conflicts.append(f"{i}: both sides changed {', '.join(clash)} (release-1: {', '.join(repr(o[HEADER.index(c)][:50]) for c in clash)}; main: {', '.join(repr(t[HEADER.index(c)][:50]) for c in clash)})")
            else:
                result[i] = merged
                report.append(f"{i}: changed on both sides in different fields, merged")
    for i, o in ours.items():
        if i in theirs:
            continue
        b = base.get(i)
        if b is not None and b == o:
            report.append(f"{i}: removed on main, removed")
        elif b is not None:
            conflicts.append(f"{i}: removed on main, changed on release-1")
        else:
            result[i] = o                       # release-1's own row (its block)

    # renumber the release-1 rows that collided with a different row main added
    taken = set(result) | set(theirs)
    for old, row in renames.items():
        if row[1] == "Epic":
            n = max(num(i) for i in taken if i.startswith("E")) + 1
            new = f"E{n:0{len(old) - 1}d}"
        else:
            free = [n for n in RELEASE_BLOCK if f"S{n}" not in taken]
            if not free:
                conflicts.append(f"{old}: release-1 block S600-S699 is full, cannot renumber")
                continue
            new = f"S{free[0]}"
        taken.add(new)
        moved = list(row)
        moved[0] = new
        result[new] = moved
        for r in result.values():
            if r[5] == old and r[0] in ours and r[0] not in theirs:    # release-1's own children follow their renumbered epic
                r[5] = new
        report.append(f"RENUMBERED {old} -> {new} (release-1 row collided with main's {old}; grep docs/ and commit messages for '{old}' if it is referenced)")
    if conflicts:
        raise Refused(["conflict: " + c for c in conflicts])

    order = [i for i in theirs if i in result] + [i for i in result if i not in theirs]
    merged_rows = [result[i] for i in order]
    problems = validate_rows(merged_rows, "merged")
    if problems:
        raise Refused(problems)
    outside = [r[0] for r in merged_rows if r[0] not in theirs and r[0] not in base and not (r[0].startswith("E") or num(r[0]) in RELEASE_BLOCK)]
    if outside:
        report.append(f"note: release-1 rows outside the block S600-S699: {', '.join(outside)} (they may collide with main's next ids)")
    return serialize(merged_rows, crlf), report


def git_show(ref, rel=REL, cwd=ROOT):
    r = subprocess.run(["git", "-C", str(cwd), "show", f"{ref}:{rel}"], capture_output=True)
    return r.stdout.decode("utf-8") if r.returncode == 0 else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--git", metavar="THEIRS_REF", help="merge this ref's file into the work tree's (base = merge-base of HEAD and the ref)")
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--base")
    ap.add_argument("--ours")
    ap.add_argument("--theirs")
    ap.add_argument("--out")
    args = ap.parse_args(argv)
    root = pathlib.Path(args.root)
    if args.git:
        mb = subprocess.run(["git", "-C", str(root), "merge-base", "HEAD", args.git], capture_output=True, text=True).stdout.strip()
        base_text, theirs_text = git_show(mb, cwd=root), git_show(args.git, cwd=root)
        ours_path, out_path = root / REL, root / REL
        ours_text = ours_path.read_text(encoding="utf-8", newline="")
    else:
        if not (args.ours and args.theirs and args.out):
            ap.error("--git, or --ours --theirs --out (and --base)")
        base_text = pathlib.Path(args.base).read_text(encoding="utf-8", newline="") if args.base else ""
        ours_text = pathlib.Path(args.ours).read_text(encoding="utf-8", newline="")
        theirs_text = pathlib.Path(args.theirs).read_text(encoding="utf-8", newline="")
        out_path = pathlib.Path(args.out)
    if theirs_text is None:
        print("merge_backlog: the other side has no backlog.csv", file=sys.stderr)
        return 2
    try:
        text, report = merge(base_text or "", ours_text, theirs_text)
    except Refused as e:
        print("merge_backlog: NOT merged\n  " + "\n  ".join(e.lines), file=sys.stderr)
        return 2
    out_path.write_bytes(text.encode("utf-8"))
    for line in report:
        print("merge_backlog: " + line)
    print(f"merge_backlog: wrote {out_path} ({text.count(chr(10))} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
