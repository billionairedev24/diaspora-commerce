#!/usr/bin/env python3
"""Guard: every Stripe webhook the platform expects has a public route on api.<domain>, in both places.

Stripe can only reach a service through the edge. The OVH single-server ingress (infra/ovh-vps/deploy.sh) and the
Gateway API overlays (infra/k8s/platform/edge/overlays/<env>/routes.yaml) must each route the three webhooks to the
right service, with the prefix rewritten to the service's own path, and must expose nothing else of seller:

    /payments/v1/webhooks              -> payments /v1/webhooks            (STRIPE_WEBHOOK_SECRET)
    /seller/v1/webhooks/stripe-connect -> seller   /v1/webhooks/stripe-connect  (STRIPE_CONNECT_WEBHOOK_SECRET)
    /seller/v1/webhooks/stripe-billing -> seller   /v1/webhooks/stripe-billing  (STRIPE_BILLING_WEBHOOK_SECRET)

    python3 tools/ci/check_webhook_routes.py          # exit 0 clean, 1 on a missing or too-wide route
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "/payments/v1/webhooks": ("payments", "/v1/webhooks"),
    "/seller/v1/webhooks/stripe-connect": ("seller", "/v1/webhooks/stripe-connect"),
    "/seller/v1/webhooks/stripe-billing": ("seller", "/v1/webhooks/stripe-billing"),
}


def check(label, found, errors):
    for path, want in EXPECTED.items():
        if found.get(path) != want:
            errors.append(f"{label}: {path} should route to {want}, found {found.get(path)}")
    for path in found:
        if path.startswith("/seller") and path not in EXPECTED:
            errors.append(f"{label}: {path} exposes more of the seller service than its two webhooks")


def main():
    errors = []
    deploy = (ROOT / "infra/ovh-vps/deploy.sh").read_text()
    found = {m[0]: (m[1], m[2]) for m in re.findall(r'^\s*route api (\S+) (\S+) "([^"]*)"', deploy, re.M)
             if m[0].startswith(("/payments", "/seller"))}
    found = {p: (s, r) for p, (s, r) in found.items()}
    check("infra/ovh-vps/deploy.sh", found, errors)
    for routes in sorted((ROOT / "infra/k8s/platform/edge/overlays").glob("*/routes.yaml")):
        found = {}
        for doc in yaml.safe_load_all(routes.read_text()):
            if not doc or doc.get("kind") != "HTTPRoute" or doc["metadata"]["name"] != "api":
                continue
            for rule in doc["spec"]["rules"]:
                for m in rule.get("matches", []):
                    path = m["path"]["value"]
                    if path.startswith(("/payments", "/seller")):
                        rewrite = rule["filters"][0]["urlRewrite"]["path"]["replacePrefixMatch"]
                        found[path] = (rule["backendRefs"][0]["name"], rewrite)
        check(str(routes.relative_to(ROOT)), found, errors)
    for e in errors:
        print("ERROR", e)
    print("webhook routes: " + ("FAILED" if errors else "ok (deploy.sh + dev/uat/prod overlays)"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
