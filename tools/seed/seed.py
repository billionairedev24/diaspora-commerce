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
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from seedlib.http import ApiError
from seedlib.seeder import STEPS, Seeder, load_env

ROOT = Path(__file__).resolve().parents[2]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--only", help=f"comma-separated steps: {','.join(STEPS)}")
    ap.add_argument("--skip", help="comma-separated steps to leave out")
    ap.add_argument("--if-empty", action="store_true",
                    help="seed only what is missing: everything on an empty catalog, else the ops step when its data "
                         "is missing")
    args = ap.parse_args(argv)
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
