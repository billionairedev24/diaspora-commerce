#!/usr/bin/env python3
"""Release 1 (ADR 0011) monitoring parity check, no running stack needed.

Fails when a rule or dashboard selects `service="x"` / `service=~"a|b"` with a value that no module or deployable can export, or when a
scrape config lists an application target that the monolith no longer serves.

  * Valid `service` values: the Java modules mounted by afrotransact-core (keys of at.modules in java/apps/core application.yml),
    the Go modules of afrotransact-go (go/apps/afrotransact-go/internal/registry/registry.go), the two deployables, and the
    platform components that label themselves (alloy, valkey, meilisearch, kube-state-metrics ...).
  * Local Prometheus (infra/observability/prometheus.yml): application jobs are exactly afrotransact-core :8120 and afrotransact-go :8200;
    no old per-service port (8100-8115, 8201-8210) may be a scrape target.
  * Alloy (infra/k8s/platform/lean/alloy/config.alloy): the metric allowlist must keep at_module_up and honor_labels for the deployables.
"""
import json
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[3]
OBS = ROOT / "infra" / "observability"

PLATFORM_SERVICES = {"alloy", "valkey", "meilisearch", "kube-state-metrics", "cloudflared", "temporal", "kafka-exporter"}
DEPLOYABLES = {"afrotransact-core", "afrotransact-go"}


def java_modules():
    cfg = yaml.safe_load((ROOT / "java/apps/core/src/main/resources/application.yml").read_text())
    return set(cfg["at"]["modules"]) - {"schema-admin"}


def go_modules():
    text = (ROOT / "go/apps/afrotransact-go/internal/registry/registry.go").read_text()
    names = set(re.findall(r"\{Name: (\w+)\.Name", text))
    mapping = {"carriergateway": "carrier-gateway", "authzsync": "authz-sync", "bffmodule": "gateway-bff"}
    return {mapping.get(n, n) for n in names}


def selector_values(expr):
    for m in re.finditer(r'\bservice\s*(=~|=)\s*"([^"]*)"', expr):
        yield from m.group(2).split("|") if m.group(1) == "=~" else [m.group(2)]


def main():
    valid = java_modules() | go_modules() | DEPLOYABLES | PLATFORM_SERVICES
    problems = []

    exprs = []
    for f in sorted((OBS / "rules").glob("*.yaml")):
        for g in yaml.safe_load(f.read_text())["groups"]:
            for r in g["rules"]:
                exprs.append((f"{f.name}:{r.get('alert') or r.get('record')}", r["expr"]))
    for f in sorted((OBS / "grafana/dashboards").glob("*.json")):
        for p in json.loads(f.read_text())["panels"]:
            for t in p.get("targets", []):
                exprs.append((f"{f.name}:{p['title']}", t.get("expr", "")))
    for where, expr in exprs:
        for v in selector_values(expr):
            if v in ("", ".*", "$service") or v.startswith("$"):
                continue
            if v not in valid:
                problems.append(f"{where}: service \"{v}\" is neither a module nor a deployable")

    prom = yaml.safe_load((OBS / "prometheus.yml").read_text())
    jobs = {j["job_name"]: j for j in prom["scrape_configs"]}
    for name, port in (("afrotransact-core", 8120), ("afrotransact-go", 8200)):
        job = jobs.get(name)
        if not job:
            problems.append(f"prometheus.yml: no job {name}")
            continue
        targets = [t for sc in job["static_configs"] for t in sc["targets"]]
        if targets != [f"host.docker.internal:{port}"]:
            problems.append(f"prometheus.yml: job {name} targets {targets}, expected host.docker.internal:{port}")
        if not job.get("honor_labels"):
            problems.append(f"prometheus.yml: job {name} needs honor_labels: true")
    old = re.compile(r":(81(0\d|1[0-5])|82(0[1-9]|10))$")
    for name, job in jobs.items():
        for sc in job.get("static_configs", []):
            for t in sc["targets"]:
                if old.search(t):
                    problems.append(f"prometheus.yml: job {name} scrapes the legacy per-service port {t}")

    alloy = (ROOT / "infra/k8s/platform/lean/alloy/config.alloy").read_text()
    if "at_module_(up|ready)" not in alloy:
        problems.append("alloy config.alloy: the metric allowlist lacks at_module_up")
    if "tasks_scheduled_execution_seconds" not in alloy:
        problems.append("alloy config.alloy: the metric allowlist lacks tasks_scheduled_execution_seconds (scheduler panels)")
    if not re.search(r'prometheus\.scrape "deployable_pods" \{[^}]*honor_labels\s*=\s*true', alloy, re.S):
        problems.append("alloy config.alloy: deployable_pods scrape needs honor_labels = true")

    if problems:
        print("monolith monitoring parity problems:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print(f"monolith monitoring parity OK ({len(exprs)} expressions, {len(valid)} valid service names)")


if __name__ == "__main__":
    main()
