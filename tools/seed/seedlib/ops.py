"""The `ops` step: operations demo data through the services' APIs (seedlib/ops_dataset.py describes it).

AfroTransact Retail (suppliers, items, purchase orders in every state with shipments, receipts and three-way matched
invoices), inventory (FBA inbound shipments in every stage, short-dated, held and damaged lots, cycle counts, a
transfer), orders in every state placed by the test customers and moved through the fulfilment centre, carriers and
AfroTransact Delivery (routes, the driver's day, an SOS, a vehicle defect, a seller pickup), returns, an A-to-z claim
and a payout request.

Customers check out, sellers pack and the driver drives with their own tokens (locally the services act as the person
whose token a call carries); staff work runs without a token, as the local super admin. Payments are authorised by the
payments service's local test stand-in for Stripe (or Stripe test mode when a key is set). Every record is looked up
by a stable key first, so a re-run creates nothing new; a scenario left half-way by an interrupted run is taken on to
its target state.
"""
from __future__ import annotations

import datetime as dt
import re
import time
import uuid
import zlib
from collections.abc import Callable
from typing import Any

from . import dataset as D
from . import ops_dataset as O
from .http import ApiError, Http
from .users import SignedIn

PO_ORDER = ("DRAFT", "PENDING_APPROVAL", "APPROVED", "SENT", "CONFIRMED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED")
STAGES = ("BOOKED", "CARGO_READY", "PICKED_UP", "EXPORT_CLEARED", "DEPARTED", "ARRIVED_PORT", "CUSTOMS_CLEARED",
          "DELIVERED_TO_FC", "RECEIVED")
SHIP_ORDER = ("pending_payment", "placed", "confirmed", "packed", "ready", "picked_up", "in_transit",
              "out_for_delivery", "delivered")
TARGET_STATUS = {"placed": "placed", "picking": "confirmed", "packed": "packed", "ready": "ready",
                 "shipped": "in_transit", "out_for_delivery": "out_for_delivery", "delivered": "delivered",
                 "cancelled": "cancelled"}
SHORT_DATED = "Short-dated"


def _today() -> dt.date:
    return dt.date.today()


def _iso(d: dt.date) -> str:
    return d.isoformat()


def _now_iso(delta: dt.timedelta = dt.timedelta()) -> str:
    return (dt.datetime.now(dt.timezone.utc) + delta).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def stable(text: str, digits: int) -> str:
    """A number of `digits` digits derived from text (the same on every run, unlike hash())."""
    return f"{zlib.crc32(text.encode()) % 10 ** digits:0{digits}d}"


def items_of(page: Any) -> list[dict[str, Any]]:
    """The records of a list answer: a page {items: [...]}, or a bare list."""
    if isinstance(page, list):
        return page
    if isinstance(page, dict):
        return page.get("items") or page.get("rows") or []
    return []


