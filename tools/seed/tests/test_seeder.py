"""Seeder steps against in-memory fakes of the services: placements replace their migration twins, offers end up with the
right seller and inventory SKU, receipts are idempotent, sellers are provisioned once."""
import tempfile
import unittest
from pathlib import Path

from seedlib import dataset as D
from seedlib.http import ApiError
from seedlib.seeder import Seeder


class FakeHttp:
    """Routes (METHOD, path) to handlers; records calls."""

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

    def patch(self, path, body=None, **kw):
        return self._call("PATCH", path, body, **kw)

    def request(self, method, path, json_body=None, **kw):
        class R:
            pass
        r = R()
        r.status, r.body = 201, self._call(method, path, json_body, **kw)
        return r


def seeder():
    tmp = tempfile.mkdtemp()
    s = Seeder({"SEED_STATE_DIR": tmp, "SEED_TOKEN": "t", "SEED_WIKIMEDIA": "0"}, Path(tmp), log=lambda m: None)
    s.svc = {k: FakeHttp() for k in s.svc}
    s.up = {k: True for k in s.svc}
    return s


class PlacementTest(unittest.TestCase):
    def test_creates_approves_and_ends_what_it_replaces(self):
        s = seeder()
        content = s.svc["content"]
        store = [{"id": "plc_hero_party_jollof", "name": "AfroTransact Brand jollof", "status": "LIVE", "payload": {}}]
        content.on("GET", "/v1/placements", lambda *a: [dict(p) for p in store])

        def create(path, body, kw):
            store.append({"id": f"plc_{len(store)}", "name": body["name"], "status": "DRAFT", "payload": body["payload"]})
            return store[-1]

        def transition(path, body, kw):
            pid, action = path.split("/")[3], path.split("/")[4]
            p = next(x for x in store if x["id"] == pid)
            p["status"] = {"submit": "IN_REVIEW", "approve": "LIVE", "end": "ENDED"}[action]
            return p

        content.on("POST", "/v1/placements", create)
        content.on("POST", "/v1/placements/*", transition)
        payload = {"title": "Party jollof, sorted.", "imageUrl": "http://localhost:9000/media/x.jpg"}

        s._ensure_placement("hero_carousel", "AfroTransact Brand jollof", payload, 40, "plc_hero_party_jollof")
        self.assertEqual([(p["id"], p["status"]) for p in store],
                         [("plc_hero_party_jollof", "ENDED"), ("plc_1", "LIVE")])
        self.assertEqual(store[1]["name"], "AfroTransact Brand jollof · demo seed")
        created = next(c for c in content.calls if c[:2] == ("POST", "/v1/placements"))[2]
        self.assertFalse(created["paid"])
        self.assertEqual(created["targeting"]["markets"], ["US"])

        before = len(content.calls)
        s._ensure_placement("hero_carousel", "AfroTransact Brand jollof", payload, 40, "plc_hero_party_jollof")
        self.assertEqual(len(content.calls), before + 1, "unchanged: only the lookup")

        s._ensure_placement("hero_carousel", "AfroTransact Brand jollof", dict(payload, imageUrl=None), 40)
        self.assertEqual([p["status"] for p in store], ["ENDED", "ENDED", "LIVE"], "a changed payload replaces it")


