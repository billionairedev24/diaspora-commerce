"""The demo dataset is valid for the catalog's taxonomy (tools/seed/catalog_taxonomy.py, the V2 migration's source):
every product path exists, variants fit a variation theme, attribute values fit their options, SKUs follow catalog's
rule and are unique per seller, ISBNs are valid."""
import re
import unittest

import catalog_taxonomy as T
from seedlib import dataset as D
from seedlib.seeder import attributes_for, sku_for, theme_for

PATHS = {f"{dept} › {cat} › {leaf}": code for dept, cats in T.TREE.items() for cat, leaves in cats.items()
         for leaf, code in leaves.items()}


def product_type(code):
    _, attrs, themes, rule = T.TYPES[code]
    return {"code": code, "attributes": attrs, "themes": themes, "identifierRule": rule}


def isbn_ok(digits):
    return len(digits) == 13 and sum(int(c) * (1 if i % 2 == 0 else 3) for i, c in enumerate(digits)) % 10 == 0


class DatasetTest(unittest.TestCase):
    def test_size_and_unique_keys(self):
        self.assertTrue(60 <= len(D.PRODUCTS) <= 120, len(D.PRODUCTS))
        self.assertEqual(len({p.key for p in D.PRODUCTS}), len(D.PRODUCTS))
        self.assertEqual(len({p.title for p in D.PRODUCTS}), len(D.PRODUCTS), "titles are the idempotency key")
        for p in D.PRODUCTS:
            self.assertRegex(p.key, r"^[a-z0-9]+(-[a-z0-9]+)*$")
            self.assertFalse(re.search(r"-\d+$", p.key), "trailing numbers mean extra photos in SEED_IMAGES_DIR")
            self.assertTrue(10 <= len(p.title) <= 200, p.title)
            self.assertTrue(2 <= p.images <= 4)
            self.assertTrue(p.queries, p.key)

    def test_every_product_fits_the_catalog_taxonomy(self):
        skus = {}
        for p in D.PRODUCTS:
            with self.subTest(product=p.key):
                self.assertIn(p.path, PATHS)
                ptype = product_type(PATHS[p.path])
                theme, options = theme_for(ptype, p.variants)
                dims = next(t["dimensions"] for t in ptype["themes"] if t["name"] == theme)
                for opts in options:
                    for d in dims:
                        self.assertIn(opts[d["name"]], d["options"])
                attrs = attributes_for(p, ptype, ["credit"])
                for a in ptype["attributes"]:
                    value = attrs[a["key"]]
                    self.assertTrue(value, a["key"])
                    if a["options"]:
                        self.assertIn(value, a["options"], a["key"])
                self.assertEqual(attrs["community"], p.community)
                for opts, (_, price) in zip(options, p.variants):
                    self.assertGreater(price, 0)
                    sku = sku_for(p.seller, p.key, opts)
                    self.assertRegex(sku, r"^[A-Z0-9][A-Z0-9._-]{1,39}$")
                    self.assertNotIn((p.seller, sku), skus, f"duplicate SKU with {skus.get((p.seller, sku))}")
                    skus[(p.seller, sku)] = p.key
                if ptype["identifierRule"] == "ISBN":
                    self.assertTrue(isbn_ok(p.attrs["gtin"]), p.attrs["gtin"])
                    self.assertEqual(len(p.variants), 1, "one ISBN per book variant")

    def test_sellers_offers_and_content_reference_known_things(self):
        keys = {p.key for p in D.PRODUCTS}
        orgs = [s.org_id for s in D.SELLERS] + [p.org_id for p in D.PROVIDERS]
        self.assertEqual(len(orgs), len(set(orgs)))
        for org in orgs:
            self.assertRegex(org, r"^org_[a-z0-9_]{3,60}$")
        self.assertIn(D.RETAIL_ORG, orgs)
        nodes = {"HOU-FC", "ATL-FC", "EWR-FC", "LAX-FC"}
        for s in D.SELLERS:
            self.assertIn(s.node, nodes)
            self.assertIn(s.fulfilment, ("AFROTRANSACT", "SELLER"))
        for p in D.PRODUCTS:
            self.assertIn(p.seller, D.SELLER_BY_KEY)
            for other, factor in p.also:
                self.assertIn(other, D.SELLER_BY_KEY)
                self.assertNotEqual(other, p.seller)
                self.assertTrue(0.5 < factor < 2)
        for ad in D.ADS:
            self.assertIn(ad["seller"], D.SELLER_BY_KEY)
            for k in ad["products"]:
                self.assertIn(k, keys)
                self.assertEqual(next(p.seller for p in D.PRODUCTS if p.key == k), ad["seller"],
                                 "advertisers promote products whose buy box they hold")
        names = [x["name"] for x in D.HEROES + D.TILES + D.BANNERS + D.RAILS + D.CATEGORY_BANNERS]
        self.assertEqual(len(names), len(set(names)))
        self.assertLessEqual(len(D.HEROES), 6)
        self.assertLessEqual(len(D.RAILS) + 1, 6, "home_product_row holds 6 (one comes from the migration)")
        for t in D.TILES:
            self.assertLessEqual(len(t["cells"]), 4)
        for h in D.HEROES:
            self.assertLessEqual(len(h["payload"]["title"]), 60)
            self.assertIn(h["payload"]["label"], ("AfroTransact", "Sponsored"))
        departments = {path.split(" › ")[0] for path in PATHS}
        for c in D.CATEGORY_BANNERS:
            self.assertIn(c["category"], departments)
        for hrefs in [x["payload"].get("href") for x in D.HEROES + D.TILES + D.BANNERS + D.RAILS + D.CATEGORY_BANNERS]:
            self.assertTrue(hrefs.startswith("/"), hrefs)
            self.assertNotIn("/search", hrefs, "the storefront's search page is /s")

    def test_design_nav_departments_and_communities_are_covered(self):
        departments = {p.path.split(" › ")[0] for p in D.PRODUCTS}
        self.assertTrue({"Groceries", "Fashion", "Beauty", "Home & Art", "Books", "Health", "Event & party"} <= departments)
        communities = {p.community for p in D.PRODUCTS}
        self.assertTrue({"Nigerian", "Ghanaian", "Ethiopian", "Indian", "Jamaican"} <= communities)
        self.assertTrue(any(p.brand == "AfroTransact Brand" for p in D.PRODUCTS))
        for name in ("Mama Put Pantry", "Lagos Larder", "Accra Market Co.", "Bonwire Weavers Collective", "Kente & Co.",
                     "Tamale Naturals"):
            self.assertIn(name, {s.name for s in D.SELLERS})


class SkuThemeTest(unittest.TestCase):
    def test_theme_errors(self):
        with self.assertRaises(ValueError):
            theme_for(product_type("grocery"), (("3 lb", 100),))
        with self.assertRaises(ValueError):
            theme_for(product_type("dress"), (({"Size": "M", "Fit": "x"}, 100),))
        self.assertEqual(theme_for(product_type("dress"), (({"Size": "M", "Color": "Gold"}, 1),))[0], "Size × Color")
        self.assertEqual(theme_for(product_type("dress"), (("M", 1),))[0], "Size")

    def test_sku(self):
        self.assertEqual(sku_for("mama-put", "ofada-rice", {"Net weight": "5 lb"}), "MAMA-OFADA-RICE-5LB")
        self.assertNotEqual(sku_for("kente-co", "dress", {"Size": "S", "Color": "Multicolour"}),
                            sku_for("kente-co", "dress", {"Size": "M", "Color": "Multicolour"}))


if __name__ == "__main__":
    unittest.main()
