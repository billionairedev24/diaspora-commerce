#!/usr/bin/env python3
"""Release 1 upstream wiring check (ADR 0011, docs/runbooks/release-1-deploy.md "Upstream wiring").

Outside the local profile nothing serves the old per-service ports, and a module that moved into a deployable is no longer reachable by its
old hostname unless an alias Service exists. The charts therefore carry ONE list of upstreams per deployable (values `upstreams` of
java/apps/core/deploy/helm and go/apps/afrotransact-go/deploy/helm) that the library chart (infra/helm/library/at-service/templates/_upstreams.tpl)
renders as environment variables, from `cutover.services`. This tool keeps that list honest.

  python3 tools/release/upstreams.py            run every check (exit 1 on a finding)
  python3 tools/release/upstreams.py --scan     print the upstream names the code reads, with their consumers
  python3 tools/release/upstreams.py --render core prod   print the rendered *_URL variables of one deployable and environment (complete launch set)
  python3 tools/release/upstreams.py --helm     require helm and render with `helm template` (the default when helm is installed; CI has it)

What it checks
  scan     every `${NAME:default}` of java/services/*/src/main/resources/application*.yml and every `env:"NAME"` of go/services/*/internal/config
           whose default points at a platform service (http://localhost:81xx/82xx or http://<service>[:8080]) is an UPSTREAM; gateway-bff's
           <NAME>_URL overrides come from its Named() list. Names that are not Service URLs are classified in tools/release/upstreams.yaml.
  charts   for uat and prod and three cutover states (the committed one, the complete launch set, and batch 1 as a mixed state) each deployable
           is rendered and
             R1  no *_URL points at an old port (81xx/82xx), at :8080 of a Service (a Service listens on 80), or at localhost other than core's loopback;
             R2  a URL that names a per-service host (http://catalog) is only allowed while that module has NOT moved;
             R3  a URL that names a deployable points at the module's deployable, with /modules/<m> for every Java module and every `prefixed` Go module,
                 and only for a module that moved there; core's loopback only for a module that runs in core;
             R4  every scanned upstream of a module that is enabled in the deployable is declared in values `upstreams` (and rendered, except a Go -> Go
                 edge inside the binary, which is in-process), unless it is `converted` or `not_upstream` in tools/release/upstreams.yaml;
             R5  the registry in the library chart agrees with launch-set/release-1.yaml, CoreApplication and the Go registry (mounts), and no values file
                 overrides an upstream name by hand (extraEnv).
  edge     the Gateway API routes of every environment (infra/k8s/platform/edge/overlays/*/routes.yaml) reach only the allowed public Services on port 80,
           api /bff -> gateway-bff, auth -> identity, never mcp-server / core / the Go deployable / any internal module, and every prefix that can land on a
           deployable denies /modules and the private mcp-server paths (a moved Service selects pods that serve every module of the deployable).
  mcp      McpExposureGuard: the MCP_PUBLIC_URL core renders is core's own in-cluster Service URL (no alias Service needed).

Standard library plus PyYAML. `helm` is optional: without it the charts are rendered by an emulation of the template (same rules, written
separately), and the real template is rendered by tools/release/test_cutover_render.sh and by this tool when helm is on the PATH (CI).
"""
import argparse
import copy
import json
import pathlib
import re
import shutil
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
CHARTS = {
    "afrotransact-core": ("java/apps/core/deploy/helm", "java"),
    "afrotransact-go": ("go/apps/afrotransact-go/deploy/helm", "go"),
}
ENVS = ("uat", "prod")
PLAN = "infra/argocd/launch-set/release-1.yaml"
CLASSIFY = "tools/release/upstreams.yaml"
LIBRARY_REGISTRY = "infra/helm/library/at-service/templates/_upstreams.tpl"
OLD_PORT = re.compile(r"(?:localhost|127\.0\.0\.1|[a-z][a-z0-9.-]*):(?:81|82)\d\d(?:/|$)")
# platform-internal defaults: the old local ports (8100-8115 Java, 8200-8210 Go) and bare Service names
INTERNAL_DEFAULT = re.compile(r"^https?://(?:localhost:(?:81|82)\d\d|[a-z][a-z0-9-]*(?::8080)?)(?:/.*)?$")
PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*):([^}$]*)\}")