class OffersAndStockTest(unittest.TestCase):
    def setUp(self):
        self.s = seeder()
        self.offers = self.s.svc["offers"]
        self.store = []

        def listing(path, body, kw):
            p = kw["params"]
            return {"items": [o for o in self.store if o["sellerOrgId"] == p["sellerOrgId"] and o["atin"] == p["atin"]]}

        def create(path, body, kw):
            o = dict(body, id=f"o{len(self.store)}", status="ACTIVE", version=0)
            self.store.append(o)
            return o

        def action(path, body, kw):
            o = next(x for x in self.store if x["id"] == path.split("/")[3])
            if path.endswith("/withdraw"):
                o["status"] = "WITHDRAWN"
            return o

        def patch(path, body, kw):
            o = next(x for x in self.store if x["id"] == path.split("/")[3])
            o.update({k: v for k, v in body.items() if k != "version"})
            o["version"] += 1
            return o

        self.offers.on("GET", "/v1/offers", listing)
        self.offers.on("POST", "/v1/offers", create)
        self.offers.on("POST", "/v1/offers/*", action)
        self.offers.on("PATCH", "/v1/offers/*", patch)

    def product(self, key):
        p = next(x for x in D.PRODUCTS if x.key == key)
        variants = [{"atin": f"ATVAR{i:05d}", "sellerSku": f"SKU{i}", "options": {}} for i in range(len(p.variants))]
        for v, (_, price) in zip(variants, p.variants):  # offers' auto-created AfroTransact Retail offer
            self.store.append({"id": f"r{v['atin']}", "atin": v["atin"], "sellerOrgId": D.RETAIL_ORG, "status": "ACTIVE",
                               "priceMinor": price, "inventorySku": v["sellerSku"], "version": 0})
        return p, {"atin": "ATPROD0001", "variants": variants, "productType": "grocery"}

    def test_seller_product_withdraws_the_retail_auto_offer(self):
        p, product = self.product("ofada-rice")  # Mama Put Pantry, also Lagos Larder at 1.06
        stocked = self.s._offers(p, product)

        active = [o for o in self.store if o["status"] == "ACTIVE"]
        self.assertEqual({o["sellerOrgId"] for o in active}, {"org_mama_put_pantry", "org_lagos_larder"})
        self.assertTrue(all(o["inventorySku"] == o["atin"] for o in active), "FBA stock is keyed by variant ATIN")
        mama = [o for o in active if o["sellerOrgId"] == "org_mama_put_pantry"]
        self.assertEqual([o["priceMinor"] for o in mama], [899, 1850], "the main seller keeps the dataset price")
        lagos = [o for o in active if o["sellerOrgId"] == "org_lagos_larder"]
        self.assertEqual([o["priceMinor"] for o in lagos], [949, 1959])
        self.assertEqual(set(stocked), {"mama-put", "lagos-larder"})

        receipts = []
        self.s.svc["inventory"].on("POST", "/v1/receipts", lambda path, body, kw: receipts.append((body, kw)) or {})
        self.s._stock(p, product, stocked)
        nodes = [b["nodeId"] for b, _ in receipts]
        self.assertEqual(nodes, ["HOU-FC", "ATL-FC"], "two owners never share a node for one SKU")
        self.assertEqual(receipts[0][0]["ownerOrg"], "org_mama_put_pantry")
        self.assertEqual(receipts[0][1]["headers"]["Idempotency-Key"], receipts[0][0]["receiptRef"])
        self.assertIn("expDate", receipts[0][0]["lines"][0], "food lots carry dates")

        again = len(self.store)
        self.s._offers(p, product)
        self.assertEqual(len(self.store), again, "re-runs create nothing")

    def test_retail_product_keeps_its_offer_with_a_list_price(self):
        p, product = self.product("at-jollof-seasoning")
        self.s._offers(p, product)

        retail = [o for o in self.store if o["sellerOrgId"] == D.RETAIL_ORG]
        self.assertEqual([o["status"] for o in retail], ["ACTIVE", "ACTIVE"])
        self.assertEqual([(o["priceMinor"], o["listPriceMinor"]) for o in retail], [(649, 799), (1099, 1349)])
        self.assertTrue(all(o["inventorySku"] == o["atin"] for o in retail))

    def test_receipts_already_taken_are_fine(self):
        p, product = self.product("garam-masala")

        def taken(path, body, kw):
            raise ApiError("POST", path, 409, {"code": "inventory.idempotency-key-reused"})

        self.s.svc["inventory"].on("POST", "/v1/receipts", taken)
        self.s._stock(p, product, self.s._offers(p, product))
        self.assertEqual(self.s.report.counts[("inventory receipts", "replayed")], 1)


class SellersTest(unittest.TestCase):
    def test_provisions_every_marketplace_org_but_not_retail(self):
        s = seeder()
        seen = []
        s.svc["seller"].on("POST", "/v1/orgs", lambda path, body, kw: seen.append(body) or body)
        s.step_sellers()

        ids = [b["id"] for b in seen]
        self.assertNotIn(D.RETAIL_ORG, ids)
        self.assertEqual(len(ids), len(D.SELLERS) - 1 + len(D.PROVIDERS))
        self.assertEqual({b["kind"] for b in seen}, {"SELLER", "PROVIDER"})

    def test_provisioning_off_is_reported(self):
        s = seeder()

        def off(path, body, kw):
            raise ApiError("POST", path, 404, {"code": "seller.provisioning-off"})

        s.svc["seller"].on("POST", "/v1/orgs", off)
        s.step_sellers()
        self.assertEqual(len(s.report.problems), 1)


if __name__ == "__main__":
    unittest.main()
