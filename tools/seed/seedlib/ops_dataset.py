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
SELLER_BAMAKO = "seller.bamako@afrotransact.test"  # Bamako Home
DRIVER = "driver@afrotransact.test"
FINANCE_ORG = "org_mama_put_pantry"  # the seller whose matured sale is paid out
BAMAKO_ORG = "org_bamako_home"

# Shipments packed by the seller (from a seller location, or a pickup) are driven with these seller test users (see ops._drive_shipment).
SELLER_FULFILMENT_ACTORS: dict[str, str] = {
    FINANCE_ORG: SELLER_OWNER,
    BAMAKO_ORG: SELLER_BAMAKO,
}


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
                  note="Christmas and Thanksgiving basmati: above the $10,000 Finance threshold"),
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
# AfroTransact keeps no marketplace seller stock (ADR 0013), so there are no seller inbound shipments here: the lots below belong to
# AfroTransact Retail's own purchase orders. Lots from the received purchase orders with less than the 90-day minimum life left are
# quarantined at receiving (short-dated); the seeder releases them as "accepted short-dated" so they are sellable and show up for
# markdown, except the one that expires tomorrow (it stays on hold and becomes EXPIRED at the next nightly expiry run).
ACCEPT_SHORT_DATED_REASON = "Accepted short-dated for markdown (buyer decision)"
COUNT_SKUS = ("at-pepper-sauce", "at-dried-pepper")  # Retail lots counted: the first is left open for review, the second is posted
TRANSFER = ("at-pepper-sauce", 0, "ATL-FC", 12, "Rebalance: Atlanta demand for pepper sauce")


# ------------------------------------------------------------------------------------------------ orders
@dataclass(frozen=True)
class Line:
    product: str
    variant: int = 0
    qty: int = 1
    seller: str = ""  # dataset seller key of the offer to buy (default: the product's main seller)


@dataclass(frozen=True)
class Package:
    """One package of an order: its lines (one seller and fulfilment) and how the customer gets it. `option` is
    afrotransact_delivery (Fulfilled by AfroTransact: AfroTransact delivers) | pickup (Seller pickup: the customer collects)
    | door (pre-order). Never a carrier: seller self-shipping is retired (ADR 0011).
    `target`: placed · picking · packed · ready (an FBA package waits for the Delivery desk; a pickup one for the customer) ·
    picked_up · out_for_delivery · delivered (staff, with photo proof) · failed (delivery failed, with a reason) · returned
    (back to the seller after a failed delivery) · collected (pickup handed over with the customer's code) · cancelled.
    Seller-packed packages (a seller location, or a pickup) are confirmed, packed and marked ready by the seller (only Mama Put
    has a test user); the delivery steps are staff's, from the Delivery desk (`delivery:manage`)."""
    lines: tuple[Line, ...]
    option: str = "afrotransact_delivery"
    target: str = "placed"
    route: str = ""  # dispatch route key (only with the dormant driver app: DRIVER_APP_ENABLED=true)


@dataclass(frozen=True)
class Order:
    key: str
    customer: str
    packages: tuple[Package, ...]
    note: str = ""
    preorder: bool = False
    # (target status, reason, method) of a return of the first line: APPROVED (these reasons approve themselves) or
    # REFUNDED. Drop-off returns need a carrier label (carrier-gateway, not in the local stack) and a driver pickup needs the
    # dormant driver app, so locally they wait at approved; with the driver app on, a DRIVER_PICKUP return is collected on a
    # returns route and refunded after inspection. The method asked for falls back to the first one the return rules offer.
    returns: tuple[tuple[str, str, str], ...] = ()
    claim: tuple[str, str] = ("", "")  # (type, description)
    settle_early: bool = False  # start the settlement hold as already matured (payout demo)


