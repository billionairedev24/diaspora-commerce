"""tools/smoke/release1.sh against a fake stack (one in-process HTTP server answering every URL the script uses):
all green exits 0 with a PASS line per check; a thin catalogue and a missing photo exit 1 with the failing URL and status."""
import json
import os
import re
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SCRIPT = Path(__file__).resolve().parent / "release1.sh"
DEPARTMENTS = ["Groceries & Food", "Beverages", "Services"]


def hit(n, image="http://{host}/media/p.jpg"):
    return {"atin": f"B0{n:08d}", "offerId": f"offer-{n}", "title": f"Item {n}", "imageUrl": image, "sellerName": "Mama Put Pantry",
            "sellerKind": "MARKETPLACE"}


class Fake(BaseHTTPRequestHandler):
    total = 106
    photoless = False

    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json", headers=()):
        raw = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        host = self.headers["Host"]
        p = re.sub(r"^/modules/[a-z-]+", "", u.path)  # core's single entry: /modules/<module>/...
        if p == "/actuator/health/readiness":
            return self.send(200, {"status": "UP"})
        if p == "/actuator/health":
            return self.send(200, {"status": "UP", "components": {"catalog": {"status": "UP"}, "orders": {"status": "UP"}}})
        if p == "/readyz":
            return self.send(200, {"status": "ok"})
        if p == "/v1/search":
            if q.get("q") == ["nothing"]:
                return self.send(200, {"total": 0, "hits": []})
            n = 3 if "department" not in q else (0 if q["department"] == ["Beverages"] and self.total < 0 else 1)
            hits = [hit(i, "" if (self.photoless and i == 2) else f"http://{host}/media/p.jpg") for i in range(1, 4)]
            return self.send(200, {"total": self.total if "department" not in q else n, "hits": hits})
        if p == "/media/p.jpg":
            return self.send(200, b"x", "image/jpeg")
        if p == "/v1/home":
            return self.send(200, {"blocks": [{"items": [hit(1, f"http://{host}/media/p.jpg")]}]})
        if p == "/v1/categories":
            return self.send(200, [{"level": "DEPARTMENT", "name": d} for d in DEPARTMENTS] + [{"level": "CATEGORY", "name": "x"}])
        if p.startswith("/v1/products/"):
            return self.send(200, {"product": {"images": [{"url": "http://x/y.jpg"}]}, "buyBox": {"priceMinor": 899, "seller": {"name": "Mama Put Pantry"}},
                                   "soldBy": "Mama Put Pantry"})
        if p.startswith("/p/") or p == "/":
            return self.send(200, "<html>ok</html>", "text/html")
        if p.startswith("/b/mama-put-pantry"):
            return self.send(200, '<a href="/p/B000000001">x</a>', "text/html")
        if p == "/v1/cart":
            return self.send(200, {"packages": [{"lines": [{"id": "l1"}]}]})
        if p == "/v1/checkout/tax-estimate":
            return self.send(200, {"taxEstimateStatus": "ESTIMATED", "taxRateLabel": "8.25% TX", "taxJurisdictionLabel": "Houston, TX"})
        if p == "/v1/checkout/summary":
            return self.send(200, {"addresses": [{"id": "a1"}], "taxQuoteId": "tq",
                                   "packages": [{"packageId": "s:AFROTRANSACT", "deliveryOptions": [{"key": "afrotransact_delivery"}]}]})
        if p == "/api/v1/info":
            return self.send(200, {"Version": "v1"})
        if p == "/stats":
            return self.send(200, {"indexes": {"products": {"numberOfDocuments": 106}}})
        if p == "/v1/admin/orders":
            return self.send(200, {"total": 14, "items": [{"status": "placed", "attribution": {"src": "demo-seed"}}, {"status": "placed"}, {"status": "delivered"}]})
        if p == "/api/v1/targets":
            return self.send(200, {"data": {"activeTargets": [{"scrapeUrl": "http://h:8120/actuator/prometheus", "health": "up"},
                                                               {"scrapeUrl": "http://h:8200/metrics", "health": "up"}]}})
        self.send(404, {"code": "not-found"})

    def do_POST(self):
        u = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        if u.path == "/oauth2/token":
            return self.send(200, {"access_token": "t"})
        if u.path == "/v1/auth/password":
            return self.send(200, {"loginTicket": "ticket"})
        if u.path == "/v1/cart/items":
            return self.send(200, {"packages": []})
        if u.path == "/v1/checkout/orders":
            return self.send(201, {"orderNumber": "AT-1", "status": "awaiting_payment", "paymentClientSecret": "pi_x_secret_y"})
        self.send(404, {"code": "not-found"})

    def redirect_authorize(self):
        self.send(302, b"", headers=[("Location", "http://localhost:3000/cb?code=abc&state=smoke")])


