import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import catalog_import_poc as poc  # noqa: E402
import detect  # noqa: E402


def fx(name):
    with open(os.path.join(HERE, "..", "fixtures", name), encoding="utf-8") as f:
        return f.read()


class Normalise(unittest.TestCase):
    def test_shopify_public_json(self):
        items = poc.from_shopify_products_json(json.loads(fx("shopify_products.json")))
        self.assertEqual(len(items), 4)
        wig = items[1]
        self.assertEqual([v.price for v in wig.variants], [129.0, 159.0])
        self.assertEqual(wig.variants[1].compare_at, 199.0)
        self.assertIsNone(items[0].variants[0].options.get("option"))  # "Default Title" is not a real option

    def test_shopify_bulk_jsonl_parents(self):
        items = poc.from_shopify_bulk_jsonl(fx("shopify_bulk.jsonl"))
        self.assertEqual([i.external_id for i in items], ["7001", "7005"])
        self.assertEqual(items[0].variants[0].stock, 24)
        self.assertAlmostEqual(items[0].variants[0].weight_g, 397)
        self.assertEqual(items[0].images[0].width, 1600)
        self.assertEqual(items[1].source_status, "DRAFT")
        self.assertAlmostEqual(items[1].variants[0].weight_g, 45.36, places=1)

    def test_woo_store_api_minor_units_and_variations(self):
        items = poc.from_woo_store_api(json.loads(fx("woo_store_products.json")))
        self.assertEqual(items[0].variants[0].price, 12.99)
        self.assertEqual(items[0].variants[0].compare_at, 14.99)
        self.assertEqual(items[0].source_category, "Hair Oils")
        self.assertEqual(len(items[1].variants), 2)
        self.assertEqual(items[1].variants[0].options, {"Size": "Small"})

    def test_json_ld_group_and_og_fallback(self):
        items = poc.from_json_ld(fx("jsonld_page.html"))
        self.assertEqual(len(items), 1)  # the Organization block is ignored
        self.assertEqual(len(items[0].variants), 2)
        self.assertEqual([v.available for v in items[0].variants], [True, False])
        self.assertEqual(len(items[0].images), 2)  # per-variant images, not the og:image

    def test_spreadsheet(self):
        items = poc.from_spreadsheet(fx("sheet.csv"))
        self.assertEqual(items[0].variants[0].stock, 40)
        self.assertIsNone(items[1].variants[0].price)  # "abc" is not a price, never guessed

    def test_gtin(self):
        self.assertTrue(poc.gtin_ok("012345678905"))
        self.assertFalse(poc.gtin_ok("012345678900"))
        self.assertFalse(poc.gtin_ok("0000000000000"))


