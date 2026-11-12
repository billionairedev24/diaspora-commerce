#!/usr/bin/env python3
"""Probes the DEPLOYED hosts for SEO drift (docs/runbooks/seo-and-search-console.md). Hosts come from infra/cloudflare/hosts.json, nothing is hard-coded.

  python3 tools/ci/seo_probe.py                      check every environment that answers
  python3 tools/ci/seo_probe.py --strict             an environment that does not answer is a failure
  python3 tools/ci/seo_probe.py --min-urls 500       prod sitemap must list at least 500 URLs (alert on a drop)
  python3 tools/ci/seo_probe.py --env trial          one environment only

What it proves:
  non-prod storefronts   robots.txt is "Disallow: /" with no Sitemap, /sitemap.xml is 404, pages carry X-Robots-Tag: noindex
  every env's seller / admin / api / auth host   X-Robots-Tag: noindex on the root response (prod too)
  prod storefront        robots.txt allows crawling and names the sitemap index, the index and every child parse, each child has
                         <= 50,000 URLs and <= 50 MB, a sample of URLs answers 200 with ONE canonical, is not noindex, and
                         product pages carry Product JSON-LD; the total URL count is not below --min-urls
Exit code 1 on any failure; the messages are one per line (a scheduled workflow turns them into an alert).
"""
from __future__ import annotations

import argparse
import json
import random
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
MAP = ROOT / "infra/cloudflare/hosts.json"
NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
MAX_URLS, MAX_BYTES = 50_000, 52_428_800

# fetch(url) -> (status, headers (lower-case keys), body bytes); status 0 = no answer
Fetch = Callable[[str], tuple[int, dict[str, str], bytes]]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):  # noqa: D401 - never follow: we inspect the first response
        return None


def http_fetch(url: str) -> tuple[int, dict[str, str], bytes]:
    req = urllib.request.Request(url, headers={"User-Agent": "afrotransact-seo-probe/1"})
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(req, timeout=20) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()
    except Exception:  # noqa: BLE001 - DNS failure, timeout, TLS: the host does not answer
        return 0, {}, b""


def hosts_of(m: dict, env: str) -> dict[str, str]:
    cfg = m["envs"][env]
    zone, suf = cfg.get("zone", m["zone"]), cfg["suffix"]
    out = {"storefront": zone if suf == "" else f"{suf}.{zone}"}
    for r in m["roles"] + m["other_roles"]:
        out[r] = f"{r}.{zone}" if suf == "" else f"{r}-{suf}.{zone}"
    return out


def noindexed(status: int, headers: dict[str, str], body: bytes) -> bool:
    return "noindex" in headers.get("x-robots-tag", "").lower() or bool(re.search(rb'<meta name="robots" content="[^"]*noindex', body))


def check_nonprod_storefront(host: str, fetch: Fetch) -> list[str]:
    errs: list[str] = []
    base = f"https://{host}"
    st, h, b = fetch(base + "/robots.txt")
    if st != 200 or b.decode("utf-8", "replace").strip() != "User-agent: *\nDisallow: /":
        errs.append(f"{host}/robots.txt is not 'Disallow: /' (status {st})")
    if re.search(rb"(?i)sitemap:", b):
        errs.append(f"{host}/robots.txt names a sitemap")
    st, _, _ = fetch(base + "/sitemap.xml")
    if st != 404:
        errs.append(f"{host}/sitemap.xml answers {st}, expected 404")
    st, h, b = fetch(base + "/")
    if st and not noindexed(st, h, b):
        errs.append(f"{host}/ is indexable (no X-Robots-Tag noindex)")
    return errs


def check_backoffice(host: str, fetch: Fetch) -> list[str]:
    st, h, b = fetch(f"https://{host}/")
    if st == 0:
        return []
    return [] if noindexed(st, h, b) else [f"{host}/ answers {st} without noindex"]


