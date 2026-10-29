"""Tests for tools/legal/build_seed.py. Run: python3 -m unittest discover -s tools/legal -p 'test_*.py'

The migration SQL is also applied to a scratch PostgreSQL when LEGAL_TEST_PG (a psql connection string such as
"postgresql://postgres@localhost:5432/postgres") is set; CI runs the content service's own integration tests for that.
"""
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_seed  # noqa: E402

DATA = os.path.join(HERE, "testdata")


class BuildSeedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.migrations = os.path.join(self.tmp, "migration")
        # Only the content seeds the loader reasons about, not the whole schema history.
        os.makedirs(self.migrations)
        for name in os.listdir(build_seed.MIGRATIONS):
            if name.endswith(".sql"):
                shutil.copy(os.path.join(build_seed.MIGRATIONS, name), self.migrations)
        self.before = set(os.listdir(self.migrations))

    def run_tool(self, docs, *flags):
        out, err = io.StringIO(), io.StringIO()
        old = sys.stderr
        sys.stderr = err
        try:
            code = build_seed.run(os.path.join(DATA, docs) if not os.path.isabs(docs) else docs, self.migrations,
                                  check="--check" in flags, rewrite="--rewrite" in flags, listing="--list" in flags, out=out)
        except build_seed.Problem as e:
            code = 2
            err.write(str(e))
        finally:
            sys.stderr = old
        return code, out.getvalue(), err.getvalue()

    def generated(self):
        return sorted(n for n in os.listdir(self.migrations) if build_seed.GENERATED_RE.match(n))

    def test_zero_documents_is_fine(self):
        empty = os.path.join(self.tmp, "empty")
        os.makedirs(empty)
        code, out, _ = self.run_tool(empty)
        self.assertEqual(code, 0)
        self.assertIn("nothing to load", out)
        self.assertEqual(set(os.listdir(self.migrations)), self.before)
        self.assertEqual(self.run_tool(empty, "--check")[0], 0)
        self.assertEqual(self.run_tool(os.path.join(self.tmp, "does-not-exist"))[0], 0)

    def test_generates_the_next_flyway_version_with_a_stable_hash_name(self):
        code, out, _ = self.run_tool("ok")
        self.assertEqual(code, 0, out)
        [name] = self.generated()
        number = int(build_seed.GENERATED_RE.match(name).group(1))
        versions = [int(build_seed.MIGRATION_RE.match(n).group(1)) for n in self.before]
        self.assertEqual(number, max(versions) + 1)
        with open(os.path.join(self.migrations, name), encoding="utf-8") as f:
            sql = f.read()
        for key in ("gift-card-terms", "privacy", "sms-terms"):
            self.assertIn(f"'{key}'", sql)
        # Existing CURRENT versions are archived before the new one is inserted; the draft banner follows front matter.
        self.assertIn("(major, minor, patch) < (4, 1, 0)", sql)
        self.assertIn("'4.1', 4, 1, 0, '2026-11-15'::date, 'CURRENT', false", sql)
        self.assertIn("'1.1', 1, 1, 0, '2026-11-01'::date, 'CURRENT', true", sql)
        self.assertIn('"fr": "# Conditions des cartes cadeaux', sql)
        self.assertIn("It''s covered", sql)  # quotes are escaped
        # Re-running changes nothing, and --check agrees.
        code, out, _ = self.run_tool("ok")
        self.assertIn("up to date", out)
        self.assertEqual(self.generated(), [name])
        self.assertEqual(self.run_tool("ok", "--check")[0], 0)

    def test_check_fails_when_documents_changed_or_the_migration_was_edited(self):
        self.assertEqual(self.run_tool("ok", "--check")[0], 1)  # documents but no migration
        self.run_tool("ok")
        docs = os.path.join(self.tmp, "docs")
        shutil.copytree(os.path.join(DATA, "ok"), docs)
        self.assertEqual(self.run_tool(docs, "--check")[0], 0)
        with open(os.path.join(docs, "privacy.en.md"), "a", encoding="utf-8") as f:
            f.write("\nMore text.\n")
        code, _, err = self.run_tool(docs, "--check")
        self.assertEqual(code, 1)
        self.assertIn("docs/legal changed", err)
        # A new migration is added at the next version; --rewrite replaces the newest one instead.
        self.run_tool(docs)
        self.assertEqual(len(self.generated()), 2)
        self.assertEqual(self.run_tool(docs, "--check")[0], 0)
        with open(os.path.join(docs, "privacy.en.md"), "a", encoding="utf-8") as f:
            f.write("\nEven more.\n")
        self.run_tool(docs, "--rewrite")
        self.assertEqual(len(self.generated()), 2)
        self.assertEqual(self.run_tool(docs, "--check")[0], 0)
        latest = os.path.join(self.migrations, self.generated()[-1])
        with open(latest, "a", encoding="utf-8") as f:
            f.write("-- hand edit\n")
        self.assertEqual(self.run_tool(docs, "--check")[0], 1)

    def test_rejects_bad_documents_with_a_message_per_problem(self):
        cases = {
            "bad-markers": "[[LEGAL ...]] counsel marker(s) are still in the text",
            "bad-placeholder": "unknown placeholder {supportEmail}",
            "bad-no-english": "English is the binding text",
            "bad-key": "not a legal document key",
            "bad-no-sections": "at least one '## Section'",
            "bad-front-matter": "starts without front matter",
        }
        for folder, expected in cases.items():
            with self.subTest(folder):
                code, _, err = self.run_tool(folder)
                self.assertEqual(code, 2)
                self.assertIn(expected, err)
        self.assertEqual(self.generated(), [])

    def test_first_written_documents_load_as_the_content_team_writes_them(self):
        # Version 1.0 below the seeded 3.2/4.0 placeholders, "effective: TBD-at-launch", a colon in applies_to, a README and
        # notes next to the documents, counsel markers in a draft.
        code, out, err = self.run_tool("first")
        self.assertEqual(code, 0, err)
        self.assertIn("conditions-of-use: written 1.0 replaces the seeded placeholder 3.2", out)
        self.assertIn("privacy: written 1.0 replaces the seeded placeholder 4.0", out)
        [name] = self.generated()
        with open(os.path.join(self.migrations, name), encoding="utf-8") as f:
            sql = f.read()
        self.assertIn("'2026-12-01'::date", sql)  # TBD-at-launch -> the launch date
        self.assertEqual(self.run_tool("first", "--check")[0], 0)
        # The launch date is a parameter, and is part of the hash.
        launched = build_seed.run(os.path.join(DATA, "first"), self.migrations, launch_date="2027-01-15", out=io.StringIO())
        self.assertEqual(launched, 0)
        self.assertEqual(len(self.generated()), 2)
        code, out, _ = self.run_tool("first", "--list")
        self.assertIn("counsel marker(s)", out)
        self.assertIn("conditions-of-use", out)

    def test_every_key_of_the_content_service_is_known(self):
        keys = build_seed.known_keys()
        self.assertEqual(len(keys), 21)
        self.assertIn("seller-fees", keys)
        seeded = build_seed.seeded_current_versions(self.migrations)
        self.assertEqual(set(seeded), set(keys), "every document must be seeded so its page renders before the loader runs")

    def test_list_shows_seeded_and_written_versions(self):
        code, out, _ = self.run_tool("ok", "--list")
        self.assertEqual(code, 0)
        self.assertIn("privacy", out)
        self.assertIn("written 4.1 en effective 2026-11-15", out)
        self.assertIn("written not written", out)

    @unittest.skipUnless(os.environ.get("LEGAL_TEST_PG"), "LEGAL_TEST_PG not set")
    def test_sql_follows_the_service_version_rules_on_postgres(self):
        dsn = os.environ["LEGAL_TEST_PG"]
        db = "legal_seed_test"

        def psql(sql, dbname=None):
            target = dsn.rsplit("/", 1)[0] + "/" + (dbname or "postgres")
            r = subprocess.run(["psql", target, "-v", "ON_ERROR_STOP=1", "-At", "-c", sql], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            return r.stdout.strip()

        def psql_file(path):
            target = dsn.rsplit("/", 1)[0] + "/" + db
            r = subprocess.run(["psql", target, "-v", "ON_ERROR_STOP=1", "-q", "-f", path], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

        psql(f"drop database if exists {db}")
        psql(f"create database {db}")
        self.addCleanup(psql, f"drop database if exists {db}")
        for name in ("V1__content_schema.sql", "V5__seed_legal.sql", "V12__seed_legal_extended.sql"):
            psql_file(os.path.join(self.migrations, name))
        self.run_tool("ok")
        name = self.generated()[0]
        path = os.path.join(self.migrations, name)
        psql_file(path)
        psql_file(path)  # idempotent
        rows = psql("select doc_type, version, status, draft_banner from legal_version "
                    "where doc_type in ('privacy','gift-card-terms','sms-terms') order by doc_type, major, minor", db)
        self.assertEqual(rows.split("\n"), [
            "gift-card-terms|1.0|ARCHIVED|t", "gift-card-terms|1.1|CURRENT|t",
            "privacy|3.4|ARCHIVED|t", "privacy|4.0|ARCHIVED|t", "privacy|4.1|CURRENT|f",
            "sms-terms|1.0|CURRENT|t"])
        self.assertEqual(psql("select count(*) from legal_version where status = 'CURRENT' group by doc_type having count(*) > 1", db), "")
        self.assertEqual(psql("select bodies->'fr' is not null from legal_version where doc_type = 'gift-card-terms' and version = '1.1'", db), "t")
        self.assertEqual(psql("select archived_at is not null from legal_version where doc_type = 'privacy' and version = '4.0'", db), "t")
        # A staff amendment to the CURRENT sms-terms must survive a later load of the same version.
        psql("update legal_version set draft_banner = false, updated_at = now() + interval '1 hour' where doc_type = 'sms-terms'", db)
        psql_file(path)
        self.assertEqual(psql("select draft_banner from legal_version where doc_type = 'sms-terms'", db), "f")
        # The first written Conditions of Use (1.0) replaces the untouched 3.2 placeholder and its invented history; the
        # amended (touched) privacy placeholder is left alone.
        self.run_tool("first")
        first_path = os.path.join(self.migrations, self.generated()[-1])
        self.assertNotEqual(first_path, path)
        psql("update legal_version set draft_banner = false, updated_at = now() + interval '1 hour' "
             "where doc_type = 'conditions-of-use' and version = '3.2'", db)
        psql_file(first_path)
        self.assertEqual(psql("select version || ':' || status from legal_version where doc_type = 'conditions-of-use' order by major desc", db).split("\n"),
                         ["3.2:CURRENT", "3.1:ARCHIVED", "3.0:ARCHIVED"])  # touched: never replaced
        psql("update legal_version set updated_at = created_at where doc_type = 'conditions-of-use'", db)
        psql_file(first_path)
        self.assertEqual(psql("select version || ':' || status || ':' || created_by from legal_version where doc_type = 'conditions-of-use'", db),
                         "1.0:CURRENT:legal-loader")
        # A newer version raised in the staff console is never replaced.
        psql("update legal_version set status = 'ARCHIVED', archived_at = now() where doc_type = 'privacy' and version = '4.1'", db)
        psql("insert into legal_version select gen_random_uuid(), doc_type, '5.0', 5, 0, 0, effective_date, 'CURRENT', true, bodies, "
             "'staff', now(), 'maya', now(), null from legal_version where doc_type = 'privacy' and version = '4.1'", db)
        psql_file(path)
        self.assertEqual(psql("select version from legal_version where doc_type = 'privacy' and status = 'CURRENT'", db), "5.0")


if __name__ == "__main__":
    unittest.main()
