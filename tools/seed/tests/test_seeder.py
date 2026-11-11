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


class TileRuleTest(unittest.TestCase):
    def test_step_content_seeds_rule_tiles_without_images_and_ends_the_per_category_tile(self):
        s = seeder()
        content = s.svc["content"]
        store = [{"id": "plc_old", "name": "Shop by department \u00b7 demo seed", "status": "LIVE", "payload": {"cells": []}}]
        content.on("GET", "/v1/placements", lambda *a: [dict(p) for p in store])

        def create(path, body, kw):
            store.append({"id": f"plc_{len(store)}", "name": body["name"], "status": "DRAFT", "slot": body["slot"],
                          "payload": body["payload"]})
            return store[-1]

        def transition(path, body, kw):
            pid, action = path.split("/")[3], path.split("/")[4]
            p = next(x for x in store if x["id"] == pid)
            p["status"] = {"submit": "IN_REVIEW", "approve": "LIVE", "end": "ENDED"}[action]
            return p

        content.on("POST", "/v1/placements", create)
        content.on("POST", "/v1/placements/*", transition)
        for t in D.TILE_RULES:
            cells = [{"source": dict(rule)} for rule in t["rules"]]
            s._ensure_placement("home_tiles", t["name"], dict(t["payload"], cells=cells), t["priority"], t["replaces"])
        s._end_replaced_placements(D.RETIRED_PLACEMENTS)

        rule_tiles = [p for p in store if p.get("slot") == "home_tiles" and p["status"] == "LIVE"]
        self.assertEqual(len(rule_tiles), len(D.TILE_RULES))
        for p in rule_tiles:
            for cell in p["payload"]["cells"]:
                self.assertEqual(set(cell), {"source"}, "no pinned label, link or image: the product supplies them")
        top = next(p for p in rule_tiles if p["name"].startswith("Top rated"))
        self.assertEqual([c["source"]["department"] for c in top["payload"]["cells"]], ["Groceries & Food", "Fashion & Accessories", "Beauty & Personal Care", "Home & Household"])
        self.assertEqual(top["payload"]["cells"][0]["source"], {"kind": "search", "sort": "rating", "department": "Groceries & Food"})
        self.assertEqual(next(p for p in store if p["id"] == "plc_old")["status"], "ENDED")


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


class RetailOfferWaitTest(OffersAndStockTest):
    """The Retail offer comes from Kafka (catalog.product.created -> offers): a bounded, longer wait and a clear error."""

    def setUp(self):
        super().setUp()
        self.now = 1000.0
        self.waits = []
        self.arrive_after = None  # seconds of fake time after which the Retail offers appear
        self.pending = []

        def sleep(sec):
            self.now += sec
            self.waits.append(sec)
            if self.arrive_after is not None and self.now >= self.arrive_after and self.pending:
                self.store.extend(self.pending)
                self.pending = []

        self.s.clock = lambda: self.now
        self.s.sleep = sleep

    def late_product(self, key):
        p, product = self.product(key)
        self.pending = [o for o in self.store if o["sellerOrgId"] == D.RETAIL_ORG]
        self.store[:] = [o for o in self.store if o["sellerOrgId"] != D.RETAIL_ORG]
        return p, product

    def test_an_offer_that_arrives_late_is_found_and_a_re_run_for_an_existing_product_succeeds(self):
        p, product = self.late_product("at-jollof-seasoning")
        self.arrive_after = self.now + 45  # far beyond the old 30 s wait
        self.s._offers(p, product)
        retail = [o for o in self.store if o["sellerOrgId"] == D.RETAIL_ORG]
        self.assertEqual([o["status"] for o in retail], ["ACTIVE", "ACTIVE"])
        self.assertLess(self.now, 1000.0 + 120)
        self.assertTrue(self.s.retail_event_seen)
        again = len(self.store)
        self.s._offers(p, product)  # the product exists, the offer is already there
        self.assertEqual(len(self.store), again)

    def test_the_first_wait_is_long_and_then_fails_with_what_to_check(self):
        p, product = self.late_product("at-jollof-seasoning")
        with self.assertRaises(ValueError) as caught:
            self.s._offers(p, product)
        msg = str(caught.exception)
        self.assertIn("offers did not create the Retail offer from catalog.product.created: check .local/logs/offers.log, "
                      "Kafka (docker ps | grep kafka) and run make seed again (it picks up where it stopped)", msg)
        self.assertGreaterEqual(self.now - 1000.0, 120)
        self.assertLess(self.now - 1000.0, 125)

    def test_after_a_timeout_the_other_products_are_not_made_to_wait_120_s_each(self):
        p, product = self.late_product("at-jollof-seasoning")
        with self.assertRaises(ValueError):
            self.s._offers(p, product)
        spent = self.now
        with self.assertRaises(ValueError):
            self.s._offers(p, product)
        self.assertLess(self.now - spent, 30)

    def test_once_an_offer_was_seen_the_next_variants_wait_briefly(self):
        p, product = self.product("at-jollof-seasoning")
        self.s._offers(p, product)  # seen at once
        self.assertTrue(self.s.retail_event_seen)
        ghost = {"atin": "ATGHOST001", "sellerSku": "G", "options": {}}
        before = self.now
        self.assertIsNone(self.s._wait_retail_offer(ghost["atin"]))
        self.assertLess(self.now - before, 25)

    def test_a_seller_product_does_not_wait_the_long_time_for_an_offer_it_will_withdraw(self):
        p, product = self.late_product("ofada-rice")
        self.s._offers(p, product)
        self.assertLess(self.now - 1000.0, 60)


