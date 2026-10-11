"""The `ops` step: its dataset is consistent with the catalog dataset and covers every state, purchase orders are
found by supplier and lines and only moved forward, orders are found by their attribution (a re-run checks out
nothing), failed checkouts retry under a new key, and test users' tokens are cached and refused when a second factor
is needed."""
from __future__ import annotations

import base64
import json
import unittest

from seedlib import dataset as D
from seedlib import ops_dataset as O
from seedlib.http import ApiError
from seedlib.ops import PO_ORDER, STAGES, TARGET_STATUS, OpsSeeder, stable
from seedlib.users import SignedIn

PRODUCT_KEYS = {p.key: p for p in D.PRODUCTS}


class Fake:
    """Routes (METHOD, path) to handlers and records every call."""

    def __init__(self):
        self.calls, self.routes = [], {}

    def on(self, method, path, fn):
        self.routes[(method, path)] = fn

    def _call(self, method, path, body=None, **kw):
        self.calls.append((method, path, body, kw.get("params"), kw.get("headers")))
        for (m, p), fn in self.routes.items():
            if m == method and (p == path or (p.endswith("*") and path.startswith(p[:-1]))):
                return fn(path, body, kw)
        raise AssertionError(f"unexpected {method} {path}")

    def get(self, path, **kw):
        return self._call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self._call("POST", path, body, **kw)

    def put(self, path, body=None, **kw):
        return self._call("PUT", path, body, **kw)


class Users:
    def headers(self, email):
        return {"Authorization": "Bearer " + email}

    def claims(self, email):
        return {"sub": "driver-1"}


def seeder(**svc):
    problems = []
    s = OpsSeeder(svc, Users(), lambda title: None, log=lambda m: None, add=lambda w, o: None,
                  problem=problems.append, sleep=lambda s: None, wait=0)
    s.problems = problems
    return s


