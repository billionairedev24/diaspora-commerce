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
    """The type as the catalog serves it after V15: fashion types let sellers write their own Size and Color values."""
    _, attrs, themes, rule = T.TYPES[code]
    return {"code": code, "attributes": attrs, "themes": T.with_custom(code, themes), "identifierRule": rule}


OWN_VALUE_MARKS = " .,/+()'’&#×:_–—-"


def own_value_ok(value):
    """Catalog's rule for a size or colour the seller writes: at most 30 characters, letters, digits, spaces and a few marks."""
    return (0 < len(value) <= 30 and value == " ".join(value.split()) and value[0].isalnum()
            and all(c.isalnum() or c in OWN_VALUE_MARKS for c in value))


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
                        if d.get("allowCustomValues"):
                            self.assertTrue(opts[d["name"]] in d["options"] or own_value_ok(opts[d["name"]]), opts)
                        else:
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

    def test_seller_written_sizes_and_colours_follow_catalogs_rules(self):
        own = {}
        for p in D.PRODUCTS:
            ptype = product_type(PATHS[p.path])
            theme, options = theme_for(ptype, p.variants)
            dims = next(t["dimensions"] for t in ptype["themes"] if t["name"] == theme)
            for d in dims:
                values = [o[d["name"]] for o in options]
                seen = {}
                for v in values:
                    seen.setdefault(v.lower(), v)
                self.assertEqual(len(seen), len(set(values)), f"{p.key}: {d['name']} values differ only by case")
                mine = sorted({v for v in values if v not in d["options"]})
                if mine:
                    self.assertTrue(d.get("allowCustomValues"), f"{p.key}: {d['name']} does not allow {mine}")
                    self.assertLessEqual(len(mine), 40)
                    own[p.key] = own.get(p.key, []) + mine
        self.assertIn("UK 12", own["adire-shift-dress"], "the demo shows a size the category does not list")
        self.assertIn("Indigo adire", own["adire-shift-dress"])
        self.assertEqual(own["sequin-gele"], ["Champagne", "Teal & gold"], "custom colours of a headwrap")

    def test_size_charts_are_valid_and_cover_what_their_products_sell(self):
        self.assertEqual(len({c.key for c in D.SIZE_CHARTS}), len(D.SIZE_CHARTS))
        self.assertEqual(len({c.name.lower() for c in D.SIZE_CHARTS}), len(D.SIZE_CHARTS), "names are the idempotency key")
        for c in D.SIZE_CHARTS:
            with self.subTest(chart=c.key):
                self.assertTrue(2 <= len(c.name) <= 80)
                self.assertIn(c.path, PATHS)
                code = PATHS[c.path]
                self.assertIn(code, T.SIZING, "a chart is made for a sized product type")
                self.assertIn(c.unit, ("CM", "IN"))
                self.assertIn(c.fit, ("", "RUNS_SMALL", "TRUE_TO_SIZE", "RUNS_LARGE"))
                self.assertLessEqual(len(c.how), 1000)
                self.assertTrue(1 <= len(c.columns) <= 12 and 1 <= len(c.rows) <= 60)
                keys = [k for k, _ in c.columns]
                self.assertEqual(len(set(keys)), len(keys))
                for key, label in c.columns:
                    self.assertRegex(key, r"^[a-z][a-z0-9_]{0,39}$")
                    self.assertEqual(key, re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_"), "the key a label derives to")
                    self.assertTrue(1 <= len(label) <= 40)
                sizes = [s.lower() for s, _ in c.rows]
                self.assertEqual(len(set(sizes)), len(sizes), "no two sizes equal ignoring case")
                for size, values in c.rows:
                    self.assertTrue(own_value_ok(size), size)
                    self.assertEqual(len(values), len(c.columns), f"{size}: a value for every measurement")
                    for lo, hi in values:
                        self.assertTrue(0 < lo <= hi <= 1000, (size, lo, hi))

    def test_sized_products_link_a_chart_of_their_type_with_a_row_for_every_size_sold(self):
        linked = [p for p in D.PRODUCTS if p.chart]
        self.assertGreaterEqual(len(linked), 6)
        for p in linked:
            with self.subTest(product=p.key):
                chart = D.SIZE_CHART_BY_KEY[p.chart]
                code = PATHS[p.path]
                self.assertEqual(PATHS[chart.path], code, "a chart is linked to products of its product type")
                self.assertIn(code, T.SIZING)
                if p.fit:
                    self.assertIn(p.fit, ("RUNS_SMALL", "TRUE_TO_SIZE", "RUNS_LARGE"))
                rows = {s.lower() for s, _ in chart.rows}
                _, options = theme_for(product_type(code), p.variants)
                for o in options:
                    self.assertIn(o["Size"].lower(), rows, f"{p.key} sells size {o['Size']} that {chart.name} has no row for")

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
