#!/usr/bin/env python3
"""Does the production plan fit the ONE b3-16 node, at the batch production is at and at every later batch? (make release-1-check)

From the charts (no helm, no cluster): the per-service requests of values.yaml + values-prod.yaml, the two deployables' requests, and the fixed
platform figures in tools/release/parity.yaml `capacity`. For every cutover state (nothing moved, then after each batch of
infra/argocd/launch-set/release-1.yaml):
  steady  = fixed + the per-service Applications still running + core's request (scheduled for that batch) + the Go deployable's request
  surge   = steady + core's request again (a rolling update runs two core pods for about a minute, `maxSurge 25 %`)
Fails when surge exceeds allocatable, or when production is AT a batch and values-prod.yaml reserves LESS for core than that batch's schedule
(the JVM would be OOM-killed before the scheduler noticed). Prints the table. Exit 1 on a failure.
"""
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]


def mib(v):
    m = re.fullmatch(r"(\d+(?:\.\d+)?)(Ki|Mi|Gi|M|G)?", str(v))
    return float(m.group(1)) * {"Ki": 1 / 1024, "Mi": 1, "Gi": 1024, "M": 0.9537, "G": 976.6, None: 1 / 1048576}[m.group(2)]


def load(p):
    try:
        return yaml.safe_load(p.read_text()) or {}
    except FileNotFoundError:
        return {}


def deep(a, b):
    out = dict(a)
    for k, v in b.items():
        out[k] = deep(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


def request(chart, env="prod"):
    v = deep(load(chart / "values.yaml"), load(chart / f"values-{env}.yaml"))
    return mib(((v.get("resources") or {}).get("requests") or {}).get("memory", 0))


def main(root=ROOT):
    cfg = (load(root / "tools/release/parity.yaml").get("capacity")) or {}
    plan = load(root / "infra/argocd/launch-set/release-1.yaml")
    if not cfg or not plan:
        print("capacity: no capacity section or release-1.yaml: skipped")
        return 0
    launch = {m for m in (plan["deployables"]["afrotransact-core"]["modules"] + plan["deployables"]["afrotransact-go"]["modules"])}
    launched = sorted(launch - {"promise", "carrier-gateway", "retail", "delivery-runs", "authz-sync"})
    per = {}
    for m in launched:
        chart = next(iter((root / "java/services").glob(f"{m}/deploy/helm")), None) or next(iter((root / "go/services").glob(f"{m}/deploy/helm")))
        per[m] = request(chart)
    core_prod = request(root / plan["deployables"]["afrotransact-core"]["chart"])
    go_prod = request(root / plan["deployables"]["afrotransact-go"]["chart"])
    fixed = sum(cfg["fixed_mib"].values())
    alloc = cfg["allocatable_mib"]
    schedule = {int(k): v for k, v in cfg["core_request_mib_by_batch"].items()}
    batches = plan.get("batches") or []
    core_modules = set(plan["deployables"]["afrotransact-core"]["modules"])
    moved_now = set((plan.get("enabled") or {}).get("prod", {}).get("afrotransact-core", [])) | set((plan.get("enabled") or {}).get("prod", {}).get("afrotransact-go", []))
    bad = 0
    print(f"{'state':<26}{'services':>10}{'core':>8}{'go':>6}{'fixed':>7}{'steady':>8}{'+surge':>8}  allocatable {alloc:.0f} MiB")
    moved, reached = set(), 0
    states = [(0, set())]
    for b in batches:
        moved = moved | set(b["core"]) | set(b["go"])
        states.append((b["id"], set(moved)))
        if set(b["core"]) | set(b["go"]) <= moved_now:
            reached = b["id"]
    for bid, mv in states:
        services = sum(v for m, v in per.items() if m not in mv)
        core = schedule.get(bid, 0) if mv & core_modules else 0
        go = go_prod if mv - core_modules else 0
        steady = fixed + services + core + go
        surge = steady + core
        flag = "" if surge <= alloc else "  DOES NOT FIT"
        print(f"{'nothing moved' if bid == 0 else 'after batch ' + str(bid):<26}{services:>10.0f}{core:>8.0f}{go:>6.0f}{fixed:>7.0f}{steady:>8.0f}{surge:>8.0f}{flag}")
        if flag:
            bad += 1
    if reached and core_prod < schedule.get(reached, 0):
        bad += 1
        print(f"FAIL production is at batch {reached}: values-prod.yaml reserves {core_prod:.0f} Mi for core, the schedule says {schedule[reached]} Mi")
    if core_prod > schedule.get(reached, 0) and reached == 0 and moved_now == set():
        # nothing moved: the deployable renders replicas 0, so its request costs nothing; but the first batch needs the schedule's value
        pass
    nxt = schedule.get(reached + 1, 0)
    if core_prod > max(schedule.get(reached, 0), nxt) + 1:
        print(f"note: core's request in values-prod.yaml ({core_prod:.0f} Mi) is above the schedule for batches {reached} and {reached + 1}; the node-fit table above uses the schedule")
    print("capacity: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
