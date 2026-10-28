#!/usr/bin/env python3
"""Prepares a Stripe TEST account for the local stack (make stripe-setup).

- Checks the keys in .local/secrets.env (or the environment): test-mode secret key, matching publishable key.
- Checks Connect is enabled (seller onboarding creates connected accounts) and that Stripe accepts the seller
  account settings (a throwaway test account, deleted).
- Lists the plan Prices the account already has. It does not create any: plans are AfroTransact's own catalogue
  (seller service, staff console > Plans & billing). The seller service creates a Product per plan and a Price per
  plan version and cadence when a plan is published or when it starts, and adopts Prices an older version of this
  script made (lookup keys <plan>_monthly | _quarterly | _annual) when amount, currency and interval match.

Refuses live keys. Standard library only.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SECRETS = os.path.join(ROOT, ".local", "secrets.env")
API = "https://api.stripe.com/v1"

# Lookup keys of the seeded catalogue (java/services/seller/.../V70__plan_catalog_trials_rewards.sql), for the report.
PLAN_CODES = ["seller_growth", "seller_pro", "provider_solo", "provider_studio", "provider_business"]


def secrets():
    values = {}
    if os.path.exists(SECRETS):
        with open(SECRETS, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip().strip('"').strip("'")
    for k in ("STRIPE_SECRET_KEY", "STRIPE_PUBLISHABLE_KEY"):
        if os.environ.get(k):
            values[k] = os.environ[k]
    return values


def call(key, method, path, params=None, idempotency_key=None):
    data = urllib.parse.urlencode(params or {}, doseq=True).encode() if method == "POST" else None
    url = API + path
    if method == "GET" and params:
        url += "?" + urllib.parse.urlencode(params, doseq=True)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Basic " + base64.b64encode((key + ":").encode()).decode())
    if idempotency_key:
        req.add_header("Idempotency-Key", idempotency_key)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        body = json.loads(e.read() or b"{}")
        raise RuntimeError(body.get("error", {}).get("message", str(e))) from None


# The controller settings StripeConnectAdapter creates sellers' accounts with (keep in sync).
SELLER_ACCOUNT = {
    "controller[requirement_collection]": "stripe",
    "controller[losses][payments]": "stripe",
    "controller[fees][payer]": "account",
    "controller[stripe_dashboard][type]": "none",
}


def probe_connect(sk):
    """Creates a throwaway test connected account with the seller settings, reports Stripe's answer, deletes it."""
    params = dict(SELLER_ACCOUNT, country="US", business_type="individual",
                  **{"capabilities[card_payments][requested]": "true", "capabilities[transfers][requested]": "true",
                     "settings[payouts][schedule][interval]": "manual", "metadata[purpose]": "make stripe-setup probe"})
    try:
        acct = call(sk, "POST", "/accounts", params)
    except RuntimeError as e:
        print(f"  ✗ Seller account settings refused by Stripe: {e}")
        print("    " + ", ".join(f"{k}={v}" for k, v in SELLER_ACCOUNT.items()))
        return
    print("  ✓ Seller account settings accepted (" + ", ".join(f"{k.split('[')[-1].rstrip(']')}={v}"
                                                             for k, v in SELLER_ACCOUNT.items()) + ")")
    try:
        call(sk, "DELETE", f"/accounts/{acct['id']}")
    except RuntimeError as e:
        print(f"    (could not delete the probe account {acct['id']}: {e})")


def main():
    s = secrets()
    sk, pk = s.get("STRIPE_SECRET_KEY", ""), s.get("STRIPE_PUBLISHABLE_KEY", "")
    if not sk:
        sys.exit("STRIPE_SECRET_KEY is not set: put your test keys in .local/secrets.env (make secrets-check)")
    if not (sk.startswith("sk_test_") or sk.startswith("rk_test_")):
        sys.exit("STRIPE_SECRET_KEY is not a test key (sk_test_… / rk_test_…): refusing")
    if not pk.startswith("pk_test_"):
        sys.exit("STRIPE_PUBLISHABLE_KEY must be the pk_test_… key of the same account")

    account = call(sk, "GET", "/account")
    print(f"Stripe test account {account['id']} ({account.get('settings', {}).get('dashboard', {}).get('display_name') or account.get('email') or 'no name'})")

    try:
        call(sk, "GET", "/accounts", {"limit": 1})
        print("  ✓ Connect is enabled")
    except RuntimeError as e:
        print(f"  ✗ Connect: {e}\n    Enable it: Dashboard (test mode) → Connect → Get started (any platform type).")

    probe_connect(sk)

    keys = [f"{code}_{cadence}" for code in PLAN_CODES for cadence in ("monthly", "quarterly", "annual")]
    found = call(sk, "GET", "/prices", {"lookup_keys[]": keys, "active": "true", "limit": 100})["data"]
    print(f"  · Plans: {len(found)} existing plan Price(s) under the catalogue's lookup keys; the seller service adopts "
          "matching ones and creates the rest on startup (staff console > Plans & billing > Sync with Stripe).")
    print("Next: run the webhook listeners (docs/engineering/secrets-and-configuration.md → Stripe) and restart "
          "payments and seller: tools/dev/local.sh restart payments seller")


if __name__ == "__main__":
    main()
