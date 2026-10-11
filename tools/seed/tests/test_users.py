"""The `users` step (S276) and that every list of test users in the repo names the same accounts."""
from __future__ import annotations

import base64
import json
import re
import unittest
from pathlib import Path

from seedlib import dataset as D
from seedlib.http import ApiError
from seedlib.users import TestUsers, jwt_claims

REPO = Path(__file__).resolve().parents[3]
EMAIL = re.compile(r"[a-z.]+@afrotransact\.test")


def token(claims: dict) -> str:
    body = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
    return f"h.{body}.s"


class FakeIdentity:
    base_url = "http://identity.test"


class Scripted(TestUsers):
    """TestUsers with identity's answers scripted per email."""

    def __init__(self, steps: dict, claims: dict | None = None, wait: float = 30):
        super().__init__(FakeIdentity(), {}, lambda m: None, wait=wait, sleep=lambda s: None)
        self.steps, self.claims, self.calls = steps, claims or {}, []

    def client_token(self) -> str:
        return "client"

    def sign_in(self, token: str, email: str) -> dict:
        self.calls.append(email)
        step = self.steps[email]
        if isinstance(step, Exception):
            raise step
        return step

    def access_claims(self, ticket: str) -> dict:
        answers = self.claims[ticket]
        return answers.pop(0) if len(answers) > 1 else answers[0]


OK = {"status": "AUTHENTICATED", "loginTicket": "t"}
MFA = {"status": "MFA_REQUIRED", "challengeId": "c"}


class UsersStepTest(unittest.TestCase):
    def test_customers_sign_in_with_password_only_and_staff_get_the_two_step_challenge(self):
        u = Scripted({"a@afrotransact.test": OK, "s@afrotransact.test": MFA})
        self.assertTrue(u.check("x", ("a@afrotransact.test", "Customer", "Storefront", None, False)).ok)
        staff = u.check("x", ("s@afrotransact.test", "Support", "Staff console", None, True))
        self.assertTrue(staff.ok)
        self.assertIn("Mailpit", staff.note)

    def test_staff_without_a_second_step_or_a_customer_with_one_is_a_problem(self):
        u = Scripted({"a@afrotransact.test": MFA, "s@afrotransact.test": OK})
        self.assertFalse(u.check("x", ("a@afrotransact.test", "Customer", "Storefront", None, False)).ok)
        self.assertFalse(u.check("x", ("s@afrotransact.test", "Support", "Staff console", None, True)).ok)

    def test_waits_for_identity_to_link_the_organisation(self):
        u = Scripted({"o@afrotransact.test": {"status": "AUTHENTICATED", "loginTicket": "t1"}},
                     {"t1": [{"pt": "CUSTOMER"}, {"pt": "SELLER_MEMBER", "org": "org_mama_put_pantry"}]})
        c = u.check("x", ("o@afrotransact.test", "Owner", "Seller Center", "org_mama_put_pantry", False))
        self.assertTrue(c.ok, c.note)
        self.assertEqual(len(u.calls), 2)

    def test_an_organisation_that_never_arrives_is_reported(self):
        u = Scripted({"o@afrotransact.test": OK}, {"t": [{"pt": "CUSTOMER"}]}, wait=0)
        c = u.check("x", ("o@afrotransact.test", "Owner", "Seller Center", "org_mama_put_pantry", False))
        self.assertFalse(c.ok)
        self.assertIn("not linked", c.note)

    def test_a_missing_account_says_how_to_get_it(self):
        u = Scripted({"a@afrotransact.test": ApiError("POST", "/v1/auth/password", 401, {"code": "x"})})
        c = u.check("x", ("a@afrotransact.test", "Customer", "Storefront", None, False))
        self.assertFalse(c.ok)
        self.assertIn("restart identity", c.note)

    def test_jwt_claims_and_table(self):
        self.assertEqual(jwt_claims(token({"org": "org_x", "pt": "SELLER_MEMBER"}))["org"], "org_x")
        lines = TestUsers.table([("a@afrotransact.test", "Customer", "Storefront")])
        self.assertIn("a@afrotransact.test  Storefront", lines[0])


class SameAccountsEverywhereTest(unittest.TestCase):
    """identity creates the users; the seeder, make up's summary and the docs list them: they must agree."""

    def emails(self, relative: str) -> list[str]:
        return EMAIL.findall((REPO / relative).read_text(encoding="utf-8"))

    def test_identity_personas_match_the_dataset(self):
        java = (REPO / "java/services/identity/src/main/java/com/afrotransact/identity/application/TestPersonas.java")
        source = java.read_text(encoding="utf-8")
        locals_ = re.findall(r'(?:shopper|member|staff)\("([a-z.]+)"|email\("([a-z.]+)"\)', source)
        emails = [f"{a or b}@afrotransact.test" for a, b in locals_]
        self.assertEqual(sorted(emails), sorted(e for e, *_ in D.TEST_USERS))

    def test_local_sh_docs_and_readme_list_every_user(self):
        want = {e for e, *_ in D.TEST_USERS}
        for doc in ("tools/dev/local.sh", "docs/engineering/local-development.md", "tools/seed/README.md"):
            self.assertEqual(set(self.emails(doc)), want, doc)

    def test_staff_need_the_code_and_everyone_else_does_not(self):
        for email, _, app, _, mfa in D.TEST_USERS:
            self.assertEqual(mfa, app == "Staff console", email)


if __name__ == "__main__":
    unittest.main()
