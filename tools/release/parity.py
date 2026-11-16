#!/usr/bin/env python3
"""release-1 parity: what main and develop do must have its equivalent in the modular monolith (ADR 0011).

Done on main is not done until this is green on release-1. The script checks that every service, route, migration, Kafka consumer,
setting, scrape/alert and delivery file that exists in the per-service world also exists in the two deployables
(afrotransact-core, afrotransact-go), and prints a table of gaps with a one-line fix hint for each. Exit 1 on any gap.

  python3 tools/release/parity.py                       check the whole tree (state mode): the invariant release-1 must always satisfy
  python3 tools/release/parity.py --main origin/main    also say which gaps come from what main changed since the last sync
  python3 tools/release/parity.py --only mounted-java,config
  python3 tools/release/parity.py --route-report r.json include the route diff of core (tools/release/route_diff.py --json-out r.json)
  python3 tools/release/parity.py --format markdown     table for $GITHUB_STEP_SUMMARY / a PR comment

Checks (id: what fails)
  mounted-java   a java/services/<s> directory that CoreApplication does not mount as a @ModuleSpec (or core's pom does not depend on)
                 and that parity.yaml does not list under `unmounted.java` with a reason
  mounted-go     a go/services/<s> directory that go/apps/afrotransact-go/internal/registry does not mount (or parity.yaml `unmounted.go`)
  routes         a new or changed OpenAPI operation of a mounted Java module (diff against the last sync) that the core route diff
                 report says core does not answer; without a report the changed routes are listed as UNVERIFIED (--require-route-report fails)
  migrations     a Flyway migration of a mounted module that its schema role cannot run: CREATE EXTENSION of an extension the bootstrap does not
                 pre-create, a reference to another module's schema, CREATE SCHEMA/ROLE/DATABASE, GRANT/REVOKE, SET ROLE, ALTER SYSTEM
  kafka          a @KafkaListener without an explicit consumer group, a group that is not <module>-service[-suffix] (core would
                 split offsets), a standalone application*.yml that sets another spring.kafka.consumer.group-id, a topic default that is
                 not declared in contracts/asyncapi/events.yaml
  config         the non-secret settings of a service chart (extraEnv) not in the deployable chart (tools/release/chart_env_sync.py), and
                 a mounted module without its switch/pool/mode entries in the deployable chart and core application.yml
  secrets        a Secret key a service chart documents that infra/ovh-vps/secrets.sh does not render
  observability  a `service=` value in a rule, dashboard or scrape config that is neither a module, a deployable nor a platform component
  delivery       a per-service Dockerfile/chart/workflow for a service that is not a module of a deployable (release-1.yaml), a service
                 missing from infra/ovh-vps/services.conf, a workflow file nobody classified in parity.yaml `workflows`, or one whose
                 triggers contradict its class
  http-edges     a new Http* adapter under adapter/out that is always on and not in java/apps/core/src/test/resources/http-edges.txt
                 (the static twin of ModuleEdgeGuardTest)

Standard library plus PyYAML. Reads only from the work tree (and `git` for --main).
"""
import argparse
import fnmatch
import json
import pathlib
import re
import subprocess
import sys

import yaml

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import chart_env_sync  # noqa: E402

METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
CHECKS = ["mounted-java", "mounted-go", "routes", "migrations", "kafka", "config", "secrets", "observability", "delivery", "http-edges"]


class Gap:
    def __init__(self, check, subject, problem, fix, source=None):
        self.check, self.subject, self.problem, self.fix, self.source = check, subject, problem, fix, source

    def row(self):
        return [self.check, self.subject, self.problem, self.fix]


