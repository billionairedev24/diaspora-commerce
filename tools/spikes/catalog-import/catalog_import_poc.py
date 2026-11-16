#!/usr/bin/env python3
"""Throwaway proof of concept for docs/engineering/catalog-import-spike.md.

Standard library only, LOCAL FIXTURES ONLY: nothing here opens a socket (see `check_url`, which only parses). It shows:

  1. source payloads -> one canonical product (Shopify products.json, Shopify GraphQL bulk JSONL, WooCommerce Store API,
     schema.org JSON-LD, a spreadsheet row),
  2. deterministic category mapping onto OUR taxonomy (tools/listing-rules/rules.json), with a confidence and a
     "needs a person (or the capped AI assist)" outcome; the AI step itself is NOT implemented,
  3. the draft gate report: which listing-rule fields are missing per product, what the platform decides itself (tax),
     and which import-specific refusals apply (icon images, services, sold out, small main image).

Run: python3 tools/spikes/catalog-import/catalog_import_poc.py   (prints the report for every fixture)
Test: python3 tools/spikes/catalog-import/tests/test_poc.py
"""
import csv
import io
import ipaddress
import json
import os
import re
from dataclasses import dataclass, field
from html import unescape
from html.parser import HTMLParser
from typing import Optional
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
RULES_PATH = os.path.normpath(os.path.join(HERE, "..", "..", "listing-rules", "rules.json"))
MAIN_IMAGE_MIN_LONG_EDGE = 1000  # listing-rules D15 (media-service configuration in the real system)


# ---------------------------------------------------------------- canonical model

@dataclass
class Variant:
    external_id: str
    sku: str = ""
    gtin: str = ""
    price: Optional[float] = None
    compare_at: Optional[float] = None
    currency: str = "USD"
    stock: Optional[int] = None          # None = the source did not say
    available: Optional[bool] = None     # in stock flag when no count is given
    weight_g: Optional[float] = None
    dims_cm: Optional[tuple] = None
    options: dict = field(default_factory=dict)


@dataclass
class Image:
    url: str
    width: Optional[int] = None
    height: Optional[int] = None
    alt: str = ""


@dataclass
class Canonical:
    source: str
    external_id: str
    title: str
    description: str = ""
    brand: str = ""
    source_category: str = ""
    tags: list = field(default_factory=list)
    variants: list = field(default_factory=list)
    images: list = field(default_factory=list)
    source_status: str = "ACTIVE"


def text(html):
    return re.sub(r"\s+", " ", unescape(re.sub(r"<[^>]+>", " ", html or ""))).strip()


def money(v):
    try:
        return float(str(v).replace(",", "").replace("$", "")) if v not in (None, "") else None
    except ValueError:
        return None


def gtin_ok(code):
    """GS1 mod-10 check digit over 8, 12, 13 or 14 digits (the same rule as catalog's Gtin)."""
    if not re.fullmatch(r"\d{8}|\d{12}|\d{13}|\d{14}", code or "" ) or set(code) == {"0"}:
        return False
    body, check = code[:-1], int(code[-1])
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return (10 - total % 10) % 10 == check


# ---------------------------------------------------------------- normalisers

def from_shopify_products_json(doc):
    out = []
    for p in doc["products"]:
        c = Canonical("shopify-public-json", str(p["id"]), p["title"], text(p.get("body_html")), p.get("vendor") or "",
                      p.get("product_type") or "", list(p.get("tags") or []))
        for v in p["variants"]:
            c.variants.append(Variant(str(v["id"]), v.get("sku") or "", v.get("barcode") or "", money(v.get("price")),
                                      money(v.get("compare_at_price")), available=v.get("available"),
                                      weight_g=float(v["grams"]) or None if v.get("grams") else None,
                                      options={"option": v.get("option1")} if v.get("option1") not in (None, "Default Title") else {}))
        c.images = [Image(i["src"], i.get("width"), i.get("height"), i.get("alt") or "") for i in p.get("images", [])]
        out.append(c)
    return out


def from_shopify_bulk_jsonl(lines):
    """Bulk operation output is JSONL where children point at their parent with __parentId (flat, not nested)."""
    products, order = {}, []
    for line in lines.splitlines():
        if not line.strip():
            continue
        o = json.loads(line)
        parent = o.get("__parentId")
        if parent is None and "/Product/" in o["id"]:
            products[o["id"]] = Canonical("shopify-bulk", o["id"].rsplit("/", 1)[1], o["title"], text(o.get("descriptionHtml")),
                                          o.get("vendor") or "", o.get("productType") or "", o.get("tags") or [],
                                          source_status=o.get("status", "ACTIVE"))
            order.append(o["id"])
        elif parent in products and "/ProductVariant/" in o["id"]:
            w = (((o.get("inventoryItem") or {}).get("measurement") or {}).get("weight")) or {}
            grams = None
            if w.get("value"):
                grams = w["value"] * {"GRAMS": 1, "KILOGRAMS": 1000, "POUNDS": 453.592, "OUNCES": 28.3495}.get(w.get("unit"), 1)
            products[parent].variants.append(Variant(o["id"].rsplit("/", 1)[1], o.get("sku") or "", o.get("barcode") or "",
                                                     money(o.get("price")), money(o.get("compareAtPrice")),
                                                     stock=o.get("inventoryQuantity"), weight_g=grams))
        elif parent in products and "image" in o:
            im = o["image"]
            products[parent].images.append(Image(im["url"], im.get("width"), im.get("height"), im.get("altText") or ""))
    return [products[k] for k in order]


