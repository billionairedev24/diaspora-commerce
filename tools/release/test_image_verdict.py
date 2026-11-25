#!/usr/bin/env python3
"""Tests of the unverified-image guard: tools/release/image_verdict.py and the --require-tags rule of check_launch_set.py.

    python3 tools/release/test_image_verdict.py
"""
import glob
import pathlib
import sys
import unittest

import yaml

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "infra/argocd/scripts"))
import image_verdict as iv  # noqa: E402
import check_launch_set as cls  # noqa: E402


def sig(value=None):
    optional = {} if value is None else {iv.ANNOTATION: value}
    return {"critical": {"image": {"docker-manifest-digest": "sha256:" + "a" * 64}}, "optional": optional}


class Verdict(unittest.TestCase):
    def test_states(self):
        self.assertEqual(iv.verdict([sig("true")]), "verified")
        self.assertEqual(iv.verdict([sig("false")]), "unverified")
        self.assertEqual(iv.verdict([sig()]), "unmarked")
        self.assertEqual(iv.verdict([]), "unmarked")
        self.assertEqual(iv.verdict([{"optional": None}]), "unmarked")

    def test_one_unverified_signature_taints_the_digest(self):
        self.assertEqual(iv.verdict([sig("true"), sig("false")]), "unverified")
        self.assertEqual(iv.verdict([sig("true"), sig()]), "unmarked")

    def test_prod_refuses_unverified(self):
        for svc in ("afrotransact-core", "afrotransact-go", "cart"):
            self.assertIsNotNone(iv.decide("prod", svc, [sig("false")])[1], svc)

    def test_prod_refuses_unmarked_deployable_but_not_legacy_service(self):
        self.assertIsNotNone(iv.decide("prod", "afrotransact-core", [sig()])[1])
        self.assertIsNone(iv.decide("prod", "cart", [sig()])[1])

    def test_prod_accepts_verified(self):
        self.assertEqual(iv.decide("prod", "afrotransact-go", [sig("true")]), ("verified", None))

    def test_uat_and_dev_accept_unverified(self):
        for env in ("uat", "dev"):
            self.assertEqual(iv.decide(env, "afrotransact-core", [sig("false")]), ("unverified", None))


class RequireTagsRule(unittest.TestCase):
    def test_marker_refused_in_prod_only(self):
        pinned = {"image": {"tag": "a" * 40, "digest": "sha256:" + "b" * 64, "unverified": True}}
        self.assertTrue(cls.unverified_pin_error("prod", "x", pinned))
        self.assertIsNone(cls.unverified_pin_error("uat", "x", pinned))
        self.assertIsNone(cls.unverified_pin_error("prod", "x", {"image": {"tag": "a" * 40}}))
        self.assertIsNone(cls.unverified_pin_error("prod", "x", {}))

    def test_committed_prod_pins_are_not_marked(self):
        files = glob.glob(str(ROOT / "*/*/*/deploy/helm/values-prod.yaml"))
        self.assertTrue(files)
        for f in files:
            values = yaml.safe_load(pathlib.Path(f).read_text()) or {}
            self.assertFalse((values.get("image") or {}).get("unverified"), f)


if __name__ == "__main__":
    unittest.main()
