"""Checks the local test users (S276) the way the web apps sign them in, and prints who they are.

identity creates the users on the local profile (TestPersonas.java), and links the organisation personas as soon as
the `sellers` step's organisations reach it (seller.org.approved over Kafka). This step proves it through identity's
first-party sign-in API, as gateway-bff uses it: client credentials (gateway-bff, scope auth:first-party) →
POST /v1/auth/password → for personas that need no second factor, the login ticket is redeemed on /oauth2/authorize
and the access token's `org` claim shows the membership. Staff answer MFA_REQUIRED (their texted code is in Mailpit).
"""
from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Union

from . import dataset as D
from .http import ApiError, Http

FIRST_PARTY_CLIENT = "gateway-bff"
LOCAL_FIRST_PARTY_SECRET = "local-bff-secret"  # identity's application-local.yml default; not a secret
REDIRECT = "http://localhost:3000/api/bff/v1/auth/oidc/callback"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001 (urllib signature)
        return None


@dataclass
class UserCheck:
    email: str
    persona: str
    app: str
    ok: bool
    note: str


def jwt_claims(token: str) -> dict[str, Any]:
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return verifier, challenge


class TestUsers:
    """`users` step: one sign-in per test user. `identity` is the identity service client (no bearer token)."""

    __test__ = False  # not a unittest class

    def __init__(self, identity: Http, env: dict[str, str], log: Callable[[str], None], wait: float = 30,
                 sleep: Callable[[float], None] = time.sleep):
        self.identity = identity
        self.env = env
        self.log = log
        self.wait = wait
        self.sleep = sleep
        self.password = env.get("SEED_TEST_USERS_PASSWORD") or D.TEST_USER_PASSWORD
        self.secret = env.get("GATEWAY_BFF_CLIENT_SECRET") or env.get("BFF_CLIENT_SECRET") or LOCAL_FIRST_PARTY_SECRET
        self.no_redirect = Http(identity.base_url, retries=0, opener=urllib.request.build_opener(_NoRedirect()))

    def client_token(self) -> str:
        basic = base64.b64encode(f"{FIRST_PARTY_CLIENT}:{self.secret}".encode()).decode()
        res = self.identity.request("POST", "/oauth2/token", authenticate=False,
                                    data=urllib.parse.urlencode({"grant_type": "client_credentials",
                                                                 "scope": "auth:first-party"}).encode(),
                                    headers={"Authorization": "Basic " + basic,
                                             "Content-Type": "application/x-www-form-urlencoded"})
        return res.body["access_token"]

    def sign_in(self, token: str, email: str) -> dict[str, Any]:
        return self.identity.post("/v1/auth/password", {"email": email, "password": self.password,
                                                        "clientIp": "127.0.0.1", "userAgent": "AfroTransactSeeder"},
                                  headers={"Authorization": "Bearer " + token}, authenticate=False)

    def access_claims(self, ticket: str) -> dict[str, Any]:
        """Redeems a login ticket like gateway-bff: authorization code + PKCE, then the token endpoint."""
        return jwt_claims(self.access_token(ticket))

    def access_token(self, ticket: str) -> str:
        """The access token of a login ticket (authorization code + PKCE, then the token endpoint)."""
        verifier, challenge = pkce()
        res = self.no_redirect.request("GET", "/oauth2/authorize", ok=(302, 303), authenticate=False, params={
            "response_type": "code", "client_id": FIRST_PARTY_CLIENT, "redirect_uri": REDIRECT, "scope": "openid",
            "state": "seed", "code_challenge": challenge, "code_challenge_method": "S256", "login_ticket": ticket})
        location = res.headers.get("Location") or res.headers.get("location") or ""
        code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [""])[0]
        if not code:
            raise ValueError(f"no authorization code (redirected to {location or 'nowhere'})")
        basic = base64.b64encode(f"{FIRST_PARTY_CLIENT}:{self.secret}".encode()).decode()
        tok = self.identity.request("POST", "/oauth2/token", authenticate=False, data=urllib.parse.urlencode({
            "grant_type": "authorization_code", "code": code, "redirect_uri": REDIRECT,
            "code_verifier": verifier}).encode(), headers={"Authorization": "Basic " + basic,
                                                           "Content-Type": "application/x-www-form-urlencoded"})
        return tok.body["access_token"]

    def check(self, token: str, user: tuple[str, str, str, Union[str, None], bool]) -> UserCheck:
        email, persona, app, org, mfa = user
        deadline = time.time() + self.wait
        while True:
            try:
                step = self.sign_in(token, email)
            except ApiError as e:
                if e.status in (400, 401, 404):
                    return UserCheck(email, persona, app, False, "cannot sign in: identity has no such test user "
                                     "(restart identity on the local profile: tools/dev/local.sh restart identity)")
                raise
            if step.get("status") == "MFA_REQUIRED":
                ok = mfa
                note = "password ok; two-step code by text (Mailpit)" if mfa else "unexpected two-step challenge"
                return UserCheck(email, persona, app, ok, note)
            if mfa:
                return UserCheck(email, persona, app, False, "staff signed in without the two-step code")
            if not org:
                return UserCheck(email, persona, app, True, "signs in with email + password")
            claims = self.access_claims(step["loginTicket"])
            if claims.get("org") == org:
                return UserCheck(email, persona, app, True, f"signs in, acts for {org}")
            if time.time() > deadline:
                return UserCheck(email, persona, app, False, f"not linked to {org} yet (run the sellers step; "
                                 f"identity links members when the organisation reaches it)")
            self.sleep(2)

    def run(self) -> list[UserCheck]:
        token = self.client_token()
        return [self.check(token, u) for u in D.TEST_USERS]

    @staticmethod
    def table(rows: list[tuple[str, str, str]]) -> list[str]:
        width = max(len(r[0]) for r in rows)
        return [f"  {email:<{width}}  {app:<14} {persona}" for email, persona, app in rows]


