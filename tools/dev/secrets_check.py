#!/usr/bin/env python3
"""Which secrets and provider settings each AfroTransact app needs, and which of them you have set locally.

Reads .local/secrets.env (gitignored; template: infra/local/secrets.env.example) on top of the environment, the same
way tools/dev/local.sh does (the file wins), and prints one table per app or service: variable, set or missing,
required or optional, a masked value and the feature it powers. Values are never printed: secrets show nothing,
public identifiers (publishable keys, SIDs, client ids) their first 4 characters.

    python3 tools/dev/secrets_check.py                 # every table (make secrets-check)
    python3 tools/dev/secrets_check.py --app payments  # one app
    python3 tools/dev/secrets_check.py --summary       # one line per provider group (make up prints it)
    python3 tools/dev/secrets_check.py --file other.env
    python3 tools/dev/secrets_check.py --inventory     # every env var each app reads, scanned from the code
    python3 tools/dev/secrets_check.py --check         # CI guard: every secret-like var in code is documented
    python3 tools/dev/secrets_check.py --write-docs    # refresh the generated tables in the reference page

Exit status: 0, or 1 when a variable that is required locally is missing (--summary always exits 0; --check exits 1
on undocumented secrets). Formats are checked where cheap (sk_test_/pk_test_/whsec_/AC…/re_…/E.164) and live keys are
flagged: warnings never change the exit status.

The catalogue below is the single source for the checker, the summary and the generated tables in
docs/engineering/secrets-and-configuration.md. Python 3.9+, standard library only.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FILE = ROOT / ".local" / "secrets.env"
DOC = ROOT / "docs" / "engineering" / "secrets-and-configuration.md"

SECRET = "secret"      # never shown
PUBLIC = "public"      # identifier: first 4 characters shown
CONFIG = "config"      # plain setting: shown in full


@dataclass(frozen=True)
class Var:
    name: str
    provider: str
    apps: Tuple[str, ...]
    kind: str                      # SECRET | PUBLIC | CONFIG
    feature: str                   # what it powers
    without: str                   # what happens locally when it is missing
    required: bool = False         # required for the local stack's core flows (checkout)
    pattern: Optional[str] = None  # expected format (regex), a warning when it does not match
    hint: str = ""                 # what the format should look like
    live: Optional[str] = None     # regex of live/production values: a loud warning locally
    deployed_only: bool = False    # not read by the local stack (documented for dev/uat/prod)


E164 = r"^\+[1-9]\d{6,14}$"

# ------------------------------------------------------------------------------------------------ the catalogue
# Every external-provider variable the code reads (classes a in docs/engineering/secrets-and-configuration.md), plus
# the local switches that decide whether a real provider or a local fallback is used.
CATALOGUE: List[Var] = [
    # Stripe ---------------------------------------------------------------------------------------------------------
    Var("STRIPE_SECRET_KEY", "Stripe", ("payments", "seller"), SECRET,
        "payments: PaymentIntents, refunds, saved cards, Connect transfers; seller: Connect onboarding, plan billing",
        "checkout and saved cards fail (payments falls back to a placeholder key; Stripe answers 401)",
        required=True, pattern=r"^(sk|rk)_test_[A-Za-z0-9]+$", hint="sk_test_… (Stripe test mode)",
        live=r"^(sk|rk)_live_"),
    Var("STRIPE_PUBLISHABLE_KEY", "Stripe", ("gateway-bff",), PUBLIC,
        "Stripe.js Payment Element / Express Checkout in the storefront, PaymentSheet in the app (served by the BFF)",
        "the storefront shows its labelled dev-only test card form instead of Stripe Elements",
        required=True, pattern=r"^pk_test_[A-Za-z0-9]+$", hint="pk_test_… from the same Stripe account as the secret key",
        live=r"^pk_live_"),
    Var("STRIPE_WEBHOOK_SECRET", "Stripe", ("payments",), SECRET,
        "verifies Stripe's webhooks (payment succeeded, refunds, disputes) at POST :8105/v1/webhooks/stripe",
        "webhooks are rejected: orders never see the payment succeed", required=True,
        pattern=r"^whsec_[A-Za-z0-9]+$", hint="whsec_… printed by `stripe listen`"),
    Var("STRIPE_CONNECT_WEBHOOK_SECRET", "Stripe", ("seller",), SECRET,
        "Connect account updates (verification, payouts enabled) at POST :8106/v1/webhooks/stripe-connect",
        "Connect events are rejected: Account health does not update", pattern=r"^whsec_", hint="whsec_…"),
    Var("STRIPE_BILLING_WEBHOOK_SECRET", "Stripe", ("seller",), SECRET,
        "plan subscription events (invoices, renewals) at POST :8106/v1/webhooks/stripe-billing",
        "billing events are rejected: plan status does not update", pattern=r"^whsec_", hint="whsec_…"),
    Var("EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY", "Stripe", ("mobile-customer",), PUBLIC,
        "PaymentSheet in the customer app (inlined by Metro at bundle time)",
        "local.sh copies STRIPE_PUBLISHABLE_KEY; without either the app uses pk_test_mock (mock sheet)",
        pattern=r"^pk_test_", hint="pk_test_…", live=r"^pk_live_"),
    Var("EXPO_PUBLIC_STRIPE_MERCHANT_ID", "Stripe", ("mobile-customer",), CONFIG,
        "Apple Pay merchant identifier for PaymentSheet", "default merchant.com.afrotransact (Apple Pay on device only)"),
    # Twilio ---------------------------------------------------------------------------------------------------------
    Var("TWILIO_ACCOUNT_SID", "Twilio", ("notifications",), PUBLIC,
        "SMS: sign-in and enrolment codes, order and delivery texts",
        "texts are only logged (identity's one-time codes go to Mailpit)",
        pattern=r"^AC[0-9a-fA-F]{32}$", hint="AC + 32 hex characters"),
    Var("TWILIO_AUTH_TOKEN", "Twilio", ("notifications",), SECRET,
        "authenticates the Twilio API; also verifies Twilio's status callbacks", "texts are only logged",
        pattern=r"^[0-9a-fA-F]{32}$", hint="32 hex characters"),
    Var("TWILIO_FROM", "Twilio", ("notifications",), CONFIG,
        "the sending number (when no Messaging Service is set)", "Twilio stays off unless TWILIO_MESSAGING_SERVICE_SID is set",
        pattern=E164, hint="E.164, e.g. +15005550006 (test credentials) or your Twilio number"),
    Var("TWILIO_MESSAGING_SERVICE_SID", "Twilio", ("notifications",), PUBLIC,
        "sends through a Messaging Service (10DLC campaign; preferred over TWILIO_FROM)", "TWILIO_FROM is used",
        pattern=r"^MG[0-9a-fA-F]{32}$", hint="MG + 32 hex characters"),
    # Resend ---------------------------------------------------------------------------------------------------------
    Var("RESEND_API_KEY", "Resend", ("notifications",), SECRET,
        "transactional email (order, account, seller emails) to real inboxes",
        "every email goes to Mailpit http://localhost:8025", pattern=r"^re_[A-Za-z0-9_]+$", hint="re_…"),
    Var("EMAIL_FROM", "Resend", ("notifications",), CONFIG,
        "sender address; must be on a domain verified in Resend (or onboarding@resend.dev)",
        "AfroTransact <no-reply@afrotransact.local>: fine for Mailpit, refused by Resend"),
    Var("EMAIL_REPLY_TO", "Resend", ("notifications",), CONFIG, "Reply-To header", "support@afrotransact.local"),
    Var("RESEND_WEBHOOK_SECRET", "Resend", ("notifications",), SECRET,
        "verifies Resend delivery/bounce webhooks (POST /v1/webhooks/resend); deployed environments",
        "no delivery receipts from Resend (local does not verify webhooks)", pattern=r"^whsec_", hint="whsec_…"),
    # Alternative email / SMS providers -----------------------------------------------------------------------------
    Var("SENDGRID_API_KEY", "SendGrid", ("notifications",), SECRET,
        "email failover after Resend (dev/uat/prod); locally used when Resend is not set",
        "not used", pattern=r"^SG\.", hint="SG.…"),
    Var("SENDGRID_WEBHOOK_PUBLIC_KEY", "SendGrid", ("notifications",), SECRET,
        "verifies SendGrid event webhooks (deployed)", "no SendGrid receipts"),
    Var("VONAGE_API_KEY", "Vonage", ("notifications",), PUBLIC, "SMS failover after Twilio (deployed)", "not used"),
    Var("VONAGE_API_SECRET", "Vonage", ("notifications",), SECRET, "SMS failover after Twilio (deployed)", "not used"),
    Var("VONAGE_FROM", "Vonage", ("notifications",), CONFIG, "Vonage sender id", "AfroTransact"),
    # Push and WhatsApp ----------------------------------------------------------------------------------------------
    Var("FCM_SERVICE_ACCOUNT_JSON", "Firebase (FCM)", ("notifications",), SECRET,
        "Android (and iOS via FCM) push notifications", "push messages are only logged"),
    Var("FCM_PROJECT_ID", "Firebase (FCM)", ("notifications",), CONFIG, "Firebase project id (default: from the JSON)", "-"),
    Var("APNS_KEY_P8", "Apple Push (APNs)", ("notifications",), SECRET,
        "iOS push notifications (token-based .p8 key)", "push messages are only logged"),
    Var("APNS_KEY_ID", "Apple Push (APNs)", ("notifications",), PUBLIC, "the .p8 key's Key ID", "-",
        pattern=r"^[A-Z0-9]{10}$", hint="10 characters"),
    Var("APNS_TEAM_ID", "Apple Push (APNs)", ("notifications",), PUBLIC, "Apple Developer Team ID", "-",
        pattern=r"^[A-Z0-9]{10}$", hint="10 characters"),
    Var("WHATSAPP_TOKEN", "WhatsApp Cloud API", ("notifications",), SECRET,
        "WhatsApp messages (chat channel)", "WhatsApp messages are only logged"),
    Var("WHATSAPP_PHONE_NUMBER_ID", "WhatsApp Cloud API", ("notifications",), PUBLIC,
        "the WhatsApp Business phone number id", "-"),
    # Local switches -------------------------------------------------------------------------------------------------
    Var("PUBLIC_BASE_URL", "Local switches", ("notifications",), CONFIG,
        "notifications' public URL: a tunnel (cloudflared/ngrok) lets Twilio post delivery status callbacks",
        "http://localhost:8208: no Twilio status callbacks"),
    Var("BOOTSTRAP_ADMIN_PHONE", "Local switches", ("identity",), CONFIG,
        "the bootstrap admin's phone (staff sign-in codes) when the admin is first created", "none",
        pattern=E164, hint="E.164"),
    Var("VERIFICATION_SENDER", "Local switches", ("identity",), CONFIG,
        "where identity sends one-time codes: mailpit, log or outbox (= notifications: Twilio/Resend)",
        "local.sh picks outbox when Twilio is set, else mailpit"),
    # OpenRouter -----------------------------------------------------------------------------------------------------
    Var("OPENROUTER_API_KEY", "OpenRouter", ("ai-gateway",), SECRET,
        "Lola shopping assistant, semantic search embeddings, AI image moderation (all via ai-gateway)",
        "AI calls fail: the assistant is unavailable, search stays keyword-only",
        pattern=r"^sk-or-", hint="sk-or-v1-…"),
    # Social sign-in -------------------------------------------------------------------------------------------------
    Var("GOOGLE_CLIENT_ID", "Google sign-in", ("identity",), PUBLIC, "Continue with Google",
        "no Google button", pattern=r"\.apps\.googleusercontent\.com$", hint="….apps.googleusercontent.com"),
    Var("GOOGLE_CLIENT_SECRET", "Google sign-in", ("identity",), SECRET, "Continue with Google", "no Google button",
        pattern=r"^GOCSPX-", hint="GOCSPX-…"),
    Var("APPLE_CLIENT_ID", "Apple sign-in", ("identity",), PUBLIC, "Sign in with Apple (Services ID)",
        "no Apple button"),
    Var("APPLE_CLIENT_SECRET", "Apple sign-in", ("identity",), SECRET,
        "Sign in with Apple: a JWT you sign with your .p8 key (expires after at most 6 months)", "no Apple button",
        pattern=r"^eyJ", hint="a JWT (eyJ…)"),
    Var("FACEBOOK_CLIENT_ID", "Facebook sign-in", ("identity",), PUBLIC, "Continue with Facebook",
        "no Facebook button", pattern=r"^\d+$", hint="numeric App ID"),
    Var("FACEBOOK_CLIENT_SECRET", "Facebook sign-in", ("identity",), SECRET, "Continue with Facebook",
        "no Facebook button", pattern=r"^[0-9a-f]{32}$", hint="32 hex characters"),
    # Maps / geocoding -----------------------------------------------------------------------------------------------
    Var("GOOGLE_MAPS_API_KEY", "Google Maps Platform", ("geo",), SECRET,
        "address autocomplete + Place Details (Google Places (New), the only autocomplete provider when set), reverse "
        "geocoding (google-geocoding) and drive-time routing (google); enable Places API (New), Geocoding API, Routes API",
        "OpenStreetMap Nominatim + sample addresses; straight-line distance × detour factor",
        pattern=r"^AIza", hint="AIza…"),
    Var("GOOGLE_PLACES_API_KEY", "Google Maps Platform", ("geo",), SECRET,
        "a separate key for Google Places (New) only; wins over GOOGLE_MAPS_API_KEY for autocomplete",
        "GOOGLE_MAPS_API_KEY, else OpenStreetMap Nominatim + sample addresses", pattern=r"^AIza", hint="AIza…"),
    Var("GEO_PROVIDERS", "Google Maps Platform", ("geo",), CONFIG,
        "autocomplete providers without a Google key (locally fixture,osm-nominatim); ignored when a key is set",
        "fixture,osm-nominatim"),
    Var("SMARTY_AUTH_ID", "Smarty", ("geo",), PUBLIC, "US address autocomplete (provider smarty)", "not used"),
    Var("SMARTY_AUTH_TOKEN", "Smarty", ("geo",), SECRET, "US address autocomplete (provider smarty)", "not used"),
    # Carriers -------------------------------------------------------------------------------------------------------
    Var("UPS_CLIENT_ID", "UPS", ("carrier-gateway",), PUBLIC, "UPS rates, labels, tracking (sandbox locally)",
        "UPS is skipped at start-up"),
    Var("UPS_CLIENT_SECRET", "UPS", ("carrier-gateway",), SECRET, "UPS OAuth", "UPS is skipped"),
    Var("UPS_ACCOUNT", "UPS", ("carrier-gateway",), PUBLIC, "UPS shipper number", "UPS is skipped"),
    Var("UPS_WEBHOOK_CREDENTIAL", "UPS", ("carrier-gateway",), SECRET, "verifies UPS tracking webhooks", "polling only"),
    Var("USPS_CLIENT_ID", "USPS", ("carrier-gateway",), PUBLIC, "USPS rates, labels, tracking (TEM sandbox locally)",
        "USPS is skipped"),
    Var("USPS_CLIENT_SECRET", "USPS", ("carrier-gateway",), SECRET, "USPS OAuth", "USPS is skipped"),
    Var("USPS_PAYMENT_TOKEN", "USPS", ("carrier-gateway",), SECRET, "USPS label payment authorization",
        "USPS labels cannot be bought"),
    Var("FEDEX_CLIENT_ID", "FedEx", ("carrier-gateway",), PUBLIC, "FedEx rates, labels, tracking (sandbox locally)",
        "FedEx is skipped"),
    Var("FEDEX_CLIENT_SECRET", "FedEx", ("carrier-gateway",), SECRET, "FedEx OAuth", "FedEx is skipped"),
    Var("FEDEX_ACCOUNT", "FedEx", ("carrier-gateway",), PUBLIC, "FedEx account number", "FedEx is skipped"),
    Var("FEDEX_WEBHOOK_SECRET", "FedEx", ("carrier-gateway",), SECRET, "verifies FedEx tracking webhooks", "polling only"),
    Var("DHL_USERNAME", "DHL Express", ("carrier-gateway",), PUBLIC, "MyDHL API (test endpoint locally)", "DHL is skipped"),
    Var("DHL_PASSWORD", "DHL Express", ("carrier-gateway",), SECRET, "MyDHL API", "DHL is skipped"),
    Var("DHL_ACCOUNT", "DHL Express", ("carrier-gateway",), PUBLIC, "DHL account number", "DHL is skipped"),
    Var("DHL_WEBHOOK_SECRET", "DHL Express", ("carrier-gateway",), SECRET, "verifies DHL push notifications", "polling only"),
    # Product data / images ------------------------------------------------------------------------------------------
    Var("GS1_API_KEY", "GS1", ("catalog",), SECRET,
        "GTIN product data (Verified by GS1) when GS1_ENABLED=true", "Open Food Facts only (no key)"),
    Var("GS1_ENABLED", "GS1", ("catalog",), CONFIG, "turns the GS1 lookup on", "false"),
    Var("PEXELS_API_KEY", "Pexels", ("tools/seed",), SECRET,
        "demo product photos for make seed; the mobile apps' welcome photo / clip (make mobile-hero)",
        "Unsplash, then Wikimedia Commons (no key)"),
    Var("UNSPLASH_ACCESS_KEY", "Unsplash", ("tools/seed",), SECRET, "demo product photos for make seed",
        "Wikimedia Commons (no key)"),
    Var("UPLOADTHING_TOKEN", "UploadThing", ("media",), SECRET,
        "product and storefront images in dev/uat/prod (UploadThing v7 token)", "local stores every file in MinIO",
        deployed_only=True),
    Var("UPLOADTHING_API_KEY", "UploadThing", ("media",), SECRET,
        "UploadThing secret key (sk_live_…; with UPLOADTHING_APP_ID) when no v7 token is set",
        "local stores every file in MinIO", deployed_only=True),
    Var("UPLOADTHING_APP_ID", "UploadThing", ("media",), PUBLIC, "UploadThing app id", "-", deployed_only=True),
    # SMTP relay (any provider that speaks SMTP) -----------------------------------------------------------------------
    Var("SMTP_HOST", "SMTP relay", ("notifications",), CONFIG,
        "the smtp email provider's host (locally Mailpit on localhost:1025)", "localhost (Mailpit)"),
    Var("SMTP_USERNAME", "SMTP relay", ("notifications",), CONFIG, "SMTP login", "none (Mailpit)"),
    Var("SMTP_PASSWORD", "SMTP relay", ("notifications",), SECRET, "SMTP password", "none (Mailpit)"),
    # Error monitoring -----------------------------------------------------------------------------------------------
    Var("NEXT_PUBLIC_SENTRY_DSN", "Sentry", ("web-storefront", "web-seller-center", "web-staff-console"), PUBLIC,
        "browser error reporting", "errors stay in the browser console", pattern=r"^https://[^@]+@[^/]+/\d+$",
        hint="https://<key>@<org>.ingest.sentry.io/<project>"),
    Var("SENTRY_DSN", "Sentry", ("web-storefront", "web-seller-center", "web-staff-console"), PUBLIC,
        "server-side (Next.js) error reporting", "errors stay in the dev server log",
        pattern=r"^https://[^@]+@[^/]+/\d+$", hint="https://<key>@<org>.ingest.sentry.io/<project>"),
    Var("EXPO_PUBLIC_SENTRY_DSN", "Sentry", ("mobile-customer", "mobile-driver"), PUBLIC,
        "app crash and error reporting", "errors stay in Metro's log", pattern=r"^https://[^@]+@[^/]+/\d+$",
        hint="https://<key>@<org>.ingest.sentry.io/<project>"),
    Var("SENTRY_AUTH_TOKEN", "Sentry", ("web-storefront", "web-seller-center", "web-staff-console", "mobile-customer",
                                        "mobile-driver"), SECRET,
        "uploads source maps at build time (CI / EAS)", "stack traces are minified", pattern=r"^sntry",
        hint="sntrys_… (organization token)"),
]

BY_NAME: Dict[str, Var] = {v.name: v for v in CATALOGUE}

# Provider groups for the one-line summary: (label, vars that must all be set, effect when set, effect when missing).
SUMMARY: List[Tuple[str, Tuple[str, ...], str, str]] = [
    ("Stripe", ("STRIPE_SECRET_KEY", "STRIPE_PUBLISHABLE_KEY", "STRIPE_WEBHOOK_SECRET"),
     "test keys set", "missing → checkout cannot complete"),
    ("Twilio", ("TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN"),
     "set → real SMS (test users' 555-01xx numbers still go to Mailpit)", "missing → texts logged, codes in Mailpit"),
    ("Resend", ("RESEND_API_KEY",), "set → real email (test users still go to Mailpit)", "missing → email to Mailpit"),
    ("OpenRouter", ("OPENROUTER_API_KEY",), "set → AI on (start ai-gateway)", "missing → AI assistant off"),
    ("Google sign-in", ("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"), "set", "missing → no Google button"),
    ("Google Maps", ("GOOGLE_MAPS_API_KEY",), "set → Google Places address suggestions",
     "missing → OpenStreetMap + sample addresses (GOOGLE_PLACES_API_KEY alone also works)"),
]

# ------------------------------------------------------------------------------------------------ reading the values


def parse_env_file(path: Path) -> Dict[str, str]:
    """KEY=value lines as a POSIX shell would read them for simple files: `export` prefix, # comments, '…' and "…"
    quotes (also across lines, for PEM keys and JSON), no variable expansion."""
    out: Dict[str, str] = {}
    if not path.is_file():
        return out
    text = path.read_text(encoding="utf-8")
    i, n = 0, len(text)
    line_re = re.compile(r"[ \t]*(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)=")
    while i < n:
        m = line_re.match(text, i)
        if not m:
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        key, i = m.group(1), m.end()
        value = []
        while i < n and text[i] not in "\n":
            c = text[i]
            if c in "'\"":
                end = text.find(c, i + 1)
                end = n if end < 0 else end
                value.append(text[i + 1:end])
                i = end + 1
            elif c == "#" and (not value or value[-1].endswith((" ", "\t"))):
                j = text.find("\n", i)
                i = n if j < 0 else j
            elif c == "\\" and i + 1 < n:
                value.append(text[i + 1])
                i += 2
            else:
                j = i
                while j < n and text[j] not in "'\"\n#\\":
                    j += 1
                value.append(text[i:j])
                i = j
        out[key] = "".join(value).strip()
        i += 1
    return out


@dataclass
class Values:
    file: Dict[str, str]
    env: Dict[str, str]

    def get(self, name: str) -> Tuple[str, str]:
        """(value, source): the file wins, as when local.sh sources it."""
        if self.file.get(name):
            return self.file[name], "file"
        if self.env.get(name):
            return self.env[name], "env"
        return "", ""


def mask(var: Var, value: str) -> str:
    if not value:
        return ""
    if var.kind == SECRET:
        return "(hidden)"
    if var.kind == PUBLIC:
        return value[:4] + "…"
    return value if len(value) <= 40 else value[:37] + "…"


@dataclass
class Finding:
    level: str  # "warn" | "LIVE"
    text: str


def validate(var: Var, value: str, values: Values) -> List[Finding]:
    found: List[Finding] = []
    if not value:
        return found
    if var.live and re.search(var.live, value):
        found.append(Finding("LIVE", f"{var.name} is a LIVE key: use test mode locally (real money moves)"))
        return found
    if var.pattern and not re.search(var.pattern, value):
        found.append(Finding("warn", f"{var.name} does not look right: expected {var.hint or var.pattern}"))
    if var.name == "STRIPE_PUBLISHABLE_KEY" and value.startswith("pk_test_mock"):
        found.append(Finding("warn", "STRIPE_PUBLISHABLE_KEY is the mock BFF's key: use your own pk_test_…"))
    return found


def cross_checks(values: Values) -> List[Finding]:
    found: List[Finding] = []
    v = lambda n: values.get(n)[0]  # noqa: E731
    sk, pk = v("STRIPE_SECRET_KEY"), v("STRIPE_PUBLISHABLE_KEY")
    if bool(sk) != bool(pk):
        found.append(Finding("warn", "set STRIPE_SECRET_KEY and STRIPE_PUBLISHABLE_KEY together (same Stripe account)"))
    if sk and not v("STRIPE_WEBHOOK_SECRET"):
        found.append(Finding("warn", "STRIPE_WEBHOOK_SECRET missing: run `stripe listen --forward-to "
                                     "localhost:8105/v1/webhooks/stripe` and copy the whsec_… it prints"))
    if v("TWILIO_ACCOUNT_SID") and v("TWILIO_AUTH_TOKEN") and not (v("TWILIO_FROM") or v("TWILIO_MESSAGING_SERVICE_SID")):
        found.append(Finding("warn", "Twilio needs a sender: TWILIO_FROM (E.164) or TWILIO_MESSAGING_SERVICE_SID; "
                                     "until then texts are only logged"))
    if bool(v("TWILIO_ACCOUNT_SID")) != bool(v("TWILIO_AUTH_TOKEN")):
        found.append(Finding("warn", "set TWILIO_ACCOUNT_SID and TWILIO_AUTH_TOKEN together"))
    if v("RESEND_API_KEY"):
        sender = v("EMAIL_FROM")
        if not sender or re.search(r"\.(local|test|example|invalid)>?\s*$", sender):
            found.append(Finding("warn", "RESEND_API_KEY is set but EMAIL_FROM is not an address Resend accepts: set "
                                         "EMAIL_FROM to an address on your verified domain, or "
                                         "'AfroTransact <onboarding@resend.dev>' (delivers to your own Resend "
                                         "account email only)"))
    if bool(v("GOOGLE_CLIENT_ID")) != bool(v("GOOGLE_CLIENT_SECRET")):
        found.append(Finding("warn", "set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET together"))
    if v("GOOGLE_PLACES_API_KEY") and v("GOOGLE_MAPS_API_KEY") and v("GOOGLE_PLACES_API_KEY") != v("GOOGLE_MAPS_API_KEY"):
        found.append(Finding("warn", "GOOGLE_PLACES_API_KEY and GOOGLE_MAPS_API_KEY differ: address autocomplete uses "
                                     "GOOGLE_PLACES_API_KEY, routing GOOGLE_MAPS_API_KEY (enable Places API (New) on the first)"))
    if v("GS1_API_KEY") and v("GS1_ENABLED").lower() != "true":
        found.append(Finding("warn", "GS1_API_KEY is set but GS1_ENABLED is not true: the lookup stays off"))
    return found


# ------------------------------------------------------------------------------------------------ output


def apps_in_order() -> List[str]:
    seen: List[str] = []
    for var in CATALOGUE:
        for a in var.apps:
            if a not in seen:
                seen.append(a)
    return seen


def table(rows: List[List[str]], headers: List[str]) -> str:
    widths = [max(len(h), *(len(r[i]) for r in rows)) for i, h in enumerate(headers)]
    widths[-1] = len(headers[-1])  # last column is free text
    fmt = "  ".join(f"{{:<{w}}}" for w in widths[:-1]) + "  {}"
    lines = [fmt.format(*headers), fmt.format(*("-" * w for w in widths))]
    lines += [fmt.format(*r) for r in rows]
    return "\n".join("  " + ln for ln in lines)


def report(values: Values, only_app: Optional[str]) -> Tuple[str, int]:
    out: List[str] = []
    missing_required: List[str] = []
    findings: List[Finding] = []
    seen_findings = set()
    for app in apps_in_order():
        if only_app and app != only_app:
            continue
        rows = []
        for var in CATALOGUE:
            if app not in var.apps:
                continue
            value, source = values.get(var.name)
            need = "REQUIRED" if var.required else ("deployed" if var.deployed_only else "optional")
            status = f"set ({source})" if value else ("MISSING" if var.required else "-")
            effect = var.feature if value else f"{var.feature}. Without: {var.without}"
            rows.append([var.name, status, need, mask(var, value), effect])
            if var.required and not value and var.name not in missing_required:
                missing_required.append(var.name)
            for f in validate(var, value, values):
                if f.text not in seen_findings:
                    seen_findings.add(f.text)
                    findings.append(f)
        out.append(f"\n{app}\n" + table(rows, ["VARIABLE", "STATUS", "NEED", "VALUE", "FEATURE"]))
    for f in cross_checks(values):
        if f.text not in seen_findings:
            seen_findings.add(f.text)
            findings.append(f)
    if findings:
        out.append("\nChecks")
        for f in findings:
            out.append(f"  {'!!! LIVE KEY' if f.level == 'LIVE' else '!'} {f.text}")
    out.append("")
    if missing_required:
        out.append("Missing for a full local test: " + ", ".join(missing_required) +
                   "  (where to get them: docs/engineering/secrets-and-configuration.md)")
    else:
        out.append("Everything required locally is set.")
    out.append("Internal secrets (DB passwords, signing keys, service client secrets) have committed local "
               "defaults: nothing to fill in for the local stack.")
    return "\n".join(out), (1 if missing_required else 0)


def summary(values: Values) -> str:
    parts = []
    for label, names, ok, missing in SUMMARY:
        have = [n for n in names if values.get(n)[0]]
        if len(have) == len(names):
            text = ok
            if any(f.level == "LIVE" for n in names for f in validate(BY_NAME[n], values.get(n)[0], values)):
                text = "LIVE KEYS — use test mode locally"
        elif have:
            text = "partly set (" + ", ".join(sorted(set(names) - set(have))) + " missing)"
        else:
            text = missing
        parts.append(f"{label}: {text}")
    return "secrets: " + " · ".join(parts)


# ------------------------------------------------------------------------------------------------ inventory (code scan)

SKIP_DIRS = {"node_modules", ".next", ".next-e2e", ".open-next", "target", "dist", "build", ".expo", ".turbo",
             "test", "tests", "e2e", "__tests__", "testdata", "generated", ".git", "test-results", "storybook-static"}
SECRETISH = re.compile(r"(SECRET|PASSWORD|TOKEN|_KEY$|_KEYS?_|API_KEY|PRIVATE|_PEM$|_P8$|_DSN$|_SID$|CREDENTIAL|"
                       r"SERVICE_ACCOUNT_JSON|ACCESS_KEY)")
# Inventory noise: framework/runtime variables the apps read but nobody sets for AfroTransact.
IGNORED = {"NODE_ENV", "NEXT_DIST_DIR", "CI", "E2E_PORT", "E2E_SKIP_BUILD", "SPECS", "VITEST_MODE", "HOSTNAME"}

# Internal secrets (class b): generated or provisioned by the platform, never by a third party. Purpose and how to
# generate a value for dev/uat/prod; locally every one has a committed, clearly-labelled default.
INTERNAL: Dict[str, str] = {
    "DB_PASSWORD": "the service's Postgres password (Terraform creates the role; one database per service)",
    "DB_URL": "the service's Postgres URL (Go: DSN incl. password; Java: JDBC URL, password in DB_PASSWORD)",
    "SIGNING_KEY_PEM": "identity's JWT signing key (RSA, PEM); local generates an ephemeral one",
    "IDENTITY_DATA_KEY": "identity's AES-256 key for TOTP secrets at rest (base64 of 32 random bytes)",
    "IDENTITY_DATA_KEYS_PREVIOUS": "retired data keys during rotation (id:base64,…)",
    "IDENTITY_TEST_USERS_PASSWORD": "dev only: the shared password of the seeded test users (local: Test1234!Local)",
    "BOOTSTRAP_ADMIN_PASSWORD": "first super admin's password (identity creates the admin once)",
    "SESSION_SIGNING_KEY": "gateway-bff session cookie HMAC key",
    "OIDC_CLIENT_SECRET": "gateway-bff's confidential OIDC client at identity (= identity's GATEWAY_BFF_CLIENT_SECRET)",
    "GATEWAY_BFF_CLIENT_SECRET": "identity's copy of the BFF client secret",
    "BFF_CLIENT_SECRET": "legacy alias of GATEWAY_BFF_CLIENT_SECRET",
    "HANDOVER_CODE_KEY": "orders: HMAC key for delivery hand-over codes",
    "NEWSLETTER_TOKEN_KEY": "content: HMAC key for newsletter confirm/unsubscribe links",
    "UNSUBSCRIBE_SIGNING_KEY": "notifications: HMAC key for one-click unsubscribe links",
    "FIELD_ENCRYPTION_KEY": "seller: AES-256 key for bank and tax fields (base64 of 32 bytes)",
    "LINK_SIGNING_KEY": "media: HMAC key for long-lived document links",
    "LINK_SIGNING_KEYS_PREVIOUS": "media: retired link keys during rotation",
    "ADS_TOKEN_KEY": "search: HMAC key for sponsored-result click tokens",
    "MEDIA_SERVICE_TOKEN": "seller → media service token (legacy; client credentials preferred)",
    "TEMPORAL_TLS_KEY": "orders: mTLS key for Temporal Cloud (with TEMPORAL_TLS_CERT)",
    "FGA_API_TOKEN": "OpenFGA pre-shared key (Java services)",
    "OPENFGA_API_TOKEN": "OpenFGA pre-shared key (authz-sync)",
    "ES_API_KEY": "Elasticsearch API key (search, indexer)",
    "ES_PASSWORD": "Elasticsearch password (when not using an API key)",
    "S3_ACCESS_KEY_ID": "carrier-gateway label store (S3-compatible) when not using workload identity",
    "S3_SECRET_ACCESS_KEY": "carrier-gateway label store secret",
    "MINIO_ACCESS_KEY": "local MinIO (minioadmin)",
    "MINIO_SECRET_KEY": "local MinIO (minioadmin)",
    "EDI_AS2_KEYSTORE_PATH": "retail: AS2 keystore file for supplier EDI",
    "EDI_SFTP_PRIVATE_KEY_PATH": "retail: SFTP private key file for supplier EDI",
    "CLOUDFLARE_API_TOKEN": "storefront service: Cloudflare cache purge (also a GitHub Actions secret)",
    "SEED_TOKEN": "tools/seed: a ready bearer token instead of client credentials (optional)",
    "SEED_TEST_USERS_PASSWORD": "tools/seed: the test users' password when it is not the local one (dev)",
}
CLIENT_SECRET_RE = re.compile(r"^[A-Z_]+_CLIENT_SECRET$")
CLIENT_SECRET_PURPOSE = ("OAuth2 client-credentials secret of a service at identity (identity holds the same value "
                         "as <SERVICE>_CLIENT_SECRET)")


def _walk(base: Path, suffixes: Tuple[str, ...]):
    if not base.exists():
        return
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in filenames:
            if fn.endswith(suffixes) and not fn.endswith(("_test.go", ".test.ts", ".test.tsx", ".spec.ts")):
                yield Path(dirpath) / fn


JAVA_RE = re.compile(r"\$\{([A-Z][A-Z0-9_]*)")
GO_RE = re.compile(r'env:"([A-Z][A-Z0-9_]*)"')
TS_RE = re.compile(r"process\.env\.([A-Z][A-Z0-9_]*)|(?<![A-Za-z0-9_])(EXPO_PUBLIC_[A-Z0-9_]+|NEXT_PUBLIC_[A-Z0-9_]+)")
TS_ENV_CALL_RE = re.compile(r"\benv\(\s*'([A-Z][A-Z0-9_]*)'\s*\)")
PY_RE = re.compile(r"env(?:iron)?(?:\.get)?\(?\[?[\"']([A-Z][A-Z0-9_]*)[\"']")
GH_RE = re.compile(r"secrets\.([A-Z][A-Z0-9_]*)")


def inventory() -> Dict[str, Dict[str, set]]:
    """{app: {VAR: {files}}} for every env var the code reads."""
    inv: Dict[str, Dict[str, set]] = {}

    def add(app: str, name: str, path: Path) -> None:
        if name in IGNORED:
            return
        inv.setdefault(app, {}).setdefault(name, set()).add(str(path.relative_to(ROOT)))

    for svc in sorted((ROOT / "java" / "services").glob("*/")):
        for p in _walk(svc / "src" / "main", (".yml", ".yaml", ".properties")):
            for m in JAVA_RE.finditer(p.read_text(encoding="utf-8", errors="ignore")):
                add(svc.name, m.group(1), p)
    for svc in sorted((ROOT / "go" / "services").glob("*/")):
        for p in _walk(svc / "internal" / "config", (".go",)):
            for m in GO_RE.finditer(p.read_text(encoding="utf-8", errors="ignore")):
                add(svc.name, m.group(1), p)
    for kind, base in (("web", ROOT / "web" / "apps"), ("mobile", ROOT / "mobile" / "apps")):
        for app in sorted(base.glob("*/")):
            for p in _walk(app, (".ts", ".tsx", ".mjs", ".js", ".json", ".jsonc")):
                if p.name in ("package.json", "tsconfig.json") or "node_modules" in p.parts:
                    continue
                text = p.read_text(encoding="utf-8", errors="ignore")
                for m in TS_RE.finditer(text):
                    add(f"{kind}-{app.name}", m.group(1) or m.group(2), p)
                for m in TS_ENV_CALL_RE.finditer(text):
                    add(f"{kind}-{app.name}", m.group(1), p)
    for p in _walk(ROOT / "web" / "packages" / "telemetry", (".ts",)):
        for m in TS_RE.finditer(p.read_text(encoding="utf-8", errors="ignore")):
            for app in ("web-storefront", "web-seller-center", "web-staff-console"):
                add(app, m.group(1) or m.group(2), p)
    for p in _walk(ROOT / "tools" / "seed", (".py",)):
        for m in PY_RE.finditer(p.read_text(encoding="utf-8", errors="ignore")):
            add("tools/seed", m.group(1), p)
    for p in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        for m in GH_RE.finditer(p.read_text(encoding="utf-8", errors="ignore")):
            if m.group(1) != "GITHUB_TOKEN":
                add("GitHub Actions", m.group(1), p)
    return inv


NOT_SECRET_SUFFIX = re.compile(r"_(URL|ID|TTL|TIMEOUT|ENABLED|PATH)$")


def classify(name: str) -> str:
    if name in BY_NAME:
        v = BY_NAME[name]
        return f"provider: {v.provider}" if v.provider != "Local switches" else "local switch"
    if name in INTERNAL or CLIENT_SECRET_RE.match(name):
        return "internal secret"
    if SECRETISH.search(name) and not NOT_SECRET_SUFFIX.search(name):
        return "secret?"
    return "config"


def print_inventory() -> None:
    inv = inventory()
    for app in sorted(inv):
        names = sorted(inv[app])
        print(f"\n{app} ({len(names)} variables)")
        for n in names:
            print(f"  {n:<40} {classify(n)}")


def check_documented() -> int:
    inv = inventory()
    problems = []
    for app, names in sorted(inv.items()):
        if app == "GitHub Actions":
            continue
        for n in sorted(names):
            if classify(n) == "secret?":
                problems.append(f"{app}: {n} looks like a secret but is neither in the CATALOGUE nor in INTERNAL "
                                f"(tools/dev/secrets_check.py); read in {', '.join(sorted(names[n]))}")
    for v in CATALOGUE:
        if v.provider == "Local switches" or v.name.startswith("EXPO_PUBLIC_") and v.name == "EXPO_PUBLIC_STRIPE_MERCHANT_ID":
            continue
        if not any(v.name in names for names in inv.values()) and v.name not in ("SENTRY_AUTH_TOKEN",):
            problems.append(f"catalogue: {v.name} is not read anywhere any more: remove or rename it")
    for p in problems:
        print(f"secrets-check: {p}")
    print(f"secrets-check: {'FAIL' if problems else 'OK'} - {sum(len(v) for v in inv.values())} variable uses in "
          f"{len(inv)} apps scanned")
    return 1 if problems else 0


# ------------------------------------------------------------------------------------------------ generated docs

BEGIN = "<!-- BEGIN GENERATED by tools/dev/secrets_check.py --write-docs: {name} -->"
END = "<!-- END GENERATED: {name} -->"


def md_escape(s: str) -> str:
    return s.replace("|", "\\|")


def generated_sections() -> Dict[str, str]:
    inv = inventory()
    used_by: Dict[str, List[str]] = {}
    for app, names in inv.items():
        for n in names:
            used_by.setdefault(n, []).append(app)

    rows = ["| Variable | Provider | Read by | Kind | Locally | What it powers / without it |", "|---|---|---|---|---|---|"]
    for v in CATALOGUE:
        need = "**required**" if v.required else ("deployed only" if v.deployed_only else "optional")
        kind = {"secret": "secret", "public": "public id", "config": "config"}[v.kind]
        rows.append(f"| `{v.name}` | {v.provider} | {', '.join(v.apps)} | {kind} | {need} | "
                    f"{md_escape(v.feature)}. Without: {md_escape(v.without)} |")
    external = "\n".join(rows)

    names = sorted(n for n in used_by if classify(n) == "internal secret")
    rows = ["| Variable | Read by | Purpose |", "|---|---|---|"]
    for n in names:
        purpose = INTERNAL.get(n) or (CLIENT_SECRET_PURPOSE if CLIENT_SECRET_RE.match(n) else "")
        rows.append(f"| `{n}` | {', '.join(sorted(set(used_by[n])))} | {md_escape(purpose)} |")
    internal = "\n".join(rows)

    rows = ["| App / service | Provider variables it reads | Internal secrets it reads |", "|---|---|---|"]
    for app in sorted(inv):
        if app == "GitHub Actions":
            continue
        prov = sorted(n for n in inv[app] if classify(n).startswith("provider"))
        intl = sorted(n for n in inv[app] if classify(n) == "internal secret")
        if prov or intl:
            rows.append(f"| {app} | {', '.join(f'`{n}`' for n in prov) or '-'} | "
                        f"{', '.join(f'`{n}`' for n in intl) or '-'} |")
    per_app = "\n".join(rows)
    return {"external": external, "internal": internal, "per-app": per_app}


def write_docs(check_only: bool) -> int:
    text = DOC.read_text(encoding="utf-8")
    new = text
    for name, body in generated_sections().items():
        b, e = BEGIN.format(name=name), END.format(name=name)
        pat = re.compile(re.escape(b) + r".*?" + re.escape(e), re.S)
        if not pat.search(new):
            print(f"secrets-check: {DOC.relative_to(ROOT)} has no '{name}' markers", file=sys.stderr)
            return 1
        new = pat.sub(lambda _m: f"{b}\n{body}\n{e}", new)
    if new == text:
        print(f"secrets-check: {DOC.relative_to(ROOT)} is up to date")
        return 0
    if check_only:
        print(f"secrets-check: {DOC.relative_to(ROOT)} is stale: run python3 tools/dev/secrets_check.py --write-docs")
        return 1
    DOC.write_text(new, encoding="utf-8")
    print(f"secrets-check: wrote {DOC.relative_to(ROOT)}")
    return 0


# ------------------------------------------------------------------------------------------------ main


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--file", type=Path, default=DEFAULT_FILE, help="secrets file (default .local/secrets.env)")
    ap.add_argument("--app", help="only this app or service")
    ap.add_argument("--summary", action="store_true", help="one line per provider group; always exits 0")
    ap.add_argument("--no-env", action="store_true", help="ignore the environment, read only the file")
    ap.add_argument("--inventory", action="store_true", help="list every env var each app reads (code scan)")
    ap.add_argument("--check", action="store_true", help="fail when a secret-like var in code is undocumented, "
                                                          "or the generated doc tables are stale")
    ap.add_argument("--write-docs", action="store_true", help="refresh the generated tables in the reference page")
    args = ap.parse_args(argv)

    if args.inventory:
        print_inventory()
        return 0
    if args.write_docs:
        return write_docs(check_only=False)
    if args.check:
        return max(check_documented(), write_docs(check_only=True))

    path = args.file if args.file.is_absolute() else Path.cwd() / args.file
    values = Values(parse_env_file(path), {} if args.no_env else dict(os.environ))
    if args.summary:
        print(summary(values))
        return 0
    if args.app and args.app not in apps_in_order():
        print(f"unknown app {args.app!r}; one of: {', '.join(apps_in_order())}", file=sys.stderr)
        return 2
    try:
        shown = path.relative_to(ROOT)
    except ValueError:
        shown = path
    state = "found" if path.is_file() else "not found: cp infra/local/secrets.env.example .local/secrets.env"
    print(f"Secrets and provider settings · {shown} ({state}) + environment · values masked")
    text, code = report(values, args.app)
    print(text)
    return code


if __name__ == "__main__":
    sys.exit(main())
