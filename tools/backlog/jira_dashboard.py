"""Saved filters and the 'AfroTransact v2 · Delivery' dashboard for project ATV2 (idempotent by name).

    python3 tools/backlog/jira_dashboard.py
"""
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jira_sync as js  # noqa: E402

P = "ATV2"
FILTERS = {
    "ATV2 · All work": 'project = %s ORDER BY Rank ASC' % P,
    "ATV2 · All stories": 'project = %s AND issuetype = Story ORDER BY Rank ASC' % P,
    "ATV2 · Open work": 'project = %s AND issuetype = Story AND statusCategory != Done ORDER BY priority DESC, Rank ASC' % P,
    "ATV2 · Needs verification (in review)": 'project = %s AND labels = in-review AND statusCategory != Done ORDER BY updated ASC' % P,
    "ATV2 · Launch blockers": 'project = %s AND issuetype = Story AND priority = Highest AND statusCategory != Done ORDER BY Rank ASC' % P,
    "ATV2 · Tax": 'project = %s AND (component in (\"returns\", \"tax-returns\") OR labels = tax) ORDER BY statusCategory ASC, Rank ASC' % P,
    "ATV2 · Work streams (epics)": 'project = %s AND issuetype = Epic ORDER BY key ASC' % P,
    "ATV2 · Done": 'project = %s AND issuetype = Story AND statusCategory = Done ORDER BY resolved DESC' % P,
    "ATV2 · Launch: what's left": 'project = %s AND fixVersion = "Launch" AND statusCategory != Done ORDER BY priority DESC, Rank ASC' % P,
    "ATV2 · Launch: all": 'project = %s AND fixVersion = "Launch" ORDER BY Rank ASC' % P,
    "ATV2 · After launch": 'project = %s AND fixVersion = "After launch" ORDER BY priority DESC, Rank ASC' % P,
    "ATV2 · QA": 'project = %s AND labels = qa ORDER BY priority DESC, Rank ASC' % P,
    "ATV2 · Open stories (all releases)": 'project = %s AND issuetype = Story AND statusCategory != Done ORDER BY Rank ASC' % P,
    "OKRs · Objectives": 'project = OKR AND labels = okr ORDER BY key ASC',
}
G = "rest/gadgets/1.0/g/com.atlassian.jira.gadgets:"
GADGETS = [  # (title, uri, column, filter, config)
    ("Open stories by work stream", G + "pie-chart-gadget/gadgets/piechart-gadget.xml", 0, "ATV2 · Open work", {"statType": "components"}),
    ("Stories by status", G + "pie-chart-gadget/gadgets/piechart-gadget.xml", 1, "ATV2 · All stories", {"statType": "statuses"}),
    ("Work stream × status", G + "two-dimensional-stats-gadget/gadgets/two-dimensional-stats-gadget.xml", 0,
     "ATV2 · All stories", {"xstattype": "statuses", "ystattype": "components", "sortBy": "natural", "sortDirection": "asc",
                         "showTotals": "true", "numberToShow": "50"}),
    ("Launch blockers", G + "filter-results-gadget/gadgets/filter-results-gadget.xml", 1, "ATV2 · Launch blockers",
     {"num": "20", "columnNames": "issuetype|issuekey|summary|status|priority|components"}),
    ("Needs verification", G + "filter-results-gadget/gadgets/filter-results-gadget.xml", 1,
     "ATV2 · Needs verification (in review)", {"num": "20", "columnNames": "issuekey|summary|components|updated"}),
    ("Open work by priority", G + "stats-gadget/gadgets/stats-gadget.xml", 0, "ATV2 · Open work", {"statType": "priorities"}),
    ("Created vs resolved", G + "created-vs-resolved-issues-chart-gadget/gadgets/createdvsresolved-gadget.xml", 0,
     "ATV2 · All work", {"periodName": "weekly", "daysprevious": "90", "isCumulative": "true", "showUnresolvedTrend": "true"}),
    ("Objectives (OKRs)", G + "filter-results-gadget/gadgets/filter-results-gadget.xml", 1, "OKRs · Objectives",
     {"num": "10", "columnNames": "issuekey|summary|status"}),
]