class MappingAndGates(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rules = poc.load_rules()
        cls.items = poc.from_shopify_products_json(json.loads(fx("shopify_products.json")))

    def report(self, i):
        c = self.items[i]
        return poc.gate_report(c, self.rules, poc.map_category(c, self.rules))

    def test_category_codes_exist_in_our_taxonomy(self):
        codes = {c["code"] for c in self.rules["categories"]}
        self.assertTrue(set(poc.SEEDS) <= codes)

    def test_deterministic_mapping(self):
        self.assertEqual(poc.map_category(self.items[0], self.rules)["code"], "haircare")
        self.assertEqual(poc.map_category(self.items[1], self.rules)["code"], "wigs-extensions")

    def test_unknown_goes_to_ai_or_person_never_guessed(self):
        c = poc.from_spreadsheet(fx("sheet.csv"))[1]
        self.assertEqual(poc.map_category(c, self.rules)["decision"], "AI_OR_PERSON")

    def test_tax_is_never_asked_of_the_seller(self):
        for i in (0, 1, 3):
            r = self.report(i)
            self.assertTrue(any("tax" in p for p in r["platform_decides"]))
            self.assertFalse([m for m in r["missing"] if "tax" in m])

    def test_missing_fields_follow_the_kind_rules(self):
        r = self.report(0)  # beauty kind: ingredients, label photo, dangerous goods are required and never in a feed
        for f in ("ingredients", "label_photo", "dangerous_goods"):
            self.assertTrue(any(m.startswith(f) for m in r["missing"]), f)
        self.assertEqual(r["state"], "NEEDS_SELLER")

    def test_no_image_is_missing_and_icon_is_refused(self):
        self.assertTrue(any(m.startswith("images") for m in self.report(3)["missing"]))
        good, notes = poc.image_findings(self.items[2].images)
        self.assertEqual(good, [])
        self.assertTrue(notes)

    def test_small_main_image_warned(self):
        self.assertTrue(any("under 1000px" in n for n in self.report(1)["notes"]))

    def test_gift_card_not_imported_as_product(self):
        self.assertEqual(self.report(2)["state"], "SKIPPED")

    def test_sold_out_kept_as_draft_off_the_rails(self):
        self.assertIn("SOLD_OUT", self.report(3)["stock"])
        self.assertIn("some variants", self.report(1)["stock"])

    def test_invalid_barcode_is_dropped(self):
        c = poc.from_json_ld(fx("jsonld_page.html"))[0]
        r = poc.gate_report(c, self.rules, poc.map_category(c, self.rules))
        self.assertTrue(any("invalid barcode" in n for n in r["notes"]))
        self.assertTrue(any(m.startswith("product_id") for m in r["missing"]))

    def test_document_fields_are_not_per_product(self):
        r = self.report(0)
        self.assertFalse(any("authorization" in m or "invoice" in m for m in r["missing"]))


class Ssrf(unittest.TestCase):
    def test_refused(self):
        for u in ("http://example.com/a.jpg", "https://127.0.0.1/x", "https://[::1]/x", "https://2130706433/x", "https://0x7f.1/x",
                  "https://169.254.169.254/latest/meta-data", "https://shop.example.com:8443/x", "https://u:p@example.com/x",
                  "https://metadata.google.internal/x", "https://db.internal/x"):
            self.assertIsNotNone(poc.check_url(u), u)

    def test_allowed_static_check(self):
        self.assertIsNone(poc.check_url("https://cdn.example.com/a.jpg"))

    def test_resolved_address_classes(self):
        for ip in ("10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.169.254", "127.0.0.1", "::1", "fd00:ec2::254", "::ffff:10.0.0.1", "100.64.0.1"):
            self.assertTrue(poc.non_public(ip), ip)
        self.assertFalse(poc.non_public("93.184.216.34"))


class Detect(unittest.TestCase):
    def test_shopify(self):
        e = detect.detect({"X-ShopId": "123"}, '<script src="https://cdn.shopify.com/s/x.js"></script>')
        self.assertEqual(e[0].platform, "shopify")

    def test_woo(self):
        e = detect.detect({}, '<link href="/wp-content/plugins/woocommerce/a.css">')
        self.assertEqual(e[0].platform, "woocommerce")

    def test_json_ld_generic(self):
        e = detect.detect({}, fx("jsonld_page.html"))
        self.assertIn("generic-json-ld", [x.platform for x in e])

    def test_unknown_is_empty_not_a_guess(self):
        self.assertEqual(detect.detect({"server": "nginx"}, "<html><body>Hello</body></html>"), [])
        self.assertEqual(detect.detect(None, None), [])

    def test_plugin_registration_needs_no_core_change(self):
        before = len(detect.REGISTRY)

        @detect.connector
        class Acme(detect.Fingerprint):
            platform = "acme"
            html_hits = (r"acme-cart",)

        try:
            self.assertEqual(detect.detect({}, "<div class='acme-cart'>")[0].platform, "acme")
        finally:
            detect.REGISTRY.pop()
        self.assertEqual(len(detect.REGISTRY), before)


if __name__ == "__main__":
    unittest.main()
