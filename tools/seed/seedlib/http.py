"""A small JSON HTTP client on urllib with retries and RFC 9457 problem details."""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any

USER_AGENT = "AfroTransactSeeder/1.0 (+tools/seed; local and dev demo data)"


class ApiError(Exception):
    """A non-2xx answer (or a transport failure, status 0) from a service."""

    def __init__(self, method: str, url: str, status: int, body: Any, detail: str = ""):
        self.method, self.url, self.status, self.body = method, url, status, body
        self.code = body.get("code") if isinstance(body, dict) else None
        msg = detail or (body.get("detail") or body.get("title") if isinstance(body, dict) else str(body)[:300])
        errors = body.get("errors") or body.get("fieldErrors") if isinstance(body, dict) else None
        super().__init__(f"{method} {url} -> {status} {self.code or ''} {msg or ''}{' ' + json.dumps(errors) if errors else ''}".strip())


@dataclass
class Response:
    status: int
    body: Any
    headers: Mapping[str, str]


def _decode(raw: bytes, content_type: str) -> Any:
    if not raw:
        return None
    if "json" in content_type:
        try:
            return json.loads(raw.decode("utf-8"))
        except ValueError:
            pass
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw


class Http:
    """JSON client for one service base URL. Retries transport errors, 502/503/504 and 429 with backoff."""

    def __init__(self, base_url: str, token: Callable[[], str | None] | None = None, timeout: float = 30,
                 retries: int = 3, backoff: float = 0.5, opener: urllib.request.OpenerDirector | None = None):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.retries = retries
        self.backoff = backoff
        self.opener = opener or urllib.request.build_opener()

    def url(self, path: str, params: Mapping[str, Any] | None = None) -> str:
        url = path if path.startswith(("http://", "https://")) else self.base_url + path
        if params:
            clean = {k: v for k, v in params.items() if v is not None}
            if clean:
                url += ("&" if "?" in url else "?") + urllib.parse.urlencode(clean, doseq=True)
        return url

    def request(self, method: str, path: str, *, json_body: Any = None, params: Mapping[str, Any] | None = None,
                headers: Mapping[str, str] | None = None, data: bytes | None = None, ok: tuple[int, ...] = (),
                retries: int | None = None, authenticate: bool = True) -> Response:
        url = self.url(path, params)
        hdrs = {"User-Agent": USER_AGENT, "Accept": "application/json, application/problem+json, */*"}
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        if authenticate and self.token:
            tok = self.token()
            if tok:
                hdrs["Authorization"] = "Bearer " + tok
        hdrs.update(headers or {})
        attempts = (self.retries if retries is None else retries) + 1
        last: ApiError | None = None
        for attempt in range(attempts):
            req = urllib.request.Request(url, data=data, method=method, headers=hdrs)
            try:
                with self.opener.open(req, timeout=self.timeout) as res:
                    raw = res.read()
                    return Response(res.status, _decode(raw, res.headers.get("Content-Type", "")), dict(res.headers))
            except urllib.error.HTTPError as e:
                raw = e.read()
                body = _decode(raw, e.headers.get("Content-Type", "") if e.headers else "")
                if e.code in ok:
                    return Response(e.code, body, dict(e.headers or {}))
                last = ApiError(method, url, e.code, body)
                if e.code not in (429, 502, 503, 504):
                    raise last from None
            except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
                reason = getattr(e, "reason", e)
                last = ApiError(method, url, 0, None, f"unreachable: {reason}")
            if attempt + 1 < attempts:
                time.sleep(self.backoff * (2 ** attempt))
        assert last is not None
        raise last

    def get(self, path: str, **kw: Any) -> Any:
        return self.request("GET", path, **kw).body

    def post(self, path: str, body: Any = None, **kw: Any) -> Any:
        return self.request("POST", path, json_body=body if body is not None else {}, **kw).body

    def put(self, path: str, body: Any = None, **kw: Any) -> Any:
        return self.request("PUT", path, json_body=body if body is not None else {}, **kw).body

    def patch(self, path: str, body: Any = None, **kw: Any) -> Any:
        return self.request("PATCH", path, json_body=body if body is not None else {}, **kw).body

    def delete(self, path: str, **kw: Any) -> Any:
        return self.request("DELETE", path, **kw).body

    def healthy(self, path: str) -> bool:
        try:
            self.request("GET", path, retries=0, authenticate=False)
            return True
        except ApiError:
            return False


def put_bytes(url: str, data: bytes, headers: Mapping[str, str], timeout: float = 60,
              opener: urllib.request.OpenerDirector | None = None) -> int:
    """PUT raw bytes to a pre-signed storage URL (no bearer token: the signature is the credential)."""
    req = urllib.request.Request(url, data=data, method="PUT", headers=dict(headers))
    try:
        with (opener or urllib.request.build_opener()).open(req, timeout=timeout) as res:
            return res.status
    except urllib.error.HTTPError as e:
        raise ApiError("PUT", url.split("?")[0], e.code, _decode(e.read(), e.headers.get("Content-Type", ""))) from None


def multipart(field: str, filename: str, content_type: str, data: bytes) -> tuple[bytes, str]:
    """multipart/form-data body with one file field (UploadThing-style uploads)."""
    boundary = "----atseed" + str(int(time.time() * 1000))
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return head + data + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"
