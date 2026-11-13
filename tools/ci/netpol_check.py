#!/usr/bin/env python3
"""F13: every service chart renders a NetworkPolicy with default-deny egress, DNS egress and no way to the metadata IP.

For each service chart (go/services/*/deploy/helm, java/services/*/deploy/helm) this runs `helm template` for the given env
(default prod) with networkPolicy.egress.enabled=true and checks the rendered NetworkPolicy:
  - exactly one NetworkPolicy for the service, policyTypes Ingress and Egress;
  - an egress rule to kube-dns in kube-system on port 53 UDP and TCP (no service lacks DNS);
  - every ipBlock with cidr 0.0.0.0/0 excludes 169.254.0.0/16 (the cloud metadata IP 169.254.169.254 is never reachable), and
    no ipBlock cidr covers 169.254.169.254;
  - with egress disabled (the default) the policy stays ingress-only (nothing changes until an environment opts in).

    python3 tools/ci/netpol_check.py [--env prod] [--only media,orders]

Needs helm and PyYAML. Exit 1 on any failure.
"""
import argparse
import ipaddress
import pathlib
import subprocess
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
METADATA = ipaddress.ip_address("169.254.169.254")


def charts():
    for runtime in ("go", "java"):
        yield from sorted((ROOT / runtime / "services").glob("*/deploy/helm"))


def render(chart, env, extra):
    cmd = ["helm", "template", chart.parents[1].name, str(chart), "-n", f"afrotransact-{env}"]
    for f in (chart / f"values-{env}.yaml", ROOT / "infra/argocd/envs" / env / "platform-values.yaml"):
        if f.exists():
            cmd += ["-f", str(f)]
    # platform-values may enable egress already; the explicit flag makes the check independent of the rollout state
    cmd += extra
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError(r.stderr.strip())
    return [d for d in yaml.safe_load_all(r.stdout) if d]


def policies(docs):
    return [d for d in docs if d.get("kind") == "NetworkPolicy"]


def check(chart, env):
    name = chart.parents[1].name
    errs = []
    subprocess.run(["helm", "dependency", "build", str(chart)], capture_output=True)
    try:
        on = policies(render(chart, env, ["--set", "networkPolicy.egress.enabled=true"]))
        off = policies(render(chart, env, ["--set", "networkPolicy.egress.enabled=false"]))
    except RuntimeError as e:
        return [f"helm template failed: {e}"]
    if len(on) != 1:
        return [f"expected 1 NetworkPolicy, got {len(on)}"]
    spec = on[0]["spec"]
    if sorted(spec.get("policyTypes", [])) != ["Egress", "Ingress"]:
        errs.append(f"policyTypes {spec.get('policyTypes')} lack Egress")
    dns_ports = set()
    for rule in spec.get("egress", []):
        for to in rule.get("to", []):
            ns = to.get("namespaceSelector", {}).get("matchLabels", {}).get("kubernetes.io/metadata.name")
            if ns == "kube-system":
                dns_ports |= {(p.get("port"), p.get("protocol")) for p in rule.get("ports", [])}
        for to in rule.get("to", []):
            blk = to.get("ipBlock")
            if not blk:
                continue
            net = ipaddress.ip_network(blk["cidr"])
            excepts = [ipaddress.ip_network(e) for e in blk.get("except", [])]
            if METADATA in net and not any(METADATA in e for e in excepts):
                errs.append(f"ipBlock {blk['cidr']} reaches the metadata IP 169.254.169.254")
    if not {(53, "UDP"), (53, "TCP")} <= dns_ports:
        errs.append("no DNS egress (kube-system, 53 UDP and TCP)")
    if not spec.get("egress"):
        errs.append("empty egress list: default deny with no allowance")
    if len(off) != 1 or off[0]["spec"].get("policyTypes") != ["Ingress"]:
        errs.append("with egress disabled the policy must stay ingress-only")
    return [f"{name}: {e}" for e in errs]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default="prod")
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    only = {s for s in a.only.split(",") if s}
    failed = 0
    n = 0
    for chart in charts():
        name = chart.parents[1].name
        if only and name not in only:
            continue
        n += 1
        errs = check(chart, a.env)
        if errs:
            failed += 1
            print(f"FAIL {name}")
            for e in errs:
                print(f"     {e}")
        else:
            print(f"ok   {name}")
    print(f"{n} charts, {failed} failed")
    return 1 if failed or n == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