class Ctx:
    def __init__(self, root, main=None, base=None, route_report=None, require_route_report=False):
        self.root = pathlib.Path(root)
        self.main, self.base = main, base
        self.route_report = route_report
        self.require_route_report = require_route_report
        self.cfg = yaml.safe_load((self.root / "tools/release/parity.yaml").read_text()) or {}
        self.changed = self._diff() if main and base else None   # path -> status letter, files main changed since the last sync
        self.notes = []

    def _diff(self):
        out = subprocess.run(["git", "-C", str(self.root), "diff", "--name-status", "--no-renames", f"{self.base}..{self.main}"],
                             capture_output=True, text=True, check=True).stdout
        return {line.split("\t", 1)[1]: line[0] for line in out.splitlines() if "\t" in line}

    def touched(self, *prefixes):
        """True when main changed a path under one of the prefixes (None-safe: state mode says False)."""
        return bool(self.changed) and any(p.startswith(pre) for p in self.changed for pre in prefixes)

    def text(self, rel):
        try:
            return (self.root / rel).read_text()
        except (FileNotFoundError, UnicodeDecodeError):
            return ""

    def show_base(self, rel):
        r = subprocess.run(["git", "-C", str(self.root), "show", f"{self.base}:{rel}"], capture_output=True, text=True)
        return r.stdout if r.returncode == 0 else None

    def dirs(self, rel):
        base = self.root / rel
        return sorted(p.name for p in base.iterdir() if p.is_dir()) if base.is_dir() else []

    def plan(self):
        return yaml.safe_load(self.text("infra/argocd/launch-set/release-1.yaml")) or {}

    def src(self, svc):
        return self.cfg_unmounted().get(svc)

    def cfg_unmounted(self):
        return {**(self.cfg.get("unmounted") or {}).get("java", {}), **(self.cfg.get("unmounted") or {}).get("go", {})}


# ------------------------------------------------------------------------------------------------------------- mounted modules
def java_mounted(ctx):
    """service directory names core mounts: @ModuleSpec(name = "x", service = "x", ...) in CoreApplication."""
    text = ctx.text("java/apps/core/src/main/java/com/afrotransact/core/CoreApplication.java")
    return set(re.findall(r'@ModuleSpec\(\s*name\s*=\s*"[^"]+",\s*service\s*=\s*"([^"]+)"', text))


def go_mounted(ctx):
    text = ctx.text("go/apps/afrotransact-go/internal/registry/registry.go")
    return set(re.findall(r'go/services/([a-z][a-z0-9-]*)', text))


def check_mounted_java(ctx):
    gaps = []
    mounted = java_mounted(ctx)
    pom = ctx.text("java/apps/core/pom.xml")
    allowed = ctx.cfg.get("unmounted", {}).get("java", {}) or {}
    for svc in ctx.dirs("java/services"):
        if svc in allowed and allowed[svc]:
            continue
        src = ctx.touched(f"java/services/{svc}/")
        if svc not in mounted:
            gaps.append(Gap("mounted-java", f"java/services/{svc}", "service is not mounted as a module in java/apps/core",
                            f"add @ModuleSpec(name = \"{svc}\", service = \"{svc}\", ...) to CoreApplication and the module checklist "
                            "(release-1-architecture.md section 9), or list it under unmounted.java in tools/release/parity.yaml with a reason", src))
        elif f"<artifactId>{svc}-service</artifactId>" not in pom:
            gaps.append(Gap("mounted-java", f"java/services/{svc}", "core's pom has no dependency on the service jar",
                            f"add {svc}-service (classifier plain) to java/apps/core/pom.xml", src))
    for svc in sorted(mounted - set(ctx.dirs("java/services"))):
        gaps.append(Gap("mounted-java", f"CoreApplication: {svc}", "mounted module has no java/services directory",
                        "remove the @ModuleSpec or restore the service", None))
    return gaps


def check_mounted_go(ctx):
    gaps = []
    mounted = go_mounted(ctx)
    allowed = ctx.cfg.get("unmounted", {}).get("go", {}) or {}
    for svc in ctx.dirs("go/services"):
        if svc in allowed and allowed[svc]:
            continue
        if svc not in mounted:
            gaps.append(Gap("mounted-go", f"go/services/{svc}", "service is not mounted in go/apps/afrotransact-go",
                            f"add a host.Spec for {svc} to go/apps/afrotransact-go/internal/registry/registry.go (and its module.go), "
                            "or list it under unmounted.go in tools/release/parity.yaml with a reason", ctx.touched(f"go/services/{svc}/")))
    for svc in sorted(mounted - set(ctx.dirs("go/services"))):
        gaps.append(Gap("mounted-go", f"registry: {svc}", "mounted module has no go/services directory", "remove the registry entry or restore the service"))
    return gaps