def from_woo_store_api(items):
    out = []
    for p in items:
        pr = p["prices"]
        div = 10 ** int(pr.get("currency_minor_unit", 2))
        cat = " > ".join(c["name"] for c in p.get("categories", []))
        c = Canonical("woocommerce-store-api", str(p["id"]), p["name"], text(p.get("description") or p.get("short_description")),
                      (p.get("brands") or [{}])[0].get("name", ""), cat, [t["name"] for t in p.get("tags", [])])
        dims = p.get("dimensions") or {}
        d = tuple(float(dims[k]) * 2.54 for k in ("length", "width", "height") if dims.get(k)) or None
        base = Variant(str(p["id"]), p.get("sku") or "", "", int(pr["price"]) / div,
                       int(pr["regular_price"]) / div if pr["regular_price"] != pr["price"] else None,
                       pr.get("currency_code", "USD"), available=p.get("is_in_stock"),
                       weight_g=float(p["weight"]) * 453.592 if p.get("weight") else None, dims_cm=d)
        if p.get("variations"):  # Store API lists variation ids and their attribute values
            for v in p["variations"]:
                c.variants.append(Variant(str(v["id"]), "", "", base.price, None, base.currency, available=base.available,
                                          options={a["name"]: a["value"] for a in v["attributes"]}))
        else:
            c.variants.append(base)
        c.images = [Image(i["src"], None, None, i.get("alt") or "") for i in p.get("images", [])]
        out.append(c)
    return out


class _LdParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.blocks, self.og, self._in = [], {}, False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "script" and a.get("type") == "application/ld+json":
            self._in = True
            self.blocks.append("")
        if tag == "meta" and (a.get("property") or "").startswith("og:"):
            self.og[a["property"]] = a.get("content", "")

    def handle_data(self, data):
        if self._in:
            self.blocks[-1] += data

    def handle_endtag(self, tag):
        if tag == "script":
            self._in = False


def from_json_ld(html):
    parser = _LdParser()
    parser.feed(html)
    out = []
    for raw in parser.blocks:
        try:
            doc = json.loads(raw)
        except ValueError:
            continue  # malformed block: skipped, never guessed at
        for node in doc if isinstance(doc, list) else [doc]:
            if node.get("@type") not in ("Product", "ProductGroup"):
                continue
            kids = node.get("hasVariant") or [node]
            c = Canonical("json-ld", str(node.get("productGroupID") or node.get("sku") or node.get("name")), node["name"],
                          text(node.get("description")), (node.get("brand") or {}).get("name", "") if isinstance(node.get("brand"), dict) else str(node.get("brand") or ""))
            for k in kids:
                offer = k.get("offers") or {}
                gt = next((str(k[g]) for g in ("gtin", "gtin13", "gtin12", "gtin14", "gtin8") if k.get(g)), "")
                c.variants.append(Variant(str(k.get("sku") or k.get("name")), str(k.get("sku") or ""), gt, money(offer.get("price")),
                                          currency=offer.get("priceCurrency", "USD"),
                                          available=str(offer.get("availability", "")).endswith("InStock") if offer.get("availability") else None,
                                          options={"name": k.get("name")}))
                imgs = k.get("image") or []
                for u in [imgs] if isinstance(imgs, str) else imgs:
                    c.images.append(Image(u if isinstance(u, str) else u.get("url", "")))
            if not c.images and parser.og.get("og:image"):
                c.images.append(Image(parser.og["og:image"]))  # Open Graph is only a fallback image
            out.append(c)
    return out


def from_spreadsheet(csv_text):
    out = []
    for i, row in enumerate(csv.DictReader(io.StringIO(csv_text)), start=2):
        c = Canonical("spreadsheet", f"row{i}", row.get("Item Name", "").strip(), row.get("Description", ""), row.get("Brand", ""),
                      row.get("Category", ""))
        qty = row.get("Qty", "").strip()
        lb = money(row.get("Weight (lb)"))
        c.variants.append(Variant(f"row{i}", "", row.get("UPC", "").strip(), money(row.get("Price")),
                                  stock=int(qty) if qty.isdigit() else None, weight_g=lb * 453.592 if lb else None))
        if row.get("Photo URL"):
            c.images.append(Image(row["Photo URL"]))
        out.append(c)
    return out


