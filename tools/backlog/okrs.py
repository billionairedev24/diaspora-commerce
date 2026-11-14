"""AfroTransact v2 OKRs in Jira Product Discovery (project OKR), each objective linked to its delivery epics in ATV2.

Idempotent by label okr-O<n>. Targets are DRAFTS for the product owner to confirm; the measure for each key result
names where its number comes from, so nothing is a guess dressed as a fact.

    python3 tools/backlog/okrs.py            # create/update objectives, themes, links
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jira_sync as js  # noqa: E402

ROOT = js.ROOT
KEY = "OKR"
THEMES = ["Launch safely", "Tax you can trust", "Seller success", "Shopper trust", "Quality and cost"]

OKRS = [
    ("O1", "Launch the US marketplace in production, safely", "Launch safely", "Now", ["E29", "E30", "E28", "E01"],
     "Production on OVH Managed Kubernetes runs the launch set with no data or security incident.",
     [("Every service in infra/argocd/launch-set is deployed in prod and healthy 7 days in a row", "Argo CD + /actuator/health, /healthz"),
      ("Storefront and checkout availability at least 99.5% over 30 days", "Prometheus / Grafana uptime SLO"),
      ("Zero open Highest-priority stories in the launch epics at go-live", "Jira filter ATV2 launch blockers"),
      ("Demo data never present in prod: launch check green on every deploy", "make launch-set-check, CI")]),
    ("O2", "Every live product carries a correct tax decision, never a guess", "Tax you can trust", "Now", ["E17"],
     "Tax is automatic from rules; people review only conflicts, no-match and regulated categories.",
     [("100% of live listings have a stored RESOLVED tax decision", "catalog tax gate sweep report"),
      ("Shadow report: at least 99% of lines within $0.01 of Stripe Tax and Avalara over 30 days", "Staff console Sales tax > Shadow report"),
      ("At most 5% of new listings need a person; median review under 24 hours", "Tax review queue metrics"),
      ("Texas tax professional signs off the rules before RISK_BASED replaces review-all in prod", "Signed decision record")]),
    ("O3", "Sellers list and sell with store pickup and AfroTransact delivery", "Seller success", "Now", ["E13", "E24", "E12", "E08", "E03"],
     "Most early sellers use pickup and delivery by AfroTransact; staff run delivery end to end without the driver app.",
     [("Launch sellers live with at least one in-stock listing with a real photo (target set by owner)", "Seller Center / catalog"),
      ("Median time from sign-up to first live listing under 15 minutes", "Seller onboarding funnel analytics"),
      ("100% of AfroTransact-delivered orders carry delivery proof shown on the customer order", "Delivery module audit"),
      ("Zero seller-shipped orders waiting in an AfroTransact driver queue", "Orders state report")]),
    ("O4", "Shoppers can trust every number and promise we show", "Shopper trust", "Next", ["E06", "E23", "E22", "E04"],
     "Estimated tax, arrival windows and recommendations come from real data only.",
     [("Estimated tax within 1% of final on at least 99% of orders", "orders vs checkout estimate log"),
      ("At least 95% of packages arrive inside the window shown at checkout", "promise service vs delivered events"),
      ("Claims audit stays green: no banned phrase, no unearned social proof", "storefront banned-phrase test in CI"),
      ("Checkout conversion baseline measured, then improved (owner sets target after 30 days)", "storefront analytics")]),
    ("O5", "Ship with quality, observability and cost under control", "Quality and cost", "Next", ["E36", "E33", "E28", "E21", "E31"],
     "Main stays green, stories are verified before Done, and AI spend stays inside budget.",
     [("CI green on main on at least 95% of days", "GitHub Actions"),
      ("No story In Review for more than 7 days", "Jira filter ATV2 in review"),
      ("Zero known vulnerabilities in dependencies", "pnpm audit, OWASP, govulncheck in CI"),
      ("Lola AI and MCP spend at most USD 2 per day", "ai-gateway cost metrics")]),
]


def adf(okr):
    _, title, theme, roadmap, epics, why, krs = okr
    base = js.BASE
    keys = json.load(open(js.MAP_PATH))
    rows = [{"type": "tableRow", "content": [js.cell(*js.text(h), header=True, width=w) for h, w in
                                             zip(("#", "Key result", "Measured in", "Target"), (50, 430, 230, 110))]}]
    for i, (kr, where) in enumerate(krs, 1):
        rows.append({"type": "tableRow", "content": [js.cell(*js.text("KR%d" % i), width=50), js.cell(*js.text(kr), width=430),
                                                    js.cell(*js.text(where), width=230),
                                                    js.cell(js.lozenge("draft", "yellow"), width=110)]})
    links = []
    for e in epics:
        k = keys.get(e)
        if k:
            links += [{"type": "text", "text": k, "marks": [{"type": "link", "attrs": {"href": "%s/browse/%s" % (base, k)}}]},
                      {"type": "text", "text": "  "}]
    return {"type": "doc", "version": 1, "content": [
        {"type": "paragraph", "content": [js.lozenge(theme, "purple"), {"type": "text", "text": "  "}, js.lozenge(roadmap, "blue")]},
        js.panel("note", js.para(why)),
        js.heading("Key results", 2),
        {"type": "table", "attrs": {"isNumberColumnEnabled": False, "layout": "wide"}, "content": rows},
        js.heading("Delivered by", 3), {"type": "paragraph", "content": links or js.text("-")},
        js.panel("warning", js.para("Targets are drafts for the product owner to confirm.")),
    ]}


def ensure_themes(c):
    ctx = c.call("GET", "/rest/api/3/field/customfield_10243/context")["values"][0]["id"]
    opts = c.call("GET", "/rest/api/3/field/customfield_10243/context/%s/option" % ctx)["values"]
    have = {o["value"] for o in opts}
    new = [{"value": t} for t in THEMES if t not in have]
    if new:
        c.call("POST", "/rest/api/3/field/customfield_10243/context/%s/option" % ctx, {"options": new})
    for o in opts:
        if o["value"] not in THEMES:
            try:
                c.call("DELETE", "/rest/api/3/field/customfield_10243/context/%s/option/%s" % (ctx, o["id"]))
            except RuntimeError:
                pass


def main():
    env = js.load_env("~/.jira.env")
    c = js.Jira(env["JIRA_BASE_URL"], env["JIRA_EMAIL"], env["JIRA_API_TOKEN"])
    js.BASE = env["JIRA_BASE_URL"].rstrip("/")
    epic_keys = json.load(open(js.MAP_PATH))
    itype = c.call("GET", "/rest/api/3/project/%s?expand=issueTypes" % KEY)["issueTypes"][0]["id"]
    for okr in OKRS:
        oid, title, theme, roadmap, epics, why, krs = okr
        fields = {"summary": "%s · %s" % (oid, title), "description": adf(okr), "labels": ["okr", "okr-" + oid, "theme-" + theme.lower().replace(" ", "-")],
                  "customfield_10244": {"value": roadmap},
                  "customfield_10254": why[:250]}
        hits = c.search('project = %s AND labels = "okr-%s"' % (KEY, oid), fields="summary")
        if hits:
            key = hits[0]["key"]
            c.call("PUT", "/rest/api/3/issue/" + key, {"fields": fields})
        else:
            fields.update({"project": {"key": KEY}, "issuetype": {"id": itype}})
            key = c.call("POST", "/rest/api/3/issue", {"fields": fields})["key"]
        linked = {l.get("outwardIssue", l.get("inwardIssue", {})).get("key")
                  for l in c.call("GET", "/rest/api/3/issue/%s?fields=issuelinks" % key)["fields"]["issuelinks"]}
        for e in epics:
            ek = epic_keys.get(e)
            if ek and ek not in linked:
                c.call("POST", "/rest/api/3/issueLink", {"type": {"name": "Relates"},
                                                          "inwardIssue": {"key": key}, "outwardIssue": {"key": ek}})
        print(key, oid, title, "->", ", ".join(epic_keys.get(e, e + "?") for e in epics))


if __name__ == "__main__":
    main()
