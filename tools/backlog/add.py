"""Append a story to docs/backlog/backlog.csv under an epic, with the next free id.

Usage:
  python3 tools/backlog/add.py E02 "Summary" "Description" "criterion 1|criterion 2" --points 3 --priority High --labels "identity,security"
"""
import argparse
import csv
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATH = os.path.join(ROOT, "docs", "backlog", "backlog.csv")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("epic")
    p.add_argument("summary")
    p.add_argument("description")
    p.add_argument("criteria", help="acceptance criteria separated by |")
    p.add_argument("--points", default="3")
    p.add_argument("--priority", default="High", choices=["Highest", "High", "Medium", "Low"])
    p.add_argument("--labels", default="")
    p.add_argument("--status", default="To Do")
    a = p.parse_args()
    with open(PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fields, rows = reader.fieldnames, list(reader)
    epic = next((r for r in rows if r["Issue Id"] == a.epic and r["Issue Type"] == "Epic"), None)
    if epic is None:
        raise SystemExit("unknown epic " + a.epic)
    next_id = "S%03d" % (1 + max(int(r["Issue Id"][1:]) for r in rows if r["Issue Id"].startswith("S")))
    rows.append({"Issue Id": next_id, "Issue Type": "Story", "Summary": a.summary, "Description": a.description,
                 "Acceptance Criteria": "\n".join("- " + c.strip() for c in a.criteria.split("|") if c.strip()),
                 "Parent Id": a.epic, "Epic Name": "", "Priority": a.priority, "Story Points": a.points,
                 "Labels": " ".join(x for x in a.labels.split(",") if x), "Component": epic["Component"],
                 "Status": a.status})
    with open(PATH, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, quoting=csv.QUOTE_ALL)
        w.writeheader()
        w.writerows(rows)
    print(next_id)


if __name__ == "__main__":
    main()