class OpsDatasetTest(unittest.TestCase):
    def test_every_product_key_is_in_the_catalog_dataset(self):
        keys = [it.product for it in O.ITEMS]
        keys += [ln.product for po in O.PURCHASE_ORDERS for ln in po.lines]
        keys += [ln.product for ib in O.INBOUND for ln in ib.lines]
        keys += [ln.product for o in O.ORDERS + [O.SELLER_PICKUP["order"]] for pkg in o.packages for ln in pkg.lines]
        keys += [O.SELLER_PICKUP["product"], O.TRANSFER[0]]
        for key in keys:
            self.assertIn(key, PRODUCT_KEYS)

    def test_retail_items_are_afrotransact_retail_products_with_known_suppliers(self):
        self.assertEqual(len({it.product for it in O.ITEMS}), len(O.ITEMS))
        for it in O.ITEMS:
            with self.subTest(item=it.product):
                self.assertEqual(PRODUCT_KEYS[it.product].seller, "retail")
                self.assertLess(it.variant, len(PRODUCT_KEYS[it.product].variants))
                self.assertIn(it.supplier, O.SUPPLIER_BY_CODE)
                self.assertGreater(it.shelf_life_days, 0, "retail requires a shelf life")
        for po in O.PURCHASE_ORDERS:
            for ln in po.lines:
                item = next(i for i in O.ITEMS if i.product == ln.product)
                self.assertEqual(item.supplier, po.supplier, f"{po.key}: {ln.product} comes from {item.supplier}")

    def test_purchase_orders_cover_every_state(self):
        targets = {po.target for po in O.PURCHASE_ORDERS}
        self.assertEqual(targets, set(PO_ORDER) | {"CANCELLED"})
        self.assertEqual(len({po.key for po in O.PURCHASE_ORDERS}), len(O.PURCHASE_ORDERS))
        received = [po for po in O.PURCHASE_ORDERS if po.target in ("RECEIVED", "CLOSED")]
        self.assertTrue(any(ln.invoice_price_pct for po in received for ln in po.lines), "a price exception")
        self.assertTrue(any(po.invoice and not any(ln.invoice_price_pct for ln in po.lines) for po in received))
        for po in O.PURCHASE_ORDERS:
            if po.shipment:
                self.assertIn(po.shipment, STAGES)
            if po.receive:
                self.assertEqual(po.shipment, "DELIVERED_TO_FC", f"{po.key} is received at the FC")
            signature = sorted((ln.product, ln.qty) for ln in po.lines)
            others = [sorted((ln.product, ln.qty) for ln in x.lines) for x in O.PURCHASE_ORDERS
                      if x.supplier == po.supplier and x is not po]
            self.assertNotIn(signature, others, "supplier + lines identify a purchase order")

    def test_inbound_shipments_cover_every_stage_in_whole_cases(self):
        self.assertEqual({ib.target for ib in O.INBOUND}, {"WORKING", "SHIPPED", "RECEIVING", "CLOSED"})
        shipped = [ib for ib in O.INBOUND if ib.target == "SHIPPED"]
        self.assertTrue(any(ib.arrives_in_days > 0 for ib in shipped), "one in transit")
        self.assertTrue(any(ib.arrives_in_days <= 0 for ib in shipped), "one arrived, waiting to be received")
        lines = [ln for ib in O.INBOUND for ln in ib.lines]
        self.assertTrue(all(ln.qty % 12 == 0 for ln in lines))
        self.assertTrue(any(ln.hold for ln in lines), "a QC hold")
        self.assertTrue(any(ln.damaged for ln in lines), "damaged units")
        self.assertTrue(any(ln.expires_in_days <= 1 for ln in lines), "a lot that expires now")
        self.assertTrue(any(30 <= ln.expires_in_days <= 60 for ln in lines), "markdown candidates")
        self.assertEqual(len({ln.lot for ln in lines}), len(lines))

    def test_orders_cover_every_shipment_state_both_customers_and_both_fulfilments(self):
        orders = O.ORDERS + [O.SELLER_PICKUP["order"]]
        self.assertEqual(len({o.key for o in orders}), len(orders), "keys are the orders' attribution")
        targets = {pkg.target for o in orders for pkg in o.packages}
        self.assertTrue({"placed", "picking", "packed", "shipped", "out_for_delivery", "delivered", "cancelled"}
                        <= targets)
        self.assertTrue(all(t in TARGET_STATUS for t in targets))
        self.assertEqual({o.customer for o in orders}, {O.HOUSTON, O.LUBBOCK})
        self.assertTrue(any(len(o.packages) > 1 for o in orders), "a multi-package order")
        self.assertTrue(any(o.preorder for o in orders))
        fulfilments = {D.SELLER_BY_KEY[ln.seller or PRODUCT_KEYS[ln.product].seller].fulfilment
                       for o in orders for pkg in o.packages for ln in pkg.lines}
        self.assertEqual(fulfilments, {"AFROTRANSACT", "SELLER"}, "FBA and seller-fulfilled packages")
        for o in orders:
            for pkg in o.packages:
                if pkg.route:
                    self.assertIn(pkg.route, O.ROUTES)
                    self.assertEqual(pkg.option, "afrotransact_delivery")
                if pkg.option == "afrotransact_delivery" and not pkg.lines[0].seller:
                    self.assertEqual(D.SELLER_BY_KEY[PRODUCT_KEYS[pkg.lines[0].product].seller].node, "HOU-FC",
                                     "local delivery leaves from the Houston FC")
        self.assertTrue(any(o.returns for o in orders) and any(o.claim[0] for o in orders))
        self.assertTrue(any(o.settle_early for o in orders), "a payout")

    def test_stable_numbers_do_not_change_between_runs(self):
        self.assertEqual(stable("received-matched", 5), stable("received-matched", 5))
        self.assertEqual(len(stable("x", 8)), 8)


