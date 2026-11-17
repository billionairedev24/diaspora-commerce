#!/usr/bin/env python3
"""Tests of tools/release/check_capacity.py on a copy of the real charts: python3 tools/release/test_capacity.py"""
import contextlib
import io
import pathlib
import re
import shutil
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import check_capacity  # noqa: E402


def copy_tree(dst):
    for pattern in ("java/services/*/deploy/helm/values*.yaml", "go/services/*/deploy/helm/values*.yaml", "java/apps/core/deploy/helm/values*.yaml",
                    "go/apps/afrotransact-go/deploy/helm/values*.yaml", "infra/argocd/launch-set/release-1.yaml", "tools/release/parity.yaml"):
        for f in ROOT.glob(pattern):
            t = dst / f.relative_to(ROOT)
            t.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(f, t)


class Capacity(unittest.TestCase):
    def run_check(self, root):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = check_capacity.main(root)
        return rc, out.getvalue()

    def setUp(self):
        self._t = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._t.name)
        copy_tree(self.root)

    def tearDown(self):
        self._t.cleanup()

    def test_the_real_plan_fits(self):
        rc, out = self.run_check(ROOT)
        self.assertEqual(rc, 0, out)
        self.assertIn("after batch 1", out)

    def test_a_smaller_node_does_not_fit(self):
        p = self.root / "tools/release/parity.yaml"
        p.write_text(p.read_text().replace("allocatable_mib: 13312", "allocatable_mib: 11000"))
        rc, out = self.run_check(self.root)
        self.assertEqual(rc, 1)
        self.assertIn("DOES NOT FIT", out)

    def test_core_request_below_the_schedule_at_the_batch_production_is_at(self):
        plan = self.root / "infra/argocd/launch-set/release-1.yaml"
        # Production runs the whole launch set now: put it back at batch 1 (the state this check is about).
        text = re.sub(r"(  prod:\n)    afrotransact-core: \[[^\]]*\]\n    afrotransact-go: \[[^\]]*\]",
                      r"\1    afrotransact-core: [catalog, tax-returns]\n    afrotransact-go: [cart, geo]", plan.read_text())
        self.assertNotEqual(text, plan.read_text())
        plan.write_text(text)
        v = self.root / "java/apps/core/deploy/helm/values-prod.yaml"
        v.write_text(v.read_text().replace("memory: 1536Mi}", "memory: 512Mi}"))
        rc, out = self.run_check(self.root)
        self.assertEqual(rc, 1)
        self.assertIn("reserves 512 Mi for core, the schedule says 768 Mi", out)


if __name__ == "__main__":
    unittest.main()
