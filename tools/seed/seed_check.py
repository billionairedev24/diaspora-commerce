#!/usr/bin/env python3
"""`make seed-check`: validates every demo product the seeder would create against the catalog's schemas, offline and read-only.

For each product of the dataset it builds the same create request the seeder sends (seeder.create_body) from the recorded
category tree, product-type schemas and listing rules in tools/seed/testdata (gen_testdata.py makes them from the sources the
catalog loads), then runs a port of the catalog's publish gate (listing.check_listing) on it. A product whose category is
gated (NEEDS_APPROVAL ...) is listed as skipped, exactly as the seeder skips it. Exit status 1 when any product would be refused.

    python3 tools/seed/seed_check.py            # check everything
    python3 tools/seed/seed_check.py -v         # also print each product's answers
    python3 tools/seed/seed_check.py --testdata DIR
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from seedlib import dataset as D  # noqa: E402
from seedlib import listing as L  # noqa: E402
from seedlib.seeder import Seeder, create_body  # noqa: E402

TESTDATA = Path(__file__).resolve().parent / "testdata"
SKU = re.compile(r"^[A-Z0-9._-]{2,40}$")
FAKE_IMAGE = {"url": "http://localhost:9000/media/demo.jpg", "width": 1200, "height": 1200, "bytes": 200000,
              "contentType": "image/jpeg"}


def load(testdata: Path) -> tuple[dict[str, dict[str, Any]], dict[str, Any], dict[str, Any]]:
    nodes = json.loads((testdata / "categories.json").read_text(encoding="utf-8"))
    return ({n["path"]: n for n in nodes}, json.loads((testdata / "product_types.json").read_text(encoding="utf-8")),
            json.loads((testdata / "listing_rules.json").read_text(encoding="utf-8")))


def check_product(p: D.Product, cats: dict[str, dict[str, Any]], types: dict[str, Any],
                  rules_by_code: dict[str, Any]) -> tuple[str, dict[str, str], dict[str, Any] | None]:
    """(outcome, errors, body): outcome is "ok", "skipped: ..." or "refused"."""
    cat = cats.get(p.path)
    if not cat:
        return "refused", {"category": f"{p.path!r} is not in the category tree"}, None
    status = Seeder._tax_status(cat, cats)
    if status != "ACTIVE":
        return f"skipped: category {status}", {}, None
    ptype = types.get(cat["productType"])
    if not ptype:
        return "refused", {"productType": f"no schema for {cat['productType']!r}"}, None
    by_id = {c["id"]: c for c in cats.values()}
    rules = L.Rules(rules_by_code.get(L.category_code(cat, by_id)) or rules_by_code["general"])
    label = dict(FAKE_IMAGE, width=900, height=1200, contentType="image/png") if rules.expects("label_photo") else None
    chart = {"id": "00000000-0000-0000-0000-000000000000"} if p.chart else None
    try:
        body = create_body(p, cat, ptype, rules, [FAKE_IMAGE], label, [], chart)
    except (ValueError, KeyError) as e:
        return "refused", {"body": str(e)}, None
    errors = L.check_listing(rules, ptype, body)
    if len(p.title) < 10:
        errors["title"] = "At least 10 characters"
    for i, v in enumerate(body["variants"]):
        if not SKU.match(v["sellerSku"]):
            errors[f"variants[{i}].sellerSku"] = f"{v['sellerSku']!r} is not 2-40 of A-Z 0-9 . _ -"
    return ("refused" if errors else "ok"), errors, body


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--testdata", type=Path, default=TESTDATA)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)
    cats, types, rules = load(args.testdata)
    ok = refused = 0
    skipped: list[str] = []
    for p in D.PRODUCTS:
        outcome, errors, body = check_product(p, cats, types, rules)
        if outcome == "ok":
            ok += 1
            if args.verbose and body:
                print(f"  ok  {p.key}: {json.dumps(body['attributes'], ensure_ascii=False)[:200]}")
        elif outcome.startswith("skipped"):
            skipped.append(f"{p.key} ({outcome[9:]})")
        else:
            refused += 1
            print(f"  REFUSED {p.key} ({p.path})")
            for path, msg in errors.items():
                print(f"      {path}: {msg}")
    print(f"seed-check: {len(D.PRODUCTS)} products, {ok} pass the publish gate, {len(skipped)} skipped, {refused} refused")
    if skipped:
        print("  skipped like the seeder does: " + ", ".join(skipped))
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