LEFT = [
    ("Launch: open stories by work stream", G + "pie-chart-gadget/gadgets/piechart-gadget.xml", 0, "ATV2 · Launch: what's left", {"statType": "components"}),
    ("Open work by release", G + "pie-chart-gadget/gadgets/piechart-gadget.xml", 1, "ATV2 · Open stories (all releases)", {"statType": "fixfor"}),
    ("Launch: work stream × status", G + "two-dimensional-stats-gadget/gadgets/two-dimensional-stats-gadget.xml", 0,
     "ATV2 · Launch: all", {"xstattype": "statuses", "ystattype": "components", "sortBy": "natural", "sortDirection": "asc",
                            "showTotals": "true", "numberToShow": "50"}),
    ("QA & launch verification", G + "two-dimensional-stats-gadget/gadgets/two-dimensional-stats-gadget.xml", 1,
     "ATV2 · QA", {"xstattype": "statuses", "ystattype": "priorities", "sortBy": "natural", "sortDirection": "asc",
                   "showTotals": "true", "numberToShow": "10"}),
    ("Launch: what's left, highest first", G + "filter-results-gadget/gadgets/filter-results-gadget.xml", 1, "ATV2 · Launch: what's left",
     {"num": "50", "columnNames": "issuetype|issuekey|summary|status|priority|components"}),
    ("QA checks", G + "filter-results-gadget/gadgets/filter-results-gadget.xml", 0, "ATV2 · QA",
     {"num": "50", "columnNames": "issuekey|summary|status|priority"}),
]


def build(c, share, ids, title, desc, gadgets):
    dash = next((d for d in c.call("GET", "/rest/api/3/dashboard/search?" + urllib.parse.urlencode({"dashboardName": title}))["values"]
                 if d["name"] == title), None)
    if dash:
        for g in c.call("GET", "/rest/api/3/dashboard/%s/gadget" % dash["id"])["gadgets"]:
            c.call("DELETE", "/rest/api/3/dashboard/%s/gadget/%s" % (dash["id"], g["id"]))
    else:
        dash = c.call("POST", "/rest/api/3/dashboard", {"name": title, "description": desc, "sharePermissions": share,
                                                        "editPermissions": []})
    did = dash["id"]
    colors = ["blue", "green", "purple", "yellow", "cyan", "red", "gray", "white"]
    rows = {0: 0, 1: 0}
    for i, (gtitle, uri, col, fname, cfg) in enumerate(gadgets):
        row = rows[col]
        rows[col] += 1
        g = c.call("POST", "/rest/api/3/dashboard/%s/gadget" % did,
                   {"uri": uri, "title": gtitle, "color": colors[i % len(colors)], "position": {"column": col, "row": row}})
        conf = dict(cfg, filterId="filter-" + ids[fname], projectOrFilterId="filter-" + ids[fname], isConfigured="true",
                    refresh="15")
        c.call("PUT", "/rest/api/3/dashboard/%s/items/%s/properties/config" % (did, g["id"]), conf)
    return did


def main():
    env = js.load_env("~/.jira.env")
    c = js.Jira(env["JIRA_BASE_URL"], env["JIRA_EMAIL"], env["JIRA_API_TOKEN"])
    share = [{"type": "project", "project": {"id": c.call("GET", "/rest/api/3/project/" + P)["id"]}}]
    ids = {}
    for name, jql in FILTERS.items():
        q = urllib.parse.urlencode({"filterName": name, "maxResults": 50})
        mine = {f["name"]: f for f in c.call("GET", "/rest/api/3/filter/search?" + q)["values"]}
        body = {"name": name, "jql": jql, "favourite": True, "sharePermissions": share}
        if name in mine:
            ids[name] = mine[name]["id"]
            c.call("PUT", "/rest/api/3/filter/" + ids[name], body)
        else:
            ids[name] = c.call("POST", "/rest/api/3/filter", body)["id"]
        print("filter", ids[name], name)

    base = env["JIRA_BASE_URL"].rstrip("/")
    d1 = build(c, share, ids, "AfroTransact v2 · Delivery",
               "Work streams, open work, launch blockers, verification queue and OKRs for AfroTransact v2.", GADGETS)
    d2 = build(c, share, ids, "AfroTransact v2 · What's left to launch",
               "Everything in the Launch release that is not done yet, by work stream and status, plus QA.", LEFT)
    print("dashboard", base + "/jira/dashboards/" + str(d1))
    print("dashboard", base + "/jira/dashboards/" + str(d2))


if __name__ == "__main__":
    main()
