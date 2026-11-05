import base64
import hashlib
import json
import unittest

import keycloak_to_identity as m

SALT = base64.b64encode(b"saltsaltsalt12").decode()
KEY = base64.b64encode(hashlib.pbkdf2_hmac("sha256", b"correct horse", b"saltsaltsalt12", 27500, 32)).decode()

# Verbatim from a Keycloak 26.6.4 credential row (base64 padded, as Keycloak stores it).
ARGON2_SALT = "crl5cpBzhvVCBJMKAVu/iQ=="
ARGON2_VALUE = "8yFwnbwKPy1wXy/tDFOU/sB9q5gkLbvk1+Y9xH5KeJA="
ARGON2_CREDENTIAL_DATA = {"hashIterations": 5, "algorithm": "argon2",
                          "additionalParameters": {"hashLength": ["32"], "memory": ["7168"], "type": ["id"],
                                                   "version": ["1.3"], "parallelism": ["1"]}}


def row(**kw):
    base = {"id": "6f1d3c1e-0000-4000-8000-000000000001", "email": "Ada@example.com", "email_verified": True, "enabled": True,
            "first_name": "Ada", "last_name": "Obi", "created_timestamp": 1_700_000_000_000,
            "secret_data": json.dumps({"value": KEY, "salt": SALT, "additionalParameters": {}}),
            "credential_data": json.dumps({"hashIterations": 27500, "algorithm": "pbkdf2-sha256"}),
            "attrs": {"phoneNumber": "+1 (806) 555-0100"}, "links": [{"provider": "Google", "subject": "g-1", "email": "ada@gmail.com"}]}
    base.update(kw)
    return base


class ConvertTest(unittest.TestCase):
    def test_keeps_id_email_hash_phone_and_links(self):
        a = m.convert(row())
        self.assertEqual(a.id, "6f1d3c1e-0000-4000-8000-000000000001")
        self.assertEqual(a.password_hash, "{kc-pbkdf2-sha256}27500$%s$%s" % (SALT, KEY))
        self.assertEqual(a.phone, "+18065550100")
        self.assertEqual(a.links, [("google", "g-1", "ada@gmail.com")])
        self.assertTrue(a.email_verified)

    def test_hash_is_the_one_identity_verifies(self):
        # identity splits on $ and re-derives with PBKDF2WithHmacSHA256; same derivation as here
        _, rest = m.password_hash(row()["secret_data"], row()["credential_data"]).split("}", 1)
        iterations, salt, key = rest.split("$")
        derived = hashlib.pbkdf2_hmac("sha256", b"correct horse", base64.b64decode(salt), int(iterations), 32)
        self.assertEqual(base64.b64encode(derived).decode(), key)

    def test_argon2_becomes_the_standard_phc_string(self):
        # A real Keycloak 26.6.4 credential. Spring's stock Argon2PasswordEncoder reads the parameters out of
        # this string, so the salt and hash must lose their base64 padding and version 1.3 must become v=19.
        a = m.convert(row(secret_data=json.dumps({"value": ARGON2_VALUE, "salt": ARGON2_SALT, "additionalParameters": {}}),
                          credential_data=json.dumps(ARGON2_CREDENTIAL_DATA)))
        self.assertEqual(
            a.password_hash,
            "{argon2}$argon2id$v=19$m=7168,t=5,p=1$crl5cpBzhvVCBJMKAVu/iQ$8yFwnbwKPy1wXy/tDFOU/sB9q5gkLbvk1+Y9xH5KeJA")

    def test_argon2_falls_back_to_keycloak_defaults_when_parameters_are_absent(self):
        a = m.convert(row(secret_data=json.dumps({"value": ARGON2_VALUE, "salt": ARGON2_SALT}),
                          credential_data=json.dumps({"hashIterations": 5, "algorithm": "argon2"})))
        self.assertEqual(a.password_hash.split("$")[1], "argon2id")
        self.assertIn("$v=19$", a.password_hash)

    def test_unsupported_or_broken_credentials_become_no_password(self):
        self.assertIsNone(m.convert(row(credential_data=json.dumps({"hashIterations": 5, "algorithm": "scrypt"}))).password_hash)
        self.assertIsNone(m.convert(row(secret_data=json.dumps({"value": "***", "salt": SALT}))).password_hash)
        self.assertIsNone(m.convert(row(secret_data=None)).password_hash)

    def test_users_without_email_are_skipped_and_names_fall_back(self):
        self.assertIsInstance(m.convert(row(email=None)), m.Skipped)
        a = m.convert(row(first_name=None, last_name=None))
        self.assertEqual((a.first_name, a.last_name), ("Ada", ""))

    def test_bad_phone_and_unknown_provider_are_dropped(self):
        a = m.convert(row(attrs={"phone": "555-0100"}, links=[{"provider": "github", "subject": "x"}]))
        self.assertIsNone(a.phone)
        self.assertEqual(a.links, [])

    def test_sql_is_idempotent_and_never_overwrites_a_changed_password(self):
        sql = "\n".join(m.statements([m.convert(row(**{"email": "o'brien@example.com"}))]))
        self.assertIn("on conflict (id) do update", sql)
        self.assertIn("like '{kc-pbkdf2-%'", sql)
        self.assertIn("'o''brien@example.com'", sql)
        self.assertIn("insert into federated_identity", sql)
        self.assertTrue(sql.startswith("begin;") and sql.endswith("commit;"))

    def test_every_account_is_a_customer_and_mapped_roles_are_added(self):
        a = m.convert(row(roles=["default-roles-afrotransact", "seller-admin", "mystery"]), {"seller-admin": "seller.admin"})
        self.assertEqual(a.roles, ["account.customer", "seller.admin"])
        self.assertEqual(a.unmapped_roles, ["mystery"])
        sql = "\n".join(m.statements([a]))
        self.assertIn("insert into user_role", sql)
        self.assertIn("'keycloak-import'", sql)
        self.assertIn("where exists (select 1 from role where key = 'seller.admin')", sql)

    def test_memberships_only_attach_to_organisations_that_exist(self):
        a = m.convert(row(), None, {row()["id"]: [{"orgId": "0a0a0a0a-0000-4000-8000-000000000009", "role": "seller.owner"}]})
        sql = "\n".join(m.statements([a]))
        self.assertIn("insert into org_membership", sql)
        self.assertIn("exists (select 1 from organization where id = '0a0a0a0a-0000-4000-8000-000000000009'::uuid)", sql)


if __name__ == "__main__":
    unittest.main()
