"""Create (or update) the backlog in Jira: one programme parent, the backlog epics as work streams, every story.

Each story gets its description, acceptance criteria, generated testing instructions and a definition of done.
Idempotent: every issue carries the label bk-<backlog id> (bk-E01, bk-S037); a re-run updates instead of duplicating,
and docs/backlog/jira-map.json records backlog id -> Jira key.

Credentials come only from a file (default ~/.jira.env, never committed, never printed):
    JIRA_BASE_URL=https://your-site.atlassian.net
    JIRA_EMAIL=you@example.com
    JIRA_API_TOKEN=...            (https://id.atlassian.com/manage-profile/security/api-tokens)
    JIRA_PROJECT_KEY=AT            (created as a company-managed Scrum project if it does not exist)

Usage:
    python3 tools/backlog/jira_sync.py --dry-run            # no network; writes a preview next to the map
    python3 tools/backlog/jira_sync.py --check              # connects, reports issue types/fields/statuses, creates nothing
    python3 tools/backlog/jira_sync.py                      # create/update everything
    python3 tools/backlog/jira_sync.py --only E17,S448      # just these (an epic id alone does not pull its stories)
"""
import argparse
import base64
import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CSV_PATH = os.path.join(ROOT, "docs", "backlog", "backlog.csv")
MAP_PATH = os.path.join(ROOT, "docs", "backlog", "jira-map.json")
PREVIEW_PATH = os.path.join(ROOT, "docs", "backlog", "jira-preview.md")
PROGRAMME = "AfroTransact v2"
PROGRAMME_LABEL = "afrotransact-v2"

JAVA = {"identity": 8100, "catalog": 8101, "offers": 8102, "inventory": 8103, "orders": 8104, "payments": 8105,
        "seller": 8106, "retail": 8107, "fulfilment": 8108, "bookings": 8109, "trust-ops": 8110,
        "tax-returns": 8111, "content": 8112, "ai-gateway": 8113, "mcp-server": 8114, "delivery-runs": 8115}
GO = {"gateway-bff": 8200, "search": 8201, "indexer": 8202, "cart": 8203, "promise": 8204, "geo": 8205,
      "media": 8207, "notifications": 8208, "carrier-gateway": 8209, "authz-sync": 8210}
WEB = {"storefront": ("storefront", "http://localhost:3000"),
       "seller-center": ("seller-center", "http://localhost:3010"),
       "staff-console": ("staff-console", "http://localhost:3002")}

# Work stream (backlog component) -> services and surfaces a tester touches. Unknown streams fall back to labels.
STREAM = {
    "foundation": ([], [], []), "identity": (["identity"], ["authz-sync"], ["storefront", "staff-console"]),
    "catalog": (["catalog"], ["media"], ["seller-center", "staff-console"]), "search": ([], ["search", "indexer"], ["storefront"]),
    "offers": (["offers"], [], ["seller-center"]), "checkout": ([], ["cart", "gateway-bff"], ["storefront"]),
    "payments": (["payments"], [], ["storefront", "staff-console"]), "orders": (["orders"], [], ["storefront", "seller-center"]),
    "inventory": (["inventory"], [], ["seller-center"]), "retail": (["retail"], [], ["staff-console"]),
    "carriers": ([], ["carrier-gateway"], ["seller-center"]), "delivery": (["fulfilment"], ["promise"], ["staff-console"]),
    "onboarding": (["seller"], [], ["seller-center", "staff-console"]), "billing": (["seller", "payments"], [], ["seller-center"]),
    "bookings": (["bookings"], [], ["storefront", "seller-center"]), "storefront": ([], ["gateway-bff"], ["storefront"]),
    "returns": (["orders", "tax-returns"], [], ["storefront", "staff-console"]), "tax-returns": (["tax-returns"], [], ["staff-console"]),
    "trust": (["trust-ops"], [], ["staff-console"]), "notifications": ([], ["notifications"], []),
    "content": (["content"], [], ["storefront", "staff-console"]), "ai": (["ai-gateway", "mcp-server"], [], ["storefront"]),
    "geo": ([], ["geo"], ["storefront"]), "storefront-web": ([], ["gateway-bff"], ["storefront"]),
    "seller-center": ([], ["gateway-bff"], ["seller-center"]), "staff-console": ([], ["gateway-bff"], ["staff-console"]),
    "mobile": ([], ["gateway-bff"], []), "docs": ([], [], []), "observability": ([], [], []), "infra": ([], [], []),
    "cicd": ([], [], []), "legal": ([], [], ["storefront"]), "exports": ([], ["gateway-bff"], ["staff-console"]),
    "quality": ([], [], []), "delivery-runs": (["delivery-runs"], [], ["storefront", "staff-console"]),
    "seller": (["seller"], [], ["seller-center"]), "qa": ([], [], []), "gateway-bff": ([], ["gateway-bff"], []), "tools": ([], [], []),
}
STATUS_ALIASES = {"to do": ["to do", "backlog", "open", "selected for development"],
                  "in progress": ["in progress", "doing"],
                  "in review": ["in review", "review", "code review", "testing", "qa", "in progress"],
                  "done": ["done", "closed", "resolved", "complete"]}


