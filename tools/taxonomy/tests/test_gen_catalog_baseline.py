import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import gen_catalog_baseline as gen  # noqa: E402


class CatalogBaselineTest(unittest.TestCase):
    def setUp(self):
        self.dataset = gen.load(gen.FROZEN / "category_tax_map.json")
        self.schemas = gen.load(gen.FROZEN / "schemas.json")

    def test_committed_initial_data_is_current(self):
        self.assertEqual(gen.OUT.read_text(encoding="utf-8"), gen.build())

    def test_generation_is_deterministic(self):
        self.assertEqual(gen.build(), gen.build())

    def test_ids_match_the_catalog_planner(self):
        # TaxonomyPlanner.idOf("beauty-personal-care") in the catalog service
        self.assertEqual(gen.uuid_of(gen.NAMESPACE, "beauty-personal-care"), "97728d39-b4bf-5ba7-9939-84a26af78563")

    def test_tree_shape(self):
        nodes, types = gen.plan(self.dataset, self.schemas)
        levels = [n["level"] for n in nodes]
        self.assertEqual(levels.count("DEPARTMENT"), 13)
        self.assertEqual(levels.count("CATEGORY"), 74)  # 73 plus the provisional Fabrics & textiles
        self.assertEqual(levels.count("PRODUCT_TYPE"), 77)
        self.assertEqual(len({n["code"] for n in nodes}), len(nodes))
        self.assertEqual(len({n["id"] for n in nodes}), len(nodes))
        self.assertEqual(len({t["code"] for t in types}), len(types))
        fabrics = next(n for n in nodes if n["code"] == "fabrics-textiles")
        self.assertEqual((fabrics["status"], fabrics["profile"]), ("NEEDS_APPROVAL", None))

    def test_every_category_has_a_tax_profile_and_status_except_the_provisional_one(self):
        nodes, _ = gen.plan(self.dataset, self.schemas)
        for n in nodes:
            if n["level"] == "CATEGORY" and n["code"] != "fabrics-textiles":
                self.assertEqual(n["profile"], n["code"])
                self.assertIn(n["status"], {"ACTIVE", "NEEDS_APPROVAL", "BLOCKED_BUNDLE", "REFINE_CODE", "CONFLICT_OPEN"})

    def test_fashion_types_are_sized(self):
        _, types = gen.plan(self.dataset, self.schemas)
        by_code = {t["code"]: t for t in types}
        for code in ("dress", "top", "menwear", "footwear", "kids"):
            self.assertEqual(by_code[code]["traits"]["sizing"]["sizeDimension"], "Size")

    def test_starter_brands_are_scoped_to_existing_nodes(self):
        nodes, _ = gen.plan(self.dataset, self.schemas)
        codes = {n["code"] for n in nodes}
        brands = gen.load(gen.FROZEN / "starter_brands.json")
        self.assertEqual(len(brands), 56)
        for b in brands:
            self.assertTrue(b["scopes"], b["name"])
            self.assertTrue(set(b["scopes"]) <= codes, b["name"])


if __name__ == "__main__":
    unittest.main()