class TaxReviewTest(unittest.TestCase):
    """The seeder acts as a tax reviewer: queue -> approve RESOLVED records with a reason, never anything else."""

    def setUp(self):
        self.s = seeder()
        self.now = 0.0
        self.s.clock = lambda: self.now
        self.s.sleep = lambda sec: setattr(self, "now", self.now + sec)
        self.tax = self.s.svc["tax-returns"]
        self.records = {}
        self.approved = []

        def queue(path, body, kw):
            items = [r for r in self.records.values() if r["status"] == kw["params"]["status"]]
            return {"items": items, "page": 0, "size": 200, "total": len(items)}

        def one(path, body, kw):
            atin = path.rsplit("/", 1)[1]
            if atin not in self.records:
                raise ApiError("GET", path, 404, {"code": "tax.classification-not-found"})
            return self.records[atin]

        def approve(path, body, kw):
            atin = path.split("/")[4]
            self.approved.append((atin, body))
            self.records[atin]["status"] = "APPROVED"
            return self.records[atin]

        self.tax.on("GET", "/v1/tax/variant-classifications", queue)
        self.tax.on("GET", "/v1/tax/variant-classifications/*", one)
        self.tax.on("POST", "/v1/tax/variant-classifications/*", approve)

    def record(self, atin, status="PENDING_REVIEW", decision="RESOLVED"):
        self.records[atin] = {"atin": atin, "status": status, "resolution": {"status": decision}}

    def test_resolved_variants_are_approved_with_the_demo_reason(self):
        self.record("ATA"), self.record("ATB")
        self.s.tax_atins.update({"ATA": "A", "ATB": "B"})
        self.s._review_tax()
        self.assertEqual(self.approved, [("ATA", {"reason": "Demo data approved by the seeder"}),
                                         ("ATB", {"reason": "Demo data approved by the seeder"})])
        self.assertEqual(self.s.report.counts[("tax review", "approved")], 2)
        self.assertEqual(self.s.report.problems, [])

    def test_only_resolved_records_are_ever_approved(self):
        self.record("ATOK"), self.record("ATMISS", decision="MISSING_FACTS"), self.record("ATBLOCK", decision="BLOCKED")
        self.record("ATGATE", decision="NEEDS_APPROVAL")
        self.s.tax_atins.update({a: a for a in self.records})
        self.s._review_tax()
        self.assertEqual([a for a, _ in self.approved], ["ATOK"])
        self.assertEqual(len(self.s.report.problems), 3)
        self.assertTrue(all("not RESOLVED" in m for m in self.s.report.problems))
        self.assertLess(self.now, 10, "a record that cannot be approved is not waited for")

    def test_a_re_run_leaves_approved_records_alone_and_does_not_wait(self):
        self.record("ATDONE", status="APPROVED")
        self.s.tax_atins["ATDONE"] = "Done"
        self.s._review_tax()
        self.assertEqual(self.approved, [])
        self.assertEqual(self.now, 0)
        self.assertEqual(self.s.report.counts[("tax review", "already approved")], 1)

    def test_a_record_that_arrives_over_kafka_a_little_late_is_approved(self):
        self.s.tax_atins["ATLATE"] = "Late"
        calls = []
        real_sleep = self.s.sleep

        def sleep(sec):
            real_sleep(sec)
            calls.append(sec)
            if len(calls) == 3:
                self.record("ATLATE")

        self.s.sleep = sleep
        self.s._review_tax()
        self.assertEqual([a for a, _ in self.approved], ["ATLATE"])

    def test_a_record_that_never_arrives_is_reported_after_a_bounded_wait(self):
        self.s.tax_atins["ATNONE"] = "Ghost"
        self.s._review_tax()
        self.assertTrue(60 <= self.now <= 65)
        self.assertIn("tax-returns has no classification yet", self.s.report.problems[0])

    def test_a_principal_without_the_permission_is_told_what_to_set(self):
        self.record("ATA")
        self.s.tax_atins["ATA"] = "A"

        def deny(path, body, kw):
            raise ApiError("POST", path, 403, {"code": "forbidden"})

        self.tax.on("POST", "/v1/tax/variant-classifications/*", deny)
        self.s._review_tax()
        self.assertIn("SEED_TAX_TOKEN", self.s.report.problems[0])
        self.assertEqual(len(self.s.report.problems), 1)

    def test_tax_returns_down_is_reported(self):
        self.s.up["tax-returns"] = False
        self.s.tax_atins["ATA"] = "A"
        self.s._review_tax()
        self.assertIn("tax-returns is down", self.s.report.problems[0])

    def test_approve_retries_after_the_tax_gate_flipped_the_variant_to_review(self):
        self.record("ATP")
        catalog = self.s.svc["catalog"]
        state = {"ready": False, "calls": 0}

        def approve(path, body, kw):
            state["calls"] += 1
            if not state["ready"]:
                raise ApiError("POST", path, 422, {"code": "catalog.tax-not-ready"})
            return {"status": "LIVE"}

        catalog.on("POST", "/v1/products/*", approve)
        orig = self.tax.routes[("POST", "/v1/tax/variant-classifications/*")]
        self.tax.on("POST", "/v1/tax/variant-classifications/*",
                    lambda path, body, kw: state.update(ready=True) or orig(path, body, kw))
        p = next(x for x in D.PRODUCTS)
        out = self.s._approve_product(p, {"id": "pid", "status": "IN_REVIEW", "variants": [{"atin": "ATP"}]})
        self.assertEqual(out["status"], "LIVE")
        self.assertEqual([a for a, _ in self.approved], ["ATP"])
        self.assertEqual(state["calls"], 2)


