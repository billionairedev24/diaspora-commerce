#!/usr/bin/env python3
"""Live route probe for Release 1 (ADR 0011, S4): sends every METHOD + path of a module's contract to a running afrotransact-core, on the
module's OLD port (local profile: at.core.module-ports) or under /modules/<name>, and reports the routes core answers with "no mapping"
(Spring's 404 "No static resource ..." / "No endpoint ...", or a 405 for that method). Any other answer (200, 400, 401, 403, 404 with a
problem code, 409, 422, 5xx) means a handler took the request. Run it against a THROWAWAY database: it sends POST/PUT/PATCH/DELETE with
empty or dummy input.

  python3 tools/release/route_probe.py --modules orders,payments --host http://127.0.0.1                 # old ports 81xx
  python3 tools/release/route_probe.py --modules inventory --base http://127.0.0.1:8120 --prefix          # /modules/inventory/...
  python3 tools/release/route_probe.py --modules inventory,payments --base http://127.0.0.1:8120           # core port, no prefix (collisions go to the module mounted first)
Exit 1 when a contract route has no mapping. Standard library plus PyYAML.
"""
import argparse
import concurrent.futures
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
METHODS = {"get", "put", "post", "delete", "patch"}
LOCAL_PORTS = {"identity": 8100, "catalog": 8101, "offers": 8102, "inventory": 8103, "orders": 8104, "payments": 8105,
               "seller": 8106, "retail": 8107, "fulfilment": 8108, "bookings": 8109, "trust-ops": 8110,
               "tax-returns": 8111, "content": 8112, "ai-gateway": 8113, "mcp-server": 8114, "delivery-runs": 8115}
DUMMY = "00000000-0000-0000-0000-000000000000"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def probe(base, method, path):
    """A mapped answer for the first dummy path value that a handler takes (some templates carry a regex, {kind:[a-z-]+})."""
    last = (0, "")
    for dummy in (DUMMY, "abc", "1"):
        last = probe_once(base, method, re.sub(r"\{[^}/]*\}", dummy, path))
        if last[0] != -1 and not unmapped(*last):
            return last
    return last


def probe_once(base, method, path):
    url = base + path
    data = b"{}" if method in ("POST", "PUT", "PATCH") else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", "Accept": "application/json",
                                                                          "Idempotency-Key": DUMMY, "X-Forwarded-For": "127.0.0.1"})
    try:
        with OPENER.open(req, timeout=8) as r:
            return r.status, ""
    except urllib.error.HTTPError as e:
        body = e.read(2000).decode("utf-8", "replace")
        return e.code, (body, e.headers.get("Allow", ""))
    except TimeoutError:
        return 0, "timeout (a handler is holding the request)"
    except urllib.error.URLError as e:
        return -1, str(e.reason)


def unmapped(status, extra):
    if status == 405:
        return True
    if status == 404:
        body = extra[0] if isinstance(extra, tuple) else ""
        try:
            doc = json.loads(body)
        except ValueError:
            return "No static resource" in body or "No endpoint" in body or "<html" in body.lower()
        return not doc.get("code") and re.match(r"(No static resource|No endpoint|No resource)", doc.get("detail", "")) is not None
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--modules", required=True)
    ap.add_argument("--host", default="http://127.0.0.1", help="host for the old per-module ports")
    ap.add_argument("--base", help="one base URL for every module (core port); default is each module's old port")
    ap.add_argument("--prefix", action="store_true", help="with --base: address /modules/<name>/...")
    ap.add_argument("--json", help="write the report here")
    args = ap.parse_args()
    report, bad = {}, 0
    for m in [x for x in args.modules.split(",") if x]:
        doc = yaml.safe_load((ROOT / "contracts/openapi" / f"{m}.yaml").read_text())
        base = (args.base.rstrip("/") + (f"/modules/{m}" if args.prefix else "")) if args.base else f"{args.host}:{LOCAL_PORTS[m]}"
        routes = sorted({(mt.upper(), p) for p, item in (doc.get("paths") or {}).items() for mt in (item or {}) if mt.lower() in METHODS})
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            results = list(pool.map(lambda r: (r, probe(base, *r)), routes))
        missing = [(r, s, e) for r, (s, e) in results if s == -1 or unmapped(s, e)]
        by_status = {}
        for r, (s, e) in results:
            by_status[s] = by_status.get(s, 0) + 1
        report[m] = {"base": base, "routes": len(routes), "no_mapping": [f"{r[0]} {r[1]} -> {s}" for r, s, e in missing],
                     "statuses": dict(sorted(by_status.items()))}
        bad += len(missing)
        print(f"[{m}] {base}: {len(routes)} contract routes, no mapping {len(missing)}; statuses {dict(sorted(by_status.items()))}")
        for r, s, e in missing:
            print(f"    NO MAPPING {r[0]} {r[1]} -> {s}")
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps(report, indent=2))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
