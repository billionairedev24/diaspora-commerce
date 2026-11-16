"""Platform detection ladder (rung 2) and the plug-in registry, offline: it classifies a response that was ALREADY fetched.

A connector plug-in is one class that declares its fingerprints and capabilities and nothing else the core needs to know.
Adding a platform = adding a class and registering it (in the product: a module implementing the SourceConnector port,
discovered by a service loader / Go registry; no change to the import core). Fingerprints are HEURISTICS: a miss is
normal, a hit is only a hypothesis until the connector's own probe (e.g. GET /products.json) answers the expected shape.
"""
import re
from dataclasses import dataclass

REGISTRY = []


@dataclass
class Evidence:
    platform: str
    score: float
    why: list


def connector(cls):
    REGISTRY.append(cls)
    return cls


class Fingerprint:
    platform = ""
    capabilities = ()          # what the connector can do once authorised: "public-json", "admin-api", "csv-export", ...
    header_hits = {}           # lower-case header name -> regex
    html_hits = ()             # regexes over the first 200 KB of markup
    path_probes = ()           # paths the next rung may probe (still subject to robots.txt and ownership)

    @classmethod
    def match(cls, headers, html):
        why = []
        for name, pat in cls.header_hits.items():
            if re.search(pat, headers.get(name, ""), re.I):
                why.append(f"header {name}")
        for pat in cls.html_hits:
            if re.search(pat, html or "", re.I):
                why.append(f"markup {pat}")
        return why


@connector
class Shopify(Fingerprint):
    platform = "shopify"
    capabilities = ("admin-graphql-bulk", "csv-export", "public-products-json (ToS: owner consent only)")
    header_hits = {"x-shopid": r"\d+", "x-shopify-stage": r".+", "server": r"shopify"}
    html_hits = (r"cdn\.shopify\.com", r"Shopify\.theme", r"window\.Shopify")
    path_probes = ("/products.json?limit=1",)


@connector
class WooCommerce(Fingerprint):
    platform = "woocommerce"
    capabilities = ("rest-v3-keys", "store-api-public", "csv-export")
    html_hits = (r"wp-content/plugins/woocommerce", r"<meta name=\"generator\" content=\"WooCommerce", r"woocommerce-[a-z-]+")
    path_probes = ("/wp-json/wc/store/v1/products?per_page=1",)


@connector
class BigCommerce(Fingerprint):
    platform = "bigcommerce"
    capabilities = ("catalog-v3-oauth", "csv-export")
    html_hits = (r"cdn\d*\.bigcommerce\.com", r"BigCommerce")


@connector
class Squarespace(Fingerprint):
    platform = "squarespace"
    capabilities = ("commerce-api-key-or-oauth", "csv-export")
    html_hits = (r"static1\.squarespace\.com", r"Squarespace")
    header_hits = {"server": r"squarespace"}


@connector
class Wix(Fingerprint):
    platform = "wix"
    capabilities = ("stores-catalog-oauth", "csv-export")
    html_hits = (r"static\.wixstatic\.com", r"wix-?(code|stores)")
    header_hits = {"x-wix-request-id": r".+"}


@connector
class JsonLdSite(Fingerprint):
    platform = "generic-json-ld"
    capabilities = ("schema-org-product-reader",)
    html_hits = (r"application/ld\+json[^>]*>[^<]*\"@type\"\s*:\s*\"(Product|ProductGroup)\"",)


def detect(headers, html):
    """Returns ranked evidence; an empty list means rung 2 found nothing and the ladder moves on (structured extraction,
    then concierge or file import). Never raises on junk input."""
    headers = {k.lower(): v for k, v in (headers or {}).items()}
    found = []
    for cls in REGISTRY:
        why = cls.match(headers, (html or "")[:200_000])
        if why:
            found.append(Evidence(cls.platform, min(1.0, 0.4 * len(why)), why))
    return sorted(found, key=lambda e: -e.score)
