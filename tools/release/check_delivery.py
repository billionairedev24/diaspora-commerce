#!/usr/bin/env python3
"""Offline consistency check of the Release 1 delivery files (make release-1-check). No cluster, no helm, no database.

  1. connection pools: modules.<m>.pool in the two charts == the schema:pool lists of infra/ops/postgres/release-1-bootstrap.sql
     (the SQL derives each role's CONNECTION LIMIT from them) and the sums respect dbBudget (steady <= maxPerPod, 2 pods <= total);
  2. modules: every module of launch-set/release-1.yaml is a key of its chart's `modules`, and the reverse;
  3. the schema/role names in the charts match the SQL (a module with a pool must have a schema line);
  4. the Java module environment-variable stems used by the core chart exist in the core application.yml (warns on a module the
     Java side has not added yet: that is expected until its batch);
  5. every per-environment values file of both charts exists.
"""
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
SQL = ROOT / "infra/ops/postgres/release-1-bootstrap.sql"
CHARTS = {"afrotransact-core": ROOT / "java/apps/core/deploy/helm", "afrotransact-go": ROOT / "go/apps/afrotransact-go/deploy/helm"}
bad = 0


def fail(msg):
    global bad
    bad += 1
    print("FAIL " + msg)


def sql_list(name):
    m = re.search(rf"^\\set {name} '([^']*)'", SQL.read_text(), re.M)
    return {a.split(":")[0]: int(a.split(":")[1]) for a in m.group(1).split(",")}


sql = {"afrotransact-core": sql_list("core_modules"), "afrotransact-go": sql_list("go_modules")}
plan = yaml.safe_load((ROOT / "infra/argocd/launch-set/release-1.yaml").read_text())
core_yml = (ROOT / "java/apps/core/src/main/resources/application.yml")

for dep, chart in CHARTS.items():
    values = yaml.safe_load((chart / "values.yaml").read_text())
    mods = values["modules"]
    pools = {m["schema"]: m["pool"] for m in mods.values() if m.get("pool")}
    if pools != sql[dep]:
        fail(f"{dep}: chart pools {pools} differ from the SQL {sql[dep]}")
    total = sum(pools.values())
    budget = values["dbBudget"]
    if total > budget["maxPerPod"]:
        fail(f"{dep}: pools add up to {total}, over dbBudget.maxPerPod {budget['maxPerPod']}")
    if 2 * total >= budget["total"]:
        fail(f"{dep}: two pods during a rolling update ({2 * total}) reach dbBudget.total {budget['total']}")
    want = set(plan["deployables"][dep]["modules"])
    if set(mods) != want:
        fail(f"{dep}: chart modules differ from release-1.yaml: only in chart {sorted(set(mods) - want)}, only in release-1.yaml {sorted(want - set(mods))}")
    for env in ("dev", "uat", "prod"):
        if not (chart / f"values-{env}.yaml").exists():
            fail(f"{dep}: missing values-{env}.yaml")
    print(f"{dep}: {len(pools)} modules with a database, pools {total} per pod ({2 * total} with a rolling-update surge), budget {budget['total']}")
    if dep == "afrotransact-core" and core_yml.exists():
        text = core_yml.read_text()
        for name, m in mods.items():
            stem = name.upper().replace("-", "_")
            if f"MODULE_{stem}_ENABLED" not in text:
                print(f"  note: {name} has no MODULE_{stem}_ENABLED in core's application.yml yet (its batch has not landed)")

# 6. Monitoring parity: every service="..." value the alert rules select on must still exist as a series: a module short name (the app
#    keeps tagging meters with it inside a deployable), a deployable, or a platform component.
known = set(plan["deployables"]["afrotransact-core"]["modules"]) | set(plan["deployables"]["afrotransact-go"]["modules"]) | set(CHARTS) | {"alloy"}
for rules in sorted((ROOT / "infra/observability/rules").glob("*.yaml")):
    for m in re.finditer(r'service(?:=~|=)\\?"([^"]+)\\?"', rules.read_text()):
        for v in m.group(1).replace("\\", "").split("|"):
            if v not in known and not v.startswith("$"):
                fail(f"{rules.name}: service label value {v!r} is not a module, a deployable or a platform component")

print("release-1 delivery check: " + ("FAILED" if bad else "OK"))
sys.exit(1 if bad else 0)