DEPARTMENT_NODES = [{"path": n, "name": n, "level": "DEPARTMENT", "code": c} for n, c in (
    ("Fashion & Accessories", "fashion-accessories"), ("Groceries & Food", "groceries-food"), ("Beverages", "beverages"),
    ("Beauty & Personal Care", "beauty-personal-care"), ("Books & Media", "books-media"), ("Home & Household", "home-household"),
    ("Health & Wellness", "health-wellness"))]


class SellersTest(unittest.TestCase):
    def test_every_seller_is_given_the_departments_of_its_products(self):
        s = seeder()
        seen = {}
        s.svc["catalog"].on("GET", "/v1/categories", lambda *a: DEPARTMENT_NODES)
        s.svc["seller"].on("POST", "/v1/orgs", lambda path, body, kw: seen.__setitem__(body["id"], body) or body)
        s.step_sellers()

        expected = D.seller_departments()
        self.assertEqual(s.report.problems, [])
        self.assertTrue(expected)
        for org_id, names in expected.items():
            self.assertEqual(seen[org_id]["departments"], sorted({n["code"] for n in DEPARTMENT_NODES if n["name"] in names}), org_id)
        # The food sellers sell groceries and the fashion seller only fashion: nothing a seller lists is outside its set.
        self.assertIn("groceries-food", seen["org_mama_put_pantry"]["departments"])
        self.assertNotIn("fashion-accessories", seen["org_mama_put_pantry"]["departments"])
        self.assertEqual(seen["org_kente_and_co"]["departments"], ["fashion-accessories"])
        self.assertNotIn(D.RETAIL_ORG, seen)
        self.assertTrue(all("departments" not in b for oid, b in seen.items() if oid not in expected))

    def test_a_catalog_that_lacks_a_department_is_reported_and_orgs_are_still_provisioned(self):
        s = seeder()
        seen = []
        s.svc["catalog"].on("GET", "/v1/categories", lambda *a: DEPARTMENT_NODES[:1])
        s.svc["seller"].on("POST", "/v1/orgs", lambda path, body, kw: seen.append(body) or body)
        s.step_sellers()
        self.assertEqual(len(s.report.problems), 1)
        self.assertEqual(len(seen), len(D.SELLERS) - 1 + len(D.PROVIDERS))

    def test_provisions_every_marketplace_org_but_not_retail(self):
        s = seeder()
        s.svc["catalog"].on("GET", "/v1/categories", lambda *a: DEPARTMENT_NODES)
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

        s.svc["catalog"].on("GET", "/v1/categories", lambda *a: DEPARTMENT_NODES)
        s.svc["seller"].on("POST", "/v1/orgs", off)
        s.step_sellers()
        self.assertEqual(len(s.report.problems), 1)