# ------------------------------------------------------------------------------------------------------------------------ routes
def op_signatures(doc):
    out = {}
    for path, item in ((doc or {}).get("paths") or {}).items():
        for method, op in (item or {}).items():
            if method.lower() in METHODS:
                key = (method.upper(), re.sub(r"\{[^}/]*\}", "{}", path))
                out[key] = json.dumps(op, sort_keys=True, default=str)
    return out


def check_routes(ctx):
    gaps = []
    mounted = java_mounted(ctx)
    report = json.loads(pathlib.Path(ctx.route_report).read_text()) if ctx.route_report else None
    missing = {(m["module"], m["method"], m["path"]) for m in (report or {}).get("missing", [])}
    changed_routes = {}
    if ctx.changed is not None:
        for rel, status in ctx.changed.items():
            m = re.fullmatch(r"contracts/openapi/([a-z][a-z0-9-]*)\.yaml", rel)
            if not m or status == "D":
                continue
            module = m.group(1)
            new = op_signatures(yaml.safe_load(ctx.text(rel)))
            old_text = ctx.show_base(rel) if status == "M" else None
            old = op_signatures(yaml.safe_load(old_text)) if old_text else {}
            diff = sorted(k for k, v in new.items() if old.get(k) != v)
            if diff:
                changed_routes[module] = diff
    for module, diff in changed_routes.items():
        if module in mounted:
            if report is None:
                ctx.notes.append(f"routes: UNVERIFIED {len(diff)} new/changed route(s) of '{module}' (pass --route-report from route_diff.py --json-out)")
                if ctx.require_route_report:
                    gaps.append(Gap("routes", f"contracts/openapi/{module}.yaml", f"{len(diff)} new/changed route(s), no route diff report",
                                    "run tools/release/route_diff.py --live <core> --json-out report.json against the built core jar", True))
            else:
                for method, path in diff:
                    if (module, method, path) in missing:
                        gaps.append(Gap("routes", f"{method} {path} ({module})", "contract route is not answered by core (route diff)",
                                        f"implement the endpoint in the {module} module (it is served by the same controller in core), then rerun the route diff", True))
        else:
            ctx.notes.append(f"routes: '{module}' contract changed; it is not a Java module of core (Go modules are covered by their own tests, no core route diff)")
    for module, method, path in sorted(missing):
        if module in mounted and not any(g.subject.startswith(f"{method} {path} ") for g in gaps):
            gaps.append(Gap("routes", f"{method} {path} ({module})", "contract route is not answered by core (route diff)",
                            f"implement or fix the mapping in the {module} module; see tools/release/route_diff.py", None))
    return gaps


# -------------------------------------------------------------------------------------------------------------------- migrations
def bootstrap_extensions(ctx):
    m = re.search(r"\\set extensions '([^']*)'", ctx.text("infra/ops/postgres/release-1-bootstrap.sql"))
    return set((m.group(1) if m else "postgis,btree_gist").split(","))


def schema_names(ctx):
    names = set()
    text = ctx.text("infra/ops/postgres/release-1-bootstrap.sql")
    for var in ("core_modules", "go_modules"):
        m = re.search(rf"\\set {var} '([^']*)'", text)
        names |= {a.split(":")[0] for a in (m.group(1) if m else "").split(",") if a}
    names |= {"retail", "delivery_runs", "carriers"}
    return names


