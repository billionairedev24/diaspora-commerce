#!/usr/bin/env python3
"""Copy Keycloak users into the identity service so nobody has to reset a password or sign up again.

Builds the whole profile identity needs, not just the login row: the account (names, email, phone), the default
customer role every sign-up gets, roles mapped from Keycloak roles (--role-map), org memberships (--memberships) and
social links. Keeps the Keycloak user id (it becomes the token `sub` and the key every service already stores), the email and its
verified flag, name, phone, enabled flag, the password hash (verified by identity as `{kc-pbkdf2-*}`; see
KeycloakPbkdf2PasswordEncoder), and social links (Google, Apple, Facebook). Uses the `psql` CLI, no Python DB driver.

  keycloak_to_identity.py report  --keycloak "$KC_DB_URL"                       # counts and what would be skipped
  keycloak_to_identity.py sql     --keycloak "$KC_DB_URL" > import.sql          # review the SQL, run it yourself
  keycloak_to_identity.py apply   --keycloak "$KC_DB_URL" --identity "$ID_DB_URL" \
        [--role-map roles.json] [--memberships memberships.json]

  roles.json         {"kc-realm-role": "identity.role_key", ...}       e.g. {"seller-admin": "seller.admin"}
  memberships.json   {"<user id>": [{"orgId": "<uuid>", "role": "seller.owner"}]}   organisations must already exist
                     in identity (they arrive from seller-service approval events); rows for missing ones are skipped

Safe to run again (delta before cutover): a user is inserted once; a still-Keycloak password hash is refreshed so a
password changed in Keycloak meanwhile carries over; a password already changed in identity is never overwritten.
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone

ALGORITHMS = {"pbkdf2": "sha1", "pbkdf2-sha256": "sha256", "pbkdf2-sha512": "sha512"}
PROVIDERS = {"google": "google", "apple": "apple", "facebook": "facebook"}
E164 = re.compile(r"^\+[1-9]\d{6,14}$")
CUSTOMER_ROLE = "account.customer"
PHONE_ATTRS = ("phoneNumber", "phone_number", "phone", "mobile")

EXPORT_SQL = """
select coalesce(json_agg(row_to_json(t)), '[]'::json) from (
  select u.id, u.email, u.email_verified, u.enabled, u.first_name, u.last_name, u.created_timestamp,
         (select c.secret_data from credential c where c.user_id = u.id and c.type = 'password'
            order by c.priority limit 1) as secret_data,
         (select c.credential_data from credential c where c.user_id = u.id and c.type = 'password'
            order by c.priority limit 1) as credential_data,
         (select coalesce(json_object_agg(a.name, a.value), '{}'::json) from user_attribute a
            where a.user_id = u.id and a.name in ('phoneNumber','phone_number','phone','mobile')) as attrs,
         (select coalesce(json_agg(r.name), '[]'::json) from user_role_mapping m
            join keycloak_role r on r.id = m.role_id where m.user_id = u.id) as roles,
         (select coalesce(json_agg(json_build_object('provider', f.identity_provider, 'subject', f.federated_user_id,
                                                      'email', f.federated_username)), '[]'::json)
            from federated_identity f where f.user_id = u.id) as links
  from user_entity u
  where u.service_account_client_link is null and u.realm_id = (select id from realm where name = '%s')
) t
"""


@dataclass
class Account:
    id: str
    first_name: str
    last_name: str
    email: str
    email_verified: bool
    phone: str | None
    password_hash: str | None
    enabled: bool
    created_at: str
    links: list[tuple[str, str, str | None]] = field(default_factory=list)
    roles: list[str] = field(default_factory=lambda: [CUSTOMER_ROLE])
    unmapped_roles: list[str] = field(default_factory=list)
    memberships: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class Skipped:
    id: str
    reason: str


def password_hash(secret_data: str | dict | None, credential_data: str | dict | None) -> str | None:
    """`{kc-pbkdf2-sha256}iterations$salt$key` from a Keycloak password credential, or None if unsupported."""
    if not secret_data or not credential_data:
        return None
    sd = json.loads(secret_data) if isinstance(secret_data, str) else secret_data
    cd = json.loads(credential_data) if isinstance(credential_data, str) else credential_data
    alg = ALGORITHMS.get(str(cd.get("algorithm", "")).lower())
    iterations, salt, value = cd.get("hashIterations"), sd.get("salt"), sd.get("value")
    if not alg or not isinstance(iterations, int) or iterations < 1 or not salt or not value:
        return None
    try:  # both must be valid base64
        base64.b64decode(salt, validate=True)
        base64.b64decode(value, validate=True)
    except ValueError:
        return None
    return "{kc-pbkdf2-%s}%d$%s$%s" % (alg, iterations, salt, value)


def phone_of(attrs: dict | None) -> str | None:
    for name in PHONE_ATTRS:
        raw = (attrs or {}).get(name)
        if raw:
            digits = "+" + re.sub(r"\D", "", raw) if raw.strip().startswith("+") else None
            if digits and E164.match(digits):
                return digits
    return None


def roles_of(kc_roles: list[str] | None, role_map: dict[str, str]) -> tuple[list[str], list[str]]:
    """Every account is a customer; mapped Keycloak roles add to that. Returns (identity roles, unmapped names)."""
    roles = [CUSTOMER_ROLE]
    unmapped = []
    for name in kc_roles or []:
        key = role_map.get(name)
        if key:
            if key not in roles:
                roles.append(key)
        elif not name.startswith(("default-roles-", "offline_access", "uma_authorization")):
            unmapped.append(name)
    return roles, unmapped


def convert(row: dict, role_map: dict[str, str] | None = None, memberships: dict | None = None) -> Account | Skipped:
    email = (row.get("email") or "").strip()
    if not email or "@" not in email:
        return Skipped(row["id"], "no email address")
    local = email.split("@", 1)[0]
    created = datetime.fromtimestamp((row.get("created_timestamp") or 0) / 1000, tz=timezone.utc)
    links = [(PROVIDERS[l["provider"].lower()], l["subject"], l.get("email"))
             for l in (row.get("links") or []) if l.get("provider", "").lower() in PROVIDERS and l.get("subject")]
    roles, unmapped = roles_of(row.get("roles"), role_map or {})
    member = [(m["orgId"], m["role"]) for m in (memberships or {}).get(row["id"], [])]
    return Account(
        roles=roles, unmapped_roles=unmapped, memberships=member,
        id=row["id"], first_name=(row.get("first_name") or local)[:80], last_name=(row.get("last_name") or "")[:80],
        email=email[:254], email_verified=bool(row.get("email_verified")), phone=phone_of(row.get("attrs")),
        password_hash=password_hash(row.get("secret_data"), row.get("credential_data")),
        enabled=bool(row.get("enabled", True)), created_at=created.isoformat(), links=links)


def q(v: str | None) -> str:
    return "null" if v is None else "'" + v.replace("'", "''") + "'"


def b(v: bool) -> str:
    return "true" if v else "false"


def statements(accounts: list[Account]) -> list[str]:
    out = ["begin;"]
    for a in accounts:
        out.append(
            "insert into user_account (id, first_name, last_name, email, email_verified, phone_e164, password_hash, enabled, "
            "created_at, updated_at, version) values (%s, %s, %s, %s, %s, %s, %s, %s, %s, now(), 0) "
            "on conflict (id) do update set email_verified = user_account.email_verified or excluded.email_verified, "
            "password_hash = case when user_account.password_hash is null or user_account.password_hash like '{kc-pbkdf2-%%' "
            "then excluded.password_hash else user_account.password_hash end, updated_at = now() "
            "where user_account.password_hash is null or user_account.password_hash like '{kc-pbkdf2-%%' "
            "or not user_account.email_verified;"
            % (q(a.id) + "::uuid", q(a.first_name), q(a.last_name), q(a.email), b(a.email_verified), q(a.phone),
               q(a.password_hash), b(a.enabled), q(a.created_at) + "::timestamptz"))
        for role in a.roles:
            out.append("insert into user_role (user_id, role_key, granted_by) select %s::uuid, %s, 'keycloak-import' "
                       "where exists (select 1 from role where key = %s) on conflict do nothing;" % (q(a.id), q(role), q(role)))
        for org, role in a.memberships:
            out.append("insert into org_membership (org_id, user_id, role_key) select %s::uuid, %s::uuid, %s "
                       "where exists (select 1 from organization where id = %s::uuid) and exists (select 1 from role where key = %s) "
                       "on conflict do nothing;" % (q(org), q(a.id), q(role), q(org), q(role)))
        for provider, subject, mail in a.links:
            out.append("insert into federated_identity (provider, subject, user_id, email, linked_at) values (%s, %s, %s::uuid, %s, now()) "
                       "on conflict do nothing;" % (q(provider), q(subject), q(a.id), q(mail)))
    out.append("commit;")
    return out


def read_json(path: str | None) -> dict:
    return json.load(open(path)) if path else {}


def load(args) -> tuple[list[Account], list[Skipped]]:
    role_map, memberships = read_json(args.role_map), read_json(args.memberships)
    proc = subprocess.run(["psql", "-X", "-A", "-t", args.keycloak, "-c", EXPORT_SQL % args.realm.replace("'", "''")],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit("psql (keycloak) failed: " + proc.stderr.strip())
    accounts: list[Account] = []
    skipped: list[Skipped] = []
    seen: dict[str, str] = {}
    for row in json.loads(proc.stdout):
        r = convert(row, role_map, memberships)
        if isinstance(r, Skipped):
            skipped.append(r)
            continue
        key = r.email.lower()
        if key in seen:  # identity keeps one account per email
            skipped.append(Skipped(r.id, "email %s already belongs to %s" % (r.email, seen[key])))
            continue
        seen[key] = r.id
        accounts.append(r)
    return accounts, skipped


def report(accounts: list[Account], skipped: list[Skipped]) -> str:
    no_pw = [a for a in accounts if not a.password_hash]
    lines = [
        "users to import:            %d" % len(accounts),
        "  with a portable password: %d" % (len(accounts) - len(no_pw)),
        "  social-only / no password:%d (sign in with their provider, or use Forgot password)" % len(no_pw),
        "  with a social link:       %d" % sum(1 for a in accounts if a.links),
        "  with staff/seller/provider roles: %d" % sum(1 for a in accounts if len(a.roles) > 1),
        "  with an org membership:   %d" % sum(1 for a in accounts if a.memberships),
        "  email not verified:       %d" % sum(1 for a in accounts if not a.email_verified),
        "  disabled:                 %d" % sum(1 for a in accounts if not a.enabled),
        "skipped:                    %d" % len(skipped)]
    lines += ["  %s: %s" % (s.id, s.reason) for s in skipped]
    unmapped = sorted({r for a in accounts for r in a.unmapped_roles})
    if unmapped:
        lines.append("Keycloak roles with no mapping (add them to --role-map or ignore): " + ", ".join(unmapped))
    return "\n".join(lines)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["report", "sql", "apply"])
    p.add_argument("--keycloak", required=True, help="Keycloak database URL (read only user is enough)")
    p.add_argument("--identity", help="identity database URL (apply)")
    p.add_argument("--realm", default="afrotransact")
    p.add_argument("--role-map", help="JSON: Keycloak realm role name -> identity role key")
    p.add_argument("--memberships", help="JSON: user id -> [{orgId, role}] (organisations must exist in identity)")
    args = p.parse_args()
    accounts, skipped = load(args)
    if args.command == "report":
        print(report(accounts, skipped))
        return
    sql = "\n".join(statements(accounts))
    if args.command == "sql":
        print(sql)
        return
    if not args.identity:
        sys.exit("--identity is required for apply")
    proc = subprocess.run(["psql", "-X", "-v", "ON_ERROR_STOP=1", args.identity], input=sql, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit("psql (identity) failed, nothing was imported: " + proc.stderr.strip())
    print(report(accounts, skipped))


if __name__ == "__main__":
    main()