class SizeChartsTest(unittest.TestCase):
    """The size charts the sized fashion products link: written once through catalog, found again by name."""

    CODES = {D.DRESSES: "dress", D.TOPS: "top", D.MEN_SHIRTS: "menwear", D.KIDS: "kids"}

    def setUp(self):
        self.s = seeder()
        self.catalog = self.s.svc["catalog"]
        self.charts = []
        self.catalog.on("GET", "/v1/categories", lambda *a: [{"path": p, "id": f"cat-{c}", "productType": c} for p, c in self.CODES.items()])
        self.catalog.on("GET", "/v1/size-charts", lambda path, body, kw: {"items": [c for c in self.charts if kw["params"]["q"].lower() in c["name"].lower()]})

        def create(path, body, kw):
            chart = dict(body, id=f"chart-{len(self.charts)}")
            self.charts.append(chart)
            return chart

        self.catalog.on("POST", "/v1/size-charts", create)

    def test_writes_every_chart_for_the_type_of_its_category(self):
        self.s.step_size_charts()

        self.assertEqual([c["name"] for c in self.charts], [c.name for c in D.SIZE_CHARTS])
        self.assertEqual(self.s.report.problems, [])
        dresses = next(c for c in self.charts if c["name"] == "Women's dresses")
        self.assertEqual(dresses["categoryId"], "cat-dress")
        self.assertEqual(dresses["unitSystem"], "CM")
        self.assertEqual([c["key"] for c in dresses["columns"]], ["bust", "waist", "hip"])
        self.assertEqual(dresses["fitNote"], "TRUE_TO_SIZE")
        self.assertEqual(dresses["rows"][0], {"size": "XS", "values": {"bust": {"min": 80, "max": 83}, "waist": {"min": 62, "max": 65}, "hip": {"min": 88, "max": 91}}})
        self.assertIn("UK 12", [r["size"] for r in dresses["rows"]], "the seller's own size labels")
        mens = next(c for c in self.charts if c["name"] == "Men's shirts and kaftans")
        self.assertEqual(mens["unitSystem"], "IN")
        self.assertEqual(mens["rows"][0]["values"]["sleeve_length"], {"min": 24.5}, "a single value is sent without max")
        self.assertEqual(self.s.report.counts[("size charts", "created")], len(D.SIZE_CHARTS))

    def test_a_re_run_finds_the_charts_by_name_and_writes_nothing(self):
        self.s.step_size_charts()
        again = seeder()
        again.svc = self.s.svc
        again.step_size_charts()

        self.assertEqual(len(self.charts), len(D.SIZE_CHARTS))
        self.assertEqual(again.report.counts[("size charts", "existing")], len(D.SIZE_CHARTS))
        self.assertEqual({c["id"] for c in again.charts.values()}, {c["id"] for c in self.charts})

    def test_a_product_finds_or_writes_its_chart_even_when_only_the_products_step_runs(self):
        p = next(x for x in D.PRODUCTS if x.key == "dashiki")
        chart = self.s._chart_for(p, self.s._categories())

        self.assertEqual(chart["name"], "Men's shirts and kaftans")
        self.assertEqual(len(self.charts), 1)
        self.assertIs(self.s._chart_for(p, self.s._categories()), chart, "asked again, the same chart")
        self.assertIsNone(self.s._chart_for(next(x for x in D.PRODUCTS if x.key == "ofada-rice"), {}), "an unsized product links none")

    def test_a_chart_that_cannot_be_written_is_reported_and_the_product_is_listed_without_it(self):
        def refuse(path, body, kw):
            raise ApiError("POST", path, 422, {"code": "catalog.invalid"})

        self.catalog.on("POST", "/v1/size-charts", refuse)
        p = next(x for x in D.PRODUCTS if x.key == "agbada")
        self.assertIsNone(self.s._chart_for(p, self.s._categories()))
        self.assertEqual(len(self.s.report.problems), 1)
        self.assertIn("listed without it", self.s.report.problems[0])

    def test_an_existing_product_written_before_charts_gets_its_chart_linked_on_re_run(self):
        p = next(x for x in D.PRODUCTS if x.key == "dashiki")
        stored = {"id": "p1", "atin": "AT1", "status": "LIVE", "categoryId": "cat-menwear", "title": p.title, "attributes": {"brand": "x"},
                  "images": [{"url": "u"}], "variationTheme": "Size", "version": 3, "preorderEligible": False, "shipsNow": True,
                  "listing": {"description": "d", "handlingDays": 2},
                  "variants": [{"id": "v1", "atin": "AT1-1", "sellerSku": "S-1", "options": {"Size": "M"}, "priceMinor": 4000,
                                "currency": "USD", "quantity": 5, "gtinExempt": True}]}
        sent = []
        self.catalog.on("GET", "/v1/products/p1", lambda *a: stored)

        def edit(path, body, kw):
            sent.append(body)
            return dict(stored, listing=body["listing"])

        self.catalog.on("PUT", "/v1/products/p1", edit)

        done = self.s._link_existing_chart({"id": "p1", "atin": "AT1", "title": p.title}, p, self.s._categories())

        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0]["version"], 3)
        self.assertEqual(sent[0]["listing"]["description"], "d", "the rest of the listing is kept")
        self.assertEqual(sent[0]["listing"]["sizeChartId"], self.charts[0]["id"])
        self.assertEqual(sent[0]["variants"], [{"options": {"Size": "M"}, "sellerSku": "S-1", "gtinExempt": True, "priceMinor": 4000, "quantity": 5}])
        self.assertEqual(self.s.report.counts[("product size charts", "linked")], 1)
        self.assertEqual(done["listing"]["sizeChartId"], self.charts[0]["id"])

        stored["listing"] = sent[0]["listing"]
        sent.clear()
        self.assertIsNone(self.s._link_existing_chart({"id": "p1", "atin": "AT1", "title": p.title}, p, self.s._categories()))
        self.assertEqual(sent, [], "linked already: nothing is sent again")
        self.assertEqual(self.s.report.counts[("product size charts", "existing")], 1)

    def test_a_product_without_a_chart_in_the_dataset_is_left_alone(self):
        p = next(x for x in D.PRODUCTS if x.key == "ofada-rice")
        self.assertIsNone(self.s._link_existing_chart({"id": "p9", "atin": "AT9", "title": p.title}, p, self.s._categories()))

    def test_a_refused_edit_is_reported_and_the_run_goes_on(self):
        p = next(x for x in D.PRODUCTS if x.key == "dashiki")
        self.catalog.on("GET", "/v1/products/p1", lambda *a: {"id": "p1", "title": p.title, "listing": {}, "variants": []})

        def refuse(path, body, kw):
            raise ApiError("PUT", path, 422, {"code": "catalog.invalid"})

        self.catalog.on("PUT", "/v1/products/p1", refuse)
        self.assertIsNone(self.s._link_existing_chart({"id": "p1", "atin": "AT1", "title": p.title}, p, self.s._categories()))
        self.assertEqual(len(self.s.report.problems), 1)
        self.assertIn("could not be linked", self.s.report.problems[0])

    def test_the_step_comes_before_the_products_that_link_the_charts(self):
        from seedlib.seeder import STEPS

        self.assertLess(STEPS.index("size-charts"), STEPS.index("products"))