class FakeWithAuthorize(Fake):
    def do_GET(self):
        if urlparse(self.path).path == "/oauth2/authorize":
            return self.redirect_authorize()
        super().do_GET()


class SmokeTest(unittest.TestCase):
    def run_script(self, **attrs):
        handler = type("H", (FakeWithAuthorize,), attrs)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{server.server_address[1]}"
        env = dict(os.environ, BFF_URL=url, CORE_URL=url, IDENTITY_URL=url, ORDERS_URL=url, STOREFRONT_URL=url,
                   SELLER_CENTER_URL=url, STAFF_CONSOLE_URL=url, MAILPIT_URL=url, MEILISEARCH_URL=url, PROMETHEUS_URL=url,
                   SMOKE_DEPT_SKIP="Services", STACK="monolith")
        try:
            return subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=120)
        finally:
            server.shutdown()

    def test_a_healthy_stack_passes_every_check(self):
        res = self.run_script()
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertNotIn("FAIL", res.stdout)
        for label in ("core readiness", "afrotransact-go /readyz", "products exist (106 >= 40)", "search 'iru'", "search 'rice'",
                      "at least one product in each of 2 departments", "product page data", "seller store page /b/mama-put-pantry",
                      "cart add", "cart read", "estimated tax for ZIP 77042", "checkout reaches the payment-intent step",
                      "staff console login page", "Seller Center login page", "Mailpit reachable", "search index has documents",
                      "seeded orders exist in the staff order list (14 orders, 1 seeded; by state: delivered 1, placed 2)", "Prometheus target for core", "Prometheus target for afrotransact-go"):
            self.assertIn("PASS  " + label, res.stdout, label)
        self.assertRegex(res.stdout, r"smoke: \d+ passed, 0 failed")

    def test_a_thin_catalogue_and_a_missing_photo_fail_with_the_url(self):
        res = self.run_script(total=3, photoless=True)
        self.assertEqual(res.returncode, 1, res.stdout)
        self.assertRegex(res.stdout, r"FAIL  products exist \(>= 40\)  ->  http://127\.0\.0\.1:\d+/v1/search status 200, total 3")
        self.assertRegex(res.stdout, r"FAIL  every product in search has a photo  ->  .*1 of 3 hits without imageUrl")

    def test_checkout_can_be_skipped(self):
        handler = type("H", (FakeWithAuthorize,), {})
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{server.server_address[1]}"
        env = dict(os.environ, BFF_URL=url, CORE_URL=url, IDENTITY_URL=url, ORDERS_URL=url, STOREFRONT_URL=url,
                   SELLER_CENTER_URL=url, STAFF_CONSOLE_URL=url, MAILPIT_URL=url, MEILISEARCH_URL=url, PROMETHEUS_URL=url,
                   SMOKE_CHECKOUT="0", SMOKE_DEPT_SKIP="Services")
        try:
            res = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=120)
        finally:
            server.shutdown()
        self.assertIn("SKIP  checkout reaches the payment-intent step", res.stdout)
        self.assertEqual(res.returncode, 0, res.stdout)


if __name__ == "__main__":
    unittest.main()