class SignedIn:
    """Access tokens of local test users, as the web apps get them (password sign-in, then the login ticket), cached
    until shortly before they expire. Locally the services act as the customer, driver or organisation member whose
    token a call carries (platform-security LocalActorResolver), so the `ops` step places orders as the test customers,
    packs as the seller and drives as the driver. Staff work runs without a token (the local super admin)."""

    def __init__(self, users: TestUsers, clock: Callable[[], float] = time.time):
        self.users = users
        self.clock = clock
        self._client: tuple[str, float] | None = None
        self._tokens: dict[str, tuple[str, float, dict[str, Any]]] = {}

    def _client_token(self) -> str:
        if self._client is None or self.clock() > self._client[1]:
            self._client = (self.users.client_token(), self.clock() + 240)
        return self._client[0]

    def token(self, email: str) -> str:
        """A fresh access token; ValueError when the user cannot sign in with the password alone (e.g. an
        authenticator app was turned on for the account)."""
        cached = self._tokens.get(email)
        if cached and self.clock() < cached[1]:
            return cached[0]
        step = self.users.sign_in(self._client_token(), email)
        if step.get("status") == "MFA_REQUIRED" or not step.get("loginTicket"):
            raise ValueError(f"{email} needs a two-step code to sign in (turn its authenticator off in "
                             "Account › Security, or run make reset)")
        token = self.users.access_token(step["loginTicket"])
        claims = jwt_claims(token)
        ttl = float(claims.get("exp", self.clock() + 300)) - float(claims.get("iat", self.clock()))
        self._tokens[email] = (token, self.clock() + max(30.0, ttl - 60), claims)
        return token

    def claims(self, email: str) -> dict[str, Any]:
        self.token(email)
        return self._tokens[email][2]

    def headers(self, email: str) -> dict[str, str]:
        return {"Authorization": "Bearer " + self.token(email)}
