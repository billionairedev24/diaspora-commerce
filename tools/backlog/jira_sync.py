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
    "seller": (["seller"], [], ["seller-center"]), "gateway-bff": ([], ["gateway-bff"], []), "tools": ([], [], []),
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


def adf_story(row):
    setup, auto, manual = testing(row)
    content = [para(row["Description"] or row["Summary"])]
    acs = criteria(row["Acceptance Criteria"])
    if acs:
        content += [heading("Acceptance criteria"), items(acs)]
    content.append(heading("Testing instructions"))
    if setup:
        content += [para("Setup"), items(setup)]
    if auto:
        content += [para("Automated"), items(auto)]
    if manual:
        content += [para("Manual checks"), items(manual, ordered=True)]
    content += [heading("Definition of done"), items(DOD),
                para("Backlog id %s · source docs/backlog/backlog.csv · stream: %s" % (row["Issue Id"], row["Component"] or "-"))]
    return {"type": "doc", "version": 1, "content": content}


def adf_epic(row, stories):
    open_ = [s for s in stories if s["Status"] != "Done"]
    content = [para(row["Description"] or row["Summary"]),
               para("Work stream %s: %d stories, %d open." % (row["Issue Id"], len(stories), len(open_))),
               para("Backlog id %s · source docs/backlog/backlog.csv" % row["Issue Id"])]
    return {"type": "doc", "version": 1, "content": content}


def adf_programme(epics):
    return {"type": "doc", "version": 1, "content": [
        para("AfroTransact v2 marketplace: products and services for diaspora shoppers, AfroTransact Retail, seller and "
             "provider back offices, staff console, customer and driver apps."),
        heading("Work streams"), items(["%s %s" % (e["Issue Id"], e["Summary"]) for e in epics]),
        para("Source of truth: docs/backlog/backlog.csv; synced by tools/backlog/jira_sync.py.")]}


def plain(adf):
    """Markdown rendering of an ADF doc, for the dry-run preview."""
    out = []
    for n in adf["content"]:
        t = n["type"]
        if t == "paragraph":
            out.append("".join(c["text"] if not c.get("marks") else "`%s`" % c["text"] for c in n["content"]))
        elif t == "heading":
            out.append("#### " + n["content"][0]["text"])
        else:
            for i, li in enumerate(n["content"], 1):
                p = li["content"][0]
                line = "".join(c["text"] if not c.get("marks") else "`%s`" % c["text"] for c in p["content"])
                out.append(("%d. " % i if t == "orderedList" else "- ") + line)
    return "\n".join(out)


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


PROJECT_NAME = "AfroTransact"
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

    def save(self):
        with open(MAP_PATH, "w") as f:
            json.dump(dict(sorted(self.map.items())), f, indent=1)


def story_extra(sync, row, itype):
    extra = {}
    fid, _ = sync.field(itype, "story point estimate", "story points")
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
            try:
                skey, act = sync.upsert(s["Issue Id"], "story", s["Summary"], adf_story(s), sorted(set(labels)),
                                        parent=ekey, extra=story_extra(sync, s, "story"))
                st = "" if a.no_status else sync.transition(skey, s["Status"])
                print("  ", act, skey, s["Issue Id"], s["Summary"][:60], st)
            except RuntimeError as err:
                failures.append((s["Issue Id"], str(err)))
                print("  FAILED", s["Issue Id"], str(err)[:300])
    sync.save()
    print("done; %d mapped in %s; %d failures" % (len(sync.map), os.path.relpath(MAP_PATH, ROOT), len(failures)))
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
