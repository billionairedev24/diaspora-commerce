"""The seeder follows the platform picture rules (profile strict, startup or testing) instead of a fixed Amazon-style rule."""
import unittest

from seedlib import picture_rules as PR
from seedlib.images import library as L
from seedlib.listing import check_listing, Rules
from tests.test_seeder import seeder

STRICT_DOC = {"profile": "strict", "knobs": {"minImages": 1, "minLongSidePx": 1000, "minShortSidePx": 500, "maxMegabytes": 8,
              "allowPlaceholders": False, "requireRealPhotoForStorefront": True}}
STARTUP_DOC = {"profile": "startup", "knobs": {"minImages": 1, "minLongSidePx": 500, "minShortSidePx": 300, "maxMegabytes": 8,
               "allowPlaceholders": False, "requireRealPhotoForStorefront": True}}
TESTING_DOC = {"profile": "testing", "knobs": {"minImages": 0, "minLongSidePx": 1, "minShortSidePx": 1, "maxMegabytes": 8,
               "allowPlaceholders": True, "requireRealPhotoForStorefront": False}}


class PictureRulesTest(unittest.TestCase):
    def tearDown(self):
        L.set_size_rules(500, 1000)

    def test_reads_every_profile(self):
        s, u, t = PR.from_json(STRICT_DOC), PR.from_json(STARTUP_DOC), PR.from_json(TESTING_DOC)
        self.assertEqual((s.min_images, s.min_long, s.min_short), (1, 1000, 500))
        self.assertEqual((u.min_images, u.min_long, u.min_short), (1, 500, 300))
        self.assertEqual((t.min_images, t.min_long, t.min_short), (0, 1, 1))
        self.assertFalse(s.photo_optional or u.photo_optional)
        self.assertTrue(t.photo_optional)
        self.assertTrue(t.allow_placeholders)

    def test_falls_back_to_the_strict_standard_when_catalog_cannot_be_asked(self):
        logged = []

        def down(path):
            raise OSError("connection refused")

        self.assertEqual(PR.fetch(down, logged.append), PR.STRICT)
        self.assertTrue(any("strict standard" in m for m in logged))

    def test_the_size_rule_of_a_photo_follows_the_profile(self):
        self.assertFalse(L.usable_size(700, 700))
        L.set_size_rules(300, 500)  # startup
        self.assertTrue(L.usable_size(700, 700))
        self.assertFalse(L.usable_size(400, 400))
        L.set_size_rules(1, 1)  # testing
        self.assertTrue(L.usable_size(40, 40))
        L.set_size_rules(500, 1000)
        self.assertFalse(L.usable_size(700, 700))

    def test_the_offline_check_asks_for_as_many_images_as_the_rules_do(self):
        rules = Rules({"fields": []})
        body = {"images": [], "listing": {}, "attributes": {}}
        self.assertIn("images", check_listing(rules, {}, body))
        self.assertNotIn("images", check_listing(rules, {}, body, min_images=0))
        self.assertIn("images", check_listing(rules, {}, {"images": [{"url": "x"}]}, min_images=2))

    def test_the_seeder_reads_the_rules_before_it_picks_photos(self):
        s = seeder()
        s.svc["catalog"].on("GET", "/v1/public/picture-rules", lambda *a: TESTING_DOC)
        s._read_picture_rules()
        self.assertTrue(s.pictures.photo_optional)
        self.assertEqual(s.pictures.profile, "testing")
        self.assertTrue(L.usable_size(40, 40))
        s.svc["catalog"].on("GET", "/v1/public/picture-rules", lambda *a: STRICT_DOC)
        s._read_picture_rules()
        self.assertFalse(s.pictures.photo_optional)
        self.assertEqual(s.main_min_edge, 500)
        self.assertFalse(L.usable_size(40, 40))


if __name__ == "__main__":
    unittest.main()


class FormatsTest(unittest.TestCase):
    """The golden vectors the Go and Java code run, read the same way here: AVIF and WebP are images, nothing else is."""

    @staticmethod
    def vectors():
        import json
        from pathlib import Path
        return json.loads((Path(__file__).resolve().parents[3] / "contracts" / "picture-rules" / "vectors.json").read_text())

    def test_every_sample_is_sniffed_by_its_bytes_with_its_size(self):
        from seedlib.images.model import sniff
        for im in self.vectors()["images"]:
            got = sniff(bytes.fromhex(im["hex"]))
            self.assertEqual(got, (im["type"], im["width"], im["height"]), im["name"])

    def test_spoofed_files_are_not_images(self):
        from seedlib.images.model import sniff
        for r in self.vectors()["rejected"]:
            self.assertIsNone(sniff(bytes.fromhex(r["hex"])), r["name"])
