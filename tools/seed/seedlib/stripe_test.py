"""Confirms a checkout's PaymentIntent in Stripe TEST mode with Stripe's own test card, for demo orders.

When `.local/secrets.env` holds a Stripe test key, payments talks to api.stripe.com and an order waits for the card to be
confirmed (the storefront does it with Stripe.js; the webhook only arrives with `stripe listen`). The seeder plays the
customer's part: it confirms the intent with `pm_card_visa` through Stripe's API, then asks orders to continue
(`POST /v1/checkout/{n}/confirm`, which reads the intent's status itself, no webhook needed). Only a `sk_test_` / `rk_test_` key
is ever used: a live key is refused. The key is read from STRIPE_SECRET_KEY, sent only to api.stripe.com, never printed.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import Any

STRIPE_API = "https://api.stripe.com/v1"
TEST_CARD = "pm_card_visa"
RETURN_URL = "http://localhost:3000/checkout/return"

Sender = Callable[[str, dict[str, str], dict[str, str]], dict[str, Any]]


class StripeTestError(Exception):
    pass


def _send(url: str, form: dict[str, str], headers: dict[str, str]) -> dict[str, Any]:
    req = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return json.loads(res.read().decode())
    except urllib.error.HTTPError as e:
        try:
            msg = (json.loads(e.read().decode()).get("error") or {}).get("message", "")
        except ValueError:
            msg = ""
        raise StripeTestError(f"Stripe answered {e.code}: {msg or 'no detail'}") from None
    except (urllib.error.URLError, OSError) as e:
        raise StripeTestError(f"api.stripe.com is not reachable ({e})") from None


def test_key(env: dict[str, str]) -> str | None:
    """The Stripe TEST secret key of the environment, or None (no key, a placeholder, or not a test key)."""
    key = (env.get("STRIPE_SECRET_KEY") or "").strip()
    if key.startswith(("sk_test_", "rk_test_")) and "replace" not in key:
        return key
    return None


def intent_id(client_secret: str | None) -> str:
    """pi_… from a client secret (pi_…_secret_…); '' when there is none."""
    return (client_secret or "").split("_secret_")[0]


def is_local_stand_in(pi: str) -> bool:
    return pi.startswith("pi_localtest_")


def confirm(key: str, pi: str, send: Sender = _send) -> str:
    """Confirms the intent with the test card; returns its status (requires_capture for the manual-capture intents orders
    creates, succeeded otherwise). Raises StripeTestError."""
    out = send(f"{STRIPE_API}/payment_intents/{pi}/confirm",
               {"payment_method": TEST_CARD, "return_url": RETURN_URL},
               {"Authorization": "Bearer " + key, "Content-Type": "application/x-www-form-urlencoded"})
    status = str(out.get("status") or "")
    if status not in ("requires_capture", "succeeded", "processing"):
        raise StripeTestError(f"the test card left {pi} in status {status or 'unknown'}")
    return status
