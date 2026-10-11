"""ImageLibrary: SEED_IMAGES_DIR matching, download cache, one photo per product, minimum main-image size, attribution."""
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path

from seedlib.images.library import ImageLibrary
from seedlib.images.model import Candidate, sniff

from tests.helpers import jpeg_header, png, webp_vp8x


def cand(n, w=1600, h=1200, source="wikimedia", lic="CC BY 4.0"):
    return Candidate(source=source, download_url=f"https://img.example/{n}.png", page_url=f"https://commons.example/{n}",
                     title=f"Photo {n}", author=f"Author {n}", author_url="", license=lic,
                     license_url="https://creativecommons.org/licenses/by/4.0/", width=w, height=h, mime="image/png")


class FakeSource:
    name = "wikimedia"

    def __init__(self, results, fail=False):
        self.results, self.fail, self.queries = results, fail, []

    def search(self, query, limit, orientation):
        self.queries.append(query)
        if self.fail:
            raise urllib.error.URLError("blocked by policy")
        return list(self.results.get(query, []))


class SniffTest(unittest.TestCase):
    def test_sizes(self):
        self.assertEqual(sniff(png(3, 2)), ("image/png", 3, 2))
        self.assertEqual(sniff(jpeg_header(1600, 1200)), ("image/jpeg", 1600, 1200))
        self.assertEqual(sniff(webp_vp8x(1200, 800)), ("image/webp", 1200, 800))
        self.assertIsNone(sniff(b"GIF89a....."))


class LibraryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.downloads = []
        self.logs = []

    def download(self, url):
        self.downloads.append(url)
        n = int(url.rsplit("/", 1)[-1].split(".")[0])
        sizes = {1: (1200, 1200), 2: (1600, 1200), 3: (900, 600), 4: (1200, 1000), 5: (1300, 1300)}
        w, h = sizes.get(n, (1200, 1200))
        return png(w, h, seed=n)

    def lib(self, sources, local=None, offline=False):
        return ImageLibrary(self.tmp / "cache", sources, local, download=self.download, log=self.logs.append,
                            offline=offline)

    def test_downloads_caches_and_never_reuses_a_photo(self):
        src = FakeSource({"jollof rice": [cand(3, 900, 600), cand(1), cand(2)], "jollof": [cand(1), cand(4, 1200, 1000)]})
        lib = self.lib([src])

        first = lib.images_for("at-jollof", ["jollof rice"], 2, orientation="square", main_min_edge=1000)
        self.assertEqual([i.title for i in first], ["Photo 1", "Photo 2"], "photo 3 is too small for a main image")
        self.assertEqual(first[0].min_edge, 1200)
        second = lib.images_for("hero-jollof", ["jollof"], 2)
        self.assertEqual([i.title for i in second], ["Photo 4"], "photo 1 already belongs to at-jollof")
        lib.save()

        again = self.lib([FakeSource({}, fail=True)], offline=True)
        cached = again.images_for("at-jollof", ["jollof rice"], 2, main_min_edge=1000)
        self.assertEqual([i.title for i in cached], ["Photo 1", "Photo 2"], "re-runs work offline from the cache")
        self.assertEqual(self.downloads.count("https://img.example/1.png"), 1)
        manifest = json.loads((self.tmp / "cache" / "manifest.json").read_text())
        self.assertIn("wikimedia:square:jollof rice", manifest["queries"])

    def test_unreachable_source_is_skipped_for_the_rest_of_the_run(self):
        src = FakeSource({}, fail=True)
        lib = self.lib([src])

        self.assertEqual(lib.images_for("a", ["x", "y"], 2), [])
        self.assertEqual(lib.images_for("b", ["z"], 2), [])
        self.assertEqual(src.queries, ["x"])
        self.assertTrue(any("unreachable" in line for line in self.logs))

    def test_seed_images_dir_matches_slugs_numbers_folders_and_keywords(self):
        local = self.tmp / "mine"
        (local / "kente-stole").mkdir(parents=True)
        (local / "ofada-rice.png").write_bytes(png(1100, 1100, 1))
        (local / "ofada-rice-2.png").write_bytes(png(800, 600, 2))
        (local / "Ofada_Rice_3.png").write_bytes(png(1000, 1000, 3))
        (local / "kente-stole" / "front.png").write_bytes(png(1200, 1200, 4))
        (local / "jollof rice.png").write_bytes(png(1200, 900, 5))
        (local / "notes.txt").write_text("not an image")
        src = FakeSource({"whatever": [cand(1)]})
        lib = self.lib([src], local)

        rice = lib.images_for("ofada-rice", ["brown rice"], 3, main_min_edge=1000)
        self.assertEqual([Path(i.path).name for i in rice], ["ofada-rice.png", "ofada-rice-2.png", "Ofada_Rice_3.png"])
        self.assertEqual({i.source for i in rice}, {"local"})
        self.assertEqual([Path(i.path).name for i in lib.images_for("kente-stole", [], 3)], ["front.png"])
        self.assertEqual([Path(i.path).name for i in lib.images_for("hero-jollof", ["jollof rice"], 1)], ["jollof rice.png"])
        self.assertEqual(src.queries, [], "local files win: no API call")

    def test_local_main_image_must_be_large_enough(self):
        local = self.tmp / "small"
        local.mkdir()
        (local / "garri.png").write_bytes(png(600, 600, 1))
        (local / "garri-2.png").write_bytes(png(1000, 1200, 2))
        lib = self.lib([], local)

        picked = lib.images_for("garri", [], 2, main_min_edge=1000)
        self.assertEqual([Path(i.path).name for i in picked], ["garri-2.png", "garri.png"], "the big one becomes main")
        (local / "garri-2.png").unlink()
        self.assertEqual(self.lib([], local).images_for("garri", [], 2, main_min_edge=1000), [])

    def test_attribution_files(self):
        src = FakeSource({"q": [cand(1, lic="CC BY-SA 4.0"), cand(2)]})
        lib = self.lib([src])
        imgs = lib.images_for("shea", ["q"], 2)
        lib.write_attribution(self.tmp / "ATTRIBUTION.md", self.tmp / "attribution.json",
                              {imgs[0].sha256: ["shea"], imgs[1].sha256: ["shea"]})

        md = (self.tmp / "ATTRIBUTION.md").read_text()
        self.assertIn("**Photo 1** — Author 1, [CC BY-SA 4.0](https://creativecommons.org/licenses/by/4.0/)", md)
        self.assertIn("source: https://commons.example/1 (wikimedia). Used by: shea", md)
        data = json.loads((self.tmp / "attribution.json").read_text())
        self.assertEqual({d["title"] for d in data}, {"Photo 1", "Photo 2"})
        self.assertEqual(data[0]["usedBy"], ["shea"])
        self.assertIn("CC BY-SA 4.0", imgs[0].credit())


if __name__ == "__main__":
    unittest.main()