# ---------------------------------------------------------------- category mapping (deterministic first)

# Synonym seeds per OUR category code. In the product this table is data (a mapping-rule set a staff member edits, plus
# rules learned from confirmed mappings), never code per category.
SEEDS = {
    "haircare": ["hair care", "haircare", "shampoo", "conditioner", "edge control", "hair gel", "hair oil", "castor", "curl", "relaxer", "pomade", "braid spray"],
    "wigs-extensions": ["wig", "lace front", "weave", "bundle", "braiding hair", "braid", "extension", "twist"],
    "hair-accessories": ["bonnet", "comb", "brush", "hair tools", "scarf", "clip", "satin", "durag"],
    "skincare-cosmetics": ["lotion", "shea butter", "cocoa butter", "lipstick", "foundation", "skincare", "soap", "serum"],
    "gift-cards": ["gift card"],
    "rice-grains": ["rice", "grain"],
}


def _tokens(s):
    return set(re.findall(r"[a-z]+", s.lower()))


def map_category(c, rules):
    """Score = best seed hit. Title hit 0.6, source category/tag hit 0.8 (the seller's own word is stronger evidence)."""
    names = {x["code"]: x for x in rules["categories"]}
    hay_title, hay_meta = c.title.lower(), " ".join([c.source_category, *c.tags]).lower()
    scored = []
    for code, seeds in SEEDS.items():
        s = 0.0
        for seed in seeds:
            if seed in hay_meta:
                s = max(s, 0.8)
            if seed in hay_title:
                s = max(s, 0.6)
        if s and (_tokens(c.source_category) & _tokens(names[code]["name"])):
            s = min(0.95, s + 0.1)
        if s:
            scored.append((s, code))
    scored.sort(reverse=True)
    if not scored:
        return {"code": None, "confidence": 0.0, "decision": "AI_OR_PERSON", "alternatives": []}
    top = scored[0]
    tie = len(scored) > 1 and scored[1][0] == top[0]
    decision = "AUTO_SUGGEST" if top[0] >= 0.7 and not tie else "CONFIRM"
    return {"code": top[1], "confidence": round(top[0], 2), "decision": decision, "alternatives": [k for _, k in scored[1:3]]}


# ---------------------------------------------------------------- import-specific refusals + the draft gate report

NON_IMPORTABLE_KINDS = {"SRV": "Services come from the Provider Hub, not product import", "DIG": "Digital products need their own approval", "GFT": "Gift cards are not imported"}
NET_CONTENT = re.compile(r"\b\d+(\.\d+)?\s?(fl\.? ?oz|oz|lb|kg|g|ml|l)\b", re.I)
ICON_HINT = re.compile(r"(icon|logo|placeholder|emoji|sprite)", re.I)


def image_findings(images):
    good, notes = [], []
    for im in images:
        path = urlparse(im.url).path.lower()
        if path.endswith((".svg", ".ico", ".gif")) or ICON_HINT.search(path):
            notes.append(f"rejected as icon or graphic: {os.path.basename(path)}")
            continue
        good.append(im)
    if good:
        main = good[0]
        if main.width and main.height and max(main.width, main.height) < MAIN_IMAGE_MIN_LONG_EDGE:
            notes.append(f"main image {main.width}x{main.height} is under {MAIN_IMAGE_MIN_LONG_EDGE}px on the long side: the seller must replace it")
    return good, notes


