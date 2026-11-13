"""S423: production, UAT and trial never ship demo home content (community tiles, demo heroes, announcements).

Content's Flyway migrations run in every environment, so the demo placements V2 inserted must be removed by a later migration,
and no later migration may insert placements again. Local and dev get demo content only from this seeder, which refuses
production, UAT and trial (test_guard.py)."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

from tests import helpers  # noqa: F401  (puts tools/seed on sys.path)

MIGRATIONS = Path(__file__).resolve().parents[3] / "java/services/content/src/main/resources/db/migration"


def migrations() -> list[tuple[int, Path]]:
    out = []
    for p in MIGRATIONS.glob("V*__*.sql"):
        m = re.match(r"V(\d+)__", p.name)
        if m:
            out.append((int(m.group(1)), p))
    return sorted(out)


class ProductionSeedContent(unittest.TestCase):
    def test_every_seeded_placement_is_removed_by_a_later_migration(self) -> None:
        seeded: dict[str, int] = {}
        removed: dict[str, int] = {}
        for version, path in migrations():
            text = path.read_text(encoding="utf-8")
            for m in re.finditer(r"insert into placement \([^)]*\) values \('(plc_[a-z0-9_]+)'.*?'seed'", text, re.S):
                seeded[m.group(1)] = version
            for block in re.finditer(r"delete from placement\s+where id in \((.*?)\)\s+and created_by = 'seed'\s+and version = 0", text, re.S):
                for pid in re.findall(r"'(plc_[a-z0-9_]+)'", block.group(1)):
                    removed[pid] = version
        self.assertTrue(seeded, "expected content's V2 migration to seed placements")
        for pid, v in seeded.items():
            with self.subTest(placement=pid):
                self.assertIn(pid, removed, f"{pid} (seeded by V{v}) would still be live in production")
                self.assertGreater(removed[pid], v)

    def test_demo_community_cells_are_not_in_any_migration_after_the_cleanup(self) -> None:
        cleanup = max(v for v, p in migrations() if "remove_demo" in p.name)
        for version, path in migrations():
            if version <= cleanup:
                continue
            text = path.read_text(encoding="utf-8").lower()
            with self.subTest(migration=path.name):
                self.assertNotIn("insert into placement", text, "a migration runs in production: seed placements belong to tools/seed")
                self.assertNotIn("/communities/", text)

    def test_the_cleanup_only_touches_rows_staff_never_edited(self) -> None:
        text = next(p for v, p in migrations() if "remove_demo" in p.name).read_text(encoding="utf-8")
        self.assertIn("created_by = 'seed'", text)
        self.assertIn("version = 0", text)

    def test_the_seeder_refuses_trial_and_the_dev_seed_still_replaces_the_removed_ids(self) -> None:
        import seed
        from seedlib import dataset

        self.assertIsNotNone(seed.target_problem({"APP_ENV": "trial"}))
        self.assertIsNotNone(seed.target_problem({"CONTENT_URL": "https://content-test.afrotransact.com", "SEED_ALLOW_REMOTE": "1"}))
        # the dev seed creates its own (photographed) placements and ends these ids only when they exist
        replaced = {h["replaces"] for h in dataset.HEROES} | {t["replaces"] for t in dataset.TILES} | {b["replaces"] for b in dataset.BANNERS}
        self.assertIn("plc_tiles_communities", replaced)


if __name__ == "__main__":
    unittest.main()
