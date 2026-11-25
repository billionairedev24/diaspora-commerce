#!/usr/bin/env python3
"""Proves which Argo CD Applications each environment produces (the launch set), offline.

Builds infra/argocd/envs/<env> with kustomize (or `kubectl kustomize`), expands every ApplicationSet with the same emulation
render_apps.py uses, and compares the service Applications with the expected set:

  dev          every chart under java/services/* and go/services/*  (26: dev can run everything)
  uat, prod    exactly the 21 launch-set services (launch-set/kustomization.yaml)

Release 1 (release-1 branch; infra/argocd/launch-set/release-1.yaml exists): every environment also gets the two deployable
Applications (afrotransact-core, afrotransact-go), the per-service Applications stay, and the check prints for each environment
which modules run inside a deployable (cutover.services in platform-values.yaml) and which per-service Applications are therefore
scaled to 0. It fails when release-1.yaml and cutover.services disagree.

It also fails when prod (lean) would create an Application for something that must not run there (OpenFGA, cert-manager,
Kyverno, Rollouts, Prometheus, Loki, Tempo, Elasticsearch, External Secrets).

  python3 infra/argocd/scripts/check_launch_set.py            # prints the Applications per env, exit 1 on any mismatch
  python3 infra/argocd/scripts/check_launch_set.py --env prod
Used by `make launch-set-check` (and by `make helm-lint`).
"""
import argparse
import glob
import pathlib
import sys

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import render_apps as ra  # noqa: E402

JAVA = "identity catalog offers inventory orders payments seller fulfilment bookings tax-returns content trust-ops ai-gateway mcp-server".split()
GO = "gateway-bff cart search indexer geo media notifications".split()
LAUNCH = sorted(JAVA + GO)
NOT_LAUNCHED = ["promise", "carrier-gateway", "retail", "delivery-runs", "authz-sync"]
FORBIDDEN_PLATFORM = ["openfga", "cert-manager", "external-secrets", "kyverno", "argo-rollouts", "kube-prometheus-stack", "loki",
                      "tempo-distributed", "eck-operator", "elasticsearch", "observability-content", "policies", "previews"]
EXPECTED_PLATFORM_PROD = ["edge", "envoy-gateway", "lean", "namespaces", "opentelemetry-collector-gateway"]


RELEASE1 = ra.ROOT / "infra/argocd/launch-set/release-1.yaml"
DEPLOYABLE_RUNTIME = {"afrotransact-core": "java", "afrotransact-go": "go"}


def release1_plan():
    return yaml.safe_load(RELEASE1.read_text()) if RELEASE1.exists() else None


def check_release1(env, apps, pv, plan):
    """Release 1: deployables present, cutover.services == release-1.yaml `enabled`, every mapped service exists. Returns (failures, summary lines)."""
    bad, lines = 0, []
    deployables = sorted(a["metadata"]["labels"]["afrotransact.com/service"] for a in apps
                         if a["metadata"]["labels"].get("afrotransact.com/kind") == "deployable")
    if deployables != sorted(DEPLOYABLE_RUNTIME):
        bad += 1
        lines.append(f"  FAIL [{env}] deployable Applications are {deployables}, expected {sorted(DEPLOYABLE_RUNTIME)}")
    cut = (pv.get("cutover") or {}).get("services") or {}
    modules = {d: set(v["modules"]) for d, v in plan["deployables"].items()}
    want = (plan.get("enabled") or {}).get(env, {})
    for dep, mods in want.items():
        for m in mods:
            if m not in modules.get(dep, set()):
                bad += 1
                lines.append(f"  FAIL [{env}] release-1.yaml enables {m!r} in {dep}, which has no such module")
            if cut.get(m) != dep:
                bad += 1
                lines.append(f"  FAIL [{env}] {m} is enabled in {dep} (release-1.yaml) but cutover.services says {cut.get(m)!r} in envs/{env}/platform-values.yaml")
    for m, dep in cut.items():
        if m not in want.get(dep, []):
            bad += 1
            lines.append(f"  FAIL [{env}] cutover.services maps {m} to {dep} but release-1.yaml does not enable it there")
        if dep not in DEPLOYABLE_RUNTIME:
            bad += 1
            lines.append(f"  FAIL [{env}] cutover.services: {m} -> {dep!r} is not a deployable")
        elif env != "dev" and m not in LAUNCH:
            bad += 1
            lines.append(f"  FAIL [{env}] cutover.services: {m} is not a launch-set service, so there is no Application to scale down")
    for dep in sorted(DEPLOYABLE_RUNTIME):
        on = sorted(m for m, d in cut.items() if d == dep)
        lines.append(f"[{env}] {dep}-{env}: runs {len(on)} module(s) {', '.join(on) if on else '(none yet: idle, scaled to 0)'}")
    return bad, lines, cut


def generator_revisions(node):
    """Every git generator `revision` value inside an ApplicationSet (matrix generators nest)."""
    found = []
    if isinstance(node, dict):
        git = node.get("git")
        if isinstance(git, dict) and "revision" in git:
            found.append(str(git["revision"]))
        for v in node.values():
            found += generator_revisions(v)
    elif isinstance(node, list):
        for v in node:
            found += generator_revisions(v)
    return found


REVISION_FAILS = []


def unverified_pin_error(env, name, values):
    """prod must never pin an image of the one-time manual UAT build that skipped verify (_deploy.yml marks it image.unverified: true)."""
    if env == "prod" and (values.get("image") or {}).get("unverified"):
        return (f"  FAIL [{env}] {name}: image.unverified is set - this pin is an image built WITHOUT a green verify (UAT-only); "
                f"promote an image from a green verify instead")
    return None