def load(path):
    return yaml.safe_load((ROOT / path).read_text()) or {}


def deep_merge(a, b):
    out = copy.deepcopy(a)
    for k, v in (b or {}).items():
        out[k] = deep_merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else copy.deepcopy(v)
    return out


def walk(node, path=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v, f"{path}.{k}" if path else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, f"{path}[{i}]")
    else:
        yield path, node


# ------------------------------------------------------------------------------------------------------------------------------ scan
def scan_java(root=ROOT):
    """name -> {module: [default, ...]} for every URL placeholder with a platform-internal default."""
    found = {}
    for f in sorted(root.glob("java/services/*/src/main/resources/application*.yml")):
        module = f.relative_to(root).parts[2]
        for doc in yaml.safe_load_all(f.read_text()):
            for _key, value in walk(doc or {}):
                if not isinstance(value, str):
                    continue
                for name, default in PLACEHOLDER.findall(value):
                    if name.endswith("_URL") and INTERNAL_DEFAULT.match(default):
                        found.setdefault(name, {}).setdefault(module, []).append(default)
                # the token endpoint defaults to the PUBLIC issuer: ${AUTH_TOKEN_URL:${AUTH_ISSUER_URL:...}/oauth2/token}
                if "${AUTH_TOKEN_URL:" in value:
                    found.setdefault("AUTH_TOKEN_URL", {}).setdefault(module, []).append("(issuer + /oauth2/token)")
    return found


def scan_go(root=ROOT):
    found = {}
    for svc in sorted(root.glob("go/services/*")):
        module = svc.name
        tags = {}  # yaml key -> env name, from `yaml:"key" env:"NAME_URL"`
        for g in list((svc / "internal").rglob("*.go")) + list(svc.glob("*.go")):
            if g.name.endswith("_test.go"):
                continue
            for key, env in re.findall(r'yaml:"([A-Za-z]+)[^"]*"\s+env:"([A-Z][A-Z0-9_]*)"', g.read_text()):
                if env.endswith("_URL"):
                    tags[key] = env
        for cfg in sorted((svc / "config").glob("*.yaml")):
            if cfg.name == "local.yaml":
                continue
            for key_path, value in walk(yaml.safe_load(cfg.read_text()) or {}):
                leaf = re.sub(r"\[\d+\]", "", key_path).split(".")[-1]
                if leaf in tags and isinstance(value, str) and INTERNAL_DEFAULT.match(value):
                    found.setdefault(tags[leaf], {}).setdefault(module, []).append(value)
        if module == "gateway-bff":
            src = (svc / "internal/config/config.go").read_text()
            for name in re.findall(r'"([a-z_]+)":\s*&u\.', src):
                found.setdefault(name.upper() + "_URL", {}).setdefault(module, []).append("(Named())")
    return found


def classification():
    c = load(CLASSIFY)
    return c.get("not_upstream") or {}, c.get("converted") or {}


# ------------------------------------------------------------------------------------------------------------------------------ registry
def registry():
    """module -> {deployable, mount} parsed from the library chart's registry define."""
    text = (ROOT / LIBRARY_REGISTRY).read_text()
    body = text.split('define "at-service.upstreams.registry" -}}', 1)[1].split("{{- end -}}", 1)[0]
    return yaml.safe_load(body)


def launch_plan():
    return {d: s["modules"] for d, s in load(PLAN)["deployables"].items()}


def full_launch():
    """The complete launch set as a cutover map: every module of the plan that is on by default (not retail, delivery-runs, promise, carrier-gateway, authz-sync)."""
    off = {"retail", "delivery-runs", "promise", "carrier-gateway", "authz-sync"}
    return {m: d for d, mods in launch_plan().items() for m in mods if m not in off}


