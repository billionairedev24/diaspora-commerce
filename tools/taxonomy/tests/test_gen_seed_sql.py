import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import gen_seed_sql  # noqa: E402


class SeedSqlTest(unittest.TestCase):
    def setUp(self):
        self.map = json.loads(gen_seed_sql.MAP.read_text(encoding="utf-8"))

    def test_generation_is_deterministic(self):
        self.assertEqual(gen_seed_sql.generate(self.map), gen_seed_sql.generate(json.loads(json.dumps(self.map))))

    def test_committed_seed_migration_is_current(self):
        self.assertEqual(gen_seed_sql.OUT.read_text(encoding="utf-8"), gen_seed_sql.generate(self.map))

    def test_every_outcome_code_and_stripe_code_is_seeded(self):
        sql = gen_seed_sql.generate(self.map)
        rows = [r for c in self.map["categories"] for r in c["decisionTable"]]
        self.assertEqual(len(rows), 105)
        for r in rows:
            self.assertIn("'%s'" % r["internalTaxCode"], sql)
        self.assertEqual(sql.count("('AT."), 105 * 3)  # an outcome code row and two provider map rows each


if __name__ == "__main__":
    unittest.main()