# ---------------------------------------------------------------- content

def criteria(text):
    return [re.sub(r"^\s*[-*]\s*", "", line).strip() for line in text.splitlines() if line.strip()]


def services_for(row):
    comps = row["Component"].split()
    labels = set(row["Labels"].split())
    java, go, web = [], [], []
    for c in comps or [""]:
        j, g, w = STREAM.get(c, ([], [], []))
        java += j
        go += g
        web += w
    for name in list(JAVA) + list(GO) + list(WEB):
        if name in labels or name in comps:
            (java if name in JAVA else go if name in GO else web).append(name)
    if "mobile" in labels or "mobile" in comps:
        web = web  # mobile handled separately below
    dedupe = lambda xs: list(dict.fromkeys(xs))
    return dedupe(java), dedupe(go), dedupe(web), labels


def testing(row):
    """Testing instructions derived from the story's own criteria and the services it touches. Nothing invented:
    every manual check restates an acceptance criterion; the commands are the repo's real test entry points."""
    java, go, web, labels = services_for(row)
    acs = criteria(row["Acceptance Criteria"])
    spike = "spike" in labels or row["Summary"].lower().startswith("spike")
    setup = []
    auto = []
    manual = []
    if spike:
        setup.append("No running stack needed: this is a decision/research story.")
        manual.append("Open the document or tool the criteria name and confirm it exists on main.")
        manual += ["Confirm: " + a for a in acs]
        manual.append("Product owner records the decision (comment on this issue with the link).")
        return setup, auto, manual
    setup.append("Pull main, then `make up REBUILD=1` (local stack; ports in infra/local/PORTS.md). Local data is disposable.")
    for s in java:
        auto.append("`cd java && ./mvnw -pl services/%s -am verify` (unit, ArchUnit and Testcontainers ITs; needs JDK 25 and Docker)" % s)
    for s in go:
        auto.append("`cd go/services/%s && go test ./...`" % s)
    for w in web:
        auto.append("`pnpm --filter %s test` and `pnpm --filter %s exec tsc --noEmit`" % (WEB[w][0], WEB[w][0]))
    if web:
        auto.append("`pnpm e2e` (Playwright journeys on the mock stack)")
    if "mobile" in labels or "mobile" in row["Component"].split():
        auto.append("`pnpm --filter @afrotransact/customer-app test` (and driver-app if touched)")
    if not auto:
        auto.append("Run the checks named in the criteria (e.g. CI workflow, `make launch-set-check`, docs build `pnpm --filter docs build`).")
    where = []
    for s in java:
        where.append("%s service http://localhost:%d (Scalar /docs, /actuator/health)" % (s, JAVA[s]))
    for s in go:
        where.append("%s http://localhost:%d (/docs, /healthz)" % (s, GO[s]))
    for w in web:
        where.append("%s %s" % (w.replace("-", " ").title(), WEB[w][1]))
    if where:
        setup.append("Where: " + "; ".join(where) + ".")
    for a in acs:
        manual.append("Verify: " + a.rstrip("."))
    if web:
        manual.append("UI: no console errors, names shown instead of ids, works at 390px wide, keyboard reachable.")
    if manual:
        manual.append("Attach evidence for each check to this issue: screenshots at 1280 and 390 for UI, a log line or the test name otherwise.")
    return setup, auto, manual