class PayoutTest(unittest.TestCase):
    def test_the_payout_is_requested_once_the_matured_sale_is_available(self):
        payments = Fake()
        requests = []
        payments.on("GET", "/v1/payouts/requests", lambda *a: [])
        payments.on("GET", "/v1/connected-accounts/" + O.FINANCE_ORG, lambda *a: {"stripeAccountId": "acct_1x"})
        payments.on("GET", "/v1/payouts/balance", lambda *a: {"availableMinor": 4503, "minimumPayoutMinor": 2500})
        payments.on("POST", "/v1/payouts/requests", lambda p, b, kw: requests.append(b) or {"id": "r1", "status": "REQUESTED"})
        s = seeder(payments=payments)
        s._local_payments = True
        s.payouts()
        self.assertEqual(requests, [{"orgId": O.FINANCE_ORG, "all": True, "currency": "USD"}])


class PurchaseOrderTest(unittest.TestCase):
    def test_an_existing_po_is_found_by_supplier_and_lines_and_only_moved_forward(self):
        retail = Fake()
        po = next(p for p in O.PURCHASE_ORDERS if p.key == "approved")
        found = {"id": "po-1", "number": "PO-1P-9", "status": "PENDING_APPROVAL",
                 "lines": [{"sku": "AT-KEN-1", "qtyOrdered": 144}]}
        retail.on("GET", "/v1/purchase-orders", lambda *a: {"items": [found]})
        retail.on("POST", "/v1/purchase-orders/po-1/approve", lambda p, b, kw: dict(found, status="APPROVED"))
        s = seeder(retail=retail)
        s.suppliers = {"SUP-ADD": {"id": "sup-1"}}
        s.items = {"kenyan-coffee": {"id": "item-1", "sku": "AT-KEN-1"}}

        s._purchase_order(po)

        posts = [c[1] for c in retail.calls if c[0] == "POST"]
        self.assertEqual(posts, ["/v1/purchase-orders/po-1/approve"], "no new PO, no submit again")