class PickupPointsTest(unittest.TestCase):
    def test_saves_every_afrotransact_pickup_point_with_its_fixed_id(self):
        s = seeder()
        saved = {}
        s.svc["carrier-gateway"].on("PUT", "/v1/pickup-locations/*", lambda path, body, kw: saved.setdefault(path.rsplit("/", 1)[1], body))
        s.step_pickups()

        self.assertEqual(set(saved), {p["id"] for p in D.PICKUP_POINTS})
        self.assertTrue(all(b["ownerOrg"] == D.PICKUP_OWNER and b["active"] and b["kind"] != "SELLER" for b in saved.values()))
        self.assertTrue(all(b["hours"] and b["timezone"] and b["position"] for b in saved.values()))
        self.assertEqual(s.report.counts[("pickup points", "saved")], len(D.PICKUP_POINTS))

    def test_skips_when_carrier_gateway_is_down(self):
        s = seeder()
        s.up["carrier-gateway"] = False
        s.step_pickups()
        self.assertEqual(s.svc["carrier-gateway"].calls, [])



class ServiceProvidersTest(unittest.TestCase):
    """step_bookings: every provider gets in-person services with a photo and a service area; a rerun changes nothing."""

    def run_step(self, s, store):
        bk = s.svc["bookings"]
        bk.on("PUT", "/v1/providers/*", lambda path, body, kw: store.setdefault(path, body))
        bk.on("GET", "/v1/providers/*", lambda path, body, kw: store.get("__get__" + path, []))

        def post(path, body, kw):
            if path.endswith("/team"):
                return {"id": "m1"}
            if path.endswith("/activate"):
                return {}
            store.setdefault("services", []).append((path, body))
            return {"id": f"s{len(store['services'])}", "name": body["name"], "modes": body["modes"], "active": True}

        bk.on("POST", "/v1/providers/*", post)
        s.uploader.upload = lambda img, purpose: {"url": "http://media.test/" + img.sha256[:8] + ".jpg"}
        s._images = lambda key, queries, count, **kw: [type("I", (), {"sha256": "abcdef12" + str(abs(hash(key)))})()]
        s.step_bookings()

    def test_in_person_services_with_photos_and_areas(self):
        s = seeder()
        store = {}
        self.run_step(s, store)
        services = store["services"]
        self.assertEqual(len(services), sum(len(p.services) for p in D.PROVIDERS))
        for _path, body in services:
            self.assertTrue(set(body["modes"]) <= {"IN_PERSON", "AT_HOME"}, body)
            self.assertTrue(body["imageUrl"].startswith("http://media.test/"), "a real photo, never a placeholder")
        areas = {p: b for p, b in store.items() if p.endswith("/service-area")}
        self.assertEqual(len(areas), len(D.PROVIDERS))
        hill = areas["/v1/providers/org_hill_country_braids/service-area"]
        self.assertEqual(hill, {"baseZip": "78664", "radiusMiles": 20, "extraAreas": [{"kind": "CITY", "value": "Taylor", "state": "TX"}]})
        bolas = areas["/v1/providers/org_bolas_kitchen/service-area"]
        self.assertEqual(bolas["extraAreas"], [{"kind": "ZONE", "value": "greater-austin"}])
        settings = {p: b for p, b in store.items() if p.endswith("/settings")}
        self.assertEqual(settings["/v1/providers/org_adire_tailors/settings"]["timeZone"], "America/New_York")

if __name__ == "__main__":
    unittest.main()
