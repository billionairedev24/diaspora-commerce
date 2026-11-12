#!/usr/bin/env python3
"""Seed the local (or dev) stack with the AfroTransact demo dataset — `make seed`.

    python3 tools/seed/seed.py                    # everything (idempotent: re-run any time)
    python3 tools/seed/seed.py --if-empty         # what is missing: everything on an empty catalog, else ops (make up)
    python3 tools/seed/seed.py --only ops          # operations: purchase orders, inventory, orders, dispatch, returns
    python3 tools/seed/seed.py --only products,content
    SEED_IMAGES_DIR=~/Pictures/demo python3 tools/seed/seed.py

Everything goes through the services' public APIs (catalog, offers, inventory, media, content, delivery-runs, seller,
bookings; for `ops` also retail, cart, orders, payments, fulfilment, tax-returns, trust-ops, geo), so events reach the
search indexer, buy boxes and availability like real traffic. `ops` checks out as the test customers, packs as the seller
and drives as the driver (tools/seed/README.md). The last step, `users`, signs in every local test user (identity creates
them on the local profile).

Photos: SEED_IMAGES_DIR (your files, named after product slugs or keywords) → cached downloads (.local/seed/images) →
Pexels (PEXELS_API_KEY) → Unsplash (UNSPLASH_ACCESS_KEY) → Wikimedia Commons (no key; CC0 / public domain / CC BY /
CC BY-SA only). Credits: .local/seed/ATTRIBUTION.md. SEED_OFFLINE=1 never downloads.

Service URLs default to infra/local/PORTS.md (CATALOG_URL, OFFERS_URL, INVENTORY_URL, MEDIA_URL, CONTENT_URL,
DELIVERY_RUNS_URL, SELLER_URL, BOOKINGS_URL, SEARCH_URL; for ops ORDERS_URL, PAYMENTS_URL, RETAIL_URL, FULFILMENT_URL,
TRUST_OPS_URL, TAX_RETURNS_URL, CART_URL, GEO_URL). Auth: SEED_TOKEN, or SEED_CLIENT_ID / SEED_CLIENT_SECRET
(client credentials at AUTH_ISSUER_URL); none is needed locally, where services run with security off.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from collections.abc import Mapping
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))

from seedlib.http import ApiError
from seedlib.seeder import STEPS, Seeder, load_env

ROOT = Path(__file__).resolve().parents[2]


PRODUCTION_ENV_NAMES = ("prod", "production", "prd", "live", "uat", "staging", "stage")
# Variables other tools use to say which environment they run in; any of them naming production or UAT stops the seeder.
ENV_NAME_VARIABLES = ("APP_ENV", "ENVIRONMENT", "DEPLOY_ENV", "SPRING_PROFILES_ACTIVE", "ENV")
SEEDED_URL_VARIABLES = ("CATALOG_URL", "OFFERS_URL", "INVENTORY_URL", "MEDIA_URL", "CONTENT_URL", "DELIVERY_RUNS_URL",
                        "SELLER_URL", "BOOKINGS_URL", "SEARCH_URL", "ORDERS_URL", "PAYMENTS_URL", "RETAIL_URL",
                        "FULFILMENT_URL", "TRUST_OPS_URL", "TAX_RETURNS_URL", "CART_URL", "GEO_URL",
                        "CARRIER_GATEWAY_URL", "AUTH_ISSUER_URL", "SEED_TOKEN_URL")
LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1", "host.docker.internal")
# Only these subdomains of the platform domain may ever receive demo data (with SEED_ALLOW_REMOTE=1): the shared dev
# cluster and the throwaway trial environment. The apex, www, api., auth., uat. and every other name are production.
DEMO_SUBDOMAINS = (".dev.afrotransact.com", ".trial.afrotransact.com")
PLATFORM_DOMAIN = "afrotransact.com"


def url_host(url: str) -> str:
    """The lower-case host of an http(s) URL ('' when it has none)."""
    try:
        return (urlparse(url.strip()).hostname or "").lower().rstrip(".")
    except ValueError:
        return ""


def is_local_host(host: str) -> bool:
    return host in LOCAL_HOSTS or host.endswith(".localhost")


def is_production_host(host: str) -> bool:
    """A name under the platform domain that is not a dev or trial environment, so possibly production or UAT."""
    if host != PLATFORM_DOMAIN and not host.endswith("." + PLATFORM_DOMAIN):
        return False
    return not any(host.endswith(sub) for sub in DEMO_SUBDOMAINS)


def target_problem(env: Mapping[str, str]) -> str | None:
    """Why this environment must not be seeded, or None. Demo sellers, products, orders and test users are for local,
    dev and trial stacks only: production and UAT are refused in every case, `SEED_ALLOW_REMOTE` or not."""
    for name in ENV_NAME_VARIABLES:
        for part in re.split(r"[\s,;]+", env.get(name, "").lower()):
            if part in PRODUCTION_ENV_NAMES:
                return f"refusing to run with {name}={env[name].strip()}: demo data is for local, dev and trial stacks only"
    remote = []
    for key in SEEDED_URL_VARIABLES:
        value = env.get(key, "").strip()
        if not value.lower().startswith(("http://", "https://")):
            continue
        host = url_host(value)
        if not host:
            return f"{key} is not a valid URL ({value!r}): refusing to seed"
        if is_production_host(host):
            return f"{key} points at {host}, a production or UAT name: demo data is never written there"
        if not is_local_host(host):
            remote.append(key)
    if remote and env.get("SEED_ALLOW_REMOTE") != "1":
        return (", ".join(sorted(remote)) + " point away from this machine; set SEED_ALLOW_REMOTE=1 only for a dev or "
                "trial environment you mean to fill with demo data")
    return None


def guard_not_production() -> None:
    """The seeder writes demo sellers, products, orders and test users: it must never run against production or UAT."""
    problem = target_problem(os.environ)
    if problem:
        sys.exit("seed: " + problem)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--only", help=f"comma-separated steps: {','.join(STEPS)}")
    ap.add_argument("--skip", help="comma-separated steps to leave out")
    ap.add_argument("--if-empty", action="store_true",
                    help="seed only what is missing: everything on an empty catalog, else the ops step when its data "
                         "is missing")
    args = ap.parse_args(argv)
    guard_not_production()
    steps = tuple(s.strip() for s in args.only.split(",")) if args.only else STEPS
    if args.skip:
        steps = tuple(s for s in steps if s not in {x.strip() for x in args.skip.split(",")})
    unknown = [s for s in steps if s not in STEPS]
    if unknown:
        ap.error(f"unknown step(s) {unknown}; choose from {STEPS}")
    seeder = Seeder(load_env(), ROOT, log=lambda m: print(m, flush=True))
    if args.if_empty:
        try:
            if not seeder.catalog_is_empty():
                if "ops" in steps and seeder.ops_missing():
                    print("seed: the catalog has products but no operations data yet (purchase orders, orders, "
                          "dispatch): seeding it (--only ops)")
                    return seeder.run(("ops",))
                print("seed: the catalog and the operations data are there — skipped (make seed re-runs it; "
                      "SEED=0 turns it off)")
                return 0
        except ApiError as e:
            print(f"seed: services unreachable ({e}); skipped")
            return 0
    return seeder.run(steps)


if __name__ == "__main__":
    sys.exit(main())
