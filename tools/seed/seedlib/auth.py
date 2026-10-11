"""Bearer tokens for the seeder.

In order: SEED_TOKEN (a ready access token, e.g. a staff member's), a client-credentials token (SEED_CLIENT_ID /
SEED_CLIENT_SECRET at AUTH_TOKEN_URL, default AUTH_ISSUER_URL + /oauth2/token), or none. The local profile runs every
Java service with security off and media with auth off, so no token is needed there. Against dev, register a
confidential client with the scopes below (and staff permissions such as catalog:retail) and pass its credentials.
"""
from __future__ import annotations

import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable

# Scopes the seeder's client asks for (identity grants only what the client is registered with).
SCOPES = ("catalog:retail catalog:moderate offers:write inventory:receive content:placements.edit "
          "content:placements.approve content:ads.edit media:write preorders:manage sellers:provision "
          "bookings:services:write")


class TokenProvider:
    """Caches one token until shortly before it expires. `log` gets one line on how auth was set up."""

    def __init__(self, env: dict[str, str], log: Callable[[str], None]):
        self.env = env
        self.log = log
        self._token: str | None = None
        self._expires = 0.0
        self._mode = "unset"

    def describe(self) -> str:
        return self._mode

    def __call__(self) -> str | None:
        if self._mode == "unset":
            self._setup()
        if self._mode == "client" and time.time() > self._expires - 30:
            self._fetch()
        return self._token

    def _setup(self) -> None:
        static = self.env.get("SEED_TOKEN", "").strip()
        if static:
            self._mode, self._token = "SEED_TOKEN", static
            self.log("auth: using SEED_TOKEN")
            return
        if not self.env.get("SEED_CLIENT_ID", "").strip():
            self._mode, self._token = "none", None
            self.log("auth: no token (local services run with security off; for dev set SEED_TOKEN or "
                     "SEED_CLIENT_ID / SEED_CLIENT_SECRET)")
            return
        self._mode = "client"
        try:
            self._fetch()
            self.log(f"auth: client-credentials token for {self._client()[0]}")
        except (OSError, ValueError) as e:
            self._mode, self._token = "none", None
            self.log(f"auth: client credentials for {self._client()[0]} failed ({e}); continuing without a token")

    def _client(self) -> tuple[str, str]:
        return self.env.get("SEED_CLIENT_ID", "").strip(), self.env.get("SEED_CLIENT_SECRET", "")

    def token_url(self) -> str:
        explicit = self.env.get("AUTH_TOKEN_URL", "").strip()
        if explicit:
            return explicit
        return self.env.get("AUTH_ISSUER_URL", "http://localhost:8100").rstrip("/") + "/oauth2/token"

    def _fetch(self) -> None:
        cid, secret = self._client()
        data = urllib.parse.urlencode({"grant_type": "client_credentials",
                                       "scope": self.env.get("SEED_SCOPES", SCOPES)}).encode()
        basic = base64.b64encode(f"{urllib.parse.quote(cid)}:{urllib.parse.quote(secret)}".encode()).decode()
        req = urllib.request.Request(self.token_url(), data=data, method="POST", headers={
            "Authorization": "Basic " + basic, "Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(req, timeout=10) as res:
                body = json.loads(res.read().decode())
        except urllib.error.HTTPError as e:
            raise ValueError(f"token endpoint answered {e.code}") from None
        self._token = body["access_token"]
        self._expires = time.time() + float(body.get("expires_in", 300))