def parse_urlset(body: bytes) -> list[str]:
    return [e.text or "" for e in ET.fromstring(body).findall("sm:url/sm:loc", NS)]


def check_prod_storefront(host: str, fetch: Fetch, min_urls: int, sample: int, rng: random.Random) -> list[str]:
    errs: list[str] = []
    base = f"https://{host}"
    st, _, b = fetch(base + "/robots.txt")
    txt = b.decode("utf-8", "replace")
    if st != 200 or f"Sitemap: {base}/sitemap.xml" not in txt:
        errs.append(f"{host}/robots.txt does not name the sitemap index (status {st})")
    if re.search(r"(?m)^Disallow:\s*/\s*$", txt):
        errs.append(f"{host}/robots.txt disallows everything")
    for must in ("/_next/static/",):
        if re.search(rf"(?m)^Disallow:\s*{re.escape(must)}", txt):
            errs.append(f"{host}/robots.txt blocks {must}")
    st, _, b = fetch(base + "/sitemap.xml")
    if st != 200:
        return errs + [f"{host}/sitemap.xml answers {st}"]
    try:
        children = [e.text or "" for e in ET.fromstring(b).findall("sm:sitemap/sm:loc", NS)]
    except ET.ParseError:
        return errs + [f"{host}/sitemap.xml is not well-formed XML"]
    if not children:
        errs.append(f"{host}/sitemap.xml lists no child sitemaps")
    urls: list[str] = []
    for c in children:
        st, _, body = fetch(c)
        if st != 200:
            errs.append(f"{c} answers {st}")
            continue
        if len(body) > MAX_BYTES:
            errs.append(f"{c} is over 50 MB")
        try:
            locs = parse_urlset(body)
        except ET.ParseError:
            errs.append(f"{c} is not well-formed XML")
            continue
        if len(locs) > MAX_URLS:
            errs.append(f"{c} lists {len(locs)} URLs (> 50,000)")
        urls += locs
    if len(urls) < min_urls:
        errs.append(f"sitemap lists {len(urls)} URLs, below the floor of {min_urls}")
    for u in rng.sample(urls, min(sample, len(urls))):
        st, h, body = fetch(u)
        if st != 200:
            errs.append(f"{u} answers {st}")
            continue
        if noindexed(st, h, body):
            errs.append(f"{u} is noindex but listed in the sitemap")
        canon = re.findall(rb'<link rel="canonical" href="([^"]+)"', body)
        if len(canon) != 1:
            errs.append(f"{u} has {len(canon)} canonical links")
        if "/p/" in u and b'"@type":"Product"' not in body:
            errs.append(f"{u} has no Product JSON-LD in the first HTML")
    return errs


def main(argv: list[str], fetch: Fetch = http_fetch) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", action="append", help="environment(s) to check (default: all in hosts.json)")
    ap.add_argument("--strict", action="store_true", help="an environment that does not answer is a failure")
    ap.add_argument("--min-urls", type=int, default=1, help="prod sitemap URL floor")
    ap.add_argument("--sample", type=int, default=10, help="sitemap URLs fetched per run")
    args = ap.parse_args(argv)
    m = json.loads(MAP.read_text())
    errs: list[str] = []
    rng = random.Random()
    for env in args.env or list(m["envs"]):
        hs = hosts_of(m, env)
        if fetch(f"https://{hs['storefront']}/robots.txt")[0] == 0:
            (errs.append(f"{env}: {hs['storefront']} does not answer") if args.strict else print(f"skip {env}: {hs['storefront']} does not answer"))
            continue
        errs += check_prod_storefront(hs["storefront"], fetch, args.min_urls, args.sample, rng) if env == "prod" else check_nonprod_storefront(hs["storefront"], fetch)
        for role in ("seller", "admin", "api", "auth"):
            errs += check_backoffice(hs[role], fetch)
    for e in errs:
        print("FAIL " + e)
    print("seo_probe: " + ("FAILED" if errs else "OK"))
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
