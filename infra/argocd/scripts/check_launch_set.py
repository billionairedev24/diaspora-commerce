#!/usr/bin/env python3
"""Proves which Argo CD Applications each environment produces (the launch set), offline.

Builds infra/argocd/envs/<env> with kustomize (or `kubectl kustomize`), expands every ApplicationSet with the same emulation
render_apps.py uses, and compares the service Applications with the expected set:

  dev          every chart under java/services/* and go/services/*  (26: dev can run everything)
  uat, prod    exactly the 21 launch-set services (launch-set/kustomization.yaml)

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


def apps_for(env):
    built, err = ra.kustomize_build(ra.ROOT / "infra/argocd/envs" / env)
    if built is None:
        raise SystemExit(f"FAIL [{env}] kustomize build: {err.strip()}")
    apps, pv = [], None
    for d in (x for x in yaml.safe_load_all(built) if x):
        if d.get("kind") == "ApplicationSet":
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
        svc = sorted(a["metadata"]["labels"]["afrotransact.com/service"] for a in apps if a["spec"]["project"].startswith("afrotransact-"))
        platform = sorted(a["metadata"]["name"].removesuffix(f"-{env}") for a in apps if a["spec"]["project"].startswith("platform-"))
        expected = all_charts if env == "dev" else LAUNCH
        sync = "automated" if pv["argocd"].get("autoSync") else "MANUAL"
        print(f"[{env}] {len(svc)} service Applications, sync {sync}")
        for name in svc:
            print(f"    {name}-{env}")
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
                print(f"  FAIL [prod] platform Applications are {platform}, expected {sorted(EXPECTED_PLATFORM_PROD)}")
            leaked = [p for p in platform if p in FORBIDDEN_PLATFORM]
            if leaked:
                bad += 1
                print(f"  FAIL [prod] forbidden platform Applications: {leaked}")
            if pv["argocd"].get("autoSync"):
                bad += 1
                print("  FAIL [prod] autoSync must be false (manual sync in prod)")
            if pv.get("cloud") != "ovh":
                bad += 1
                print(f"  FAIL [prod] platform-values cloud is {pv.get('cloud')!r}, expected 'ovh'")
    if args.require_tags:
        import re
        for env in [e for e in (args.env or ra.ENVS) if e != "dev"]:
            for svc in LAUNCH:
                chart = next(iter(glob.glob(str(ra.ROOT / f"*/services/{svc}/deploy/helm"))))
                values = yaml.safe_load((pathlib.Path(chart) / f"values-{env}.yaml").read_text()) or {}
                tag = values.get("image", {}).get("tag", "")
                if not re.fullmatch(r"[0-9a-f]{40}", str(tag)):
                    bad += 1
                    print(f"  FAIL [{env}] {svc}: image.tag is {tag!r}, expected a 40-character commit sha (run the promote workflow)")
    print("launch-set check: " + ("FAILED" if bad else "OK"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
