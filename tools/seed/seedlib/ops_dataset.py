"""The operations dataset (`ops` step): AfroTransact Retail's suppliers, items and purchase orders in every state,
inventory inbound shipments, lots, counts and a transfer, and orders in every state with their fulfilment, dispatch,
returns, claims and a payout. Product keys refer to seedlib/dataset.py; dates are relative to the day of the run.

Every record carries a stable key so a re-run finds it instead of creating it again: suppliers by code, items by ATIN,
purchase orders by supplier and lines, inbound shipments by name, orders by their attribution (`src` "demo-seed", `cr`
the order key below), routes by name, returns by order, claims by order.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Union

SEED_SOURCE = "demo-seed"  # orders' attribution source: how the ops step recognises its own orders
HOUSTON = "customer.houston@afrotransact.test"
LUBBOCK = "customer.lubbock@afrotransact.test"
SELLER_OWNER = "seller.owner@afrotransact.test"  # Mama Put Pantry
DRIVER = "driver@afrotransact.test"
FINANCE_ORG = "org_mama_put_pantry"  # the seller whose matured sale is paid out


# ------------------------------------------------------------------------------------------------ AfroTransact Retail
@dataclass(frozen=True)
class Supplier:
    code: str
    name: str
    country: str
    city: str
    currency: str
    terms: str
    lead_days: int
    fx: float  # supplier currency per USD (the PO's fx rate)
    contact: tuple[str, str, str]  # name, role, email
    status: str = "APPROVED"  # APPROVED or ON_WATCH (both can take purchase orders)
    reason: str = "Vetted: samples, references and food-safety certificates checked"
    certifications: tuple[str, ...] = ()


SUPPLIERS: list[Supplier] = [
    Supplier("SUP-LAG", "Lagos Foods Wholesale Ltd", "NG", "Lagos", "NGN", "Net 45", 42, 1550.0,
             ("Chidi Okonkwo", "Export sales", "chidi.okonkwo@lagosfoods.example"), certifications=("NAFDAC", "HACCP")),
    Supplier("SUP-KUM", "Kumasi Pantry Exports", "GH", "Kumasi", "GHS", "Net 30", 35, 15.4,
             ("Akosua Mensah", "Account manager", "akosua@kumasipantry.example"), certifications=("FDA Ghana",)),
    Supplier("SUP-ADD", "Addis Highland Coffee & Spice PLC", "ET", "Addis Ababa", "ETB", "Net 30", 40, 125.0,
             ("Selam Tesfaye", "Export manager", "selam@addishighland.example"), certifications=("ECX", "Organic EU")),
    Supplier("SUP-KGN", "Kingston Caribbean Distributors", "JM", "Kingston", "USD", "Net 30", 21, 1.0,
             ("Marcus Campbell", "Wholesale desk", "marcus@kingstondist.example"), status="ON_WATCH",
             reason="Two late deliveries this quarter: on watch until on-time rate is back above 90%"),
]
SUPPLIER_BY_CODE = {s.code: s for s in SUPPLIERS}


@dataclass(frozen=True)
class Item:
    product: str  # dataset product key (an AfroTransact Retail product)
    variant: int  # index of the variant in the product
    supplier: str  # supplier code
    supplier_sku: str
    case_pack: int
    unit_cost: int  # supplier currency, minor units
    hs_code: str
    storage: str = "AMBIENT"
    shelf_life_days: int = 540  # retail requires one; non-food items use a long one
    reorder_point: int = 60


ITEMS: list[Item] = [
    Item("at-dried-pepper", 0, "SUP-LAG", "LFW-ATA-250", 24, 288000, "0904.22.0000", shelf_life_days=540),
    Item("at-pepper-sauce", 0, "SUP-LAG", "LFW-DIN-237", 12, 372000, "2103.90.9091", shelf_life_days=365),
    Item("at-jollof-seasoning", 0, "SUP-LAG", "LFW-JOL-227", 24, 310000, "0910.91.0000", shelf_life_days=720),
    Item("braiding-hair", 0, "SUP-LAG", "LFW-BRD-26-1B", 10, 620000, "6704.11.0000", shelf_life_days=1825,
         reorder_point=40),
    Item("at-black-soap", 0, "SUP-KUM", "KPE-BLK-227", 36, 3850, "3401.11.5000", shelf_life_days=1095),
    Item("kenyan-coffee", 0, "SUP-ADD", "AHC-KEN-AA-454", 12, 98000, "0901.21.0000", shelf_life_days=365),
    Item("frozen-okra", 0, "SUP-KGN", "KCD-OKR-454", 24, 165, "0710.80.9750", storage="FROZEN", shelf_life_days=365),
    Item("at-basmati", 1, "SUP-KGN", "KCD-BAS-10", 4, 905, "1006.30.9010", shelf_life_days=730, reorder_point=80),
]

DESTINATION = "HOU-FC"


@dataclass(frozen=True)
class PoLine:
    product: str
    qty: int
    invoice_price_pct: float = 0.0  # supplier invoice price above (or below) the PO price, percent


@dataclass(frozen=True)
class PurchaseOrder:
    """A purchase order and the state the seeder takes it to:
    DRAFT · PENDING_APPROVAL · APPROVED · SENT · CONFIRMED · PARTIALLY_RECEIVED · RECEIVED · CLOSED · CANCELLED.
    `shipment` books an inbound shipment and advances it to that milestone; `receive` is the share of each line
    received at the dock (1.0 = all); `invoice` registers the supplier invoice (three-way match)."""
    key: str
    supplier: str
    lines: tuple[PoLine, ...]
    target: str
    order_in_days: int = 0  # order date relative to today (negative = in the past)
    shipment: str = ""  # milestone: BOOKED … DEPARTED … DELIVERED_TO_FC
    mode: str = "OCEAN_LCL"
    receive: float = 0.0
    invoice: bool = False
    note: str = ""
    expiry_days: tuple[int, ...] = ()  # per line: days until the received lot expires (0 = the item's shelf life)


PURCHASE_ORDERS: list[PurchaseOrder] = [
    PurchaseOrder("draft", "SUP-LAG", (PoLine("at-dried-pepper", 240), PoLine("at-pepper-sauce", 120)), "DRAFT",
                  note="Q1 pepper restock: waiting for the buyer to finish quantities"),
    PurchaseOrder("pending-approval", "SUP-KGN", (PoLine("at-basmati", 1800),), "PENDING_APPROVAL", order_in_days=-1,
                  note="Diwali and Christmas basmati: above the $10,000 Finance threshold"),
    PurchaseOrder("approved", "SUP-ADD", (PoLine("kenyan-coffee", 144),), "APPROVED", order_in_days=-2,
                  note="Approved: Kenyan AA for the holiday gift guide"),
    PurchaseOrder("sent", "SUP-LAG", (PoLine("at-jollof-seasoning", 288),), "SENT", order_in_days=-12,
                  shipment="DEPARTED", mode="AIR", note="In the air: Lagos → Houston"),
    PurchaseOrder("confirmed", "SUP-KUM", (PoLine("at-black-soap", 252),), "CONFIRMED", order_in_days=-30,
                  shipment="DELIVERED_TO_FC", note="At the Houston FC dock, waiting to be received"),
    PurchaseOrder("partially-received", "SUP-KGN", (PoLine("frozen-okra", 384), PoLine("at-basmati", 200)),
                  "PARTIALLY_RECEIVED", order_in_days=-25, shipment="DELIVERED_TO_FC", mode="OCEAN_FCL",
                  receive=0.6, expiry_days=(45, 0), note="Reefer split: the rest of the basmati comes next week"),
    PurchaseOrder("received-matched", "SUP-LAG", (PoLine("at-pepper-sauce", 144), PoLine("at-dried-pepper", 96)),
                  "RECEIVED", order_in_days=-45, shipment="DELIVERED_TO_FC", mode="AIR", receive=1.0, invoice=True,
                  expiry_days=(52, 0), note="Received and matched: ready for Finance to close"),
    PurchaseOrder("received-exception", "SUP-ADD", (PoLine("kenyan-coffee", 96, invoice_price_pct=4.0),),
                  "RECEIVED", order_in_days=-40, shipment="DELIVERED_TO_FC", mode="AIR", receive=1.0, invoice=True,
                  note="Invoice 4% above the PO price: in the buyer's variance queue"),
    PurchaseOrder("closed", "SUP-KUM", (PoLine("at-black-soap", 144),), "CLOSED", order_in_days=-60,
                  shipment="DELIVERED_TO_FC", receive=1.0, invoice=True, note="Closed: supplier payment released"),
    PurchaseOrder("cancelled", "SUP-LAG", (PoLine("braiding-hair", 200),), "CANCELLED", order_in_days=-5,
                  note="Supplier out of 26 in 1B until December"),
]


# ------------------------------------------------------------------------------------------------ inventory
@dataclass(frozen=True)
class InboundLine:
    """qty: whole cases of 12 (inventory's rule for FBA plans)."""
    product: str
    variant: int
    qty: int
    lot: str
    made_days_ago: int = 30
    expires_in_days: int = 400
    received: int = -1  # units received good (-1 = all); only for RECEIVING / CLOSED
    damaged: int = 0
    hold: str = ""  # QC hold reason: the received lot goes to quarantine


@dataclass(frozen=True)
class Inbound:
    """An FBA inbound shipment of a seller, taken to WORKING (draft) · SHIPPED (in transit, or arrived) · RECEIVING ·
    CLOSED. `arrives_in_days` 0 or less with SHIPPED = arrived at the FC, waiting to be received."""
    name: str
    owner: str  # seller key
    target: str
    lines: tuple[InboundLine, ...]
    arrives_in_days: int = 3
    carrier: str = "ups"
    tracking: str = ""


INBOUND: list[Inbound] = [
    Inbound("Mama Put · holiday pantry (draft)", "mama-put", "WORKING", (
        InboundLine("ofada-rice", 1, 48, "MPP-OFA-2611", 20, 340),
        InboundLine("pepper-soup-spice", 0, 72, "MPP-PSS-2611", 15, 520))),
    Inbound("Mama Put · garri and beans", "mama-put", "SHIPPED", (
        InboundLine("ijebu-garri", 0, 96, "MPP-GAR-2610C", 25, 300),
        InboundLine("oloyin-beans", 0, 60, "MPP-OLO-2610", 40, 480)), arrives_in_days=3, tracking="1Z84A7W90311827465"),
    Inbound("Lagos Larder · egusi and suya (arrived)", "lagos-larder", "SHIPPED", (
        InboundLine("egusi", 0, 84, "LLF-EGU-2610", 35, 330),
        InboundLine("suya-spice", 0, 60, "LLF-SUY-2610", 20, 360)), arrives_in_days=-1, carrier="fedex",
        tracking="794611023817"),
    Inbound("Mama Put · chin chin and crayfish", "mama-put", "RECEIVING", (
        InboundLine("chin-chin", 0, 120, "MPP-CHN-2610", 10, 48, received=72),
        InboundLine("ground-crayfish", 0, 48, "MPP-CRF-2610", 60, 400, received=0)), arrives_in_days=-2,
        tracking="1Z84A7W90311829911"),
    Inbound("Mama Put · October pantry", "mama-put", "CLOSED", (
        InboundLine("ijebu-garri", 1, 60, "MPP-GAR-2610A", 300, 35),
        InboundLine("ofada-rice", 0, 48, "MPP-OFA-2610", 90, 300, received=46, damaged=2),
        InboundLine("pepper-soup-spice", 0, 36, "MPP-PSS-2610", 30, 400, hold="QC hold: torn seals on a case"),
        InboundLine("oloyin-beans", 1, 24, "MPP-OLO-2609", 200, 1)), arrives_in_days=-9,
        tracking="1Z84A7W90311820004"),
    Inbound("Accra Market · shito restock", "accra-market", "CLOSED", (
        InboundLine("shito", 0, 48, "ACM-SHI-2610", 140, 58),), arrives_in_days=-6, carrier="usps",
        tracking="9400111899223344556677"),
]
# Lots from the CLOSED shipments with less than the 90-day minimum life left are quarantined at receiving (short-dated);
# the seeder releases them as "accepted short-dated" so they are sellable and show up for markdown, except the one that
# expires tomorrow (it stays on hold and becomes EXPIRED at the next nightly expiry run).
ACCEPT_SHORT_DATED_REASON = "Accepted short-dated for markdown (buyer decision)"
COUNT_SKUS = ("chin-chin", "shito")  # cycle counts: the first is left open for review, the second is posted
TRANSFER = ("ijebu-garri", 1, "ATL-FC", 12, "Rebalance: Atlanta demand for garri")


# ------------------------------------------------------------------------------------------------ orders
@dataclass(frozen=True)
class Line:
    product: str
    variant: int = 0
    qty: int = 1
    seller: str = ""  # dataset seller key of the offer to buy (default: the product's main seller)


@dataclass(frozen=True)
class Package:
    """One package of an order: its lines (one seller and fulfilment) and the delivery option."""
    lines: tuple[Line, ...]
    option: str = "carrier"  # carrier | express | afrotransact_delivery | door (pre-order)
    # placed · picking · packed · ready · shipped · out_for_delivery · delivered · cancelled. Seller-fulfilled
    # packages are confirmed, packed and marked ready by the seller (only Mama Put has a test user).
    target: str = "placed"
    carrier: str = "ups"
    tracking: str = ""
    route: str = ""  # dispatch route key (AfroTransact Delivery)


@dataclass(frozen=True)
class Order:
    key: str
    customer: str
    packages: tuple[Package, ...]
    note: str = ""
    preorder: bool = False
    # (target status, reason, method) of a return of the first line: APPROVED (these reasons approve themselves) or
    # REFUNDED. Drop-off returns
    # need a carrier label (carrier-gateway, not in the local stack), so locally they wait at approved; a DRIVER_PICKUP
    # return is collected on a returns route and refunded after inspection.
    returns: tuple[tuple[str, str, str], ...] = ()
    claim: tuple[str, str] = ("", "")  # (type, description)
    settle_early: bool = False  # start the settlement hold as already matured (payout demo)


ORDERS: list[Order] = [
    Order("placed", HOUSTON, (Package((Line("chin-chin", qty=2), Line("ijebu-garri")), target="placed"),),
          note="Paid, waiting for the Houston FC to start picking"),
    Order("picking", HOUSTON, (Package((Line("smoked-catfish"),), target="picking"),),
          note="Perishable: picking started at HOU-FC"),
    Order("packed", HOUSTON, (Package((Line("at-basmati"), Line("at-pepper-sauce", qty=2)), target="packed",
                                      tracking="1Z84A7W90312000101"),),
          note="Packed with a UPS label, waiting at the dock"),
    Order("shipped-multi", HOUSTON, (
        Package((Line("at-jollof-seasoning", qty=2),), target="shipped", tracking="1Z84A7W90312000202"),
        Package((Line("kente-stole"),), target="placed")),
          note="Two packages: AfroTransact's is on its way with UPS; Bonwire Weavers ships the stole itself and has "
               "not confirmed yet"),
    Order("out-for-delivery", HOUSTON, (Package((Line("egusi", seller="mama-put"), Line("ground-crayfish")),
                                                option="afrotransact_delivery", target="out_for_delivery",
                                                route="today"),),
          note="AfroTransact Delivery: on the driver's van"),
    # AfroTransact Delivery packages leave from the Houston FC: products whose Mama Put stock is at HOU-FC.
    Order("delivered-courier", HOUSTON, (Package((Line("pepper-soup-spice", qty=3),),
                                                 option="afrotransact_delivery", target="delivered", route="today"),),
          note="AfroTransact Delivery: delivered with a photo; one jar picked up by the driver and refunded",
          returns=(("REFUNDED", "NOT_AS_DESCRIBED", "DRIVER_PICKUP"),)),
    Order("delivered-return-requested", HOUSTON, (Package((Line("ofada-rice", variant=1, qty=2), Line("goat-meat")),
                                                          target="delivered", tracking="1Z84A7W90312000303"),),
          note="Delivered; the customer asked to return the rice", settle_early=True,
          returns=(("APPROVED", "ORDERED_BY_MISTAKE", "DROP_OFF"),)),
    Order("delivered-refunded", HOUSTON, (Package((Line("at-black-soap", qty=3),), target="delivered",
                                                  tracking="1Z84A7W90312000404"),),
          note="Delivered; a soap return approved, its drop-off label waiting for carrier-gateway",
          returns=(("APPROVED", "NOT_AS_DESCRIBED", "DROP_OFF"),)),
    Order("cancelled", HOUSTON, (Package((Line("jerk-paste"),), target="cancelled"),),
          note="Cancelled by the customer before packing"),
    Order("next-route", HOUSTON, (Package((Line("oloyin-beans"),), option="afrotransact_delivery", target="ready",
                                          route="tomorrow"),),
          note="Staged for tomorrow's route (its van has an open defect)"),
    # Lubbock has an open pre-order window: its pantry lines (the window's categories) can only be a pre-order there.
    Order("preorder", LUBBOCK, (Package((Line("at-basmati"), Line("at-jollof-seasoning")), option="door",
                                        target="placed"),), preorder=True,
          note="Lubbock pre-order: delivered on the window's delivery day"),
    Order("lubbock-claim", LUBBOCK, (Package((Line("shea-butter", qty=2),), target="delivered",
                                             tracking="1Z84A7W90312000505"),),
          claim=("DAMAGED_ITEM", "One of the two jars arrived cracked and the butter had leaked into the box."),
          note="Delivered; A-to-z claim waiting for the seller"),
    Order("lubbock-seller", LUBBOCK, (Package((Line("things-fall-apart"),), target="placed"),),
          note="Seller-fulfilled by Diaspora Books: waiting for the seller to confirm"),
]

# S255 AfroTransact Delivery from the seller's store: Mama Put's Houston kitchen, an offer it ships itself. A driver
# needs a street-level position: locally geo knows the street addresses of its fixture list, this one among them.
SELLER_PICKUP = {
    "seller": "mama-put",
    "address": {"label": "Kitchen", "name": "Mama Put Pantry", "company": "Mama Put Pantry LLC", "phone": "+17135550142",
                "line1": "9800 Bissonnet St", "line2": "Suite 118", "city": "Houston", "state": "TX",
                "postalCode": "77036", "country": "US"},
    "hours": [{"day": d, "opens": "10:00", "closes": "18:00"} for d in
              ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")],
    "product": "suya-spice", "price": 899, "stock": 20,
    "order": Order("seller-pickup", HOUSTON, (Package((Line("suya-spice", seller="mama-put", qty=2),),
                                                      option="afrotransact_delivery", target="ready"),),
                   note="Seller-fulfilled, AfroTransact Delivery: packed at Mama Put's kitchen, pickup waiting"),
}

# The Houston FC as fulfilment-service's dispatch depot (routes start and end there).
DEPOT = {"name": "AfroTransact Houston FC", "kind": "FULFILMENT_CENTRE", "timezone": "America/Chicago",
         "addressLine": "15900 John F Kennedy Blvd", "city": "Houston", "state": "TX", "postalCode": "77032",
         "country": "US", "lat": 29.9389, "lon": -95.3364, "coldChain": True, "active": True}

# AfroTransact Delivery's Houston zone (geo DELIVERY zone): groups the route builder's stops and lets sellers within
# 25 miles of it offer pickups. Local geo starts without zones.
DELIVERY_ZONE = {"name": "Houston", "ownerId": "afrotransact-delivery", "type": "DELIVERY", "centerLat": 29.7604,
                 "centerLon": -95.3698, "radiusMiles": 30, "active": True}

ROUTES = {  # key: (name, days from today, window, vehicle); "returns" collects the DRIVER_PICKUP returns
    "returns": ("Demo · Houston returns · today", 0, "9:00–11:00 am", "TX-VAN-07"),
    "today": ("Demo · Houston SW · today", 0, "1:00–5:00 pm", "TX-VAN-07"),
    "tomorrow": ("Demo · Houston SW · tomorrow", 1, "9:00 am–1:00 pm", "TX-VAN-12"),
}
SOS = ("UNSAFE", "Loose dog at the gate of the next stop: waiting for the owner")
DEFECT = ("LIGHTS", "Left brake light out")

Options = Union[str, dict]
