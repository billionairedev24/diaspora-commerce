#!/usr/bin/env python3
"""Prepares a Stripe TEST account for the local stack (make stripe-setup).

- Checks the keys in .local/secrets.env (or the environment): test-mode secret key, matching publishable key.
- Checks Connect is enabled (seller onboarding creates connected accounts).
- Creates the plan Prices the seller service looks up by lookup key (<plan>_monthly | _quarterly | _annual), with
  amounts from PlanPricing: monthly = list price, quarterly = 3 months less 5 %, annual = 10 months. Free plans have
  no Price. Existing lookup keys are left alone, so it can run any number of times.

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

# Mirrors java/services/seller/src/main/resources/db/migration/V2__seed_plans_and_reason_codes.sql.
PLANS = [
    ("seller_essentials", "Seller Essentials", 0),
    ("seller_growth", "Seller Growth", 3900),
    ("seller_pro", "Seller Pro", 12900),
    ("provider_solo", "Provider Solo", 1900),
    ("provider_studio", "Provider Studio", 4900),
    ("provider_business", "Provider Business", 9900),
]
# cadence -> (months per period, total for the period from the monthly list price)
CADENCES = {
    "monthly": (1, lambda m: m),
    "quarterly": (3, lambda m: int((m * 3 * 95 + 50) // 100)),
    "annual": (12, lambda m: m * 10),
}


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

    created = 0
    for code, name, monthly in PLANS:
        if monthly == 0:
            continue
        product = None
        for cadence, (months, total) in CADENCES.items():
            lookup = f"{code}_{cadence}"
            existing = call(sk, "GET", "/prices", {"lookup_keys[]": lookup, "active": "true", "limit": 1})["data"]
            if existing:
                print(f"  · {lookup} exists ({existing[0]['unit_amount'] / 100:.2f} USD)")
                continue
            if product is None:
                product = call(sk, "POST", "/products", {"name": f"AfroTransact {name}", "metadata[plan_code]": code},
                               idempotency_key=f"at-plan-product-{code}")
            amount = total(monthly)
            call(sk, "POST", "/prices", {
                "product": product["id"], "currency": "usd", "unit_amount": amount, "lookup_key": lookup,
                "recurring[interval]": "month", "recurring[interval_count]": months,
                "metadata[plan_code]": code, "metadata[cadence]": cadence.upper(),
            }, idempotency_key=f"at-plan-price-{lookup}-{amount}")
            created += 1
            print(f"  ✓ {lookup} created ({amount / 100:.2f} USD every {months} month(s))")
    print(f"Plans: {created} price(s) created.")
    print("Next: run the webhook listeners (docs/engineering/secrets-and-configuration.md → Stripe) and restart "
          "payments and seller: tools/dev/local.sh restart payments seller")


if __name__ == "__main__":
    main()
