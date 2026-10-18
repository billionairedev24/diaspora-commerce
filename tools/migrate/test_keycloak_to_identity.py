import base64
import hashlib
import json
import unittest

import keycloak_to_identity as m

SALT = base64.b64encode(b"saltsaltsalt12").decode()
KEY = base64.b64encode(hashlib.pbkdf2_hmac("sha256", b"correct horse", b"saltsaltsalt12", 27500, 32)).decode()


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

    def test_unsupported_or_broken_credentials_become_no_password(self):
        self.assertIsNone(m.convert(row(credential_data=json.dumps({"hashIterations": 5, "algorithm": "argon2"}))).password_hash)
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


if __name__ == "__main__":
    unittest.main()