def gate_report(c, rules, mapping):
    kinds = {k["code"]: k for k in rules["kinds"]}
    cat = next((x for x in rules["categories"] if x["code"] == mapping["code"]), None)
    rep = {"title": c.title, "source": c.source, "category": mapping, "missing": [], "confirm": [], "from_seller_settings": [],
           "platform_decides": ["tax decision (never typed by the seller; the draft cannot go live until it is RESOLVED)"],
           "refused": [], "notes": [], "stock": None, "state": None}
    if cat is None:
        rep["state"] = "NEEDS_CATEGORY"
        rep["missing"].append("category (pick one; mapping had no confident answer)")
        return rep
    if cat["kind"] in NON_IMPORTABLE_KINDS:
        rep["state"] = "SKIPPED"
        rep["refused"].append(NON_IMPORTABLE_KINDS[cat["kind"]])
        return rep
    good, notes = image_findings(c.images)
    rep["notes"] += notes
    net = NET_CONTENT.search(c.title)
    gt_bad = [x.gtin for x in c.variants if x.gtin and not gtin_ok(x.gtin)]
    have = {
        "title": len(c.title) >= 10,
        "brand": bool(c.brand),
        "product_id": any(gtin_ok(x.gtin) for x in c.variants),
        "description": len(c.description) >= 10,
        "images": bool(good),
        "price": all(x.price for x in c.variants) and bool(c.variants),
        "quantity": all((x.stock is not None) or (x.available is not None) for x in c.variants) and bool(c.variants),
        "package_weight": all(x.weight_g for x in c.variants) and bool(c.variants),
        "package_size": all(x.dims_cm for x in c.variants) and bool(c.variants),
        "sizes": any(x.options for x in c.variants),
        "net_weight": bool(net),
    }
    fixed = {"handling_days": "seller settings default", "ship_from": "seller's ship-from address"}
    for fld in rules["fields"]:
        rule = fld["byKind"][cat["kind"]]
        if rule in ("hidden", "optional") or fld["group"].startswith("Documents"):  # documents are seller-level, checked at approval
            continue
        fid = fld["id"]
        if fid in fixed:
            rep["from_seller_settings"].append(f"{fid}: {fixed[fid]}")
        elif fid in have:
            if not have[fid]:
                rep["missing"].append(fid + (" (or an exemption reason)" if rule == "required_or_exemption" else ""))
        elif rule == "required" or rule == "required_or_exemption":
            rep["missing"].append(fid + " (not available from any source: the seller supplies it)")
    if gt_bad:
        rep["notes"].append(f"invalid barcode(s) dropped: {gt_bad}")
    if net:
        rep["confirm"].append(f"net content {net.group(0)!r} read from the title (a suggestion, the seller confirms)")
    out = [x.stock == 0 or x.available is False for x in c.variants]
    if c.variants and all(out):
        rep["stock"] = "SOLD_OUT: kept as a draft, never shown on home or rails until stock is above 0"
    elif any(out):
        rep["stock"] = "some variants sold out: only in-stock variants may appear on home or rails"
    if cat["approval"] != "open":
        rep["notes"].append(f"category needs seller approval ({cat['approval']}): the draft is held until the seller's scope covers it")
    if mapping["decision"] != "AUTO_SUGGEST":
        rep["confirm"].append(f"category {mapping['code']} (confidence {mapping['confidence']})")
    rep["state"] = "READY_FOR_REVIEW" if not rep["missing"] else "NEEDS_SELLER"
    return rep


def load_rules():
    with open(RULES_PATH, encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- SSRF guard (parse-only here; no DNS, no socket)

def check_url(url):
    """The static half of the URL fetcher's guard. The real fetcher also resolves ONCE, checks every answer and dials the
    checked address (so DNS rebinding cannot swap it), re-checks every redirect hop, and caps time and bytes."""
    u = urlparse(url)
    if u.scheme != "https":
        return "scheme must be https"
    if u.username or u.password:
        return "user info in URL"
    if u.port not in (None, 443):
        return "port must be 443"
    host = (u.hostname or "").strip("[]")
    try:
        ip = ipaddress.ip_address(host)
        return f"IP literal refused ({'non-public' if not ip.is_global else 'public'})"
    except ValueError:
        pass
    if re.fullmatch(r"(0x[0-9a-f]+|\d+)(\.(0x[0-9a-f]+|\d+)){0,3}", host, re.I):
        return "numeric host spelling refused"
    if host in ("localhost", "metadata.google.internal") or host.endswith((".internal", ".local", ".localhost")):
        return "internal name refused"
    return None


def non_public(ip_text):
    ip = ipaddress.ip_address(ip_text)
    mapped = getattr(ip, "ipv4_mapped", None)
    if mapped:
        ip = mapped
    return not ip.is_global or ip.is_multicast


def run():
    rules = load_rules()
    def rd(n):
        with open(os.path.join(HERE, "fixtures", n), encoding="utf-8") as f:
            return f.read()
    batches = [("shopify products.json", from_shopify_products_json(json.loads(rd("shopify_products.json")))),
               ("shopify bulk JSONL", from_shopify_bulk_jsonl(rd("shopify_bulk.jsonl"))),
               ("woocommerce store api", from_woo_store_api(json.loads(rd("woo_store_products.json")))),
               ("json-ld page", from_json_ld(rd("jsonld_page.html"))),
               ("spreadsheet", from_spreadsheet(rd("sheet.csv")))]
    for name, items in batches:
        print(f"== {name}: {len(items)} product(s)")
        for c in items:
            r = gate_report(c, rules, map_category(c, rules))
            print(f"- {c.title[:48]!r} -> {r['category']['code']} ({r['category']['decision']} {r['category']['confidence']}) {r['state']}")
            for k in ("missing", "confirm", "refused", "notes"):
                if r[k]:
                    print(f"    {k}: {'; '.join(r[k])}")
            if r["stock"]:
                print(f"    stock: {r['stock']}")


if __name__ == "__main__":
    run()
