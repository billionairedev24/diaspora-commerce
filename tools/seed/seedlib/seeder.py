"""Seeds the demo dataset through the services' public APIs. Every step is idempotent: records are looked up by a
stable key (seller org id, product title, placement / campaign / window name) before anything is created."""
from __future__ import annotations

import datetime as dt
import json
import os
import re
import time
from collections import Counter, defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import dataset as D
from .auth import TokenProvider
from .http import ApiError, Http
from .images.library import ImageLibrary
from .images.model import ImageFile
from .images.sources import PexelsSource, UnsplashSource, WikimediaSource
from .media import MediaUploader
from .state import State
from .users import TestUsers

MAIN_MIN_EDGE = 1000  # catalog: the main product image must be at least 1000 x 1000
FOOD_TYPES = {"grocery", "perishable"}
FC_NODES = ("HOU-FC", "ATL-FC", "EWR-FC", "LAX-FC")
PREORDER_TIME_ZONE = "America/Chicago"
STEPS = ("sellers", "products", "content", "ads", "preorders", "bookings", "users")

SERVICES = {  # env var, default local URL (infra/local/PORTS.md), health path
    "identity": ("IDENTITY_URL", "http://localhost:8100", "/actuator/health"),
    "catalog": ("CATALOG_URL", "http://localhost:8101", "/actuator/health"),
    "offers": ("OFFERS_URL", "http://localhost:8102", "/actuator/health"),
    "inventory": ("INVENTORY_URL", "http://localhost:8103", "/actuator/health"),
    "seller": ("SELLER_URL", "http://localhost:8106", "/actuator/health"),
    "bookings": ("BOOKINGS_URL", "http://localhost:8109", "/actuator/health"),
    "content": ("CONTENT_URL", "http://localhost:8112", "/actuator/health"),
    "delivery-runs": ("DELIVERY_RUNS_URL", "http://localhost:8115", "/actuator/health"),
    "media": ("MEDIA_URL", "http://localhost:8207", "/readyz"),
    "search": ("SEARCH_URL", "http://localhost:8201", "/readyz"),
}
REQUIRED = ("catalog", "offers", "inventory", "media")


@dataclass
class Report:
    counts: Counter = field(default_factory=Counter)
    image_sources: Counter = field(default_factory=Counter)
    problems: list[str] = field(default_factory=list)
    usage: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))

    def add(self, what: str, outcome: str, n: int = 1) -> None:
        self.counts[(what, outcome)] += n


def market_zone() -> dt.tzinfo:
    """America/Chicago (the windows' time zone); a fixed UTC-6 when the system has no zone database."""
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(PREORDER_TIME_ZONE)
    except (ImportError, KeyError, OSError, ValueError):
        return dt.timezone(dt.timedelta(hours=-6), "CST")