DOD = ["Every acceptance criterion verified with evidence attached to this issue",
       "Automated tests added or updated and green in CI",
       "Contracts (contracts/openapi, asyncapi) and docs updated where behaviour changed",
       "No new known vulnerabilities; no secrets in code or logs",
       "docs/backlog/backlog.csv status updated (tools/backlog/mark.py)"]


# ---------------------------------------------------------------- ADF

def text(t):
    parts, out = re.split(r"(`[^`]+`)", t), []
    for p in parts:
        if not p:
            continue
        if p.startswith("`") and p.endswith("`"):
            out.append({"type": "text", "text": p[1:-1], "marks": [{"type": "code"}]})
        else:
            out.append({"type": "text", "text": p})
    return out


def para(t):
    return {"type": "paragraph", "content": text(t)}


def heading(t, level=3):
    return {"type": "heading", "attrs": {"level": level}, "content": [{"type": "text", "text": t}]}


def items(xs, ordered=False):
    return {"type": "orderedList" if ordered else "bulletList",
            "content": [{"type": "listItem", "content": [para(x)]} for x in xs]}


BASE = ""      # Jira site, set at run time (links in epic tables)
KEYS = {}      # backlog id -> Jira key, set at run time
STATUS_COLOR = {"done": "green", "in progress": "blue", "in review": "purple", "to do": "neutral"}
_ids = iter(range(1, 10 ** 9))


def lid(prefix="n"):
    return "%s-%d" % (prefix, next(_ids))


def lozenge(t, color):
    return {"type": "status", "attrs": {"text": t.upper(), "color": color, "localId": lid("s")}}


def panel(kind, *nodes):
    return {"type": "panel", "attrs": {"panelType": kind}, "content": list(nodes)}


def tasks(xs, done):
    return {"type": "taskList", "attrs": {"localId": lid("tl")},
            "content": [{"type": "taskItem", "attrs": {"localId": lid("ti"), "state": "DONE" if done else "TODO"},
                         "content": text(x)} for x in xs]}


def link_text(t):
    """Text with backlog ids (S123, E05) turned into links to their Jira issues."""
    out = []
    for part in re.split(r"\b([SE]\d{3})\b", t):
        if re.fullmatch(r"[SE]\d{3}", part) and part in KEYS and BASE:
            out.append({"type": "text", "text": KEYS[part], "marks": [{"type": "link", "attrs": {"href": "%s/browse/%s" % (BASE, KEYS[part])}}]})
        elif part:
            out += text(part)
    return out


def meta_line(row):
    st = row["Status"]
    nodes = [lozenge(st, STATUS_COLOR.get(st.lower(), "neutral")), {"type": "text", "text": "  "}]
    if row.get("Story Points"):
        nodes += [lozenge("%s pts" % row["Story Points"], "neutral"), {"type": "text", "text": "  "}]
    nodes += [lozenge(row["Priority"] or "-", "red" if row["Priority"] == "Highest" else "yellow" if row["Priority"] == "High" else "neutral"),
              {"type": "text", "text": "  stream: %s · backlog %s" % (row["Component"] or "-", row["Issue Id"]),
               "marks": [{"type": "textColor", "attrs": {"color": "#6b778c"}}]}]
    return {"type": "paragraph", "content": nodes}


