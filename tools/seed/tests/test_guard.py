"""The seeder must never write demo data to production or UAT, whatever the environment says."""
from __future__ import annotations

import unittest

import seed
from tests import helpers  # noqa: F401  (puts tools/seed on sys.path)


class TargetGuard(unittest.TestCase):
    def test_a_plain_local_stack_is_allowed(self) -> None:
        self.assertIsNone(seed.target_problem({}))
        self.assertIsNone(seed.target_problem({"APP_ENV": "local", "CATALOG_URL": "http://localhost:8101",
                                               "AUTH_ISSUER_URL": "http://127.0.0.1:8100"}))
        self.assertIsNone(seed.target_problem({"APP_ENV": "dev", "MEDIA_URL": "http://host.docker.internal:8200"}))

    def test_production_and_uat_names_are_refused_in_any_spelling(self) -> None:
        for name in ("APP_ENV", "ENVIRONMENT", "DEPLOY_ENV", "SPRING_PROFILES_ACTIVE", "ENV"):
            for value in ("prod", "PROD", " Production ", "uat", "staging", "local,prod", "prod, uat"):
                with self.subTest(name=name, value=value):
                    self.assertIsNotNone(seed.target_problem({name: value}))

    def test_production_hosts_are_refused_even_with_the_remote_flag(self) -> None:
        for url in ("https://api.afrotransact.com", "https://auth.afrotransact.com", "https://afrotransact.com",
                    "https://afrotransact.com/x", "https://api-uat.afrotransact.com", "https://catalog.afrotransact.com:443",
                    "https://API.AFROTRANSACT.COM.", "https://user:pw@api.afrotransact.com"):
            with self.subTest(url=url):
                problem = seed.target_problem({"CATALOG_URL": url, "SEED_ALLOW_REMOTE": "1"})
                self.assertIsNotNone(problem)
                self.assertIn("production", problem)

    def test_a_lookalike_cannot_pass_for_localhost(self) -> None:
        for url in ("https://localhost.attacker.example", "https://api.afrotransact.com/localhost",
                    "https://x.example/?h=127.0.0.1", "http://127.0.0.1.nip.io"):
            with self.subTest(url=url):
                self.assertIsNotNone(seed.target_problem({"SELLER_URL": url}))

    def test_other_remote_hosts_need_the_explicit_flag(self) -> None:
        env = {"CATALOG_URL": "https://api-dev.afrotransact.com", "ORDERS_URL": "https://auth-dev.afrotransact.com"}
        self.assertIn("SEED_ALLOW_REMOTE", seed.target_problem(env))
        self.assertIsNone(seed.target_problem({**env, "SEED_ALLOW_REMOTE": "1"}))
        # the trial runs the prod profile: demo data is refused even with the flag
        self.assertIsNotNone(seed.target_problem({"CATALOG_URL": "https://api-test.afrotransact.com", "SEED_ALLOW_REMOTE": "1"}))
        self.assertIn("SEED_ALLOW_REMOTE", seed.target_problem({"CATALOG_URL": "https://staging.example.org"}))

    def test_an_unreadable_url_stops_the_run(self) -> None:
        self.assertIn("not a valid URL", seed.target_problem({"CATALOG_URL": "http://"}))

    def test_guard_exits_before_any_step_runs(self) -> None:
        import os
        from unittest import mock

        with mock.patch.dict(os.environ, {"APP_ENV": "prod"}, clear=False):
            with self.assertRaises(SystemExit) as ctx:
                seed.guard_not_production()
        self.assertIn("refusing", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
