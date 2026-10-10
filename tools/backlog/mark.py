"""Update story statuses in docs/backlog/backlog.csv.

Usage:
  python3 tools/backlog/mark.py S001 S002 --status Done
  python3 tools/backlog/mark.py --list            # summary per epic
Epic status is derived: Done when all its stories are Done, In Progress when any story is started.
"""
import argparse
import csv
import os
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATH = os.path.join(ROOT, "docs", "backlog", "backlog.csv")
STATUSES = ["To Do", "In Progress", "In Review", "Done"]


def load():
    with open(PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return reader.fieldnames, list(reader)


def save(fields, rows):
    with open(PATH, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, quoting=csv.QUOTE_ALL)
        w.writeheader()
        w.writerows(rows)


def roll_up(rows):
    children = defaultdict(list)
    for r in rows:
        if r["Issue Type"] != "Epic":
            children[r["Parent Id"]].append(r["Status"])
    for r in rows:
        if r["Issue Type"] == "Epic":
            st = children.get(r["Issue Id"], [])
            if st and all(s == "Done" for s in st):
                r["Status"] = "Done"
            elif any(s != "To Do" for s in st):
                r["Status"] = "In Progress"
            else:
                r["Status"] = "To Do"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("ids", nargs="*")
    p.add_argument("--status", choices=STATUSES)
    p.add_argument("--list", action="store_true")
    a = p.parse_args()
    fields, rows = load()
    if a.ids:
        if not a.status:
            p.error("--status is required with ids")
        known = {r["Issue Id"] for r in rows}
        missing = [i for i in a.ids if i not in known]
        if missing:
            p.error("unknown ids: " + ", ".join(missing))
        for r in rows:
            if r["Issue Id"] in a.ids:
                r["Status"] = a.status
        roll_up(rows)
        save(fields, rows)
    if a.list or not a.ids:
        by_epic = defaultdict(lambda: defaultdict(int))
        names = {}
        for r in rows:
            if r["Issue Type"] == "Epic":
                names[r["Issue Id"]] = r["Summary"]
            else:
                by_epic[r["Parent Id"]][r["Status"]] += 1
        total = defaultdict(int)
        for eid in sorted(names):
            c = by_epic[eid]
            n = sum(c.values())
            for k, v in c.items():
                total[k] += v
            print("%s %-55s %2d/%2d done" % (eid, names[eid][:55], c["Done"], n))
        print("TOTAL %d/%d stories done" % (total["Done"], sum(total.values())))


if __name__ == "__main__":
    main()