def batch1():
    b = load(PLAN)["batches"][0]
    return {**{m: "afrotransact-core" for m in b["core"]}, **{m: "afrotransact-go" for m in b["go"]}}


# ------------------------------------------------------------------------------------------------------------------------------ render
def values_for(deployable, env):
    chart = ROOT / CHARTS[deployable][0]
    v = {}
    for f in (chart / "values.yaml", chart / f"values-{env}.yaml", ROOT / "infra/argocd/envs" / env / "platform-values.yaml"):
        if f.exists():
            v = deep_merge(v, yaml.safe_load(f.read_text()) or {})
    return v


def emulate(deployable, env, cutover):
    """The upstream (and MCP_PUBLIC_URL) environment of a deployable, as the template renders it. Written separately from the Go template on purpose."""
    v = values_for(deployable, env)
    ns = f"afrotransact-{env}"
    runtime = v["service"]["runtime"]
    port = v["service"]["port"]
    reg = registry()
    mods = v.get("modules") or {}
    out = {}
    for name, spec in (v.get("upstreams") or {}).items():
        module, suffix = (spec, "") if isinstance(spec, str) else (spec["module"], spec.get("suffix", ""))
        if module not in reg:
            raise SystemExit(f"upstreams.{name}: unknown module {module}")
        dep = reg[module]["deployable"]
        moved = cutover.get(module, "")
        if dep == deployable:
            if (mods.get(module) or {}).get("enabled") or moved == deployable:
                if runtime == "java":
                    out[name] = f"http://localhost:{port}/modules/{module}{suffix}"
            else:
                out[name] = f"http://{module}{suffix}"
        elif moved == dep:
            prefix = f"/modules/{module}" if dep == "afrotransact-core" or reg[module].get("mount") == "prefixed" else ""
            out[name] = f"http://{dep}.{ns}.svc{prefix}{suffix}"
        else:
            out[name] = f"http://{module}{suffix}"
    if deployable == "afrotransact-core" and ((mods.get("mcp-server") or {}).get("enabled") or cutover.get("mcp-server") == deployable):
        out["MCP_PUBLIC_URL"] = f"http://afrotransact-core.{ns}.svc.cluster.local/modules/mcp-server"
    return {**out, **{k: str(x) for k, x in (v.get("extraEnv") or {}).items()}}, v


def helm_render(deployable, env, cutover):
    chart = ROOT / CHARTS[deployable][0]
    cmd = ["helm", "template", deployable, str(chart), "-n", f"afrotransact-{env}", "-f", str(chart / "values.yaml"), "-f", str(chart / f"values-{env}.yaml"),
           "-f", str(ROOT / "infra/argocd/envs" / env / "platform-values.yaml"), "--set-json", "cutover.services=" + json.dumps(cutover)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"helm template failed for {deployable} {env}: {r.stderr.strip()}")
    env_out = {}
    for doc in yaml.safe_load_all(r.stdout):
        if doc and doc.get("kind") in ("Deployment", "Rollout"):
            for c in doc["spec"]["template"]["spec"]["containers"]:
                for e in c.get("env", []):
                    env_out[e["name"]] = e.get("value", "")
    return env_out


def render(deployable, env, cutover, use_helm):
    if use_helm:
        out = helm_render(deployable, env, cutover)
        return {k: v for k, v in out.items() if k.endswith("_URL")}, values_for(deployable, env)
    return emulate(deployable, env, cutover)