class OpsSeeder:
    """Seeds the operations dataset. `svc` maps service names to Http clients; `signed_in` gives test users' tokens."""

    def __init__(self, svc: dict[str, Http], signed_in: SignedIn | None, find_product: Callable[[str], Any],
                 log: Callable[[str], None], add: Callable[[str, str], None], problem: Callable[[str], None],
                 sleep: Callable[[float], None] = time.sleep, wait: float = 1.0, up: dict[str, bool] | None = None):
        self.svc, self.users, self.find_product = svc, signed_in, find_product
        self.up = up
        self.log, self.add, self.problem, self.sleep, self.wait = log, add, problem, sleep, wait
        self.products: dict[str, dict[str, Any]] = {}
        self.items: dict[str, dict[str, Any]] = {}  # product key -> retail item
        self.suppliers: dict[str, dict[str, Any]] = {}  # code -> supplier
        self.orders: dict[str, dict[str, Any]] = {}  # order key -> order view
        self.offer_ids: dict[tuple[str, str], str] = {}
        self._local_payments: bool | None = None

    # ------------------------------------------------------------------------------------------------ helpers
    def product(self, key: str) -> dict[str, Any]:
        if key not in self.products:
            title = next(p.title for p in D.PRODUCTS if p.key == key)
            found = self.find_product(title)
            if not found:
                raise ValueError(f"product {key} is not in the catalog (run the products step first)")
            self.products[key] = found
        return self.products[key]

    def atin(self, key: str, variant: int = 0) -> str:
        return self.product(key)["variants"][variant]["atin"]

    def headers(self, email: str) -> dict[str, str]:
        if self.users is None:
            raise ValueError("identity is not reachable: test users cannot sign in")
        return self.users.headers(email)

    def poll(self, what: str, fn: Callable[[], Any], timeout: float = 30) -> Any:
        """Calls fn until it returns something truthy (events between services take a moment)."""
        deadline = time.time() + timeout
        while True:
            got = fn()
            if got or time.time() > deadline:
                return got
            self.sleep(self.wait)

    def part(self, name: str, fn: Callable[[], None], needs: tuple[str, ...] = ()) -> None:
        down = [n for n in needs if self.up is not None and not self.up.get(n)]
        if down:
            self.log(f"  · {name} skipped: {', '.join(down)} not running (tools/dev/local.sh restart <service>)")
            return
        try:
            fn()
        except (ApiError, ValueError, KeyError, StopIteration) as e:
            self.problem(f"ops {name}: {e}")

    def run(self) -> None:
        self.part("retail", self.retail)
        self.part("inventory", self.inventory)
        self.part("orders", self.place_orders)
        self.part("fulfilment", self.fulfil)
        self.part("returns and claims", self.returns_and_claims, ("tax-returns", "trust-ops"))
        self.part("dispatch", self.dispatch, ("geo", "seller"))
        self.part("returns", self.finish_returns, ("tax-returns",))
        self.part("payouts", self.payouts)

    def missing(self) -> bool:
        """True when the operations data is not there yet (`make up` seeds it then)."""
        codes = {s.get("code") for s in items_of(self.svc["retail"].get("/v1/suppliers", params={"size": 200}))}
        if any(s.code not in codes for s in O.SUPPLIERS):
            return True
        page = self.svc["orders"].get("/v1/admin/orders", params={"limit": 1})
        return int((page or {}).get("total") or 0) == 0

    # ------------------------------------------------------------------------------------------------ retail
    def retail(self) -> None:
        self.log("  · AfroTransact Retail: suppliers, items, purchase orders")
        retail = self.svc["retail"]
        existing = {s["code"]: s for s in items_of(retail.get("/v1/suppliers", params={"size": 200}))}
        for sup in O.SUPPLIERS:
            s = existing.get(sup.code)
            if s is None:
                s = retail.post("/v1/suppliers", {
                    "code": sup.code, "name": sup.name, "country": sup.country, "city": sup.city,
                    "currency": sup.currency, "paymentTerms": sup.terms, "leadTimeDays": sup.lead_days,
                    "certifications": list(sup.certifications),
                    "contacts": [{"name": sup.contact[0], "role": sup.contact[1], "email": sup.contact[2]}]})
                self.add("retail suppliers", "created")
            else:
                self.add("retail suppliers", "existing")
            for status, reason in (("APPROVED", O.SUPPLIERS[0].reason), (sup.status, sup.reason)):
                if s["status"] != status and not (status == "APPROVED" and s["status"] == "ON_WATCH"):
                    s = retail.post(f"/v1/suppliers/{s['id']}/status", {"status": status, "reason": reason})
            self.suppliers[sup.code] = s
        for it in O.ITEMS:
            self.items[it.product] = self._item(it)
        for po in O.PURCHASE_ORDERS:
            try:
                self._purchase_order(po)
            except ApiError as e:
                self.problem(f"purchase order {po.key}: {e}")
        # Listing an item LIVE points AfroTransact Retail's offer at the stock received under the retail SKU, so only
        # items with received stock are listed (the others keep selling the catalog seed's stock).
        received = {ln["sku"] for po in items_of(retail.get("/v1/purchase-orders", params={"size": 200}))
                    for ln in po.get("lines") or [] if ln.get("qtyReceived", 0) > 0}
        for it in O.ITEMS:
            item = self.items[it.product]
            if item["sku"] in received and item.get("listingStatus") != "LIVE":
                self.items[it.product] = retail.post(f"/v1/items/{item['id']}/listing", {"status": "LIVE"})
                self.add("retail items", "listed (offer sells the received stock)")

    def _item(self, it: O.Item) -> dict[str, Any]:
        retail = self.svc["retail"]
        atin = self.atin(it.product, it.variant)
        for found in items_of(retail.get("/v1/items", params={"q": atin, "size": 20})):
            if found.get("atin") == atin:
                self.add("retail items", "existing")
                return found
        p = next(x for x in D.PRODUCTS if x.key == it.product)
        sup = O.SUPPLIER_BY_CODE[it.supplier]
        name = self.product(it.product)["title"]
        variant = p.variants[it.variant]
        pack = variant[0] if isinstance(variant[0], str) else " ".join(variant[0].values())
        body = {"name": name, "category": p.path.split(" › ")[0], "atin": atin,
                "primarySupplierId": self.suppliers[it.supplier]["id"],
                "supplierItems": [{"supplierId": self.suppliers[it.supplier]["id"], "supplierSku": it.supplier_sku,
                                   "casePack": it.case_pack, "minOrderQty": it.case_pack, "unitCostMinor": it.unit_cost,
                                   "currency": sup.currency}],
                "originCountry": sup.country, "hsCode": it.hs_code, "dutyRatePercent": 4.5, "storage": it.storage,
                "reorderPoint": it.reorder_point, "packSize": pack, "innerPack": 1, "casePack": it.case_pack,
                "unitWeightGrams": 0, "priceMinor": variant[1],
                "standardCostMinor": int(round(it.unit_cost / sup.fx)), "shelfLifeDays": it.shelf_life_days}
        item = retail.post("/v1/items", body)
        self.add("retail items", "created")
        return item

    def _po_signature(self, lines: list[tuple[str, int]]) -> tuple[tuple[str, int], ...]:
        return tuple(sorted(lines))

    def _find_po(self, po: O.PurchaseOrder) -> dict[str, Any] | None:
        want = self._po_signature([(self.items[ln.product]["sku"], ln.qty) for ln in po.lines])
        sup = self.suppliers[po.supplier]
        for found in items_of(self.svc["retail"].get("/v1/purchase-orders",
                                                     params={"supplierId": sup["id"], "size": 200})):
            got = self._po_signature([(ln["sku"], ln["qtyOrdered"]) for ln in found.get("lines") or []])
            if got == want:
                return found
        return None

    def _purchase_order(self, po: O.PurchaseOrder) -> None:
        retail = self.svc["retail"]
        sup = O.SUPPLIER_BY_CODE[po.supplier]
        found = self._find_po(po)
        if found is None:
            today = _today()
            found = retail.post("/v1/purchase-orders", {
                "supplierId": self.suppliers[po.supplier]["id"], "destinationNodeId": O.DESTINATION,
                "incoterm": ("FOB " if po.mode.startswith("OCEAN") else "FCA ") + sup.city,
                "orderDate": _iso(today + dt.timedelta(days=po.order_in_days)),
                "expectedDate": _iso(today + dt.timedelta(days=po.order_in_days + sup.lead_days)),
                "fxRate": sup.fx,
                "lines": [{"itemId": self.items[ln.product]["id"], "qty": ln.qty} for ln in po.lines]})
            self.add("purchase orders", "created")
        else:
            self.add("purchase orders", "existing")
        found = self._drive_po(found, po)
        self.log(f"    {found['number']} {po.key}: {found['status']}")

    def _drive_po(self, found: dict[str, Any], po: O.PurchaseOrder) -> dict[str, Any]:
        retail = self.svc["retail"]
        pid = found["id"]
        if po.target == "CANCELLED":
            if found["status"] == "DRAFT":
                found = retail.post(f"/v1/purchase-orders/{pid}/submit")
            if found["status"] == "PENDING_APPROVAL":
                found = retail.post(f"/v1/purchase-orders/{pid}/approve", {"note": "Approved by the buyer"})
            if found["status"] not in ("CANCELLED",):
                found = retail.post(f"/v1/purchase-orders/{pid}/cancel", {"note": po.note})
            return found
        target = PO_ORDER.index(po.target) if po.target in PO_ORDER else 0
        steps = (("PENDING_APPROVAL", "submit", None), ("APPROVED", "approve", {"note": po.note or "Approved"}),
                 ("SENT", "send", None), ("CONFIRMED", "confirm", {"note": "Confirmed by the supplier"}))
        for status, action, body in steps:
            if PO_ORDER.index(status) > min(target, PO_ORDER.index("CONFIRMED")):
                break
            if PO_ORDER.index(found["status"]) < PO_ORDER.index(status):
                found = retail.post(f"/v1/purchase-orders/{pid}/{action}", body)
        if po.shipment:
            shipment = self._shipment(found, po)
            if po.receive > 0 and shipment is not None:
                self._receive_po(found, po, shipment)
            found = retail.get(f"/v1/purchase-orders/{pid}")
        if po.invoice:
            self._invoice(found, po)
            found = retail.get(f"/v1/purchase-orders/{pid}")
        if po.target == "CLOSED" and found["status"] in ("RECEIVED", "PARTIALLY_RECEIVED"):
            found = retail.post(f"/v1/purchase-orders/{pid}/close")
        return found

    def _shipment(self, found: dict[str, Any], po: O.PurchaseOrder) -> dict[str, Any] | None:
        retail = self.svc["retail"]
        existing = items_of(retail.get("/v1/shipments", params={"poId": found["id"], "size": 20}))
        if existing:
            shipment = existing[0]
        else:
            share = po.receive if 0 < po.receive < 1 else 1.0
            today = _today()
            etd = today + dt.timedelta(days=po.order_in_days + 7)
            lines = []
            for i, ln in enumerate(po.lines):
                qty = max(1, int(ln.qty * share))
                lines.append({"poLineNo": i + 1, "qty": qty, "lotNo": self._lot_no(po, i),
                              "mfgDate": _iso(today - dt.timedelta(days=40)),
                              "expDate": _iso(self._expiry(po, i))})
            shipment = retail.post("/v1/shipments", {
                "poId": found["id"], "mode": po.mode, "carrier": {"AIR": "Ethiopian Cargo", "ROAD": "Estes"}.get(
                    po.mode, "Maersk"), "trackingRef": f"{po.supplier[4:]}{stable(po.key, 8)}",
                "originPort": {"NG": "LOS", "GH": "TEM", "ET": "ADD", "JM": "KIN"}[O.SUPPLIER_BY_CODE[po.supplier]
                                                                                     .country],
                "destinationPort": "HOU", "etd": _iso(etd), "eta": _iso(etd + dt.timedelta(days=12)),
                "lines": lines})
            self.add("retail shipments", "booked")
        want = STAGES.index(po.shipment)
        for _ in range(len(STAGES)):
            stage = shipment.get("stage")
            if stage not in STAGES or STAGES.index(stage) >= want:
                break
            try:
                shipment = retail.post(f"/v1/shipments/{shipment['id']}/advance")
            except ApiError as e:
                missing = re.search(r"missing \[(.*)\]", str(e)) if e.code == "retail.documents-missing" else None
                if not missing:
                    raise
                for doc in (d.strip() for d in missing.group(1).split(", ") if d.strip()):
                    retail.post(f"/v1/shipments/{shipment['id']}/documents", {
                        "name": doc, "status": "OK", "reference": f"{doc[:3].upper()}-{stable(shipment['id'] + doc, 7)}"})
                    self.add("customs documents", "filed")
                shipment = retail.post(f"/v1/shipments/{shipment['id']}/advance")
        return shipment

    def _lot_no(self, po: O.PurchaseOrder, line: int) -> str:
        return f"{po.supplier[4:]}{_today():%y%m}-{po.key[:3].upper()}{line + 1}"

    def _expiry(self, po: O.PurchaseOrder, line: int) -> dt.date:
        days = po.expiry_days[line] if line < len(po.expiry_days) else 0
        if not days:
            it = next(i for i in O.ITEMS if i.product == po.lines[line].product)
            days = (it.shelf_life_days or 1825) - 40
        return _today() + dt.timedelta(days=days)

    def _receive_po(self, found: dict[str, Any], po: O.PurchaseOrder, shipment: dict[str, Any]) -> None:
        retail = self.svc["retail"]
        receipts = items_of(retail.get("/v1/receipts", params={"poId": found["id"], "size": 20}))
        receipt = next((r for r in receipts if r.get("shipmentId") == shipment["id"]), None)
        if receipt and receipt.get("status") == "COMPLETED":
            return
        # Inventory opens the inbound notice of the ASN from retail's shipment events (Kafka); receiving before it
        # exists would leave the notice with nothing received, so wait for it like a dock would.
        ref = shipment.get("reference")
        inv = self.svc["inventory"]
        self.poll(f"inbound notice {ref}", lambda: any(
            s.get("number") == ref for s in items_of(inv.get("/v1/inbound/shipments", params={
                "source": "RETAIL_ASN", "size": 200}))), timeout=60)
        if receipt is None:
            receipt = retail.post("/v1/receipts", {"shipmentId": shipment["id"], "poId": found["id"], "dock": "Dock 3"})
            self.add("retail receipts", "opened")
        lines = []
        for rl in receipt.get("lines") or []:
            i = int(rl.get("poLineNo") or rl.get("lineNo")) - 1
            storage = next(it.storage for it in O.ITEMS if it.product == po.lines[i].product)
            zone = {"CHILLED": "K", "FROZEN": "K", "HAZMAT": "H"}.get(storage, "R")
            lines.append({"lineNo": rl["lineNo"], "poLineNo": rl.get("poLineNo"),
                          "lotNo": rl.get("asnLotNo") or self._lot_no(po, i),
                          "mfgDate": _iso(_today() - dt.timedelta(days=40)), "expDate": _iso(self._expiry(po, i)),
                          "qtyGood": rl.get("expectedQty") or 0, "qtyDamaged": 0, "bin": f"{zone}-{i + 1:02d}-01",
                          "qa": "PASS", "scanned": True})
        retail.put(f"/v1/receipts/{receipt['id']}/lines", {"lines": lines})
        retail.post(f"/v1/receipts/{receipt['id']}/complete", headers={"Idempotency-Key": f"seed-ops-grn-{receipt['id']}"})
        self.add("retail receipts", "completed")

    def _invoice(self, found: dict[str, Any], po: O.PurchaseOrder) -> None:
        retail = self.svc["retail"]
        if items_of(retail.get("/v1/invoices", params={"poId": found["id"], "size": 20})):
            self.add("supplier invoices", "existing")
            return
        lines = []
        for i, (ln, pl) in enumerate(zip(po.lines, found.get("lines") or [])):
            price = int(round(pl["unitCostMinor"] * (1 + ln.invoice_price_pct / 100.0)))
            lines.append({"poLineNo": pl["lineNo"], "qty": pl["qtyReceived"], "unitPriceMinor": price})
        number = f"INV-{po.supplier[4:]}-{stable(po.key, 5)}"
        inv = retail.post("/v1/invoices", {"poId": found["id"], "invoiceNumber": number,
                                           "invoiceDate": _iso(_today() - dt.timedelta(days=2)),
                                           "currency": O.SUPPLIER_BY_CODE[po.supplier].currency,
                                           "fxRate": O.SUPPLIER_BY_CODE[po.supplier].fx, "lines": lines},
                          headers={"Idempotency-Key": f"seed-ops-invoice-{found['id']}"})
        self.add("supplier invoices", f"registered ({inv.get('status', '?').lower()})")

    # ------------------------------------------------------------------------------------------------ inventory
    def inventory(self) -> None:
        self.log("  · Inventory: FBA inbound shipments, lots, cycle counts, a transfer")
        for ib in O.INBOUND:
            try:
                self._inbound(ib)
            except ApiError as e:
                self.problem(f"inbound {ib.name}: {e}")
        self._release_short_dated()
        self._counts()
        self._transfer()

    def _inbound(self, ib: O.Inbound) -> None:
        inv = self.svc["inventory"]
        seller = D.SELLER_BY_KEY[ib.owner]
        found = next((s for s in items_of(inv.get("/v1/inbound/shipments", params={
            "ownerOrg": seller.org_id, "size": 200})) if s.get("name") == ib.name and s.get("status") != "CANCELLED"),
            None)
        today = _today()
        if found is None:
            found = inv.post("/v1/inbound/shipments", {
                "ownerOrg": seller.org_id, "name": ib.name, "nodeId": seller.node,
                "expectedArrival": _iso(today + dt.timedelta(days=ib.arrives_in_days)),
                "shipFrom": {"name": seller.name, "line1": "1200 Commerce St", "city": seller.city,
                             "region": seller.region, "postalCode": seller.zip, "country": "US"},
                "lines": [{"atin": self.atin(ln.product, ln.variant), "qty": ln.qty, "casePack": 12,
                           "lotNo": ln.lot, "mfgDate": _iso(today - dt.timedelta(days=ln.made_days_ago)),
                           "expDate": _iso(today + dt.timedelta(days=ln.expires_in_days))} for ln in ib.lines]})
            self.add("inbound shipments", "created")
        else:
            self.add("inbound shipments", "existing")
        sid = found["id"]
        if ib.target == "WORKING":
            return
        if found["status"] == "WORKING":
            if ib.tracking:
                inv.put(f"/v1/inbound/shipments/{sid}/tracking", {"carrier": ib.carrier, "trackingNumber": ib.tracking})
            found = inv.post(f"/v1/inbound/shipments/{sid}/ship")
        if ib.target == "SHIPPED" or found["status"] in ("CLOSED", "CANCELLED"):
            return
        if found["status"] == "SHIPPED":
            lines = []
            for ln, fl in zip(ib.lines, found.get("lines") or []):
                good = ln.qty - ln.damaged if ln.received < 0 else ln.received
                if good <= 0 and not ln.damaged:
                    continue
                line = {"sku": fl.get("sku") or self.atin(ln.product, ln.variant), "qtyGood": good,
                        "qtyDamaged": ln.damaged, "lotNo": ln.lot,
                        "mfgDate": _iso(today - dt.timedelta(days=ln.made_days_ago)),
                        "expDate": _iso(today + dt.timedelta(days=ln.expires_in_days)),
                        "bin": f"F-{stable(ln.lot, 2)}-01"}
                if ln.hold:
                    line["holdReason"] = ln.hold
                lines.append(line)
            found = inv.post(f"/v1/inbound/shipments/{sid}/receipts", {"lines": lines},
                             headers={"Idempotency-Key": f"seed-ops-inbound-{sid}"})
            found = found.get("shipment") or inv.get(f"/v1/inbound/shipments/{sid}")
            self.add("inbound receipts", "received")
        if ib.target == "CLOSED" and found.get("status") == "RECEIVING":
            inv.post(f"/v1/inbound/shipments/{sid}/close")
            self.add("inbound shipments", "closed")

    def _seeded_lots(self) -> list[dict[str, Any]]:
        inv = self.svc["inventory"]
        lot_nos = {ln.lot for ib in O.INBOUND for ln in ib.lines}
        lot_nos |= {self._lot_no(po, i) for po in O.PURCHASE_ORDERS for i in range(len(po.lines))}
        out = []
        for status in ("QUARANTINE", "SELLABLE", "DAMAGED", "EXPIRED"):
            for lot in items_of(inv.get("/v1/lots", params={"status": status, "size": 500})):
                if lot.get("lotNo") in lot_nos or (lot.get("lotNo") or "").replace("-DMG", "") in lot_nos:
                    out.append(lot)
        return out

    def _release_short_dated(self) -> None:
        """Short-dated lots are quarantined at receiving; the buyer accepts most of them for markdown. The lot that
        expires tomorrow stays on hold (the nightly expiry run marks it EXPIRED)."""
        inv = self.svc["inventory"]
        soon = _today() + dt.timedelta(days=7)
        for lot in self._seeded_lots():
            if lot.get("status") != "QUARANTINE" or not (lot.get("reason") or "").startswith(SHORT_DATED):
                continue
            if lot.get("expDate") and dt.date.fromisoformat(lot["expDate"]) <= soon:
                continue
            inv.post(f"/v1/lots/{lot['id']}/release", {"reason": O.ACCEPT_SHORT_DATED_REASON})
            self.add("lots", "short-dated released for markdown")
        try:
            inv.post("/v1/lots/markdown-runs")
        except ApiError as e:
            self.log(f"    markdown flags not recomputed: {e}")

    def _counts(self) -> None:
        inv = self.svc["inventory"]
        lots = [x for x in self._seeded_lots() if x.get("status") == "SELLABLE"]
        for i, key in enumerate(O.COUNT_SKUS):
            lot = next((x for x in lots if x.get("lotNo") in {ln.lot for ib in O.INBOUND for ln in ib.lines
                                                              if ln.product == key}), None)
            if lot is None:
                self.log(f"    cycle count of {key}: no received lot yet")
                continue
            note = f"Demo count · {key}"
            mine = [c for c in items_of(inv.get("/v1/cycle-counts", params={"sku": lot["sku"], "size": 50}))
                    if c.get("note") == note and c.get("status") != "CANCELLED"]
            if mine:
                self.add("cycle counts", "existing")
                continue
            count = inv.post("/v1/cycle-counts", {"nodeId": lot["nodeId"], "sku": lot["sku"], "blind": False,
                                                  "note": note})
            entries = []
            for ln in count.get("lines") or []:
                variance = (-2 if i == 0 else -1) if ln.get("lotId") == lot["id"] else 0
                entry = {"lineNo": ln["lineNo"], "countedQty": max(0, (ln.get("expectedQty") or 0) + variance)}
                if variance:
                    entry.update({"reasonCode": "DAMAGED" if i == 0 else "LOST",
                                  "note": "Two crushed bags behind the case" if i == 0 else "One jar not found in bin"})
                entries.append(entry)
            count = inv.put(f"/v1/cycle-counts/{count['id']}/counts", {"lines": entries})
            if i > 0:
                count = inv.post(f"/v1/cycle-counts/{count['id']}/post")
            self.add("cycle counts", "posted" if i > 0 else "counted, open for review")

    def _transfer(self) -> None:
        inv = self.svc["inventory"]
        product, variant, to_node, qty, reason = O.TRANSFER
        atin = self.atin(product, variant)
        moves = items_of(inv.get("/v1/movements", params={"type": "TRANSFER", "size": 200}))
        if any((m.get("reason") or "").endswith(reason) for m in moves):
            self.add("transfers", "existing")
            return
        lots = [x for x in items_of(inv.get("/v1/lots", params={"nodeId": "HOU-FC", "status": "SELLABLE",
                                                                  "size": 500}))
                if (x.get("sku") == atin or x.get("lotNo", "").startswith("MPP-GAR")) and x.get("qtyAvailable", 0) > qty]
        if not lots:
            self.log("    transfer skipped: no sellable garri at HOU-FC")
            return
        inv.post(f"/v1/lots/{lots[0]['id']}/transfer", {"toNodeId": to_node, "qty": qty, "reason": reason},
                 headers={"Idempotency-Key": f"seed-ops-transfer-{lots[0]['id']}"})
        self.add("transfers", "created")

    # ------------------------------------------------------------------------------------------------ orders
    def all_orders(self) -> list[O.Order]:
        return list(O.ORDERS) + [O.SELLER_PICKUP["order"]]

    def _offer_id(self, line: O.Line) -> str:
        p = next(x for x in D.PRODUCTS if x.key == line.product)
        seller = D.SELLER_BY_KEY[line.seller or p.seller]
        atin = self.atin(line.product, line.variant)
        key = (atin, seller.org_id)
        if key not in self.offer_ids:
            offers = [o for o in items_of(self.svc["offers"].get("/v1/offers", params={
                "atin": atin, "sellerOrgId": seller.org_id, "size": 20})) if o.get("status") == "ACTIVE"]
            if line.seller and line.product == O.SELLER_PICKUP["product"]:
                offers = [o for o in offers if o.get("fulfilment") == "AFROTRANSACT_DELIVERY"]
            if not offers:
                raise ValueError(f"{seller.name} has no active offer for {line.product}")
            self.offer_ids[key] = offers[0]["id"]
        return self.offer_ids[key]

    def _seller_pickup_offer(self) -> None:
        """Mama Put's kitchen as an AfroTransact Delivery pickup location, and an offer it ships from there (S255)."""
        cfg = O.SELLER_PICKUP
        seller = D.SELLER_BY_KEY[cfg["seller"]]
        sel = self.svc["seller"]
        zones = (self.svc["geo"].get("/v1/zones", params={"type": "DELIVERY"}) or {}).get("zones") or []
        if not any(z.get("name") == O.DELIVERY_ZONE["name"] for z in zones):
            self.svc["geo"].post("/v1/zones", dict(O.DELIVERY_ZONE))
            self.add("delivery zones", "created")
        addresses = items_of(sel.get(f"/v1/orgs/{seller.org_id}/ship-from-addresses"))
        address = next((a for a in addresses if a.get("line1") == cfg["address"]["line1"]), None)
        if address is None:
            created = sel.post(f"/v1/orgs/{seller.org_id}/ship-from-addresses", dict(cfg["address"]))
            address = next(a for a in items_of(created) or [created] if a.get("line1") == cfg["address"]["line1"])
            self.add("seller pickup locations", "created")
        if not (address.get("afrotransactPickup") or {}).get("enabled"):
            body = {"enabled": True, "hours": cfg["hours"]}
            if address.get("version") is not None:
                body["version"] = address["version"]
            sel.put(f"/v1/orgs/{seller.org_id}/ship-from-addresses/{address['id']}/afrotransact-pickup", body)
        self._seller_customer_pickup(seller.org_id, address, cfg)
        atin = self.atin(cfg["product"])
        offers = items_of(self.svc["offers"].get("/v1/offers", params={"atin": atin, "sellerOrgId": seller.org_id,
                                                                      "size": 20}))
        if any(o.get("fulfilment") == "AFROTRANSACT_DELIVERY" and o.get("status") != "WITHDRAWN" for o in offers):
            return
        a = cfg["address"]
        self.svc["offers"].post("/v1/offers", {
            "atin": atin, "sellerOrgId": seller.org_id, "sellerSku": "MAMA-SUYA-KITCHEN", "condition": "NEW",
            "priceMinor": cfg["price"], "currency": "USD", "fulfilment": "AFROTRANSACT_DELIVERY", "shippingMinor": 0,
            "quantity": cfg["stock"], "handlingDays": 1,
            "shipFrom": {"city": a["city"], "region": a["state"], "country": "US", "postalCode": a["postalCode"],
                         "locationId": address["id"]}})
        self.add("offers", "seller pickup offer created")

    def _seller_customer_pickup(self, org_id: str, address: dict[str, Any], cfg: dict[str, Any]) -> None:
        """Customers can also collect Mama Put's orders at the kitchen: a SELLER pickup location with the address id."""
        cg = self.svc.get("carrier-gateway")
        if cg is None or (self.up is not None and not self.up.get("carrier-gateway", True)):
            return
        a = cfg["address"]
        days = {"SUNDAY": 0, "MONDAY": 1, "TUESDAY": 2, "WEDNESDAY": 3, "THURSDAY": 4, "FRIDAY": 5, "SATURDAY": 6}
        position = address.get("position") or {}
        body = {"kind": "SELLER", "ownerOrg": org_id, "name": f"{a['name']} · {a['label']}", "timezone": "America/Chicago",
                "address": {k: a[k] for k in ("line1", "line2", "city", "state", "postalCode", "country")},
                "hours": [{"weekday": days[h["day"]], "open": h["opens"], "close": h["closes"]} for h in cfg["hours"]],
                "readyHours": 3, "active": True}
        if position.get("lat") is not None and position.get("lng") is not None:
            body["position"] = {"lat": position["lat"], "lng": position["lng"]}
        try:
            cg.put(f"/v1/pickup-locations/{address['id']}", body)
            self.add("seller pickup points", "saved")
        except ApiError as e:
            self.problem(f"seller pickup point: {e}")

    def _my_orders(self, email: str) -> list[dict[str, Any]]:
        out, cursor = [], None
        for _ in range(20):
            page = self.svc["orders"].get("/v1/orders", params={"limit": 50, "cursor": cursor},
                                          headers=self.headers(email))
            out += items_of(page)
            cursor = (page or {}).get("nextCursor")
            if not cursor:
                break
        return out

    def _find_order(self, order: O.Order) -> dict[str, Any] | None:
        for o in self._my_orders(order.customer):
            a = o.get("attribution") or {}
            if a.get("src") == O.SEED_SOURCE and a.get("cr") == order.key:
                return o
        return None

    def place_orders(self) -> None:
        self.log("  · Orders: the test customers check out")
        self.part("seller pickup offer", self._seller_pickup_offer)
        for order in self.all_orders():
            try:
                found = self._find_order(order)
                if found is None:
                    found = self._checkout(order)
                    self.add("orders", "placed")
                else:
                    self.add("orders", "existing")
                self.orders[order.key] = found
            except (ApiError, ValueError) as e:
                self.problem(f"order {order.key}: {e}")
        if self.orders:
            self.log("    payments: " + ("the local test stand-in (no Stripe key)" if self.local_payments()
                                         else "Stripe test mode"))

    def local_payments(self) -> bool:
        """True when payments run on the local test stand-in (no Stripe key; its PaymentIntents are pi_localtest_…),
        so payouts may use a test Connect account. Decided from a seeded order's payment."""
        if self._local_payments is None:
            for o in self.orders.values():
                # The customer's view has no payment id; the staff lookup has.
                payment_id = (self.svc["orders"].get(f"/v1/lookup/orders/{o['orderNumber']}") or {}).get("paymentId")
                if payment_id:
                    p = self.svc["payments"].get(f"/v1/payments/{payment_id}") or {}
                    self._local_payments = str(p.get("stripePaymentIntentId") or "").startswith("pi_localtest_")
                    break
        return bool(self._local_payments)

    def _checkout(self, order: O.Order) -> dict[str, Any]:
        h = self.headers(order.customer)
        address = items_of(self.svc["identity"].get("/v1/me/addresses", headers=h))[0]
        loc = {"country": address.get("country") or "US", "region": address.get("state"), "city": address.get("city")}
        lines, atins_by_pkg = [], []
        cart = None
        for pkg in order.packages:
            pkg_atins = []
            for line in pkg.lines:
                atin = self.atin(line.product, line.variant)
                cart = self.svc["cart"].post("/v1/cart/items", {"variantAtin": atin, "offerId": self._offer_id(line),
                                                                "quantity": line.qty}, params=loc, headers=h)
                lines.append({"variantAtin": atin, "quantity": line.qty})
                pkg_atins.append(atin)
            atins_by_pkg.append(pkg_atins)
        cart = self.svc["cart"].get("/v1/cart", params=loc, headers=h) or cart
        selections = []
        for pkg, atins in zip(order.packages, atins_by_pkg):
            cp = next((c for c in cart.get("packages") or []
                       if any(ln.get("variantAtin") in atins for ln in c.get("lines") or [])), None)
            if cp is None:
                raise ValueError(f"{order.key}: the cart has no package for {atins}")
            if order.preorder and not cp["packageId"].startswith("preorder:"):
                raise ValueError(f"{order.key}: not a pre-order at {address.get('city')} (is the window open?)")
            selections.append({"packageId": cp["packageId"], "option": pkg.option})
        body = {"addressId": address["id"], "deliverySelections": selections, "lines": lines,
                "attribution": {"src": O.SEED_SOURCE, "cr": order.key}}
        key = f"seed-ops:{order.key}:{stable(repr(lines), 6)}"
        for attempt in range(1, 4):
            # The same key replays the same checkout; one that failed (e.g. a service was down) keeps failing under
            # its key, so a new attempt gets a numbered one.
            try:
                view = self.svc["orders"].post("/v1/checkout", body, headers=dict(
                    h, **{"Idempotency-Key": key if attempt == 1 else f"{key}:{attempt}"}))
                break
            except ApiError as e:
                if attempt == 3 or e.status not in (409, 422) or e.code == "orders.idempotency-key-reused":
                    raise
        number = view["orderNumber"]
        for _ in range(10):
            if view.get("status") in ("placed", "failed", "cancelled"):
                break
            if view.get("status") == "awaiting_payment":
                view = self.svc["orders"].post(f"/v1/checkout/{number}/confirm", headers=h)  # the test card
            else:
                self.sleep(self.wait)
                view = self.svc["orders"].get(f"/v1/checkout/{number}", headers=h)
        if view.get("status") != "placed":
            raise ValueError(f"{order.key}: checkout {number} ended {view.get('status')} "
                             f"({view.get('failureMessage') or view.get('reason') or ''})")
        self.log(f"    {number} {order.key}: placed by {order.customer.split('@')[0]}")
        return self.poll(f"order {number}", lambda: self._find_order(order), timeout=20) or view

    # ------------------------------------------------------------------------------------------------ fulfilment
    def _order_view(self, order: O.Order) -> dict[str, Any]:
        found = self._find_order(order)
        if found is None:
            raise ValueError(f"order {order.key} was not placed")
        self.orders[order.key] = found
        return found

    def _shipment_of(self, view: dict[str, Any], pkg: O.Package) -> dict[str, Any]:
        atin = self.atin(pkg.lines[0].product, pkg.lines[0].variant)
        return next(s for s in view.get("shipments") or []
                    if any(ln.get("variantAtin") == atin for ln in s.get("lines") or []))

    def _tasks(self) -> dict[str, dict[str, Any]]:
        return {t["shipmentId"]: t for t in items_of(self.svc["fulfilment"].get("/v1/fc/tasks", params={"limit": 500}))}

    def _transition(self, shipment_id: str, to: str, headers: dict[str, str] | None = None, reason: str = "") -> None:
        body = {"to": to}
        if reason:
            body["reason"] = reason
        self.svc["orders"].post(f"/v1/shipments/{shipment_id}/transitions", body,
                                headers=dict(headers or {}, **{"Idempotency-Key": f"seed-ops:{shipment_id}:{to}"}))
        self.add("shipment steps", to)

    def fulfil(self) -> None:
        self.log("  · Fulfilment: the FC picks and packs, carriers move parcels, sellers pack their own")
        self._ensure_local_connected_accounts()
        tasks = self._tasks()
        for order in self.all_orders():
            try:
                view = self._order_view(order)
                for pkg in order.packages:
                    self._drive_shipment(order, view, pkg, tasks)
                view = self._order_view(order)
                self.log(f"    {view['orderNumber']} {order.key}: " + ", ".join(
                    f"{s.get('sellerName')} {s.get('status')}" for s in view.get("shipments") or []))
            except (ApiError, ValueError, StopIteration) as e:
                self.problem(f"fulfilment of {order.key}: {e}")

    def _drive_shipment(self, order: O.Order, view: dict[str, Any], pkg: O.Package,
                        tasks: dict[str, dict[str, Any]]) -> None:
        s = self._shipment_of(view, pkg)
        sid, status = s["id"], s.get("status")
        want = TARGET_STATUS[pkg.target]
        if status == want or status in ("cancelled", "delivered", "returned") or order.preorder:
            return
        if want == "cancelled":
            self.svc["orders"].post(f"/v1/orders/{view['orderNumber']}/shipments/{sid}/cancel",
                                    {"reason": "Ordered the wrong size; will reorder"},
                                    headers=dict(self.headers(order.customer),
                                                 **{"Idempotency-Key": f"seed-ops:cancel:{sid}"}))
            self.add("shipment steps", "cancelled by the customer")
            return
        rank = SHIP_ORDER.index
        local = pkg.option == "afrotransact_delivery"
        stop_at = min(rank(want), rank("ready")) if local else rank(want)
        if s.get("fulfilmentType") == "SELLER_FULFILLED":
            actor = O.SELLER_FULFILMENT_ACTORS.get(s.get("sellerOrgId") or "")
            if not actor:
                return
            h = self.headers(actor)
            for to in ("confirmed", "packed", "ready"):
                if rank(status) < rank(to) <= stop_at:
                    self._transition(sid, to, h)
                    status = to
            if pkg.tracking and not s.get("trackingNumber") and not local:
                self.svc["orders"].post(f"/v1/shipments/{sid}/tracking", {"carrier": pkg.carrier,
                                                                         "trackingNumber": pkg.tracking}, headers=h)
            return
        task = tasks.get(sid) or self.poll(f"FC task of {sid}", lambda: self._tasks().get(sid), timeout=20)
        if task is None:
            raise ValueError(f"no fulfilment-centre task for shipment {sid} of {order.key}")
        fc = self.svc["fulfilment"]
        tid = task["id"]
        if stop_at >= rank("confirmed") and task["status"] == "PICK_PENDING":
            task = fc.post(f"/v1/fc/tasks/{tid}/pick/start")
        if stop_at >= rank("packed"):
            if task["status"] in ("PICKING", "PICK_STARTED", "IN_PROGRESS") or (
                    task["status"] not in ("PICKED", "PACKED", "READY", "SHIPPED")):
                task = fc.post(f"/v1/fc/tasks/{tid}/pick", {
                    "picked": {ln["variantAtin"]: ln["quantity"] for ln in task.get("lines") or []}})
            if task["status"] == "PICKED":
                body: dict[str, Any] = {"weightLb": 2.5 * sum(ln["quantity"] for ln in task.get("lines") or [])}
                if not local and pkg.tracking:
                    body.update({"carrier": pkg.carrier, "trackingNumber": pkg.tracking})
                task = fc.post(f"/v1/fc/tasks/{tid}/pack", body)
        if stop_at >= rank("ready") and task["status"] == "PACKED":
            task = fc.post(f"/v1/fc/tasks/{tid}/ready")
        self.add("FC tasks", task["status"].lower())
        if local or stop_at <= rank("ready"):
            return
        status = self.poll(f"shipment {sid} ready", lambda: self._status(view, pkg) == "ready" and "ready",
                           timeout=30) or self._status(view, pkg)
        for to in ("picked_up", "in_transit", "out_for_delivery", "delivered"):
            if rank(to) > stop_at:
                break
            if rank(status) < rank(to):
                if to == "delivered" and order.settle_early:
                    self._settle_early(view, sid)
                self._transition(sid, to)
                status = to

    def _status(self, view: dict[str, Any], pkg: O.Package) -> str:
        fresh = self.svc["orders"].get(f"/v1/lookup/orders/{view['orderNumber']}")
        return self._shipment_of(fresh, pkg).get("status") or ""

    def _settle_early(self, view: dict[str, Any], sid: str) -> None:
        """Payout demo: the shipment's settlement hold starts as if it had been delivered 8 days ago, so its 7-day hold
        has matured and the seller can request a payout today. Only on the local test payments (no real money)."""
        if not self.local_payments():
            return
        self._test_connected_account()
        self.svc["payments"].post(f"/v1/settlements/{view['id']}/shipments/{sid}/delivered", {
            "deliveredAt": _now_iso(-dt.timedelta(days=8)), "kind": "PRODUCT"})
        self.add("settlements", "hold started as matured (payout demo)")

    # ------------------------------------------------------------------------------------------------ dispatch
    def dispatch(self) -> None:
        self.log("  · Delivery dispatch: routes, the driver's day, an SOS, a vehicle defect, a seller pickup waiting")
        fc = self.svc["fulfilment"]
        driver = self.users.claims(O.DRIVER)["sub"] if self.users else None
        if not driver:
            raise ValueError("the driver test user cannot sign in")
        if not any(c.get("id") == O.DESTINATION for c in items_of(fc.get("/v1/centres"))):
            fc.put(f"/v1/centres/{O.DESTINATION}", dict(O.DEPOT))
            self.add("fulfilment centres", "created")
        routes = items_of(fc.get("/v1/dispatch/routes", params={"limit": 200}))
        for key, (name, days, window, vehicle) in O.ROUTES.items():
            orders = [o for o in self.all_orders() if any(p.route == key for p in o.packages)]
            route = next((r for r in routes if r.get("name") == name and r.get("status") != "CANCELLED"), None)
            if route is None:
                ids = []
                for o in orders:
                    view = self._order_view(o)
                    ids += [self._shipment_of(view, p)["id"] for p in o.packages if p.route == key]
                route = fc.post("/v1/dispatch/routes", {
                    "name": name, "zone": "Houston SW", "serviceDate": _iso(_today() + dt.timedelta(days=days)),
                    "window": window, "depotNodeId": O.DESTINATION, "shipmentIds": ids})
                self.add("routes", "created")
            else:
                self.add("routes", "existing")
            if key == "returns" and route.get("status") == "PLANNED":
                self._route_return_pickups(route)
            if route.get("status") == "PLANNED":
                route = fc.post(f"/v1/dispatch/routes/{route['id']}/assign", {"driverId": driver, "vehicleId": vehicle})
            route = fc.get(f"/v1/dispatch/routes/{route['id']}")
            if key == "returns":
                self._returns_run(route, vehicle)
            elif key == "today":
                self._driver_day(route, vehicle)
            else:
                self._defect(route, vehicle)
            route = fc.get(f"/v1/dispatch/routes/{route['id']}")
            self.log(f"    {name}: {route.get('status')}, " + ", ".join(
                f"{s.get('kind', '').lower()} {s.get('status', '').lower()}" for s in route.get("stops") or []))
        waiting = [p for p in items_of(fc.get("/v1/dispatch/pickups", params={"routed": "false"}))
                   if (p.get("shipment") or {}).get("pickupLocationId")]
        self.log(f"    seller pickups waiting for a route: {len(waiting)}")

    def _event(self, route: dict[str, Any], seq: int, kind: str, at: dt.datetime, stop: str | None = None,
               **payload: Any) -> dict[str, Any]:
        e = {"eventId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"seed-ops/{route['id']}/{seq}")), "sequence": seq,
             "type": kind, "routeId": route["id"], "capturedAt": at.strftime("%Y-%m-%dT%H:%M:%SZ"),
             "lat": 29.7316, "lon": -95.5603}
        if stop:
            e["stopId"] = stop
        e.update(payload)
        return e

    @staticmethod
    def _photo(route: dict[str, Any], at: dt.datetime, lat: float, lon: float) -> dict[str, Any]:
        return {"assetId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"seed-photo/{route['id']}/{at.isoformat()}")),
                "source": "LIVE_CAMERA", "capturedAt": at.strftime("%Y-%m-%dT%H:%M:%SZ"), "lat": lat, "lon": lon,
                "accuracyM": 6, "stampRouteId": route["id"], "sha256": "9f2c"}

    def _inspection(self, route: dict[str, Any], at: dt.datetime, vehicle: str, failing: str = "") -> dict[str, Any]:
        checks = {c: ("FAIL" if c == failing else "PASS") for c in (
            "TYRES", "BRAKES", "LIGHTS", "MIRRORS", "HORN_WIPERS", "NO_LEAKS", "CARGO_AREA", "COLD_BOX", "SAFETY_KIT",
            "PHONE_MOUNT")}
        return {"vehicleId": vehicle, "checks": checks, "odometerMiles": 48222, "fuelPercent": 75, "coldBoxF": 37.0,
                "photos": {slot: self._photo(route, at, 29.6447, -95.2797) for slot in ("FRONT", "BACK", "LEFT",
                                                                                        "RIGHT")},
                "defectNote": f"Failed: {O.DEFECT[1]}" if failing else None, "certified": True,
                "signatureAssetId": "sig-driver"}

    def _sync(self, route: dict[str, Any], events: list[dict[str, Any]]) -> list[str]:
        res = self.svc["fulfilment"].post("/v1/driver/sync", {"deviceId": f"seed-phone-{route['id'][:8]}",
                                                              "events": events}, headers=self.headers(O.DRIVER))
        out = [r.get("outcome", "") + (":" + r["code"] if r.get("code") else "") for r in res.get("results") or []]
        bad = [f"{e['type']} {o}" for e, o in zip(events, out) if not o.startswith("APPLIED")]
        if bad:
            self.log("    driver app: " + "; ".join(bad))
        return out

    def _driver_day(self, route: dict[str, Any], vehicle: str) -> None:
        if route.get("status") != "ASSIGNED":
            self.add("driver days", "existing")
            return
        pkgs = route.get("packages") or []
        delivered_order = next(o for o in O.ORDERS if o.key == "delivered-courier")
        delivered_sid = self._shipment_of(self._order_view(delivered_order), delivered_order.packages[0])["id"]
        drop = next(p["dropoffStopId"] for p in pkgs if p["shipmentId"] == delivered_sid)
        label = next(p["label"] for p in pkgs if p["shipmentId"] == delivered_sid)
        t = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(minutes=95)
        events = [self._event(route, 1, "session.signed_in", t),
                  self._event(route, 2, "inspection.submitted", t + dt.timedelta(minutes=2),
                              inspection=self._inspection(route, t, vehicle))]
        seq = 3
        for i, p in enumerate(pkgs):
            events.append(self._event(route, seq, "loadout.scanned", t + dt.timedelta(minutes=5, seconds=10 * i),
                                      scan={"label": p["label"], "method": "CAMERA"}))
            seq += 1
        events.append(self._event(route, seq, "loadout.completed", t + dt.timedelta(minutes=8)))
        events.append(self._event(route, seq + 1, "route.started", t + dt.timedelta(minutes=10)))
        at = t + dt.timedelta(minutes=48)
        delivery = {"handoverMethod": "SAFE_PLACE", "safePlace": "Front door, behind the planter",
                    "photo": self._photo(route, at, 29.7316, -95.5603)}
        events += [self._event(route, seq + 2, "stop.arrived", at - dt.timedelta(minutes=2), drop),
                   self._event(route, seq + 3, "stop.scanned", at - dt.timedelta(minutes=1), drop,
                               scan={"label": label, "method": "CAMERA"}),
                   self._event(route, seq + 4, "delivery.completed", at, drop, delivery=delivery),
                   self._event(route, seq + 5, "sos.raised", at + dt.timedelta(minutes=30), sos=O.SOS[0],
                               note=O.SOS[1])]
        self._sync(route, events)
        self.add("driver days", "synced")

    def _route_return_pickups(self, route: dict[str, Any]) -> None:
        """Puts the waiting return pickups of the seeded DRIVER_PICKUP returns on the returns route."""
        fc = self.svc["fulfilment"]
        numbers = set()
        for order in self.all_orders():
            if order.returns and order.returns[0][2] == "DRIVER_PICKUP":
                _, rma = self._rma(order)
                if rma:
                    numbers.add(rma.get("number"))
        waiting = self.poll("return pickups", lambda: [
            p["id"] for p in items_of(fc.get("/v1/return-pickups", params={"status": "REQUESTED",
                                                                           "nodeId": O.DESTINATION, "limit": 100}))
            if p.get("rmaNumber") in numbers], timeout=20)
        if waiting:
            fc.post(f"/v1/dispatch/routes/{route['id']}/return-pickups", {"returnPickupIds": waiting})
            self.add("return pickups", "routed")

    def _returns_run(self, route: dict[str, Any], vehicle: str) -> None:
        """The driver's morning returns run: collects each return pickup, scans the parcels in at the depot."""
        if route.get("status") != "ASSIGNED":
            self.add("driver days", "existing")
            return
        stops = [s for s in route.get("stops") or [] if s.get("kind") == "RETURN_PICKUP"]
        if not stops:
            return
        t = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(hours=5)
        events = [self._event(route, 1, "session.signed_in", t),
                  self._event(route, 2, "inspection.submitted", t + dt.timedelta(minutes=2),
                              inspection=self._inspection(route, t, vehicle)),
                  self._event(route, 3, "loadout.completed", t + dt.timedelta(minutes=5)),
                  self._event(route, 4, "route.started", t + dt.timedelta(minutes=6))]
        seq, at = 5, t + dt.timedelta(minutes=30)
        for stop in stops:
            events += [self._event(route, seq, "stop.arrived", at, stop["id"]),
                       self._event(route, seq + 1, "return_pickup.collected", at + dt.timedelta(minutes=3), stop["id"])]
            seq, at = seq + 2, at + dt.timedelta(minutes=25)
        for p in (route.get("packages") or []):
            if p.get("label"):
                events.append(self._event(route, seq, "return.scanned", at, scan={"label": p["label"],
                                                                                  "method": "CAMERA"}))
                seq += 1
        events.append(self._event(route, seq, "shift.ended", at + dt.timedelta(minutes=30), odometer=48241))
        self._sync(route, events)
        self.add("driver days", "returns run synced")

    def _defect(self, route: dict[str, Any], vehicle: str) -> None:
        fc = self.svc["fulfilment"]
        if any(d.get("routeId") == route["id"] for d in items_of(fc.get("/v1/dispatch/defects"))):
            self.add("vehicle defects", "existing")
            return
        if route.get("status") != "ASSIGNED":
            return
        t = dt.datetime.now(dt.timezone.utc).replace(microsecond=0) - dt.timedelta(minutes=20)
        self._sync(route, [self._event(route, 1, "session.signed_in", t),
                           self._event(route, 2, "inspection.submitted", t + dt.timedelta(minutes=3),
                                       inspection=self._inspection(route, t, vehicle, O.DEFECT[0]))])
        self.add("vehicle defects", "reported")

    # ------------------------------------------------------------------------------------------------ returns, claims
    def returns_and_claims(self) -> None:
        self.log("  · Returns and A-to-z claims")
        for order in self.all_orders():
            try:
                if order.returns:
                    self._return(order)
                if order.claim[0]:
                    self._claim(order)
            except (ApiError, ValueError, StopIteration) as e:
                self.problem(f"return / claim of {order.key}: {e}")

    def _rma(self, order: O.Order) -> tuple[dict[str, Any], dict[str, Any] | None]:
        view = self._order_view(order)
        found = items_of(self.svc["tax-returns"].get("/v1/returns", params={"orderId": view["id"], "limit": 20},
                                                     headers=self.headers(order.customer)))
        return view, (found[0] if found else None)

    def _return(self, order: O.Order) -> None:
        """Requests the return as the customer; staff approve what does not approve itself."""
        tr = self.svc["tax-returns"]
        h = self.headers(order.customer)
        target, reason, method = order.returns[0]
        view, rma = self._rma(order)
        if rma is None:
            shipment = self._shipment_of(view, order.packages[0])
            line = shipment["lines"][0]
            eligibility = tr.get("/v1/returns/eligibility", params={"orderId": view["id"]}, headers=h)
            methods = next((ln.get("methods") for s in eligibility.get("shipments") or []
                            for ln in s.get("lines") or [] if ln.get("lineId") == line["id"]), None) or []
            if method not in methods:
                raise ValueError(f"{order.key}: {method} is not offered for {line.get('title')} ({methods})")
            rma = tr.post("/v1/returns", {
                "orderId": view["id"], "shipmentId": shipment["id"], "method": method,
                "lines": [{"lineId": line["id"], "quantity": 1, "reason": reason,
                           "comment": {"ORDERED_BY_MISTAKE": "Meant to order the 2 lb bag, not the 5 lb.",
                                       "NOT_AS_DESCRIBED": "Not what the photos show: it is a different blend."
                                       }.get(reason, "")}]},
                headers=dict(h, **{"Idempotency-Key": f"seed-ops:return:{view['id']}"}))
            self.add("returns", "requested")
        else:
            self.add("returns", "existing")
        if target in ("APPROVED", "REFUNDED") and rma.get("status") in ("REQUESTED", "PENDING_APPROVAL"):
            rma = tr.post(f"/v1/returns/{rma['id']}/approve", {"note": "Approved: sorry it was not what you expected"})
        self.log(f"    {rma.get('number')} on {view['orderNumber']}: {rma.get('status')} ({method.lower()})")

    def finish_returns(self) -> None:
        """Driver-collected returns: received at the return centre, inspected, refunded."""
        tr = self.svc["tax-returns"]
        for order in self.all_orders():
            if not order.returns or order.returns[0][0] != "REFUNDED":
                continue
            try:
                view, rma = self._rma(order)
                if rma is None:
                    continue
                rid = rma["id"]
                if rma.get("status") == "REFUNDED":
                    continue
                if rma.get("status") in ("IN_TRANSIT", "LABEL_ISSUED"):
                    rma = tr.post(f"/v1/returns/{rid}/receive")
                if rma.get("status") == "RECEIVED":
                    rma = tr.post(f"/v1/returns/{rid}/inspect", {
                        "lines": [{"lineId": ln["lineId"], "outcome": "PASS", "condition": "RESTOCK",
                                   "note": "Sealed, back to stock"} for ln in rma.get("lines") or []],
                        "note": "Inspected at the Houston return centre"})
                if rma.get("status") != "REFUNDED":
                    rma = self.poll(f"refund of {rid}", lambda: (lambda r: r if r.get("status") == "REFUNDED"
                                                                 else None)(tr.get(f"/v1/returns/{rid}")),
                                    timeout=20) or tr.get(f"/v1/returns/{rid}")
                self.add("returns", (rma.get("status") or "?").lower())
                self.log(f"    {rma.get('number')} on {view['orderNumber']}: {rma.get('status')} "
                         f"(refund {rma.get('refundState')})")
            except (ApiError, ValueError, StopIteration) as e:
                self.problem(f"return of {order.key}: {e}")

    def _claim(self, order: O.Order) -> None:
        ts = self.svc["trust-ops"]
        view = self._order_view(order)
        if items_of(ts.get("/v1/claims", params={"orderId": view["id"], "size": 20})):
            self.add("A-to-z claims", "existing")
            return
        kind, description = order.claim
        shipment = self._shipment_of(view, order.packages[0])
        line = shipment["lines"][0]
        claim = ts.post("/v1/claims", {
            "type": kind, "orderId": view["id"], "orderNumber": view["orderNumber"], "shipmentId": shipment["id"],
            "sellerOrgId": shipment["sellerOrgId"], "amountMinor": line["unitPriceMinor"], "currency": "USD",
            "description": description}, headers=self.headers(order.customer))
        self.add("A-to-z claims", "opened")
        self.log(f"    claim {claim.get('reference')} on {view['orderNumber']}: {claim.get('status')}")

    # ------------------------------------------------------------------------------------------------ payouts
    def _local_test_stripe_account_id(self, org_id: str, key: str) -> str:
        """Stable fake acct_… ids for local test payments (must match acct_[A-Za-z0-9]{6,64})."""
        slug = "".join(ch for ch in key.title() if ch.isalnum())[:24] or "Seller"
        return f"acct_1LocalTest{slug}"

    def _ensure_local_connected_accounts(self) -> None:
        """Register test Connect account ids for seeded seller-fulfilled orgs (local test payments only)."""
        if not self.local_payments():
            return
        pay = self.svc["payments"]
        for s in D.SELLERS:
            if s.org_id == D.RETAIL_ORG:
                continue
            acct = self._local_test_stripe_account_id(s.org_id, s.key)
            try:
                pay.get(f"/v1/connected-accounts/{s.org_id}")
            except ApiError as e:
                if e.status != 404:
                    raise
                pay.put(f"/v1/connected-accounts/{s.org_id}", {"stripeAccountId": acct})
                self.add("connected accounts", f"{s.name} test account")

    def payouts(self) -> None:
        """Mama Put's matured sale becomes a payout request in Finance's queue. Needs a connected account: on the local
        test payments the seeder registers a test one (acct_1LocalTest…); with Stripe test keys, onboard in Seller
        Center › Payments instead."""
        pay = self.svc["payments"]
        org = O.FINANCE_ORG
        if items_of(pay.get("/v1/payouts/requests", params={"orgId": org, "limit": 20})):
            self.add("payout requests", "existing")
            return
        if not any(o.settle_early for o in self.all_orders()) or not self.local_payments():
            self.log("    payouts: skipped (Stripe test mode: onboard the seller's Stripe account in Seller Center)")
            return
        self._ensure_local_connected_accounts()
        h = self.headers(O.SELLER_OWNER)

        def available() -> int:
            b = pay.get("/v1/payouts/balance", params={"orgId": org}, headers=h) or {}
            minor = int(b.get("availableMinor") or 0)
            return minor if minor >= int(b.get("minimumPayoutMinor") or 2500) else 0

        self.log("    payouts: waiting for the settlement job to transfer the matured sale (up to 2 minutes)")
        if not self.poll("payout balance", available, timeout=130):
            self.log("    payouts: no balance yet; run `make seed ARGS=\"--only ops\"` again in a minute")
            return
        req = pay.post("/v1/payouts/requests", {"orgId": org, "all": True, "currency": "USD"},
                       headers=dict(h, **{"Idempotency-Key": f"seed-ops:payout:{org}"}))
        self.add("payout requests", "requested")
        self.log(f"    payout request {req.get('id', '')[:8]}: {req.get('status')} ({req.get('amountMinor')} minor)")