def sql_code(text):
    """SQL without comments and string literals (so a word in a comment or a quoted text is not a statement)."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"--[^\n]*", " ", text)
    return re.sub(r"'(?:[^']|'')*'", "''", text)


FORBIDDEN_DDL = [
    (r"\bcreate\s+(schema|role|user|database|tablespace|publication|subscription|language)\b", "creates a {0}: only the release-1 bootstrap (admin) may"),
    (r"\balter\s+(role|user|database|system)\b", "alters a {0}: only the bootstrap (admin) may"),
    (r"\b(?:grant|revoke)\b[^;]*\b(?:to|from)\s+(?!public\b|current_user\b|session_user\b)[a-z_\"]+",
     "GRANT/REVOKE for a named role: role privileges are the bootstrap's (REVOKE ... FROM PUBLIC on the module's own table is fine)"),
    (r"\bset\s+(session\s+)?(role|session\s+authorization)\b", "SET ROLE: the module role cannot switch roles"),
    (r"\bcopy\b[^;]*\b(program|from\s+'?/)", "COPY from a program or server file needs superuser"),
]


def check_migrations(ctx):
    gaps = []
    exts, schemas = bootstrap_extensions(ctx), schema_names(ctx)
    mounted = java_mounted(ctx) | go_mounted(ctx)
    roots = [("java/services", "src/main/resources/db/migration"), ("go/services", "migrations")]
    for base, sub in roots:
        for svc in ctx.dirs(base):
            if svc not in mounted:
                continue
            own = svc.replace("-", "_")
            if svc == "carrier-gateway":
                own = "carriers"
            mig = ctx.root / base / svc / sub
            files = sorted(mig.rglob("*.sql")) if mig.is_dir() else []
            for f in files:
                rel = f.relative_to(ctx.root).as_posix()
                src = ctx.touched(rel) if ctx.changed is not None else None
                code = sql_code(f.read_text())
                for m in re.finditer(r"\bcreate\s+extension\s+(?:if\s+not\s+exists\s+)?\"?([a-z0-9_-]+)\"?", code, re.I):
                    if m.group(1).lower() not in exts:
                        gaps.append(Gap("migrations", rel, f"CREATE EXTENSION {m.group(1)}: not pre-created by the bootstrap (module roles cannot create it)",
                                        f"add it to `extensions` in infra/ops/postgres/release-1-bootstrap.sql (and ask the provider), or drop the extension", src))
                for pattern, text in FORBIDDEN_DDL:
                    m = re.search(pattern, code, re.I)
                    if m:
                        gaps.append(Gap("migrations", rel, text.format(m.group(1).lower() if m.groups() and m.group(1) else m.group(0).lower()),
                                        "move it to the release-1 bootstrap or remove it; the module role owns only its own schema", src))
                for other in sorted(schemas - {own}):
                    if re.search(rf"(?<![\w.\"]){re.escape(other)}\.[a-z_]", code, re.I):
                        gaps.append(Gap("migrations", rel, f"references schema '{other}' of another module",
                                        "a module reads only its own tables: call the other module's api package or keep a local copy", src))
    return gaps


# ------------------------------------------------------------------------------------------------------------------------ kafka
def balanced(text, start):
    depth, i = 0, start
    while i < len(text):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return text[start + 1:]


def check_kafka(ctx):
    gaps = []
    pattern = re.compile((ctx.cfg.get("kafka") or {}).get("group_pattern", r"^[a-z]+(-[a-z]+)*-service(-[a-z-]+)?$"))
    asyncapi = ctx.text("contracts/asyncapi/events.yaml")
    for svc in ctx.dirs("java/services"):
        base = ctx.root / "java/services" / svc / "src/main/java"
        for f in sorted(base.rglob("*.java")) if base.is_dir() else []:
            text = f.read_text()
            rel = f.relative_to(ctx.root).as_posix()
            for m in re.finditer(r"@KafkaListener\s*\(", text):
                args = balanced(text, m.end() - 1)
                src = ctx.touched(rel) if ctx.changed is not None else None
                g = re.search(r'groupId\s*=\s*"([^"]*)"', args)
                if not g:
                    gaps.append(Gap("kafka", rel, "@KafkaListener without an explicit groupId (core would use afrotransact-core and lose offsets)",
                                    'add groupId = "${spring.kafka.consumer.group-id:' + svc + '-service}"', src))
                else:
                    d = re.fullmatch(r"\$\{[^:}]+:([^}]+)}(.*)", g.group(1))
                    resolved = (d.group(1) + d.group(2)) if d else g.group(1)
                    if not pattern.match(resolved):
                        gaps.append(Gap("kafka", rel, f"consumer group '{resolved}' is not <module>-service[-suffix]",
                                        "keep the standalone group id so offsets carry over at cutover (KafkaGroupIdsTest)", src))
                    elif not resolved.startswith(svc + "-service"):
                        gaps.append(Gap("kafka", rel, f"consumer group '{resolved}' does not start with '{svc}-service'",
                                        f"use {svc}-service[-suffix]: the group of the module in its standalone build", src))
                tm = re.search(r'topics\s*=\s*(.*?)(?:,\s*[A-Za-z]+\s*=\s*[^=]|$)', args, re.S)
                declared = []
                if tm:
                    expr = tm.group(1)
                    defaults = re.findall(r"\$\{[^:}]+:([^}]+)}", expr)
                    declared = [t.strip() for d in defaults for t in d.split(",")]
                    if not defaults:
                        declared = [t for t in re.findall(r'"([^"$#{}]+)"', expr)]
                for topic in declared:
                    if asyncapi and topic and topic not in asyncapi:
                        gaps.append(Gap("kafka", rel, f"topic '{topic}' is not declared in contracts/asyncapi/events.yaml",
                                        "declare the topic in the AsyncAPI contract (contract first) and in infra/tofu kafka topics", src))
        for yml in sorted((ctx.root / "java/services" / svc / "src/main/resources").glob("application*.yml")):
            doc = yaml.safe_load(yml.read_text()) or {}
            gid = (((doc.get("spring") or {}).get("kafka") or {}).get("consumer") or {}).get("group-id")
            if gid and not re.sub(r"\$\{[^:}]+:([^}]+)}", r"\1", str(gid)).startswith(svc + "-service"):
                gaps.append(Gap("kafka", yml.relative_to(ctx.root).as_posix(), f"standalone group-id '{gid}' differs from core's '{svc}-service'",
                                f"set spring.kafka.consumer.group-id to {svc}-service or remove it (core never sets it)",
                                ctx.touched(yml.relative_to(ctx.root).as_posix()) if ctx.changed is not None else None))
    return gaps


# ----------------------------------------------------------------------------------------------------------------------- config
def check_config(ctx):
    gaps = []
    for dep, path, wanted, current, conflicts, _kept in chart_env_sync.plan(ctx.root):
        rel = path.relative_to(ctx.root).as_posix()
        for k, v in sorted(conflicts.items()):
            gaps.append(Gap("config", f"{rel}: {k}", f"modules set {k} to different values: {v}", "rename one variable in its module (the deployable has one environment)"))
        if wanted != current:
            missing = sorted(set(re.findall(r"^  ([A-Z][A-Z0-9_]*): ", wanted, re.M)) - set(re.findall(r"^  ([A-Z][A-Z0-9_]*): ", current, re.M)))
            gaps.append(Gap("config", rel, "missing from the deployable chart: " + (", ".join(missing) if missing else "generated block out of date"),
                            "python3 tools/release/chart_env_sync.py --write (or list the name in parity.yaml config.env_skip with a reason)",
                            ctx.touched("java/services/", "go/services/") if ctx.changed is not None else None))
    # every module has its toggle, pool and mode entry
    plan = ctx.plan()
    for dep, spec in (plan.get("deployables") or {}).items():
        chart = yaml.safe_load(ctx.text(f"{spec['chart']}/values.yaml")) or {}
        for m in spec["modules"]:
            if m not in (chart.get("modules") or {}):
                gaps.append(Gap("config", f"{spec['chart']}/values.yaml", f"module '{m}' has no entry under modules:",
                                f"add `{m}: {{enabled: false, schema: ..., pool: ...}}` (and its line in release-1-bootstrap.sql)"))
    core_yml = ctx.text("java/apps/core/src/main/resources/application.yml")
    if core_yml:
        for m in sorted(java_mounted(ctx)):
            stem = m.upper().replace("-", "_")
            if f"MODULE_{stem}_ENABLED" not in core_yml:
                gaps.append(Gap("config", f"java/apps/core application.yml: {m}", f"no MODULE_{stem}_ENABLED switch",
                                f"add `at.modules.{m}.enabled: ${{MODULE_{stem}_ENABLED:...}}` to core's application.yml"))
    return gaps


# ---------------------------------------------------------------------------------------------------------------------- secrets
def check_secrets(ctx):
    gaps = []
    script = ctx.text("infra/ovh-vps/secrets.sh")
    skip = (ctx.cfg.get("config") or {}).get("secret_skip") or {}
    rendered = set(re.findall(r'(?<![A-Z0-9_$])([A-Z][A-Z0-9_]{2,})=', script))
    rendered |= {f"{c}_CLIENT_SECRET" for c in re.findall(r"for c in ([A-Z_ ]+); do", script) for c in c.split()}
    for runtime in ("java", "go"):
        for svc in ctx.dirs(f"{runtime}/services"):
            raw = ctx.text(f"{runtime}/services/{svc}/deploy/helm/values.yaml")
            for name in re.findall(r"^#\s+- ([A-Z][A-Z0-9_]+)", raw, re.M):
                if name in skip or name in rendered:
                    continue
                gaps.append(Gap("secrets", f"{runtime}/services/{svc}/deploy/helm/values.yaml: {name}", "documented Secret key is not rendered by secrets.sh",
                                f"add {name}= to the `{svc}` branch of service_keys() in infra/ovh-vps/secrets.sh (afrotransact-{ 'core' if runtime == 'java' else 'go' }-secrets is their union)",
                                ctx.touched(f"{runtime}/services/{svc}/deploy/") if ctx.changed is not None else None))
    return gaps


# -------------------------------------------------------------------------------------------------------------- observability
def check_observability(ctx):
    gaps = []
    plan = ctx.plan()
    known = {m for d in (plan.get("deployables") or {}).values() for m in d["modules"]} | set(plan.get("deployables") or {})
    known |= set((ctx.cfg.get("observability") or {}).get("extra_service_values") or [])
    files = []
    for sub in ("infra/observability", "infra/k8s/platform/lean/alloy", "infra/k8s/platform/lean"):
        base = ctx.root / sub
        if base.is_dir():
            files += [p for p in base.rglob("*") if p.is_file() and p.suffix in (".yaml", ".yml", ".json", ".alloy", ".libsonnet", ".jsonnet")]
    seen = set()
    for f in sorted(set(files)):
        rel = f.relative_to(ctx.root).as_posix()
        for m in re.finditer(r'\bservice\s*(?:=~|=)\s*\\?"([^"\\]+)\\?"', f.read_text(errors="ignore")):
            for v in m.group(1).split("|"):
                v = v.strip()
                if not v or v.startswith("$") or ".*" in v or v in known or (rel, v) in seen:
                    continue
                seen.add((rel, v))
                gaps.append(Gap("observability", f"{rel}: service=\"{v}\"", "selects a service label that no module, deployable or platform component exports",
                                f"use a module short name ({', '.join(sorted(known)[:4])}, ...), a deployable name, or add the value under observability.extra_service_values in parity.yaml",
                                ctx.touched(rel) if ctx.changed is not None else None))
    return gaps


# ------------------------------------------------------------------------------------------------------------------- delivery
def workflow_triggers(ctx, name):
    doc = yaml.safe_load(ctx.text(f".github/workflows/{name}")) or {}
    on = doc.get(True, doc.get("on")) or {}
    if isinstance(on, str):
        on = {on: None}
    if isinstance(on, list):
        on = {k: None for k in on}
    return on


def trigger_line(on):
    """Which class the triggers fit: release-1 | per-service-only | manual | reusable."""
    keys = set(on)
    if keys == {"workflow_call"}:
        return "reusable"
    def branches(ev):
        v = on.get(ev)
        return (v or {}).get("branches") if isinstance(v, dict) else None
    runs_on_r1 = False
    for ev in ("push", "pull_request"):
        if ev in on:
            b = branches(ev)
            if b is None and ev == "pull_request":
                runs_on_r1 = True
            elif b is None and ev == "push":
                runs_on_r1 = True
            elif b and any(fnmatch.fnmatch("release-1", x) for x in b):
                runs_on_r1 = True
    if runs_on_r1:
        return "release-1"
    if keys & {"push", "pull_request"}:
        return "per-service-only"
    return "manual"


def check_delivery(ctx):
    gaps = []
    plan = ctx.plan()
    modules = {m for d in (plan.get("deployables") or {}).values() for m in d["modules"]}
    unmounted = ctx.cfg_unmounted()
    allowed_paths = (ctx.cfg.get("per_service_only") or {}).get("paths") or {}
    for runtime in ("java", "go"):
        for svc in ctx.dirs(f"{runtime}/services"):
            src = ctx.touched(f"{runtime}/services/{svc}/") if ctx.changed is not None else None
            if svc not in modules and not unmounted.get(svc):
                has = [a for a in ("Dockerfile", "deploy/helm") if (ctx.root / runtime / "services" / svc / a).exists()]
                gaps.append(Gap("delivery", f"{runtime}/services/{svc}", "no deployable equivalent: not a module in infra/argocd/launch-set/release-1.yaml"
                                + (f" (has {', '.join(has)})" if has else ""),
                                f"add {svc} to deployables.<core|go>.modules in release-1.yaml and mount it (checklist: release-1-architecture.md section 9), "
                                f"or list the per-service-only reason under unmounted.{runtime} in tools/release/parity.yaml", src))
            if svc not in unmounted and not (ctx.root / f".github/workflows/{runtime}-{svc}.yml").exists() and (ctx.root / runtime / "services" / svc / "deploy").exists():
                gaps.append(Gap("delivery", f".github/workflows/{runtime}-{svc}.yml", "per-service workflow is missing", "python3 tools/ci/gen_workflows.py", src))
    conf = ctx.text("infra/ovh-vps/services.conf")
    listed = {line.split()[0] for line in conf.splitlines() if line.strip() and not line.lstrip().startswith("#")}
    for runtime in ("java", "go"):
        for svc in ctx.dirs(f"{runtime}/services"):
            if conf and svc not in listed and svc not in unmounted:
                gaps.append(Gap("delivery", f"infra/ovh-vps/services.conf: {svc}", "service missing from services.conf (secrets.sh would refuse to render it)",
                                f"add `{svc} {runtime} <database> <wave>` to infra/ovh-vps/services.conf and a branch to service_keys() in secrets.sh"))
    # workflows classified, and the class matches the triggers
    classes = ctx.cfg.get("workflows") or {}
    wf_dir = ctx.root / ".github/workflows"
    for f in sorted(wf_dir.glob("*.yml")) if wf_dir.is_dir() else []:
        entry = classes.get(f.name)
        src = ctx.touched(f".github/workflows/{f.name}") if ctx.changed is not None else None
        if not entry or not entry.get("reason"):
            gaps.append(Gap("delivery", f".github/workflows/{f.name}", "workflow is not classified in parity.yaml (does it run on release-1?)",
                            "add it under `workflows:` as release-1 (and make it trigger on release-1), per-service-only, manual or reusable, with a reason", src))
            continue
        actual = trigger_line(workflow_triggers(ctx, f.name))
        if actual != entry.get("line"):
            gaps.append(Gap("delivery", f".github/workflows/{f.name}", f"classified '{entry.get('line')}' but its triggers make it '{actual}'",
                            "add release-1 to its push/pull_request branches, or fix the class in parity.yaml", src))
    for name in sorted(set(classes) - {p.name for p in wf_dir.glob("*.yml")}):
        gaps.append(Gap("delivery", f"parity.yaml workflows: {name}", "classified workflow file no longer exists", "remove the entry"))
    return gaps


# --------------------------------------------------------------------------------------------------------------------- http-edges
def check_http_edges(ctx):
    gaps = []
    edges = set()
    for line in ctx.text("java/apps/core/src/test/resources/http-edges.txt").splitlines():
        t = line.strip()
        if t and not t.startswith("#") and "=" in t:
            edges.add(t.split("=", 1)[0].strip())
    mounted = java_mounted(ctx)
    for svc in ctx.dirs("java/services"):
        if svc not in mounted:
            continue
        base = ctx.root / "java/services" / svc / "src/main/java"
        for f in sorted(base.rglob("Http*.java")) if base.is_dir() else []:
            rel = f.relative_to(ctx.root).as_posix()
            if "/adapter/out/" not in rel:
                continue
            text = f.read_text()
            name = f.stem
            if not re.search(rf"\b(class|record)\s+{name}\b", text) or not re.search(r"@(Component|Service|Repository)\b", text):
                continue    # @Bean-declared adapters are checked on the running context (ModuleEdgesInCoreTest)
            if "@ConditionalOnModuleMode" in text or re.search(r"@ConditionalOnExpression\([^)]*\.mode", text) \
                    or re.search(r"@ConditionalOnProperty\([^)]*mode", text):
                continue
            if name not in edges:
                gaps.append(Gap("http-edges", rel, f"{name} is an always-on HTTP adapter between modules and is not in http-edges.txt",
                                f"add an in-process adapter and @ConditionalOnModuleMode, or list {name}=<reason> in java/apps/core/src/test/resources/http-edges.txt",
                                ctx.touched(rel) if ctx.changed is not None else None))
    return gaps


RUNNERS = {
    "mounted-java": check_mounted_java, "mounted-go": check_mounted_go, "routes": check_routes, "migrations": check_migrations,
    "kafka": check_kafka, "config": check_config, "secrets": check_secrets, "observability": check_observability,
    "delivery": check_delivery, "http-edges": check_http_edges,
}


def run(ctx, only=None):
    gaps = []
    for name in only or CHECKS:
        gaps += RUNNERS[name](ctx)
    return gaps


def render(gaps, notes, fmt, ctx):
    lines = []
    if ctx.changed is not None:
        areas = {}
        for p in ctx.changed:
            a = "/".join(p.split("/")[:2]) if p.split("/")[0] in ("java", "go", "contracts", "infra", "web", "mobile", "docs") else p.split("/")[0]
            areas[a] = areas.get(a, 0) + 1
        lines.append(f"main changed {len(ctx.changed)} file(s) since {ctx.base[:12]}: " + ", ".join(f"{k} ({v})" for k, v in sorted(areas.items(), key=lambda kv: -kv[1])[:8]))
    if fmt == "markdown":
        lines.append("")
        lines.append("| Check | Subject | Gap | Fix | From main |")
        lines.append("|---|---|---|---|---|")
        for g in gaps:
            esc = lambda s: str(s).replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {g.check} | `{esc(g.subject)}` | {esc(g.problem)} | {esc(g.fix)} | {'yes' if g.source else ''} |")
        if not gaps:
            lines.append("| | | none: release-1 has parity | | |")
    else:
        w = [max([len(h)] + [len(str(r[i])) for r in (g.row() for g in gaps)]) for i, h in enumerate(("CHECK", "SUBJECT", "GAP"))]
        w = [min(x, 60) for x in w]
        for g in gaps:
            lines.append(f"{g.check:<14} {'[main] ' if g.source else ''}{g.subject}\n{'':14}  gap: {g.problem}\n{'':14}  fix: {g.fix}")
    for n in notes:
        lines.append(f"note: {n}")
    lines.append(f"release-1 parity: {'FAILED, ' + str(len(gaps)) + ' gap(s)' if gaps else 'OK'}")
    return "\n".join(lines)


def last_sync_base(root, main):
    """The last commit main and release-1 had in common: everything on main after it is what the next sync brings."""
    r = subprocess.run(["git", "-C", str(root), "merge-base", "HEAD", main], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(ROOT))
    ap.add_argument("--main", help="git ref of main (or develop): attribute gaps to what it changed since the last sync")
    ap.add_argument("--since", help="override the base commit (default: merge-base of HEAD and --main)")
    ap.add_argument("--only", help="comma separated checks: " + ", ".join(CHECKS))
    ap.add_argument("--route-report", help="JSON written by route_diff.py --json-out")
    ap.add_argument("--require-route-report", action="store_true")
    ap.add_argument("--format", choices=["text", "markdown", "json"], default="text")
    args = ap.parse_args(argv)
    only = [c for c in (args.only or "").split(",") if c] or None
    for c in only or []:
        if c not in RUNNERS:
            ap.error(f"unknown check {c}")
    base = args.since or (last_sync_base(args.root, args.main) if args.main else None)
    ctx = Ctx(args.root, args.main, base, args.route_report, args.require_route_report)
    gaps = run(ctx, only)
    if args.format == "json":
        print(json.dumps({"gaps": [dict(zip(("check", "subject", "gap", "fix"), g.row()), from_main=bool(g.source)) for g in gaps], "notes": ctx.notes}))
    else:
        print(render(gaps, ctx.notes, args.format, ctx))
    return 1 if gaps else 0


if __name__ == "__main__":
    sys.exit(main())