# ------------------------------------------------------------------------------------------------------------------------------ checks
def check_url(name, value, module, reg, env, cutover, runtime, port, errors, label):
    """R1-R3 for one rendered upstream URL whose target module is `module`."""
    ns = f"afrotransact-{env}"
    if OLD_PORT.search(value):
        errors.append(f"{label}: {name}={value} names an old per-service port (81xx/82xx); only the local profile serves them")
    m = re.match(r"^https?://([^/:]+)(?::(\d+))?(/.*)?$", value)
    if not m:
        errors.append(f"{label}: {name}={value} is not an http URL")
        return
    host, p, path = m.group(1), m.group(2), m.group(3) or ""
    dep = reg[module]["deployable"]
    moved = cutover.get(module, "")
    if host == "localhost":
        if runtime != "java" or p != str(port) or not path.startswith(f"/modules/{module}"):
            errors.append(f"{label}: {name}={value}: localhost is only core's loopback http://localhost:{port}/modules/<module>")
        elif moved != "afrotransact-core":
            errors.append(f"{label}: {name}={value} loops back to {module}, which does not run in core in this state")
        return
    if p == "8080":
        errors.append(f"{label}: {name}={value} uses :8080 on a Service; Services listen on 80")
    if host.split(".")[0] in CHARTS:
        svc = host.split(".")[0]
        if svc != dep:
            errors.append(f"{label}: {name}={value}: {module} lives in {dep}, not {svc}")
        if moved != dep:
            errors.append(f"{label}: {name}={value} names {svc}, but {module} has not moved there (cutover.services)")
        if host not in (f"{svc}.{ns}.svc", f"{svc}.{ns}.svc.cluster.local"):
            errors.append(f"{label}: {name}={value}: expected host {svc}.{ns}.svc")
        want = f"/modules/{module}" if dep == "afrotransact-core" or reg[module].get("mount") == "prefixed" else ""
        if want and not path.startswith(want):
            errors.append(f"{label}: {name}={value} must go through {want} (core serves each module under it and picks the right module for the duplicate routes; "
                          f"a prefixed Go module answers only there)")
        if not want and path.startswith("/modules/"):
            errors.append(f"{label}: {name}={value}: {module} is mounted flat, drop the prefix")
        return
    # a per-service Service name
    if host != module:
        errors.append(f"{label}: {name}={value}: host {host} is not the Service of {module}")
    elif moved:
        errors.append(f"{label}: {name}={value} still names the per-service host of {module}, which moved to {moved} "
                      f"(the alias Service is the rollback path, not an upstream)")


def declared(chart):
    return load(chart + "/values.yaml").get("upstreams") or {}


def check_charts(errors, notes, use_helm):
    reg = registry()
    not_up, converted = classification()
    scans = {"afrotransact-core": scan_java(), "afrotransact-go": scan_go()}
    for env in ENVS:
        committed = (load(f"infra/argocd/envs/{env}/platform-values.yaml").get("cutover") or {}).get("services") or {}
        for state, cutover in (("committed", committed), ("full", full_launch()), ("batch1", batch1())):
            for dep, (_chart, runtime) in CHARTS.items():
                label = f"{env}/{state}/{dep}"
                rendered, v = render(dep, env, cutover, use_helm)
                port = v["service"]["port"]
                ups = v.get("upstreams") or {}
                enabled = {m for m, spec in (v.get("modules") or {}).items() if (spec or {}).get("enabled")} | {m for m, d in cutover.items() if d == dep}
                for name, spec in ups.items():
                    module = spec if isinstance(spec, str) else spec["module"]
                    if name not in rendered:
                        if not (runtime == "go" and reg[module]["deployable"] == dep and module in enabled):
                            errors.append(f"{label}: upstream {name} (module {module}) is not rendered")
                        continue
                    check_url(name, rendered[name], module, reg, env, cutover, runtime, port, errors, label)
                for name, value in rendered.items():
                    if name in ups or name == "MCP_PUBLIC_URL" or not re.match(r"^https?://", value):
                        continue
                    host = value.split("/")[2].split(":")[0]
                    if OLD_PORT.search(value) or host in reg:
                        errors.append(f"{label}: {name}={value} is a hand-written per-service URL outside values `upstreams`")
                for name, consumers in scans[dep].items():
                    live = sorted(m for m in consumers if m in enabled)
                    if live and name not in ups and name not in not_up and name not in converted:
                        errors.append(f"{label}: {name} is read by {', '.join(live)} but missing from values `upstreams` (or classify it in {CLASSIFY})")
    for name in list(not_up) + list(converted):
        for _dep, (chart, _r) in CHARTS.items():
            if name in declared(chart):
                kind = "not_upstream" if name in not_up else "converted (in-process on every edge)"
                errors.append(f"{CLASSIFY}: {name} is {kind} but is still in {chart}/values.yaml upstreams")
    for dep, (chart, runtime) in CHARTS.items():
        for name in declared(chart):
            if name not in scans[dep]:
                notes.append(f"{chart}: upstream {name} is not read by any {runtime} module's configuration (kept for the module's own default; remove when nothing reads it)")


