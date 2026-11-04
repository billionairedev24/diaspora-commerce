#!/usr/bin/env python3
"""Download Texas Comptroller quarterly sales tax rate TXT and summarize local stacks for major cities.

Does not write to the database — prints fractions for finance/engineering to paste into Flyway or PUT /v1/tax/rates.

Usage:
  python3 tools/tax/texas_comptroller_rates.py
  python3 tools/tax/texas_comptroller_rates.py --url https://comptroller.texas.gov/data/edi/sales-tax/taxrates254.txt

Source: https://comptroller.texas.gov/taxes/file-pay/edi/sales-tax-rates.php
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.request

DEFAULT_URL = "https://comptroller.texas.gov/data/edi/sales-tax/taxrates254.txt"
STATE_RATE = 0.0625
CITIES = ("Houston", "Dallas", "Austin", "San Antonio", "Fort Worth", "El Paso", "Plano", "Arlington")


def parse_lines(text: str) -> list[tuple[str, float]]:
    """Return (city_name, summed_local_rate) for lines that start with a tracked city name."""
    out: dict[str, float] = {}
    rate_re = re.compile(r"\b0\.\d+\b")
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0].isdigit():
            continue
        for city in CITIES:
            if not line.startswith(city + " "):
                continue
            rates = [float(x) for x in rate_re.findall(line)]
            # First rate is city; rest are county/MTA/SPD (skip n/a placeholders already absent)
            local = sum(rates)
            prev = out.get(city, 0.0)
            out[city] = max(prev, local)
    return sorted(out.items())


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--url", default=DEFAULT_URL, help="Comptroller taxrates*.txt URL")
    args = p.parse_args()
    with urllib.request.urlopen(args.url, timeout=60) as resp:
        body = resp.read().decode("utf-8", errors="replace")
    header = body.splitlines()[0] if body else ""
    print(f"# Fetched: {args.url}")
    print(f"# Header: {header[:120]}...")
    print(f"# State rate (fixed): {STATE_RATE} ({STATE_RATE * 100:.2f}%)")
    print("# city | local_add_on | combined_general")
    for city, local in parse_lines(body):
        combined = STATE_RATE + local
        print(f"{city:16} {local:.6f}     {combined:.6f}  ({combined * 100:.3f}%)")
    print("# Store local_add_on in tax_local_rate.rate; grocery state+local = 0 for exempt food.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