def apps_for(env):
    built, err = ra.kustomize_build(ra.ROOT / "infra/argocd/envs" / env)
    if built is None:
        raise SystemExit(f"FAIL [{env}] kustomize build: {err.strip()}")
    apps, pv = [], None
    for d in (x for x in yaml.safe_load_all(built) if x):
        if d.get("kind") == "ApplicationSet":
            if RELEASE1.exists():
                # release-1 is the production line: every generator must read it, or the Applications (and platform-values.yaml) come from main
                bad = sorted({r for r in generator_revisions(d["spec"]) if r != "release-1"})
                if bad:
                    REVISION_FAILS.append(f"  FAIL [{env}] ApplicationSet {d['metadata']['name']} has a git generator on revision {bad}, expected release-1 (infra/argocd/release-branch)")
            generated, pv = ra.generate(d, env)
            apps += generated
    return apps, pv


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", choices=ra.ENVS, action="append")
    ap.add_argument("--require-tags", action="store_true",
                    help="pre-sync gate: every launch-set chart's values-<env>.yaml must pin image.tag to a full 40-hex commit "
                         "sha (the base values.yaml says `dev`, which does not exist in the registry)")
    args = ap.parse_args()
    all_charts = sorted(pathlib.Path(c).parts[-3] for c in
                        glob.glob(str(ra.ROOT / "java/services/*/deploy/helm")) + glob.glob(str(ra.ROOT / "go/services/*/deploy/helm")))
    bad = 0
    for env in args.env or ra.ENVS:
        apps, pv = apps_for(env)
        svc = sorted(a["metadata"]["labels"]["afrotransact.com/service"] for a in apps
                     if a["spec"]["project"].startswith("afrotransact-") and a["metadata"]["labels"].get("afrotransact.com/kind") != "deployable")
        platform = sorted(a["metadata"]["name"].removesuffix(f"-{env}") for a in apps if a["spec"]["project"].startswith("platform-"))
        expected = all_charts if env == "dev" else LAUNCH
        sync = "automated" if pv["argocd"].get("autoSync") else "MANUAL"
        print(f"[{env}] {len(svc)} service Applications, sync {sync}")
        plan = release1_plan()
        cut = {}
        if plan:
            nbad, lines, cut = check_release1(env, apps, pv, plan)
            bad += nbad
        for name in svc:
            tail = f"   scaled to 0, Service routed to {cut[name]}" if name in cut else ""
            print(f"    {name}-{env}{tail}")
        if plan:
            print("\n".join(lines))
        print(f"[{env}] {len(platform)} platform Applications: {', '.join(platform)}")
        if svc != expected:
            bad += 1
            print(f"  FAIL [{env}] service Applications differ from the expected set\n    missing: {sorted(set(expected) - set(svc))}\n    extra:   {sorted(set(svc) - set(expected))}")
        if env in ("prod", "uat"):
            leaked = [n for n in NOT_LAUNCHED if n in svc]
            if leaked:
                bad += 1
                print(f"  FAIL [{env}] not-launched services got an Application: {leaked}")
        if env == "prod":
            if platform != sorted(EXPECTED_PLATFORM_PROD):
                bad += 1
                print(f"  FAIL [{env}] platform Applications are {platform}, expected {sorted(EXPECTED_PLATFORM_PROD)}")
            leaked = [p for p in platform if p in FORBIDDEN_PLATFORM]
            if leaked:
                bad += 1
                print(f"  FAIL [{env}] forbidden platform Applications: {leaked}")
            if pv["argocd"].get("autoSync"):
                bad += 1
                print(f"  FAIL [{env}] autoSync must be false (manual sync in prod)")
            if pv.get("cloud") != "ovh":
                bad += 1
                print(f"  FAIL [{env}] platform-values cloud is {pv.get('cloud')!r}, expected 'ovh'")
    if args.require_tags:
        import re
        if release1_plan():
            for env in [e for e in (args.env or ra.ENVS) if e != "dev"]:
                for dep, spec in release1_plan()["deployables"].items():
                    values = yaml.safe_load((ra.ROOT / spec["chart"] / f"values-{env}.yaml").read_text()) or {}
                    img = values.get("image", {})
                    err = unverified_pin_error(env, dep, values)
                    if err:
                        bad += 1
                        print(err)
                    if not re.fullmatch(r"[0-9a-f]{40}", str(img.get("tag", ""))) or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(img.get("digest", ""))):
                        bad += 1
                        print(f"  FAIL [{env}] {dep}: image.tag / image.digest are not pinned (run the promote workflow for {dep})")
        for env in [e for e in (args.env or ra.ENVS) if e != "dev"]:
            for svc in LAUNCH:
                chart = next(iter(glob.glob(str(ra.ROOT / f"*/services/{svc}/deploy/helm"))))
                values = yaml.safe_load((pathlib.Path(chart) / f"values-{env}.yaml").read_text()) or {}
                err = unverified_pin_error(env, svc, values)
                if err:
                    bad += 1
                    print(err)
                tag = values.get("image", {}).get("tag", "")
                if not re.fullmatch(r"[0-9a-f]{40}", str(tag)):
                    bad += 1
                    print(f"  FAIL [{env}] {svc}: image.tag is {tag!r}, expected a 40-character commit sha (run the promote workflow)")
                digest = values.get("image", {}).get("digest", "")
                if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(digest)):
                    bad += 1
                    print(f"  FAIL [{env}] {svc}: image.digest is {digest!r}, expected sha256:<64 hex> (the promote workflow pins it after verifying the signature; a tag alone can move)")
    for line in REVISION_FAILS:
        bad += 1
        print(line)
    print("launch-set check: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