def check_registry(errors):
    reg = registry()
    plan = launch_plan()
    for dep, mods in plan.items():
        for m in mods:
            if reg.get(m, {}).get("deployable") != dep:
                errors.append(f"{LIBRARY_REGISTRY}: module {m} should be in {dep} ({PLAN})")
    for m in reg:
        if not any(m in mods for mods in plan.values()):
            errors.append(f"{LIBRARY_REGISTRY}: module {m} is not in {PLAN}")
    src = (ROOT / "go/apps/afrotransact-go/internal/registry/registry.go").read_text()
    names = {"cart": "cart", "search": "search", "carriergateway": "carrier-gateway"}
    in_code = {names.get(p, p) for p in re.findall(r"Name:\s*(\w+)\.Name[^\n]*Mount:\s*host\.MountPrefixed", src)}
    in_reg = {m for m, s in reg.items() if s.get("mount") == "prefixed"}
    if in_code != in_reg:
        errors.append(f"{LIBRARY_REGISTRY}: prefixed Go modules are {sorted(in_reg)} but go/apps/afrotransact-go registry.go mounts {sorted(in_code)} prefixed")
    core = (ROOT / "java/apps/core/src/main/java/com/afrotransact/core/CoreApplication.java").read_text()
    java_modules = set(re.findall(r'@ModuleSpec\(name = "([a-z-]+)"', core))
    reg_java = {m for m, s in reg.items() if s["deployable"] == "afrotransact-core"}
    if java_modules != reg_java:
        errors.append(f"{LIBRARY_REGISTRY}: Java modules differ from CoreApplication: {sorted(java_modules ^ reg_java)}")


def check_no_override(errors):
    """extraEnv must not redefine an upstream name (a hand edit would silently win over the single source)."""
    for _dep, (chart, _r) in CHARTS.items():
        names = set(declared(chart)) | {"MCP_PUBLIC_URL"}
        for f in sorted((ROOT / chart).glob("values*.yaml")):
            for k in ((yaml.safe_load(f.read_text()) or {}).get("extraEnv") or {}):
                if k in names:
                    errors.append(f"{f.relative_to(ROOT)}: extraEnv.{k} overrides the rendered upstream; edit values `upstreams` instead")


PUBLIC_BACKENDS = {"gateway-bff", "identity", "bookings", "fulfilment", "media", "notifications", "payments", "seller", "carrier-gateway"}
CORE_SERVICES = {"identity", "bookings", "fulfilment", "payments", "seller"}
MCP_DENY = ("/mcp", "/v1/tools", "/.well-known/oauth-protected-resource")


def route_docs(root=ROOT):
    for f in sorted((root / "infra/k8s/platform/edge/overlays").glob("*/routes.yaml")):
        for d in yaml.safe_load_all(f.read_text()):
            if d and d.get("kind") == "HTTPRoute":
                yield f.relative_to(root).as_posix(), d