def adf_story(row):
    setup, auto, manual = testing(row)
    done = row["Status"] == "Done"
    acs = criteria(row["Acceptance Criteria"])
    content = [meta_line(row), {"type": "paragraph", "content": link_text(row["Description"] or row["Summary"])}]
    if acs:
        content += [heading("Acceptance criteria", 2),
                    panel("note", {"type": "taskList", "attrs": {"localId": lid("tl")},
                                   "content": [{"type": "taskItem", "attrs": {"localId": lid("ti"), "state": "DONE" if done else "TODO"},
                                                "content": link_text(a)} for a in acs]})]
    content.append(heading("Testing instructions", 2))
    inner = []
    if setup:
        inner += [heading("Setup", 4), items(setup)]
    if auto:
        inner += [heading("Automated", 4), items(auto)]
    if manual:
        inner += [heading("Manual checks", 4), items(manual, ordered=True)]
    content.append(panel("info", *inner))
    content += [heading("Definition of done", 2), panel("success", tasks(DOD, done))]
    return {"type": "doc", "version": 1, "content": content}


def cell(*nodes, header=False, width=None):
    c = {"type": "tableHeader" if header else "tableCell", "content": [{"type": "paragraph", "content": list(nodes)}]}
    if width:
        c["attrs"] = {"colwidth": [width]}
    return c


WIDTHS = [110, 470, 150, 80]