class OrdersTest(unittest.TestCase):
    def test_a_re_run_finds_the_order_by_its_attribution_and_checks_out_nothing(self):
        orders = Fake()
        mine = {"orderNumber": "AT-2026-AAA111", "attribution": {"src": O.SEED_SOURCE, "cr": "placed"}}
        orders.on("GET", "/v1/orders", lambda *a: {"items": [mine], "nextCursor": None})
        orders.on("GET", "/v1/lookup/orders/*", lambda *a: {"paymentId": "pay-1"})
        payments = Fake()
        payments.on("GET", "/v1/payments/pay-1", lambda *a: {"stripePaymentIntentId": "pi_localtest_abc"})
        s = seeder(orders=orders, payments=payments, offers=Fake(), seller=Fake(), geo=Fake())
        s._seller_pickup_offer = lambda: None
        s.all_orders = lambda: [next(o for o in O.ORDERS if o.key == "placed")]

        s.place_orders()

        self.assertEqual([c[0] for c in orders.calls if c[0] != "GET"], [], "no checkout")
        self.assertEqual(s.orders["placed"]["orderNumber"], "AT-2026-AAA111")
        self.assertTrue(s.local_payments(), "pi_localtest_ intents: the local stand-in")
        self.assertEqual(orders.calls[0][4], {"Authorization": "Bearer " + O.HOUSTON}, "as the customer")

    def test_a_failed_checkout_is_retried_under_a_numbered_key(self):
        keys = []

        def checkout(path, body, kw):
            keys.append(kw["headers"]["Idempotency-Key"])
            if len(keys) == 1:
                raise ApiError("POST", path, 422, {"code": "orders.delivery-pickup-unavailable"})
            return {"orderNumber": "AT-2026-BBB222", "status": "placed"}

        orders, cart, identity = Fake(), Fake(), Fake()
        orders.on("POST", "/v1/checkout", checkout)
        orders.on("GET", "/v1/orders", lambda *a: {"items": [{"orderNumber": "AT-2026-BBB222", "attribution": {
            "src": O.SEED_SOURCE, "cr": "placed"}}]})
        identity.on("GET", "/v1/me/addresses", lambda *a: [{"id": "addr-1", "city": "Houston", "state": "TX"}])
        cart.on("POST", "/v1/cart/items", lambda *a: {})
        cart.on("GET", "/v1/cart", lambda *a: {"packages": [{"packageId": "org_mama_put_pantry:AFROTRANSACT",
                                                             "lines": [{"variantAtin": "AT1"}, {"variantAtin": "AT2"}]}]})
        s = seeder(orders=orders, cart=cart, identity=identity)
        s.atin = lambda key, variant=0: {"chin-chin": "AT1", "ijebu-garri": "AT2"}[key]
        s._offer_id = lambda line: "offer-" + line.product

        s._checkout(next(o for o in O.ORDERS if o.key == "placed"))

        self.assertEqual(len(keys), 2)
        self.assertTrue(keys[1].startswith(keys[0]) and keys[1].endswith(":2"))

    def test_missing_until_the_suppliers_and_an_order_exist(self):
        retail, orders = Fake(), Fake()
        retail.on("GET", "/v1/suppliers", lambda *a: {"items": [{"code": s.code} for s in O.SUPPLIERS[:2]]})
        orders.on("GET", "/v1/admin/orders", lambda *a: {"items": [], "total": 3})
        self.assertTrue(seeder(retail=retail, orders=orders).missing())
        retail.on("GET", "/v1/suppliers", lambda *a: {"items": [{"code": s.code} for s in O.SUPPLIERS]})
        self.assertFalse(seeder(retail=retail, orders=orders).missing())
        orders.on("GET", "/v1/admin/orders", lambda *a: {"items": [], "total": 0})
        self.assertTrue(seeder(retail=retail, orders=orders).missing())

    def test_a_part_whose_services_are_down_is_skipped(self):
        logs, ran = [], []
        s = OpsSeeder({}, Users(), lambda t: None, log=logs.append, add=lambda w, o: None, problem=logs.append,
                      up={"tax-returns": False, "trust-ops": True})
        s.part("returns and claims", lambda: ran.append(1), ("tax-returns", "trust-ops"))
        self.assertEqual(ran, [])
        self.assertIn("tax-returns not running", logs[0])


def jwt(claims: dict) -> str:
    return "h." + base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=") + ".s"


class SignedInTest(unittest.TestCase):
    class FakeUsers:
        def __init__(self, step):
            self.step, self.redeemed = step, 0

        def client_token(self):
            return "client"

        def sign_in(self, token, email):
            return self.step

        def access_token(self, ticket):
            self.redeemed += 1
            return jwt({"sub": "u-1", "pt": "CUSTOMER", "iat": 1000, "exp": 1300})

    def test_tokens_are_cached_until_shortly_before_they_expire(self):
        users = self.FakeUsers({"status": "AUTHENTICATED", "loginTicket": "t"})
        now = [1000.0]
        signed = SignedIn(users, clock=lambda: now[0])
        self.assertEqual(signed.headers(O.HOUSTON)["Authorization"][:7], "Bearer ")
        signed.token(O.HOUSTON)
        self.assertEqual(users.redeemed, 1)
        now[0] += 250
        signed.token(O.HOUSTON)
        self.assertEqual(users.redeemed, 2, "renewed a minute before expiry")
        self.assertEqual(signed.claims(O.HOUSTON)["sub"], "u-1")

    def test_a_user_who_needs_a_second_factor_is_refused_with_a_hint(self):
        signed = SignedIn(self.FakeUsers({"status": "MFA_REQUIRED", "challengeId": "c"}))
        with self.assertRaises(ValueError) as e:
            signed.token(O.SELLER_OWNER)
        self.assertIn("authenticator", str(e.exception))


if __name__ == "__main__":
    unittest.main()