def check_edge(errors, docs=None):
    for f, route in (docs if docs is not None else route_docs()):
        name = route["metadata"]["name"]
        label = f"{f}:{name}"
        if not route["metadata"].get("namespace", "").startswith("afrotransact-"):
            continue  # platform routes (Grafana in dev) are not AfroTransact services
        denied, allowed = set(), {}
        if len(route["spec"]["rules"]) > 16:
            errors.append(f"{label}: {len(route['spec']['rules'])} rules; the Gateway API allows 16 per HTTPRoute (merge deny matches into an existing deny rule)")
        for rule in route["spec"]["rules"]:
            if len(rule.get("matches", [])) > 64:
                errors.append(f"{label}: a rule has {len(rule['matches'])} matches; the Gateway API allows 64")
            paths = [m["path"]["value"] for m in rule.get("matches", [])] or ["/"]
            if any(fl.get("extensionRef", {}).get("name") == "deny-operational-paths" for fl in rule.get("filters", [])):
                denied.update(paths)
            for b in rule.get("backendRefs", []):
                if b["name"] not in PUBLIC_BACKENDS:
                    errors.append(f"{label}: routes to {b['name']}, which is private (mcp-server, core, the Go deployable and every internal module are cluster-internal; "
                                  f"public Services: {sorted(PUBLIC_BACKENDS)})")
                if b.get("port") != 80:
                    errors.append(f"{label}: backend {b['name']} port {b.get('port')} (Services listen on 80)")
                for p in paths:
                    allowed[p] = b["name"]
        if name == "api":
            if allowed.get("/bff") != "gateway-bff":
                errors.append(f"{label}: /bff must route to gateway-bff (the edge, in afrotransact-go after the cutover)")
            for prefix, svc in allowed.items():
                if prefix != "/bff" and prefix.count("/") == 1 and svc in CORE_SERVICES | {"media", "notifications", "carrier-gateway"}:
                    for must in [f"{prefix}/modules"] + ([f"{prefix}{x}" for x in MCP_DENY] if svc in CORE_SERVICES else []):
                        if must not in denied:
                            errors.append(f"{label}: {prefix} -> {svc} can land on a deployable after the cutover but {must} is not denied (deny-operational-paths)")
        elif name == "auth":
            if "identity" not in set(allowed.values()):
                errors.append(f"{label}: the auth host must route to identity")
            for must in ("/modules",) + MCP_DENY:
                if must not in denied:
                    errors.append(f"{label}: the auth host can land on core after the cutover but {must} is not denied (deny-operational-paths)")


def check_mcp(errors):
    for env in ENVS:
        out, _v = emulate("afrotransact-core", env, {"mcp-server": "afrotransact-core"})
        url = out.get("MCP_PUBLIC_URL", "")
        if not re.match(rf"^http://afrotransact-core\.afrotransact-{env}\.svc\.cluster\.local(/|$)", url):
            errors.append(f"{env}: MCP_PUBLIC_URL={url!r} must be core's in-cluster Service URL (McpExposureGuard)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scan", action="store_true")
    ap.add_argument("--render", nargs=2, metavar=("core|go", "env"))
    ap.add_argument("--helm", action="store_true", help="require helm and render with it")
    ap.add_argument("--no-helm", action="store_true")
    args = ap.parse_args()
    if args.helm and not shutil.which("helm"):
        print("helm is not installed", file=sys.stderr)
        return 2
    use_helm = (args.helm or bool(shutil.which("helm"))) and not args.no_helm
    if args.scan:
        for runtime, scan in (("java", scan_java()), ("go", scan_go())):
            for name, consumers in sorted(scan.items()):
                print(f"{runtime:5} {name:22} {', '.join(sorted(consumers))}")
        return 0
    if args.render:
        dep = {"core": "afrotransact-core", "go": "afrotransact-go"}[args.render[0]]
        out, _ = render(dep, args.render[1], full_launch(), use_helm)
        for k in sorted(out):
            if k.endswith("_URL"):
                print(f"{k}={out[k]}")
        return 0
    errors, notes = [], []
    check_registry(errors)
    check_no_override(errors)
    check_charts(errors, notes, use_helm)
    check_edge(errors)
    check_mcp(errors)
    for n in notes:
        print("note:", n)
    for e in errors:
        print("FAIL:", e)
    print(f"upstreams: {'FAILED (%d)' % len(errors) if errors else 'OK'} ({'helm' if use_helm else 'template emulation, helm not available'})")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
