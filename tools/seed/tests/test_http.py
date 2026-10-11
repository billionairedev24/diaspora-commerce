"""Http client against a local server: bearer tokens, retries on 503, problem details on errors."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

from seedlib.http import ApiError, Http


class Handler(BaseHTTPRequestHandler):
    hits: ClassVar[dict] = {}

    def log_message(self, *args):
        pass

    def _send(self, status, body, ctype="application/json"):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        Handler.hits[self.path] = Handler.hits.get(self.path, 0) + 1
        if self.path.startswith("/flaky") and Handler.hits[self.path] < 3:
            return self._send(503, {"title": "busy"})
        if self.path.startswith("/echo"):
            return self._send(200, {"auth": self.headers.get("Authorization"), "path": self.path})
        return self._send(404, {"code": "x.not-found", "detail": "nope"}, "application/problem+json")

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if body.get("bad"):
            return self._send(422, {"code": "catalog.rule-violation", "detail": "The product has 1 problem(s)",
                                    "errors": {"title": "Too short"}}, "application/problem+json")
        return self._send(201, {"got": body})


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_token_params_and_retries(self):
        http = Http(self.base, token=lambda: "tok", backoff=0.01)
        self.assertEqual(http.get("/echo", params={"q": "jollof rice", "skip": None}),
                         {"auth": "Bearer tok", "path": "/echo?q=jollof+rice"})
        self.assertEqual(http.get("/echo", authenticate=False)["auth"], None)
        res = Http(self.base, backoff=0.01).request("GET", "/flaky", ok=(404,))
        self.assertEqual(res.status, 404, "after two 503s the third attempt gets the real answer")
        self.assertEqual(Handler.hits["/flaky"], 3)

    def test_problem_details(self):
        http = Http(self.base, backoff=0.01)
        with self.assertRaises(ApiError) as ctx:
            http.post("/products", {"bad": True})
        e = ctx.exception
        self.assertEqual((e.status, e.code), (422, "catalog.rule-violation"))
        self.assertIn("Too short", str(e))
        self.assertEqual(http.request("GET", "/missing", ok=(404,)).body["code"], "x.not-found")
        self.assertEqual(http.post("/things", {"a": 1}), {"got": {"a": 1}})
        self.assertFalse(Http("http://127.0.0.1:9", retries=0).healthy("/readyz"))


if __name__ == "__main__":
    unittest.main()
