#!/usr/bin/env python3
"""Guard: every Stripe webhook the platform expects has a public route on api.<domain>.

Stripe can only reach a service through the edge. The Gateway API overlays (infra/k8s/platform/edge/overlays/<env>/routes.yaml)
must each route the two webhooks to the
right service, with the prefix rewritten to the service's own path, and must expose nothing else of seller (there is
no plan-fee "stripe-billing" webhook: plan fees are card-only and read back from the PaymentIntent):

    /payments/v1/webhooks              -> payments /v1/webhooks            (STRIPE_WEBHOOK_SECRET)
    /seller/v1/webhooks/stripe-connect -> seller   /v1/webhooks/stripe-connect  (STRIPE_CONNECT_WEBHOOK_SECRET)

    python3 tools/ci/check_webhook_routes.py          # exit 0 clean, 1 on a missing or too-wide route
"""
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "/payments/v1/webhooks": ("payments", "/v1/webhooks"),
    "/seller/v1/webhooks/stripe-connect": ("seller", "/v1/webhooks/stripe-connect"),
}


def check(label, found, errors):
    for path, want in EXPECTED.items():
        if found.get(path) != want:
            errors.append(f"{label}: {path} should route to {want}, found {found.get(path)}")
    for path in found:
        if path.startswith("/seller") and path not in EXPECTED:
            errors.append(f"{label}: {path} exposes more of the seller service than its Connect webhook")


def main():
    errors = []
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
    print("webhook routes: " + ("FAILED" if errors else "ok (edge overlays)"))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
