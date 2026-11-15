#!/usr/bin/env python3
"""Offline test of route_diff.py: python3 tools/release/test_route_diff.py"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "route_diff.py"


class RouteDiff(unittest.TestCase):
    def run_diff(self, contract, live, module="m"):
        with tempfile.TemporaryDirectory() as d:
            d = pathlib.Path(d)
            (d / f"{module}.yaml").write_text(contract)
            (d / "live.json").write_text(json.dumps(live))
            r = subprocess.run([sys.executable, str(SCRIPT), "--contracts", str(d), "--live-file", str(d / "live.json"), "--modules", module],
                               capture_output=True, text=True)
            return r.returncode, r.stdout

    CONTRACT = "openapi: 3.1.0\npaths:\n  /v1/items/{id}:\n    get: {}\n    put: {}\n  /v1/items:\n    post: {}\n"

    def test_equal_up_to_parameter_names(self):
        code, out = self.run_diff(self.CONTRACT, {"paths": {"/v1/items/{itemId}": {"get": {}, "put": {}}, "/v1/items": {"post": {}}}})
        self.assertEqual(code, 0, out)

    def test_missing_route_fails_and_is_named(self):
        code, out = self.run_diff(self.CONTRACT, {"paths": {"/v1/items/{id}": {"get": {}}, "/v1/items": {"post": {}}}})
        self.assertEqual(code, 1)
        self.assertIn("MISSING  PUT /v1/items/{}", out)

    def test_extra_live_route_is_not_a_failure(self):
        code, _ = self.run_diff(self.CONTRACT, {"paths": {"/v1/items/{id}": {"get": {}, "put": {}}, "/v1/items": {"post": {}}, "/v1/other": {"get": {}}}})
        self.assertEqual(code, 0)

    def test_module_without_contract_is_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            (pathlib.Path(d) / "live.json").write_text("{}")
            r = subprocess.run([sys.executable, str(SCRIPT), "--contracts", d, "--live-file", str(pathlib.Path(d) / "live.json"), "--modules", "nope"],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0)
            self.assertIn("skipped", r.stdout)


if __name__ == "__main__":
    unittest.main()