ORDERS: list[Order] = [
    Order("placed", HOUSTON, (Package((Line("chin-chin", qty=2), Line("ijebu-garri")), target="placed"),),
          note="Fulfilled by AfroTransact: paid, waiting for the Houston FC to start picking"),
    Order("picking", HOUSTON, (Package((Line("smoked-catfish"),), target="picking"),),
          note="Perishable: picking started at HOU-FC"),
    Order("packed", HOUSTON, (Package((Line("at-basmati"), Line("at-pepper-sauce", qty=2)), target="packed"),),
          note="Packed at the Houston FC, waiting at the dock"),
    Order("shipped-multi", HOUSTON, (
        Package((Line("at-jollof-seasoning", qty=2),), target="out_for_delivery"),
        Package((Line("kente-stole"),), target="placed")),
          note="Two packages, both Fulfilled by AfroTransact: one is out for delivery with staff, the stole is waiting "
               "to be picked"),
    Order("out-for-delivery", HOUSTON, (Package((Line("egusi", seller="mama-put"), Line("ground-crayfish")),
                                                target="out_for_delivery"),),
          note="Fulfilled by AfroTransact: picked up by the Delivery desk, out for delivery"),
    # AfroTransact Delivery packages leave from the Houston FC: products whose Mama Put stock is at HOU-FC.
    Order("delivered-courier", HOUSTON, (Package((Line("pepper-soup-spice", qty=3),), target="delivered"),),
          note="Fulfilled by AfroTransact: delivered, photo proof saved by staff; one jar picked up and refunded",
          returns=(("REFUNDED", "NOT_AS_DESCRIBED", "DRIVER_PICKUP"),)),
    Order("delivered-return-requested", HOUSTON, (Package((Line("ofada-rice", variant=1, qty=2), Line("goat-meat")),
                                                          target="delivered"),),
          note="Delivered with proof; the customer asked to return the rice", settle_early=True,
          returns=(("APPROVED", "ORDERED_BY_MISTAKE", "DROP_OFF"),)),
    Order("delivered-refunded", HOUSTON, (Package((Line("at-black-soap", qty=3),), target="delivered"),),
          note="Delivered with proof; a soap return approved, waiting for its return method to be carried out",
          returns=(("APPROVED", "NOT_AS_DESCRIBED", "DROP_OFF"),)),
    Order("cancelled", HOUSTON, (Package((Line("jerk-paste"),), target="cancelled"),),
          note="Cancelled by the customer before packing"),
    Order("next-route", HOUSTON, (Package((Line("oloyin-beans"),), target="ready"),),
          note="Packed and ready: waiting for the Delivery desk to pick it up"),
    Order("delivery-failed", HOUSTON, (Package((Line("suya-spice", seller="mama-put"),), target="failed"),),
          note="Fulfilled by AfroTransact from Mama Put's kitchen: nobody home, delivery failed with a reason, "
               "waiting to be reattempted"),
    Order("returned-to-seller", HOUSTON, (Package((Line("suya-spice", seller="mama-put"),), target="returned"),),
          note="Delivery failed twice, the parcel was returned to the seller (reason recorded)"),
    Order("pickup-ready", HOUSTON, (Package((Line("suya-spice", seller="mama-put"),), option="pickup", target="ready"),),
          note="Seller pickup at Mama Put's kitchen: ready, waiting for the customer to collect with the code"),
    Order("pickup-collected", HOUSTON, (Package((Line("suya-spice", seller="mama-put"),), option="pickup",
                                                target="collected"),),
          note="Seller pickup: collected with the customer's code (no delivery fee)"),
    # Lubbock has an open pre-order window: its pantry lines (the window's categories) can only be a pre-order there.
    Order("preorder", LUBBOCK, (Package((Line("at-basmati"), Line("at-jollof-seasoning")), option="door",
                                        target="placed"),), preorder=True,
          note="Lubbock pre-order: delivered on the window's delivery day"),
    # Houston: Lubbock is outside the delivery zone, where a delivery needs a quote instead of a delivery option.
    Order("lubbock-claim", HOUSTON, (Package((Line("shea-butter", qty=2),), target="delivered"),),
          claim=("DAMAGED_ITEM", "One of the two jars arrived cracked and the butter had leaked into the box."),
          note="Delivered with proof; A-to-z claim waiting for the seller"),
    Order("lubbock-seller", HOUSTON, (Package((Line("things-fall-apart"),), target="placed"),),
          note="Fulfilled by AfroTransact (Diaspora Books stock at ATL-FC): waiting to be picked"),
]

# S255 Fulfilled by AfroTransact from the seller's location: Mama Put's Houston kitchen (pickup hours, located address) and an
# offer from it; AfroTransact collects and delivers, and customers can also collect it (Seller pickup). Locally geo knows the
# street addresses of its fixture list, this one among them.
SELLER_PICKUP = {
    "seller": "mama-put",
    "address": {"label": "Kitchen", "name": "Mama Put Pantry", "company": "Mama Put Pantry LLC", "phone": "+17135550142",
                "line1": "9800 Bissonnet St", "line2": "Suite 118", "city": "Houston", "state": "TX",
                "postalCode": "77036", "country": "US"},
    "hours": [{"day": d, "opens": "10:00", "closes": "18:00"} for d in
              ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")],
    "product": "suya-spice", "price": 899, "stock": 20,
    "order": Order("seller-pickup", HOUSTON, (Package((Line("suya-spice", seller="mama-put", qty=2),),
                                                      target="ready"),),
                   note="Fulfilled by AfroTransact from Mama Put's kitchen: packed there, waiting for the Delivery desk to collect it"),
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
