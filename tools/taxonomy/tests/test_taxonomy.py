import copy
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import build_map  # noqa: E402
from evaluator import load, resolve  # noqa: E402
from validate import validate  # noqa: E402

SOURCE = json.loads((HERE / "source_scenarios.json").read_text(encoding="utf-8"))


class TaxonomyTest(unittest.TestCase):
    def setUp(self):
        self.map, self.scen = load(), load("scenarios.json")

    def test_committed_files_are_current(self):
        m, s = build_map.build()
        self.assertEqual(build_map.dump(m), (HERE / "category_tax_map.json").read_text(encoding="utf-8"))
        self.assertEqual(build_map.dump(s), (HERE / "scenarios.json").read_text(encoding="utf-8"))

    def test_validator_is_clean(self):
        self.assertEqual(validate(self.map, self.scen, SOURCE), [])

    def test_counts(self):
        self.assertEqual((len(self.map["departments"]), len(self.map["categories"]), len(self.scen["scenarios"])), (13, 73, 105))
        self.assertEqual(sum(len(c["decisionTable"]) for c in self.map["categories"]), 105)

    def test_every_scenario_resolves_as_expected(self):
        for s in self.scen["scenarios"]:
            i = s["inputs"]
            r = resolve(self.map, s["categoryCode"], i["facts"], i["sellerBehaviour"], i["context"])
            self.assertEqual(r["outcome"], s["expect"]["outcome"], s["scenario"])
            self.assertEqual(r["internalTaxCode"], s["expect"]["internalTaxCode"], s["scenario"])
            self.assertEqual(r["status"], s["expect"]["resolveStatus"], s["scenario"])

    def test_missing_fact_never_guesses(self):
        r = resolve(self.map, "nuts-seeds", {"is_candy": False, "candy_coated": False, "net_weight_oz": 8})
        self.assertEqual(r["status"], "MISSING_FACTS")
        self.assertIsNone(r["outcome"])
        self.assertEqual({m["factId"] for m in r["missingFacts"]}, {"servings_per_package", "individual_serving"})

    def test_gates(self):
        self.assertEqual(resolve(self.map, "flavored-water-soda")["status"], "CONFLICT_OPEN")
        self.assertEqual(resolve(self.map, "toys-games")["status"], "REFINE_CODE")
        self.assertEqual(resolve(self.map, "rice-grains")["status"], "RESOLVED")
        self.assertEqual(resolve(self.map, "gift-baskets-bundles", {"bundle_line_kind": "UNITEMIZED_MIXED_BUNDLE"})["status"], "BLOCKED")

    def test_documented_package_rule(self):
        def nuts(oz, servings, single=False):
            return resolve(self.map, "nuts-seeds", {"is_candy": False, "candy_coated": False, "net_weight_oz": oz, "servings_per_package": servings, "individual_serving": single})["outcome"]
        self.assertEqual(nuts(2.5, 2), "EXEMPT")
        self.assertEqual(nuts(2.4, 2), "TAXABLE")
        self.assertEqual(nuts(8, 1), "TAXABLE")

    def test_validator_detects_overlap_and_gaps(self):
        broken = copy.deepcopy(self.map)
        next(c for c in broken["categories"] if c["code"] == "bakery-products")["decisionTable"][1]["when"] = None
        self.assertTrue(any("rows match" in e for e in validate(broken, self.scen, SOURCE)))
        broken = copy.deepcopy(self.map)
        broken["categories"].pop()
        self.assertTrue(validate(broken, self.scen, SOURCE))


if __name__ == "__main__":
    unittest.main()
