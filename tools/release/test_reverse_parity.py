#!/usr/bin/env python3
"""Tests of tools/release/reverse_parity.py on a throwaway git repository (no network, no build):
python3 tools/release/test_reverse_parity.py

The repository has a `main` and a `release-1` branch cut from one commit; each test changes ONE thing on a side and expects the matching
difference with the right direction (port-back = release-1 changed it, sync-pending = main changed it, both = a conflict).
"""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import reverse_parity  # noqa: E402

MIG = "java/services/orders/src/main/resources/db/migration/"
BASE = {
    MIG + "V1__init.sql": "create table a(id int);\n",
    "contracts/openapi/orders.yaml": "openapi: 3.1.0\n",
    "java/services/identity/src/main/java/com/x/application/IdentityPermissions.java": "class IdentityPermissions { String A = \"a\"; }\n",
    "web/apps/storefront/src/i18n/en.json": json.dumps({"a": {"b": "x"}, "c": "y"}),
    "tools/release/parity.yaml": "version: 1\n",
}


def sh(root, *args):
    subprocess.run(["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@t", *args], check=True, capture_output=True)


class ReverseParityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        sh(self.root, "init", "-q", "-b", "main")
        for p, c in BASE.items():
            self.write(p, c)
        sh(self.root, "add", "-A")
        sh(self.root, "commit", "-q", "-m", "base")
        sh(self.root, "branch", "release-1")

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, path, content):
        f = self.root / path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(content)

    def commit(self, branch, files):
        sh(self.root, "checkout", "-q", branch)
        for p, c in files.items():
            self.write(p, c)
        sh(self.root, "add", "-A")
        sh(self.root, "commit", "-q", "-m", "change")

    def diffs(self, **kw):
        sh(self.root, "checkout", "-q", "release-1")
        return {(d.category, d.subject, d.direction) for d in reverse_parity.collect(self.root, "main", "release-1", **kw)}

    def test_identical_is_clean(self):
        self.assertEqual(self.diffs(), set())

    def test_migration_only_on_release_1_is_port_back(self):
        self.commit("release-1", {MIG + "V2__x.sql": "alter table a add b int;\n"})
        self.assertEqual(self.diffs(), {("migrations", MIG + "V2__x.sql", "port-back")})

    def test_migration_only_on_main_is_sync_pending(self):
        self.commit("main", {MIG + "V2__x.sql": "alter table a add b int;\n"})
        self.assertEqual(self.diffs(), {("migrations", MIG + "V2__x.sql", "sync-pending")})

    def test_contract_changed_on_release_1_only(self):
        self.commit("release-1", {"contracts/openapi/orders.yaml": "openapi: 3.1.1\n"})
        self.assertEqual(self.diffs(), {("contracts", "contracts/openapi/orders.yaml", "port-back")})

    def test_both_changed_is_both(self):
        self.commit("release-1", {"contracts/openapi/orders.yaml": "openapi: 3.1.1\n"})
        self.commit("main", {"contracts/openapi/orders.yaml": "openapi: 3.1.2\n"})
        self.assertEqual(self.diffs(), {("contracts", "contracts/openapi/orders.yaml", "both")})

    def test_permission_seed_differs(self):
        p = "java/services/identity/src/main/java/com/x/application/IdentityPermissions.java"
        self.commit("release-1", {p: "class IdentityPermissions { String A = \"a\"; String B = \"b\"; }\n"})
        self.assertEqual(self.diffs(), {("permissions", "java/services/identity/src/main/java/com/x/application/IdentityPermissions.java", "port-back")})

    def test_i18n_compares_keys_not_text(self):
        f = "web/apps/storefront/src/i18n/en.json"
        self.commit("release-1", {f: json.dumps({"a": {"b": "changed text"}, "c": "y", "d": "new"})})
        self.assertEqual(self.diffs(), {("i18n", "web/apps/storefront/src/i18n/en.json:d", "port-back")})

    def test_i18n_key_only_on_main(self):
        f = "web/apps/storefront/src/i18n/en.json"
        self.commit("main", {f: json.dumps({"a": {"b": "x"}, "c": "y", "e": "z"})})
        self.assertEqual(self.diffs(), {("i18n", "web/apps/storefront/src/i18n/en.json:e", "sync-pending")})

    def test_skip_needs_a_glob_and_is_honoured(self):
        self.commit("release-1", {MIG + "V2__x.sql": "select 1;\n"})
        self.assertEqual(self.diffs(skips={"java/services/orders/*": "release-1 only on purpose"}), set())

    def test_cli_reports_and_strict_fails(self):
        self.commit("release-1", {MIG + "V2__x.sql": "select 1;\n"})
        cmd = [sys.executable, str(HERE / "reverse_parity.py"), "--root", str(self.root), "--main", "main", "--head", "release-1"]
        ok = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(ok.returncode, 0)
        self.assertIn("port-back", ok.stdout)
        self.assertEqual(subprocess.run(cmd + ["--strict"], capture_output=True, text=True).returncode, 1)


if __name__ == "__main__":
    unittest.main()
