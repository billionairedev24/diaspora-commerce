"""Generates docs/backlog/backlog.csv (Jira CSV import format) from the story data files.

Run once to create the file; afterwards the CSV is the source of truth and statuses are updated with mark.py.
Pass --force to regenerate (existing statuses are preserved by Issue Id).
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from stories_a import EPICS_A  # noqa: E402
from stories_b import EPICS_B  # noqa: E402
from stories_c import EPICS_C  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "backlog", "backlog.csv")
HEADER = ["Issue Id", "Issue Type", "Summary", "Description", "Acceptance Criteria", "Parent Id", "Epic Name",
          "Priority", "Story Points", "Labels", "Component", "Status"]


def main():
    if os.path.exists(OUT) and "--force" not in sys.argv:
        sys.exit("backlog.csv exists; use mark.py to update statuses or --force to regenerate")
    previous = {}
    if os.path.exists(OUT):
        with open(OUT, newline="", encoding="utf-8") as f:
            previous = {r["Issue Id"]: r["Status"] for r in csv.DictReader(f)}
    rows = []
    epic_no = 0
    story_no = 0
    for name, desc, component, stories in EPICS_A + EPICS_B + EPICS_C:
        epic_no += 1
        epic_id = "E%02d" % epic_no
        rows.append([epic_id, "Epic", name, desc, "", "", name, "High", "", component, component,
                     previous.get(epic_id, "To Do")])
        for summary, sdesc, criteria, points, priority, labels in stories:
            story_no += 1
            sid = "S%03d" % story_no
            ac = "\n".join("- " + c for c in criteria)
            rows.append([sid, "Story", summary, sdesc, ac, epic_id, "", priority, str(points),
                         " ".join(labels.split(",")), component, previous.get(sid, "To Do")])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, quoting=csv.QUOTE_ALL)
        w.writerow(HEADER)
        w.writerows(rows)
    print("wrote %d epics and %d stories to %s" % (epic_no, story_no, os.path.relpath(OUT, ROOT)))


if __name__ == "__main__":
    main()