def _now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def _iso(t: dt.datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def sku_for(seller_key: str, product_key: str, options: dict[str, str]) -> str:
    """Seller SKU: <SELLER>-<PRODUCT>-<OPTIONS>, 2–40 of A–Z 0–9 . _ - (catalog's rule)."""
    prefix = re.sub(r"[^A-Z0-9]", "", seller_key.upper())[:4]
    prod = re.sub(r"[^A-Z0-9]+", "-", product_key.upper()).strip("-")[:20]
    opts = "-".join(re.sub(r"[^A-Z0-9]+", "", options[k].upper())[:6] for k in sorted(options))[:15]
    return f"{prefix}-{prod}-{opts}".strip("-")[:40]


def theme_for(ptype: dict[str, Any], variants: tuple[tuple[Any, int], ...]) -> tuple[str, list[dict[str, str]]]:
    """(variation theme name, options per variant) for the dataset's compact variant specs."""
    themes = ptype.get("themes") or []
    first = variants[0][0]
    if isinstance(first, dict):
        dims = set(first)
        for th in themes:
            if {d["name"] for d in th["dimensions"]} == dims:
                return th["name"], [dict(v) for v, _ in variants]
        raise ValueError(f"no {ptype['code']} theme with dimensions {sorted(dims)}")
    for th in themes:
        if len(th["dimensions"]) == 1 and all(v in th["dimensions"][0]["options"] for v, _ in variants):
            dim = th["dimensions"][0]["name"]
            return th["name"], [{dim: v} for v, _ in variants]
    raise ValueError(f"no single-dimension {ptype['code']} theme accepts {[v for v, _ in variants]}")


def attributes_for(p: D.Product, ptype: dict[str, Any], credits: list[str]) -> dict[str, str]:
    """Required product-type attributes (sensible defaults, the product's own values win) plus storefront extras."""
    defaults = {
        "brand": p.brand, "origin": p.origin, "ingredients": p.title, "allergens": "None", "shelf": "365",
        "storage": "Room temperature", "cert": "None", "temp": "Frozen (≤ 0°F)",
        "permit": "Demo data — not a real permit", "inci": p.title, "skin": "All",
        "mocra": "Demo data — not a real listing number", "warn": "For external use only. Patch test before first use.",
        "fibre": "100% cotton", "width": "46 in", "material": "Cotton wax print", "fit": "Regular", "length": "Maxi",
        "sleeve": "Short", "occasion": "Everyday", "care": "Hand wash cold, line dry", "style": "Pre-tied (auto gele)",
        "pieces": "1", "gender": "Girls", "safety": "Demo data — not a real certificate", "dims": p.unit or "See listing",
        "handmade": "Yes", "packs": "8", "language": "English", "author": p.brand, "publisher": p.brand, "pages": "200",
    }
    out: dict[str, str] = {}
    for a in ptype.get("attributes") or []:
        key = a["key"]
        value = str(p.attrs.get(key, defaults.get(key, "")))
        if a.get("options") and value not in a["options"]:
            value = a["options"][0]
        out[key] = value
    for k, v in p.attrs.items():
        if k != "gtin":
            out.setdefault(k, str(v))
    out.setdefault("brand", p.brand)
    out["community"] = p.community
    out["bullets"] = "\n".join(p.bullets)
    if p.unit:
        out["unit"] = p.unit
    out.setdefault("origin", p.origin)
    if credits:
        out["imageCredits"] = " | ".join(credits)[:1000]
    return out


class Seeder:
    def __init__(self, env: dict[str, str], root: Path, log: Callable[[str], None] = print):
        self.env, self.root, self.log = env, root, log
        state_dir = Path(env.get("SEED_STATE_DIR") or root / ".local" / "seed")
        state_dir.mkdir(parents=True, exist_ok=True)
        self.state_dir = state_dir
        self.token = TokenProvider(env, log)
        self.svc = {name: Http(env.get(var) or url, token=self.token) for name, (var, url, _) in SERVICES.items()}
        self.health = {name: path for name, (_, _, path) in SERVICES.items()}
        self.state = State(state_dir / "state.json")
        self.report = Report()
        sources = []
        if env.get("PEXELS_API_KEY"):
            sources.append(PexelsSource(env["PEXELS_API_KEY"]))
        if env.get("UNSPLASH_ACCESS_KEY"):
            sources.append(UnsplashSource(env["UNSPLASH_ACCESS_KEY"]))
        if env.get("SEED_WIKIMEDIA", "1") != "0":
            sources.append(WikimediaSource())
        self.library = ImageLibrary(state_dir / "images", sources, env.get("SEED_IMAGES_DIR") or None, log=log,
                                    offline=env.get("SEED_OFFLINE") == "1")
        self.uploader = MediaUploader(self.svc["media"], self.state, log)
        self.up: dict[str, bool] = {}
        self.products: dict[str, dict[str, Any]] = {}  # dataset key -> catalog product

    # ------------------------------------------------------------------------------------------------ run
    def run(self, steps: tuple[str, ...] = STEPS) -> int:
        self._preflight()
        missing = [s for s in REQUIRED if not self.up.get(s)]
        if missing and ("products" in steps or "content" in steps):
            self.log(f"✗ required services are down: {', '.join(missing)} (make up / tools/dev/local.sh status)")
            return 2
        self._image_sources_banner()
        try:
            for step in steps:
                getattr(self, "step_" + step.replace("-", "_"))()
        finally:
            for sha, img in self.library.attribution.items():
                entry = dict(img.to_json(), usedBy=sorted(set(self.state.images.get(sha, {}).get("usedBy", []))
                                                          | set(self.report.usage.get(sha, []))))
                self.state.images[sha] = entry
            self.state.save()
            self.library.save()
            images = [ImageFile.from_json(e) for e in self.state.images.values()]
            usage = {sha: e.get("usedBy", []) for sha, e in self.state.images.items()}
            self.library.write_attribution(self.state_dir / "ATTRIBUTION.md", self.state_dir / "attribution.json",
                                           usage, images)
        self._summary()
        return 1 if self.report.problems else 0

    def catalog_is_empty(self) -> bool:
        page = self.svc["catalog"].get("/v1/products", params={"size": 1})
        return int((page or {}).get("total", 0)) == 0

    def _preflight(self) -> None:
        for name, http in self.svc.items():
            self.up[name] = http.healthy(self.health[name])
        up = [n for n, ok in self.up.items() if ok]
        down = [n for n, ok in self.up.items() if not ok]
        self.log(f"services up: {', '.join(up)}" + (f" · down: {', '.join(down)}" if down else ""))
        self.token()  # logs how the seeder authenticates

    def _image_sources_banner(self) -> None:
        order = []
        if self.library.local_dir:
            order.append(f"SEED_IMAGES_DIR={self.library.local_dir}")
        order.append(f"cache {self.library.cache}")
        order += [s.name for s in self.library.sources]
        self.log("image sources, in order: " + " → ".join(order) + (" (SEED_OFFLINE=1: no downloads)" if self.library.offline else ""))

    def _problem(self, msg: str) -> None:
        self.report.problems.append(msg)
        self.log("  ✗ " + msg)

    def _images(self, key: str, queries: list[str] | tuple[str, ...], count: int, **kw: Any) -> list[ImageFile]:
        imgs = self.library.images_for(key, queries, count, **kw)
        for img in imgs:
            self.report.image_sources[img.source] += 1
            if key not in self.report.usage[img.sha256]:
                self.report.usage[img.sha256].append(key)
        return imgs

    @staticmethod
    def _describe(imgs: list[ImageFile]) -> str:
        parts = []
        for i in imgs:
            if i.source == "local":
                parts.append(f"local:{Path(i.path).name}")
            else:
                parts.append(f"{i.source}:{i.title[:40]!r} {i.license}")
        return "; ".join(parts)

    # ------------------------------------------------------------------------------------------------ sellers
    def step_sellers(self) -> None:
        self.log("== sellers and providers (seller-service POST /v1/orgs)")
        if not self.up.get("seller"):
            self.log("  ! seller-service is down: buy boxes show org ids instead of store names "
                     "(start it: tools/dev/local.sh restart seller)")
            self.report.add("sellers", "skipped", len(D.SELLERS) - 1)
            return
        orgs = [(s.org_id, "SELLER", "PRODUCTS", s.legal, s.name) for s in D.SELLERS if s.org_id != D.RETAIL_ORG]
        orgs += [(p.org_id, "PROVIDER", "SERVICES", p.legal, p.name) for p in D.PROVIDERS]
        for org_id, kind, offering, legal, name in orgs:
            try:
                res = self.svc["seller"].request("POST", "/v1/orgs", json_body={
                    "id": org_id, "kind": kind, "offering": offering, "legalName": legal, "displayName": name,
                    "country": "US"})
                self.report.add("sellers", "created" if res.status == 201 else "existing")
            except ApiError as e:
                if e.status == 404 and e.code == "seller.provisioning-off":
                    self._problem("seller-service has provisioning off (at.seller.provisioning-enabled)")
                    return
                self._problem(f"seller {name}: {e}")
        self.log(f"  ✓ {len(orgs)} organisations (Stripe Connect is not set up for them: payouts need a Stripe test "
                 "key and onboarding in Seller Center)")

    # ------------------------------------------------------------------------------------------------ products
    def _categories(self) -> dict[str, dict[str, Any]]:
        return {c["path"]: c for c in self.svc["catalog"].get("/v1/categories")}

    def _find_product(self, title: str) -> dict[str, Any] | None:
        page = self.svc["catalog"].get("/v1/products", params={"q": title, "size": 100})
        for p in (page or {}).get("items", []):
            if p.get("title") == title and p.get("status") not in ("REJECTED", "TAKEN_DOWN"):
                return p
        return None

    def step_products(self) -> None:
        self.log(f"== products ({len(D.PRODUCTS)}): catalog → approve → offers → inventory")
        cats = self._categories()
        departments = sorted({c["path"].split(" › ")[0] for c in cats.values()})
        self.log(f"  departments in catalog: {', '.join(departments)}")
        types: dict[str, dict[str, Any]] = {}
        for p in D.PRODUCTS:
            try:
                self._product(p, cats, types)
            except (ApiError, ValueError, KeyError) as e:
                self._problem(f"product {p.key}: {e}")
            self.state.save()
        self.library.save()

    def _product(self, p: D.Product, cats: dict[str, Any], types: dict[str, Any]) -> None:
        cat = cats.get(p.path)
        if not cat:
            raise ValueError(f"category {p.path!r} not in the catalog tree")
        code = cat["productType"]
        if code not in types:
            types[code] = self.svc["catalog"].get(f"/v1/product-types/{code}")
        ptype = types[code]
        product = self._find_product(p.title)
        if product:
            self.report.add("products", "existing")
            line = f"  · {p.title} (exists, {product['atin']})"
            self._images(p.key, p.queries, p.images, orientation="square", main_min_edge=MAIN_MIN_EDGE, network=False)
        else:
            imgs = self._images(p.key, p.queries, p.images, orientation="square", main_min_edge=MAIN_MIN_EDGE)
            if not imgs:
                self.report.add("products", "skipped: no image")
                self._problem(f"{p.title}: no usable image (needs a main image of at least {MAIN_MIN_EDGE}px; "
                              "set SEED_IMAGES_DIR or allow downloads)")
                return
            assets = [self.uploader.upload(i, "product-image") for i in imgs]
            theme, options = theme_for(ptype, p.variants)
            seller = D.SELLER_BY_KEY[p.seller]
            variants = []
            for opts, (_, price) in zip(options, p.variants):
                v = {"options": opts, "sellerSku": sku_for(seller.key, p.key, opts), "priceMinor": price, "quantity": 0}
                if ptype.get("identifierRule") == "ISBN":
                    v["gtin"], v["gtinExempt"] = p.attrs["gtin"], False
                else:
                    v["gtinExempt"] = True
                variants.append(v)
            body = {"categoryId": cat["id"], "title": p.title,
                    "attributes": attributes_for(p, ptype, [i.credit() for i in imgs if i.source != "local"]),
                    "images": [{k: a[k] for k in ("url", "width", "height", "bytes", "contentType")} for a in assets],
                    "variationTheme": theme, "variants": variants, "currency": "USD"}
            product = self.svc["catalog"].post("/v1/products", body)
            self.report.add("products", "created")
            line = f"  ✓ {p.title} ({product['atin']}) images: {self._describe(imgs)}"
        if product["status"] == "IN_REVIEW":
            product = self.svc["catalog"].post(f"/v1/products/{product['id']}/approve")
        if product["status"] != "LIVE":
            self._problem(f"{p.title} is {product['status']} (not LIVE)")
            return
        self.products[p.key] = product
        offers = self._offers(p, product)
        self._stock(p, product, offers)
        self.log(line)

    # ------------------------------------------------------------------------------------------------ offers
    def _list_offers(self, org: str, atin: str, withdrawn: bool = False) -> list[dict[str, Any]]:
        page = self.svc["offers"].get("/v1/offers", params={"sellerOrgId": org, "atin": atin, "size": 50})
        return [o for o in (page or {}).get("items", []) if o.get("atin") == atin
                and (withdrawn or o.get("status") != "WITHDRAWN")]

    def _wait_retail_offer(self, atin: str, timeout: float = 30) -> dict[str, Any] | None:
        """offers creates AfroTransact Retail's offer from catalog.product.created (Kafka): wait for it (an earlier
        run may have withdrawn it already)."""
        deadline = time.time() + timeout
        while True:
            found = self._list_offers(D.RETAIL_ORG, atin, withdrawn=True)
            if found or time.time() > deadline:
                found.sort(key=lambda o: o.get("status") == "WITHDRAWN")
                return found[0] if found else None
            time.sleep(1)

    def _offers(self, p: D.Product, product: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
        """Creates the sellers' offers; returns {seller key: [offer per variant]} of the offers that need FBA stock."""
        main = D.SELLER_BY_KEY[p.seller]
        sellers = [(main, 1.0)] + [(D.SELLER_BY_KEY[k], f) for k, f in p.also]
        retail_wanted = any(s.org_id == D.RETAIL_ORG for s, _ in sellers)
        stocked: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for v, (_, price) in zip(product["variants"], p.variants):
            retail = self._wait_retail_offer(v["atin"])
            for seller, factor in sellers:
                fulfilment = p.fulfilment or seller.fulfilment
                price_minor = price if factor == 1.0 else int(round(price * factor / 10.0) * 10) - 1
                listp = int(round(price_minor * (1 + p.deal) / 10.0) * 10) - 1 if p.deal and seller is main else None
                if seller.org_id == D.RETAIL_ORG:
                    if retail is None or retail.get("status") == "WITHDRAWN":
                        raise ValueError(f"AfroTransact Retail has no active offer for {v['atin']}")
                    patch = {"inventorySku": v["atin"], "priceMinor": price_minor, "version": retail["version"]}
                    if listp:
                        patch["listPriceMinor"] = listp
                    if retail.get("inventorySku") != v["atin"] or retail.get("priceMinor") != price_minor or (
                            listp and retail.get("listPriceMinor") != listp):
                        retail = self.svc["offers"].patch(f"/v1/offers/{retail['id']}", patch)
                    stocked[seller.key].append(retail)
                    self.report.add("offers", "ready")
                    continue
                existing = self._list_offers(seller.org_id, v["atin"])
                if existing:
                    offer = existing[0]
                    if offer.get("priceMinor") != price_minor or (listp and offer.get("listPriceMinor") != listp):
                        patch = {"priceMinor": price_minor, "version": offer["version"]}
                        if listp:
                            patch["listPriceMinor"] = listp
                        offer = self.svc["offers"].patch(f"/v1/offers/{offer['id']}", patch)
                        self.report.add("offers", "repriced")
                    else:
                        self.report.add("offers", "existing")
                else:
                    body: dict[str, Any] = {
                        "atin": v["atin"], "sellerOrgId": seller.org_id, "sellerSku": v["sellerSku"] if seller is main
                        else sku_for(seller.key, p.key, v.get("options") or {}), "inventorySku": v["atin"],
                        "condition": "NEW", "priceMinor": price_minor, "currency": "USD", "fulfilment": fulfilment,
                        "shippingMinor": 0, "quantity": 0, "handlingDays": 1 if fulfilment == "AFROTRANSACT" else 2}
                    if listp:
                        body["listPriceMinor"] = listp
                    if fulfilment == "SELLER":
                        body["quantity"] = p.stock
                        body["shippingMinor"] = 599
                        body["shipFrom"] = {"city": seller.city, "region": seller.region, "country": "US",
                                            "postalCode": seller.zip}
                    offer = self.svc["offers"].post("/v1/offers", body)
                    self.report.add("offers", "created")
                if fulfilment == "AFROTRANSACT":
                    stocked[seller.key].append(offer)
            if retail is not None and not retail_wanted and retail.get("status") == "ACTIVE":
                self.svc["offers"].post(f"/v1/offers/{retail['id']}/withdraw")
                self.report.add("offers", "retail auto-offer withdrawn")
        return stocked

    # ------------------------------------------------------------------------------------------------ inventory
    def _stock(self, p: D.Product, product: dict[str, Any], stocked: dict[str, list[dict[str, Any]]]) -> None:
        today = dt.datetime.now(market_zone()).date()
        food = product.get("productType") in FOOD_TYPES
        main = D.SELLER_BY_KEY[p.seller]
        used_nodes: set[str] = set()
        for key, offers in sorted(stocked.items(), key=lambda kv: kv[0] != main.key):
            seller = D.SELLER_BY_KEY[key]
            # FBA stock of one SKU is never mixed between owners at a node: other sellers use another FC.
            node = next(n for n in (seller.node, *FC_NODES) if n not in used_nodes)
            used_nodes.add(node)
            qty = p.stock if seller is main else max(6, p.stock // 3)
            lines = []
            for i, (offer, (_, price)) in enumerate(zip(offers, p.variants)):
                line = {"sku": offer["atin"], "lotNo": f"SEED-{offer['atin']}-{seller.key.upper()[:6]}",
                        "qtyGood": qty, "qtyDamaged": 0, "currency": "USD",
                        "unitLandedCostMinor": max(1, int(price * 0.55)), "bin": f"A-{i + 1:02d}"}
                if food:
                    line.update({"mfgDate": (today - dt.timedelta(days=7)).isoformat(),
                                 "expDate": (today + dt.timedelta(days=533)).isoformat(), "shelfLifeDays": 540})
                lines.append(line)
            if not lines:
                continue
            ref = f"SEED-{product['atin']}-{seller.key.upper()}"[:80]
            body: dict[str, Any] = {"receiptRef": ref, "nodeId": node, "lines": lines}
            if seller.org_id != D.RETAIL_ORG:
                body["ownerOrg"] = seller.org_id
            try:
                res = self.svc["inventory"].post("/v1/receipts", body, headers={"Idempotency-Key": ref})
                self.report.add("inventory receipts", "replayed" if (res or {}).get("replayed") else "received")
            except ApiError as e:
                if e.code != "inventory.idempotency-key-reused":  # received by an earlier run (another day's dates)
                    raise
                self.report.add("inventory receipts", "replayed")

    # ------------------------------------------------------------------------------------------------ content
    def _upload_campaign_image(self, key: str, queries: list[str], orientation: str = "landscape") -> str | None:
        imgs = self._images(key, queries, 1, orientation=orientation, main_min_edge=300)
        if not imgs:
            self.log(f"  ! {key}: no image found (the live placement keeps its current image, if any)")
            return None
        asset = self.uploader.upload(imgs[0], "storefront")
        self.log(f"    {key}: {self._describe(imgs)}")
        return asset["url"]

    def _placements(self) -> list[dict[str, Any]]:
        return self.svc["content"].get("/v1/placements") or []

    def _ensure_placement(self, slot: str, name: str, payload: dict[str, Any], priority: int,
                          replaces: str | None = None, categories: list[str] | None = None) -> None:
        """Creates (or replaces, when the payload changed) a LIVE placement named `name`; ends what it supersedes."""
        seeded = f"{name} · demo seed"
        current = self._placements()
        mine = [p for p in current if p.get("name") == seeded and p.get("status") in ("LIVE", "SCHEDULED", "IN_REVIEW", "DRAFT")]
        same = next((p for p in mine if p.get("payload") == payload and p.get("status") in ("LIVE", "SCHEDULED")), None)
        if same:
            self.report.add("placements", "existing")
            keep = same["id"]
        else:
            body = {"slot": slot, "name": seeded, "payload": payload, "priority": priority, "paid": False,
                    "schedule": {"startsAt": _iso(_now() - dt.timedelta(minutes=5))},
                    "targeting": {"markets": ["US"], "communities": [], "zipPrefixes": [], "dmas": [], "devices": [],
                                  "audience": "ALL", "categories": categories or []},
                    "frequencyCap": {"impressions": 0, "windowHours": 0}}
            created = self.svc["content"].post("/v1/placements", body)
            self.svc["content"].post(f"/v1/placements/{created['id']}/submit")
            approved = self.svc["content"].post(f"/v1/placements/{created['id']}/approve")
            keep = approved["id"]
            self.report.add("placements", "created")
        for p in current:
            if p["id"] == keep:
                continue
            if (p.get("name") == seeded or (replaces and p["id"] == replaces)) and p.get("status") in ("LIVE", "SCHEDULED"):
                self.svc["content"].post(f"/v1/placements/{p['id']}/end")
                self.report.add("placements", "superseded (ended)")

    def _live_payload(self, name: str) -> dict[str, Any]:
        """Payload of the LIVE seeded placement `name` (keeps its images when this run finds none)."""
        seeded = f"{name} · demo seed"
        for p in self._placements():
            if p.get("name") == seeded and p.get("status") in ("LIVE", "SCHEDULED"):
                return p.get("payload") or {}
        return {}

    def step_content(self) -> None:
        self.log("== home content (content placements: hero carousel, tiles, banners, rails, category banners)")
        if not self.up.get("content"):
            self.log("  ! content-service is down: skipped")
            return
        for h in D.HEROES:
            key, queries = h["image"]
            url = self._upload_campaign_image(key, queries) or self._live_payload(h["name"]).get("imageUrl")
            self._ensure_placement("hero_carousel", h["name"], dict(h["payload"], imageUrl=url), h["priority"],
                                   h["replaces"])
        for t in D.TILES:
            before = {c.get("label"): c.get("imageUrl") for c in self._live_payload(t["name"]).get("cells", [])}
            cells = []
            for label, href, key, queries in t["cells"]:
                url = self._upload_campaign_image(key, queries, orientation="square") or before.get(label)
                cells.append({"label": label, "href": href, "imageUrl": url})
            self._ensure_placement("home_tiles", t["name"], dict(t["payload"], cells=cells), t["priority"], t["replaces"])
        for b in D.BANNERS:
            key, queries = b["image"]
            url = self._upload_campaign_image(key, queries) or self._live_payload(b["name"]).get("imageUrl")
            self._ensure_placement("display_banner", b["name"], dict(b["payload"], imageUrl=url), b["priority"],
                                   b["replaces"])
        for r in D.RAILS:
            payload = dict(r["payload"])
            if r.get("deals"):
                atins = [self.products[p.key]["atin"] for p in D.PRODUCTS if p.deal and p.key in self.products]
                if not atins:
                    self.log("  · deals rail skipped (run the products step first)")
                    continue
                payload["skus"] = atins
            self._ensure_placement("home_product_row", r["name"], payload, r["priority"])
        for c in D.CATEGORY_BANNERS:
            key, queries = c["image"]
            url = self._upload_campaign_image(key, queries) or self._live_payload(c["name"]).get("imageUrl")
            payload = dict(c["payload"], imageUrl=url)
            self._ensure_placement("category_banner", c["name"], payload, 10, categories=[c["category"]])
        self.log("  ✓ placements live")

    # ------------------------------------------------------------------------------------------------ ads
    def step_ads(self) -> None:
        self.log("== sponsored products (content ads campaigns)")
        if not self.up.get("content"):
            return
        existing = {c.get("name") for c in self.svc["content"].get("/v1/ads/campaigns") or []}
        for ad in D.ADS:
            seller = D.SELLER_BY_KEY[ad["seller"]]
            name = f"{ad['name']} · demo seed"
            if name in existing:
                self.report.add("ad campaigns", "existing")
                continue
            atins = [self.products[k]["atin"] for k in ad["products"] if k in self.products]
            if not atins:
                atins = [p["atin"] for k in ad["products"] if (p := self._find_product(
                    next(x.title for x in D.PRODUCTS if x.key == k)))]
            body = {"sellerOrgId": seller.org_id, "name": name, "advertiser": seller.name, "market": "US",
                    "bidMinor": ad["bid"], "dailyBudgetMinor": max(500, ad["budget"] // 10),
                    "totalBudgetMinor": ad["budget"], "currency": "USD", "keywords": ad["keywords"],
                    "categories": ad["categories"], "atins": atins, "startsAt": _iso(_now() - dt.timedelta(minutes=5)),
                    "endsAt": _iso(_now() + dt.timedelta(days=60))}
            try:
                self.svc["content"].post("/v1/ads/campaigns", body)
                self.report.add("ad campaigns", "created")
            except ApiError as e:
                self._problem(f"ad campaign {name}: {e}")

    # ------------------------------------------------------------------------------------------------ pre-orders
    def step_preorders(self) -> None:
        self.log("== pre-orders (delivery-runs: coverage areas, windows, creatives)")
        dr = self.svc["delivery-runs"]
        if not self.up.get("delivery-runs"):
            self.log("  ! delivery-runs is down: skipped")
            return
        for w in D.PREORDERS:
            self._ensure_area(w["target"])
        windows = (dr.get("/v1/preorders", params={"size": 100}) or {}).get("items", [])
        web = self.env.get("PUBLIC_WEB_URL", "http://localhost:3000").rstrip("/")
        for w in D.PREORDERS:
            try:
                self._preorder(dr, w, windows, web)
            except ApiError as e:
                self._problem(f"pre-order {w['name']}: {e}")

    def _ensure_area(self, key: str) -> None:
        dr = self.svc["delivery-runs"]
        country, region, _ = key.split("/", 2)
        page = dr.get("/v1/preorder-areas", params={"country": country, "region": region, "size": 100}) or {}
        row = next((r for r in page.get("rows", []) if r.get("key") == key), None)
        if row is None:
            dr.post("/v1/preorder-areas", {"key": key})
            self.report.add("coverage areas", "added")
        elif not row.get("on"):
            dr.patch("/v1/preorder-areas", {"keys": [key], "on": True})
            self.report.add("coverage areas", "switched on")
        else:
            self.report.add("coverage areas", "already on")

    def window_body(self, w: dict[str, Any], web: str) -> dict[str, Any]:
        """The PreorderWindowRequest of a dataset window, its dates relative to today in the market's time zone."""
        tz = market_zone()
        today = dt.datetime.now(tz).date()
        opens = dt.datetime.combine(today + dt.timedelta(days=w["opens_in_days"]), dt.time(9, 0), tz)
        closes = dt.datetime.combine(opens.date() + dt.timedelta(days=w["open_days"]), dt.time(23, 59), tz)
        delivers = closes.date() + dt.timedelta(days=w["deliver_after_days"])
        fmt = {"open": f"{opens:%a, %b} {opens.day}", "close": f"{closes:%b} {closes.day}",
               "deliver": f"{delivers:%A, %b} {delivers.day}", "url": f"{web}/pre-orders"}
        return {"name": w["name"], "targets": [w["target"]], "opensAt": _iso(opens.astimezone(dt.timezone.utc)),
                "closesAt": _iso(closes.astimezone(dt.timezone.utc)), "deliversOn": delivers.isoformat(),
                "ribbon": w["ribbon"].format(**fmt)[:80], "headline": w["headline"].format(**fmt)[:140],
                "smsCopy": w["sms"].format(**fmt)[:160], "categories": D.PREORDER_CATEGORIES, "door": True,
                "pickup": True, "pickupPoint": w["pickup_point"], "showCount": True,
                "comms": ["ANNOUNCE", "REMIND_48H", "LAST_CALL_6H", "CLOSED", "OUT_FOR_DELIVERY"],
                "timeZone": PREORDER_TIME_ZONE}

    def _preorder(self, dr: Http, w: dict[str, Any], windows: list[dict[str, Any]], web: str) -> None:
        live = [x for x in windows if x.get("name") == w["name"] and x.get("status") in
                ("DRAFT", "PAUSED", "UPCOMING", "OPEN")]
        if live:
            window = dr.get(f"/v1/preorders/{live[0]['id']}")  # the list leaves creatives out
            self.report.add("pre-order windows", "existing")
        else:
            window = dr.post("/v1/preorders", self.window_body(w, web))
            self.report.add("pre-order windows", "created")
        have = {c.get("slot") for c in window.get("creatives") or [] if c.get("assetId") or c.get("url")}
        for slot, spec in (("HERO", w["hero"]), ("PDP", w["pdp"])):
            if slot in have:
                continue
            key, queries = spec
            imgs = self._images(key, queries, 1, orientation="landscape", main_min_edge=300)
            if not imgs:
                self.log(f"  ! {w['name']} {slot}: no image")
                continue
            asset = self.uploader.upload(imgs[0], "storefront")
            window = dr.put(f"/v1/preorders/{window['id']}/creatives/{slot}", {"assetId": asset["id"]})
            self.log(f"    {w['name']} {slot}: {self._describe(imgs)}")
        if window.get("status") == "DRAFT" or window.get("draft"):
            window = dr.post(f"/v1/preorders/{window['id']}/publish")
        self.log(f"  ✓ {window['name']} ({window.get('code')}): {window.get('status')} · "
                 f"{window.get('statusLabel', '')} · delivers {window.get('deliversOn')}")

    # ------------------------------------------------------------------------------------------------ bookings
    def step_bookings(self) -> None:
        self.log("== service providers (bookings)")
        if not self.up.get("bookings"):
            self.log("  · bookings is not running: providers' services skipped (tools/dev/local.sh restart bookings, "
                     "then make seed again)")
            self.report.add("services", "skipped (bookings down)", sum(len(p.services) for p in D.PROVIDERS))
            return
        bk = self.svc["bookings"]
        hours = {"days": {d: [{"start": "10:00", "end": "18:00"}] for d in
                          ("TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")}}
        for prov in D.PROVIDERS:
            try:
                bk.put(f"/v1/providers/{prov.org_id}/settings", {
                    "displayName": prov.name, "timeZone": "America/Chicago", "currency": "USD", "location": prov.location,
                    "rules": {"leadMinutes": 720, "horizonDays": 45, "bufferMinutes": 15, "stepMinutes": 30,
                              "maxPerDay": 6, "instantBooking": True, "autoAcceptReschedule": False}})
                team = bk.get(f"/v1/providers/{prov.org_id}/team") or []
                if not any(m.get("displayName") == prov.member for m in team):
                    bk.post(f"/v1/providers/{prov.org_id}/team", {"displayName": prov.member, "role": "OWNER",
                                                                  "bookable": True, "active": True, "hours": hours})
                have = {s.get("name"): s for s in bk.get(f"/v1/providers/{prov.org_id}/services") or []}
                for name, desc, category, mins, price, deposit, modes in prov.services:
                    svc = have.get(name)
                    if svc is None:
                        svc = bk.post(f"/v1/providers/{prov.org_id}/services", {
                            "name": name, "description": desc, "category": category, "durationMinutes": mins,
                            "priceMinor": price, "depositMinor": deposit, "modes": list(modes)})
                        self.report.add("services", "created")
                    else:
                        self.report.add("services", "existing")
                    if not svc.get("active"):
                        bk.post(f"/v1/providers/{prov.org_id}/services/{svc['id']}/activate")
                self.log(f"  ✓ {prov.name}: {len(prov.services)} services")
            except ApiError as e:
                self._problem(f"provider {prov.name}: {e}")

    # ------------------------------------------------------------------------------------------------ users
    def step_users(self) -> None:
        self.log(f"== test users (identity, local profile: {len(D.TEST_USERS)} @afrotransact.test accounts, "
                 f"password {D.TEST_USER_PASSWORD})")
        if not self.up.get("identity"):
            self.log("  ! identity is down: skipped")
            return
        try:
            checks = TestUsers(self.svc["identity"], self.env, self.log).run()
        except (ApiError, KeyError, ValueError) as e:
            self.log(f"  · test users not checked: {e} (they exist on identity's local profile only)")
            return
        for c in checks:
            self.report.add("test users", "sign in" if c.ok else "problem")
            if not c.ok:
                self._problem(f"test user {c.email}: {c.note}")
        for line in TestUsers.table([(c.email, c.persona, c.app) for c in checks]):
            self.log(line)
        self.log("  staff answer a texted two-step code: open Mailpit http://localhost:8025 (tick \"Trust this "
                 "browser\" to skip it for 30 days)")

    # ------------------------------------------------------------------------------------------------ summary
    def _summary(self) -> None:
        self.log("")
        self.log("──────────────────────────── seed summary ────────────────────────────")
        by_what: dict[str, list[str]] = defaultdict(list)
        for (what, outcome), n in sorted(self.report.counts.items()):
            by_what[what].append(f"{n} {outcome}")
        for what, parts in by_what.items():
            self.log(f"  {what:<20} {', '.join(parts)}")
        sources = ", ".join(f"{n} from {s}" for s, n in self.report.image_sources.most_common()) or "none"
        self.log(f"  {'images':<20} {sources} · uploads: {self.uploader.uploaded} new, {self.uploader.reused} reused")
        self.log(f"  attribution: {self.state_dir / 'ATTRIBUTION.md'}")
        if self.report.problems:
            self.log(f"  {len(self.report.problems)} problem(s):")
            for p in self.report.problems:
                self.log("   - " + p)
        self.log("──────────────────────────────────────────────────────────────────────")


def load_env() -> dict[str, str]:
    return dict(os.environ)


def dump(obj: Any) -> str:
    return json.dumps(obj, indent=1, default=str)
