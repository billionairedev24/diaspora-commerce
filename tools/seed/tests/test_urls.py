"""Base URLs of the seeder: core's single entry in the monolith stack, the per-service ports only for STACK=services, every
one overridable; `--if-empty` brings back demo products an earlier run lost."""
import unittest

from seedlib import dataset as D
from seedlib.seeder import JAVA_MODULES, SERVICES, service_endpoints

from tests.test_seeder import FakeHttp, seeder


class EndpointsTest(unittest.TestCase):
    def test_monolith_uses_core_and_afrotransact_go_only(self):
        e = service_endpoints({"STACK": "monolith"})
        for name in JAVA_MODULES:
            self.assertEqual(e[name][0], f"http://localhost:8120/modules/{name}", name)
            self.assertEqual(e[name][1], f"http://localhost:8120/actuator/health/{name}")
        self.assertEqual(e["media"][0], "http://localhost:8200")
        self.assertEqual(e["geo"][0], "http://localhost:8200")
        self.assertEqual(e["bff"][0], "http://localhost:8200")
        for name in ("cart", "search", "carrier-gateway"):
            self.assertEqual(e[name][0], f"http://localhost:8200/modules/{name}")
        ports = {url.rsplit(":", 1)[1].split("/")[0] for url, _ in e.values()}
        self.assertEqual(ports, {"8120", "8200"}, "no legacy per-service port")

    def test_services_is_the_default_on_main_and_keeps_the_old_ports(self):
        self.assertEqual(service_endpoints({}), service_endpoints({"STACK": "services"}))  # main: services is the default
        e = service_endpoints({"STACK": "services"})
        self.assertEqual(e["catalog"], ("http://localhost:8101", "/actuator/health"))
        self.assertEqual(e["media"][0], "http://localhost:8207")
        self.assertEqual(e["orders"][0], "http://localhost:8104")

    def test_every_url_is_overridable_and_the_entries_move_with_their_settings(self):
        e = service_endpoints({"STACK": "monolith", "CATALOG_URL": "http://x:1/", "CORE_URL": "http://core:9/",
                               "AFROTRANSACT_GO_PORT": "8250"})
        self.assertEqual(e["catalog"][0], "http://x:1")
        self.assertEqual(e["offers"][0], "http://core:9/modules/offers")
        self.assertEqual(e["media"][0], "http://localhost:8250")
        self.assertEqual(e["search"][0], "http://localhost:8250/modules/search")
        self.assertTrue(all(var for var, _, _ in SERVICES.values()))


class MissingProductsTest(unittest.TestCase):
    def test_publishable_products_that_are_not_in_the_catalog_are_found(self):
        s = seeder()
        cats = [{"id": "d", "parentId": None, "path": "Groceries & Food", "level": "DEPARTMENT"}]
        for path in {p.path for p in D.PRODUCTS}:
            cats.append({"id": path, "parentId": "d", "path": path, "level": "PRODUCT_TYPE", "taxStatus": "ACTIVE"})
        s.svc["catalog"].on("GET", "/v1/categories", lambda *a: cats)
        goat = next(p for p in D.PRODUCTS if p.key == "goat-meat")
        s.svc["catalog"].on("GET", "/v1/products", lambda path, body, kw: {"items": [
            {"title": p.title, "status": "LIVE"} for p in D.PRODUCTS if p.key != "goat-meat"]})
        self.assertEqual([p.key for p in s.missing_products()], ["goat-meat"])


if __name__ == "__main__":
    unittest.main()