def adf_epic(row, stories):
    total = len(stories)
    done = sum(1 for s in stories if s["Status"] == "Done")
    pts = sum(float(s["Story Points"] or 0) for s in stories)
    pts_done = sum(float(s["Story Points"] or 0) for s in stories if s["Status"] == "Done")
    pct = round(100 * done / total) if total else 0
    bar = "█" * (pct // 5) + "░" * (20 - pct // 5)
    content = [{"type": "paragraph", "content": link_text(row["Description"] or row["Summary"])},
               panel("info" if pct < 100 else "success",
                     {"type": "paragraph", "content": [{"type": "text", "text": "Progress  ", "marks": [{"type": "strong"}]},
                                                       {"type": "text", "text": bar, "marks": [{"type": "code"}]},
                                                       {"type": "text", "text": "  %d%%" % pct, "marks": [{"type": "strong"}]}]},
                     para("%d of %d stories done · %g of %g points · %d open" % (done, total, pts_done, pts, total - done)))]
    order = {"in review": 0, "in progress": 1, "to do": 2, "done": 3}
    rows_ = [{"type": "tableRow", "content": [cell(*text(h), header=True, width=w) for h, w in
                                              zip(("Story", "Summary", "Status", "Points"), WIDTHS)]}]
    for s in sorted(stories, key=lambda s: (order.get(s["Status"].lower(), 9), s["Issue Id"])):
        k = KEYS.get(s["Issue Id"])
        keynode = ({"type": "text", "text": k, "marks": [{"type": "link", "attrs": {"href": "%s/browse/%s" % (BASE, k)}}]}
                   if k and BASE else {"type": "text", "text": s["Issue Id"]})
        rows_.append({"type": "tableRow", "content": [cell(keynode, width=WIDTHS[0]), cell(*text(s["Summary"]), width=WIDTHS[1]),
                                                     cell(lozenge(s["Status"], STATUS_COLOR.get(s["Status"].lower(), "neutral")), width=WIDTHS[2]),
                                                     cell(*text(s["Story Points"] or "-"), width=WIDTHS[3])]})
    if total:
        content += [heading("What's left first, then what's done", 3),
                    {"type": "table", "attrs": {"isNumberColumnEnabled": False, "layout": "wide"}, "content": rows_}]
    content.append({"type": "paragraph", "content": [{"type": "text", "text": "Work stream %s · source docs/backlog/backlog.csv" % row["Issue Id"],
                                                      "marks": [{"type": "textColor", "attrs": {"color": "#6b778c"}}]}]})
    return {"type": "doc", "version": 1, "content": content}


def adf_programme(epics):
    return {"type": "doc", "version": 1, "content": [
        para("AfroTransact v2 marketplace."), heading("Work streams"),
        items(["%s %s" % (e["Issue Id"], e["Summary"]) for e in epics])]}


def plain(adf):
    """Rough text rendering of an ADF doc, for the dry-run preview."""
    out = []

    def walk(n, depth=0):
        t = n.get("type")
        if t == "text":
            return n["text"]
        if t == "status":
            return "[%s]" % n["attrs"]["text"]
        inner = "".join(walk(c, depth + 1) or "" for c in n.get("content", []))
        if t in ("paragraph", "heading", "taskItem", "listItem", "tableRow"):
            prefix = {"heading": "#### ", "taskItem": "- [ ] ", "listItem": "- ", "tableRow": "| "}.get(t, "")
            out.append(prefix + inner)
            return ""
        return inner
    walk(adf)
    return "\n".join(x for x in out if x.strip())


# ---------------------------------------------------------------- Jira client

class Jira:
    def __init__(self, base, email, token):
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(("%s:%s" % (email, token)).encode()).decode()

    def call(self, method, path, body=None, ok=(200, 201, 204)):
        data = None if body is None else json.dumps(body).encode()
        for attempt in range(6):
            req = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers={"Authorization": self.auth, "Accept": "application/json",
                                                  "Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    raw = r.read()
                    return json.loads(raw) if raw else {}
            except urllib.error.HTTPError as e:
                if e.code == 429 or e.code >= 500:
                    time.sleep(int(e.headers.get("Retry-After") or 2 ** attempt))
                    continue
                detail = e.read().decode(errors="replace")[:800]
                raise RuntimeError("%s %s -> %d %s" % (method, path, e.code, detail))
            except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
                time.sleep(2 ** attempt)  # dropped connection: retry
                last = e
                continue
        raise RuntimeError("%s %s: gave up after retries" % (method, path))

    def search(self, jql, fields="summary,status,issuetype"):
        q = urllib.parse.urlencode({"jql": jql, "fields": fields, "maxResults": 100})
        return self.call("GET", "/rest/api/3/search/jql?" + q).get("issues", [])


def load_env(path):
    env = {}
    with open(os.path.expanduser(path)) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    missing = [k for k in ("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN", "JIRA_PROJECT_KEY") if not env.get(k)]
    if missing:
        raise SystemExit("missing in %s: %s" % (path, ", ".join(missing)))
    return env


PROJECT_NAME = "AfroTransact v2"
# Company-managed Scrum: custom fields (story points, acceptance criteria) and workflows behave predictably.
SCRUM_TEMPLATE = "com.pyxis.greenhopper.jira:gh-simplified-scrum-classic"


def ensure_project(jira, key, name=None):
    """Return the project, creating a company-managed Scrum project led by the token's owner if it does not exist.
    Creating needs the Jira 'Administer Jira' global permission (site admins have it)."""
    try:
        return jira.call("GET", "/rest/api/3/project/" + key)
    except RuntimeError as e:
        if "-> 404" not in str(e):
            raise
    me = jira.call("GET", "/rest/api/3/myself")
    print("project %s not found; creating '%s' (company-managed Scrum, lead: the token owner)" % (key, name or PROJECT_NAME))
    jira.call("POST", "/rest/api/3/project", {
        "key": key, "name": name or PROJECT_NAME, "projectTypeKey": "software",
        "projectTemplateKey": SCRUM_TEMPLATE, "leadAccountId": me["accountId"], "assigneeType": "UNASSIGNED",
        "description": "AfroTransact v2 marketplace backlog, synced from docs/backlog/backlog.csv"})
    return jira.call("GET", "/rest/api/3/project/" + key)


class Sync:
    def __init__(self, jira, key):
        self.j, self.key = jira, key
        self.map = json.load(open(MAP_PATH)) if os.path.exists(MAP_PATH) else {}
        project = ensure_project(jira, key)
        self.project_id = project["id"]
        self.types = {t["name"].lower(): t for t in
                      jira.call("GET", "/rest/api/3/issue/createmeta/%s/issuetypes?maxResults=50" % key).get("issueTypes", [])}
        self.fields = {}
        for name, t in self.types.items():
            meta = jira.call("GET", "/rest/api/3/issue/createmeta/%s/issuetypes/%s?maxResults=200" % (key, t["id"]))
            self.fields[name] = {f["fieldId"]: f for f in meta.get("fields", meta.get("values", []))}
        self.components = {c["name"] for c in jira.call("GET", "/rest/api/3/project/%s/components" % key)}
        self.parent_type = next((n for n in ("initiative", "feature", "theme") if n in self.types), None)

    def report(self):
        print("project", self.key, "id", self.project_id)
        print("issue types:", ", ".join(sorted(self.types)))
        print("programme parent:", self.parent_type or "none above Epic -> label %s" % PROGRAMME_LABEL)
        for n in ("epic", "story"):
            if n not in self.types:
                print("MISSING issue type", n)
                continue
            names = sorted(f["name"] for f in self.fields[n].values())
            print(n, "fields:", ", ".join(names))

    def field(self, itype, *names):
        for fid, f in self.fields.get(itype, {}).items():
            if f["name"].lower() in names:
                return fid, f
        return None, None

    def ensure_component(self, name):
        if not name or name in self.components:
            return name in self.components
        try:
            self.j.call("POST", "/rest/api/3/component", {"name": name, "project": self.key})
            self.components.add(name)
            return True
        except RuntimeError as e:
            print("  component %s not created (%s); using a label" % (name, str(e)[:120]))
            self.components.add("")  # avoid retry spam
            return False

    def existing(self, bid):
        if bid in self.map:
            return self.map[bid]
        hits = self.j.search('project = "%s" AND labels = "bk-%s"' % (self.key, bid), fields="summary")
        if hits:
            self.map[bid] = hits[0]["key"]
            return hits[0]["key"]
        return None

    def upsert(self, bid, itype, summary, description, labels, parent=None, extra=None):
        fields = {"summary": summary[:254], "description": description, "labels": labels}
        if parent:
            fields["parent"] = {"key": parent}
        fields.update(extra or {})
        allowed = self.fields.get(itype, {})
        fields = {k: v for k, v in fields.items() if k in ("summary", "description", "labels", "parent") or k in allowed}
        key = self.existing(bid)
        if key:
            self.j.call("PUT", "/rest/api/3/issue/" + key, {"fields": fields})
            action = "updated"
        else:
            fields["project"] = {"key": self.key}
            fields["issuetype"] = {"id": self.types[itype]["id"]}
            key = self.j.call("POST", "/rest/api/3/issue", {"fields": fields})["key"]
            self.map[bid] = key
            action = "created"
        self.save()
        return key, action

    def transition(self, key, target):
        want = STATUS_ALIASES.get(target.lower(), [target.lower()])
        cur = self.j.call("GET", "/rest/api/3/issue/%s?fields=status" % key)["fields"]["status"]["name"].lower()
        if cur in want[:1] or (cur in want and target.lower() != "in review"):
            return cur
        trs = self.j.call("GET", "/rest/api/3/issue/%s/transitions" % key)["transitions"]
        for name in want:
            t = next((t for t in trs if t["to"]["name"].lower() == name), None)
            if t:
                self.j.call("POST", "/rest/api/3/issue/%s/transitions" % key, {"transition": {"id": t["id"]}})
                return name
        return cur + " (no transition to %s)" % target

    def ensure_versions(self):
        have = {v["name"]: v for v in self.j.call("GET", "/rest/api/3/project/%s/versions" % self.key)}
        for name, desc, released in VERSIONS:
            if name not in have:
                have[name] = self.j.call("POST", "/rest/api/3/version", {"name": name, "description": desc,
                                                                       "projectId": int(self.project_id), "released": released})
        return have

    def ensure_tests_link(self):
        types = {t["name"] for t in self.j.call("GET", "/rest/api/3/issueLinkType")["issueLinkTypes"]}
        if "Tests" not in types:
            self.j.call("POST", "/rest/api/3/issueLinkType", {"name": "Tests", "inward": "is tested by", "outward": "tests"})

    def link_tests(self, qa_key, target_keys):
        have = set()
        for l in self.j.call("GET", "/rest/api/3/issue/%s?fields=issuelinks" % qa_key)["fields"]["issuelinks"]:
            if l["type"]["name"] == "Tests" and "outwardIssue" in l:
                have.add(l["outwardIssue"]["key"])
        for k in target_keys:
            if k not in have and k != qa_key:
                self.j.call("POST", "/rest/api/3/issueLink", {"type": {"name": "Tests"}, "inwardIssue": {"key": qa_key},
                                                              "outwardIssue": {"key": k}})

    def save(self):
        with open(MAP_PATH, "w") as f:
            json.dump(dict(sorted(self.map.items())), f, indent=1)


def story_extra(sync, row, itype):
    extra = {}
    for name in ("story point estimate", "story points"):
        fid, _ = sync.field(itype, name)
        if fid and row["Story Points"]:
            extra[fid] = float(row["Story Points"])
    if "priority" in sync.fields.get(itype, {}) and row["Priority"]:
        extra["priority"] = {"name": row["Priority"]}
    fid, f = sync.field(itype, "acceptance criteria")
    if fid:
        acs = criteria(row["Acceptance Criteria"])
        rich = f.get("schema", {}).get("custom", "").endswith("textarea")
        extra[fid] = {"type": "doc", "version": 1, "content": [items(acs)]} if rich and acs else "\n".join("- " + a for a in acs)
    comps = [c for c in row["Component"].split() if sync.ensure_component(c)]
    if comps and "components" in sync.fields.get(itype, {}):
        extra["components"] = [{"name": c} for c in comps]
    return extra


VERSIONS = [("Built", "Stories delivered before the backlog moved to Jira", True),
            ("Launch", "Open Highest and High priority work plus QA: what must be true to go live", False),
            ("After launch", "Open Medium and Low priority work", False)]


def version_for(row):
    if row["Status"] == "Done":
        return "Built"
    return "Launch" if row["Priority"] in ("Highest", "High") or row["Parent Id"] == "E36" else "After launch"


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default="~/.jira.env")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--no-status", action="store_true", help="skip transitions")
    a = ap.parse_args()

    rows = list(csv.DictReader(open(CSV_PATH, newline="", encoding="utf-8")))
    epics = [r for r in rows if r["Issue Type"] == "Epic"]
    stories = [r for r in rows if r["Issue Type"] != "Epic"]
    by_epic = {e["Issue Id"]: [s for s in stories if s["Parent Id"] == e["Issue Id"]] for e in epics}
    only = {x.strip() for x in a.only.split(",") if x.strip()}

    if a.dry_run:
        with open(PREVIEW_PATH, "w") as f:
            f.write("# Jira preview (generated by tools/backlog/jira_sync.py --dry-run)\n\n")
            f.write("%d epics, %d stories under one programme parent '%s'.\n\n" % (len(epics), len(stories), PROGRAMME))
            for e in epics:
                f.write("## %s %s [%s]\n\n%s\n\n" % (e["Issue Id"], e["Summary"], e["Status"], plain(adf_epic(e, by_epic[e["Issue Id"]]))))
                for s in by_epic[e["Issue Id"]]:
                    if only and s["Issue Id"] not in only:
                        continue
                    f.write("### %s %s [%s, %s pts, %s]\n\n%s\n\n" % (s["Issue Id"], s["Summary"], s["Status"],
                            s["Story Points"] or "-", s["Priority"], plain(adf_story(s))))
        orphans = [s["Issue Id"] for s in stories if s["Parent Id"] not in by_epic]
        print("preview written to", os.path.relpath(PREVIEW_PATH, ROOT), "| epics", len(epics), "stories", len(stories),
              "| orphans", orphans or "none")
        return

    env = load_env(a.env)
    sync = Sync(Jira(env["JIRA_BASE_URL"], env["JIRA_EMAIL"], env["JIRA_API_TOKEN"]), env["JIRA_PROJECT_KEY"])
    if a.check:
        sync.report()
        return
    for need in ("epic", "story"):
        if need not in sync.types:
            raise SystemExit("project %s has no '%s' issue type" % (sync.key, need))
    global BASE, KEYS
    BASE = env["JIRA_BASE_URL"].rstrip("/")
    KEYS = sync.map
    sync.ensure_versions()
    sync.ensure_tests_link()

    programme_key = None
    if sync.parent_type and (not only):
        programme_key, act = sync.upsert("PROGRAMME", sync.parent_type, PROGRAMME, adf_programme(epics),
                                         [PROGRAMME_LABEL, "bk-PROGRAMME"])
        print(act, programme_key, PROGRAMME, "(%s)" % sync.parent_type)

    failures = []
    for e in epics:
        if only and e["Issue Id"] not in only and not any(s["Issue Id"] in only for s in by_epic[e["Issue Id"]]):
            continue
        extra = {}
        fid, _ = sync.field("epic", "epic name")
        if fid:
            extra[fid] = e["Summary"][:254]
        if e["Priority"]:
            extra["priority"] = {"name": e["Priority"]}
        try:
            ekey, act = sync.upsert(e["Issue Id"], "epic", e["Summary"], adf_epic(e, by_epic[e["Issue Id"]]),
                                    [PROGRAMME_LABEL, "bk-" + e["Issue Id"], "stream"], parent=programme_key, extra=extra)
            st = "" if a.no_status else sync.transition(ekey, e["Status"])
            print(act, ekey, e["Issue Id"], e["Summary"][:60], st)
        except RuntimeError as err:
            failures.append((e["Issue Id"], str(err)))
            print("FAILED", e["Issue Id"], str(err)[:300])
            continue
        for s in by_epic[e["Issue Id"]]:
            if only and s["Issue Id"] not in only and e["Issue Id"] not in only:
                continue
            labels = [PROGRAMME_LABEL, "bk-" + s["Issue Id"]] + [re.sub(r"[^\w.-]", "-", l) for l in s["Labels"].split()]
            if s["Status"].lower() == "in review":
                labels.append("in-review")  # the default Scrum workflow has no In Review column
            extra = story_extra(sync, s, "story")
            if "fixVersions" in sync.fields.get("story", {}):
                extra["fixVersions"] = [{"name": version_for(s)}]
            try:
                skey, act = sync.upsert(s["Issue Id"], "story", s["Summary"], adf_story(s), sorted(set(labels)),
                                        parent=ekey, extra=extra)
                if s["Parent Id"] == "E36":
                    ids = re.findall(r"\b(S\d{3})\b", s["Acceptance Criteria"] + " " + s["Description"])
                    sync.link_tests(skey, [sync.map[i] for i in ids if i in sync.map])
                st = "" if a.no_status else sync.transition(skey, s["Status"])
                print("  ", act, skey, s["Issue Id"], s["Summary"][:60], st)
            except RuntimeError as err:
                failures.append((s["Issue Id"], str(err)))
                print("  FAILED", s["Issue Id"], str(err)[:300])
    # second pass: epic descriptions list every story by its Jira key, so they need the whole map
    for e in epics:
        if e["Issue Id"] in sync.map and (not only or e["Issue Id"] in only):
            try:
                sync.j.call("PUT", "/rest/api/3/issue/" + sync.map[e["Issue Id"]],
                            {"fields": {"description": adf_epic(e, by_epic[e["Issue Id"]])}})
            except RuntimeError as err:
                failures.append((e["Issue Id"], str(err)))
    sync.save()
    print("done; %d mapped in %s; %d failures" % (len(sync.map), os.path.relpath(MAP_PATH, ROOT), len(failures)))
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
