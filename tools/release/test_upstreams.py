#!/usr/bin/env python3
"""Tests of tools/release/upstreams.py: the rules must fail on the mistakes they exist for (old port, old hostname after a cutover, missing prefix,
dangling deployable URL, a public route to a private Service) and the committed charts must be clean in every cutover state.

    python3 tools/release/test_upstreams.py
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import upstreams as u  # noqa: E402

REG = u.registry()
CORE, GO = "afrotransact-core", "afrotransact-go"


def url_errors(name, value, module, env="prod", cutover=None, runtime="java"):
    errors = []
    u.check_url(name, value, module, REG, env, cutover or {}, runtime, 8080, errors, "t")
    return errors


class UrlRules(unittest.TestCase):
    def test_old_port_fails(self):
        self.assertTrue(any("old per-service port" in e for e in url_errors("IDENTITY_URL", "http://localhost:8100", "identity")))
        self.assertTrue(any("old per-service port" in e for e in url_errors("GEO_URL", "http://geo:8205", "geo")))

    def test_service_8080_fails(self):
        self.assertTrue(any(":8080" in e for e in url_errors("CATALOG_URL", "http://catalog:8080", "catalog")))

    def test_old_hostname_after_cutover_fails_but_is_fine_before(self):
        self.assertTrue(url_errors("CATALOG_URL", "http://catalog", "catalog", cutover={"catalog": CORE}))
        self.assertEqual(url_errors("CATALOG_URL", "http://catalog", "catalog", cutover={}), [])

    def test_deployable_url_needs_the_module_prefix_and_the_move(self):
        ok = "http://afrotransact-core.afrotransact-prod.svc/modules/payments"
        self.assertEqual(url_errors("PAYMENTS_URL", ok, "payments", cutover={"payments": CORE}, runtime="go"), [])
        self.assertTrue(any("/modules/payments" in e for e in url_errors("PAYMENTS_URL", "http://afrotransact-core.afrotransact-prod.svc", "payments", cutover={"payments": CORE}, runtime="go")))
        self.assertTrue(any("has not moved" in e for e in url_errors("PAYMENTS_URL", ok, "payments", cutover={}, runtime="go")))

    def test_flat_and_prefixed_go_modules(self):
        base = "http://afrotransact-go.afrotransact-prod.svc"
        self.assertEqual(url_errors("GEO_URL", base, "geo", cutover={"geo": GO}), [])
        self.assertTrue(url_errors("GEO_URL", base + "/modules/geo", "geo", cutover={"geo": GO}))
        self.assertEqual(url_errors("CART_URL", base + "/modules/cart", "cart", cutover={"cart": GO}), [])
        self.assertTrue(url_errors("CART_URL", base, "cart", cutover={"cart": GO}))

    def test_loopback_only_on_core_for_a_module_that_runs_there(self):
        self.assertEqual(url_errors("CATALOG_URL", "http://localhost:8080/modules/catalog", "catalog", cutover={"catalog": CORE}), [])
        self.assertTrue(url_errors("CATALOG_URL", "http://localhost:8080/modules/catalog", "catalog", cutover={}))
        self.assertTrue(url_errors("CATALOG_URL", "http://localhost:8101", "catalog", cutover={"catalog": CORE}))
        self.assertTrue(url_errors("CATALOG_URL", "http://localhost:8080/modules/catalog", "catalog", cutover={"catalog": CORE}, runtime="go"))


class Edge(unittest.TestCase):
    def route(self, backend="gateway-bff", extra=None, denied=()):
        rules = [{"matches": [{"path": {"type": "PathPrefix", "value": "/bff"}}], "backendRefs": [{"name": backend, "port": 80}]}]
        rules += extra or []
        if denied:
            rules.append({"matches": [{"path": {"value": d}} for d in denied], "filters": [{"type": "ExtensionRef", "extensionRef": {"name": "deny-operational-paths"}}]})
        return [("x/routes.yaml", {"metadata": {"name": "api", "namespace": "afrotransact-prod"}, "spec": {"rules": rules}})]

    def test_private_backend_fails(self):
        errors = []
        u.check_edge(errors, self.route(extra=[{"matches": [{"path": {"value": "/mcp"}}], "backendRefs": [{"name": "mcp-server", "port": 80}]}]))
        self.assertTrue(any("mcp-server" in e and "private" in e for e in errors))
        errors = []
        u.check_edge(errors, self.route(backend="afrotransact-core"))
        self.assertTrue(errors)

    def test_bff_must_reach_gateway_bff(self):
        errors = []
        u.check_edge(errors, self.route(backend="identity"))
        self.assertTrue(any("/bff must route to gateway-bff" in e for e in errors))

    def test_core_prefix_needs_denies(self):
        extra = [{"matches": [{"path": {"value": "/identity"}}], "backendRefs": [{"name": "identity", "port": 80}]}]
        errors = []
        u.check_edge(errors, self.route(extra=extra))
        self.assertTrue(any("/identity/mcp" in e for e in errors) and any("/identity/modules" in e for e in errors))
        errors = []
        u.check_edge(errors, self.route(extra=extra, denied=["/identity/modules", "/identity/mcp", "/identity/v1/tools", "/identity/.well-known/oauth-protected-resource"]))
        self.assertEqual(errors, [])


class Charts(unittest.TestCase):
    def test_committed_charts_are_clean(self):
        errors, notes = [], []
        u.check_registry(errors)
        u.check_no_override(errors)
        u.check_charts(errors, notes, use_helm=False)
        u.check_edge(errors)
        u.check_mcp(errors)
        self.assertEqual(errors, [])

    def test_state_aware_rendering(self):
        out, _ = u.emulate(CORE, "prod", {"catalog": CORE, "cart": GO, "geo": GO, "orders": CORE})
        self.assertEqual(out["CATALOG_URL"], "http://localhost:8080/modules/catalog")           # runs in core: loopback
        self.assertEqual(out["INVENTORY_URL"], "http://inventory")                              # not moved yet: its own Service
        self.assertEqual(out["CART_URL"], "http://afrotransact-go.afrotransact-prod.svc/modules/cart")   # prefixed Go module
        self.assertEqual(out["GEO_URL"], "http://afrotransact-go.afrotransact-prod.svc")                 # flat Go module
        self.assertEqual(out["SEARCH_URL"], "http://search")
        self.assertEqual(out["AUTH_TOKEN_URL"], "http://identity/oauth2/token")
        go, _ = u.emulate(GO, "uat", {"catalog": CORE, "payments": CORE, "cart": GO, "geo": GO, "identity": CORE})
        self.assertEqual(go["PAYMENTS_URL"], "http://afrotransact-core.afrotransact-uat.svc/modules/payments")
        self.assertEqual(go["IDENTITY_URL"], "http://afrotransact-core.afrotransact-uat.svc/modules/identity")
        self.assertEqual(go["AUTH_TOKEN_URL"], "http://afrotransact-core.afrotransact-uat.svc/modules/identity/oauth2/token")
        self.assertEqual(go["ORDERS_URL"], "http://orders")
        self.assertNotIn("GEO_URL", go)   # geo runs in the same binary: in-process, nothing rendered

    def test_full_launch_has_no_per_service_url_for_a_moved_module(self):
        for dep in (CORE, GO):
            out, _ = u.emulate(dep, "prod", u.full_launch())
            for name, value in out.items():
                if name.endswith("_URL") and value.startswith("http://") and "localhost" not in value and ".svc" not in value:
                    host = value.split("/")[2]
                    self.assertNotIn(host, u.full_launch(), f"{dep} {name}={value}")


class Scan(unittest.TestCase):
    def test_scan_finds_the_known_edges(self):
        java, go = u.scan_java(), u.scan_go()
        self.assertIn("orders", java["IDENTITY_URL"])
        self.assertIn("gateway-bff", go["PAYMENTS_URL"])
        self.assertIn("notifications", go["SITE_CONFIG_URL"])
        self.assertIn("cart", go["AUTH_TOKEN_URL"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
