"""Image relevance: a photo must be about the product (title or categories contain the query words, nothing off topic)."""
import tempfile
import unittest
from pathlib import Path

from seedlib import dataset as D
from seedlib.images import relevance as R
from seedlib.images.library import ImageLibrary
from seedlib.images.model import Candidate, ImageFile
from seedlib.images.review import ImageReview

from tests.helpers import png


class JudgeTest(unittest.TestCase):
    def test_keeps_photos_that_name_the_product_and_refuses_the_rest(self):
        groups = R.groups_for(["garri", "cassava flakes"])
        self.assertTrue(R.judge("Garri served with groundnut", groups))
        self.assertTrue(R.judge("Cassava flakes in a bowl", groups))
        self.assertFalse(R.judge("Bowl", groups))  # nothing about it
        # categories count: the title is vague but the Commons category names the food
        self.assertTrue(R.judge(R.candidate_text("IMG 2041", {"categories": ["Garri", "Food of Nigeria"]}), groups))

    def test_off_topic_subjects_are_refused_even_when_a_word_matches(self):
        groups = R.groups_for(["ijebu garri"])
        v = R.judge("Ijebu garri basketball player", groups)
        self.assertFalse(v.ok)
        self.assertIn("basketball", v.reason)
        self.assertFalse(R.judge("Mosque in Ijebu", R.groups_for(["kuli kuli peanut snack"])).ok)
        self.assertFalse(R.judge("Seashells on a beach", R.groups_for(["dried ata rodo pepper"])).ok)

    def test_a_query_that_asks_for_the_off_topic_subject_is_not_refused(self):
        self.assertTrue(R.judge("Wedding portrait", R.groups_for(["wedding portrait"])))

    def test_all_words_of_a_group_are_needed_and_alternatives_each_count(self):
        self.assertFalse(R.judge("Rice paddies", R.groups_for(["ofada rice"])).ok)  # ofada missing
        self.assertTrue(R.judge("Ofada rices", R.groups_for(["ofada rice"])).ok)  # plural
        perfume = R.groups_for(["Perfumes & fragrances"])
        self.assertTrue(R.judge("A perfume bottle", perfume).ok)
        self.assertTrue(R.judge("Fragrance oils", perfume).ok)

    def test_generic_words_never_match_on_their_own(self):
        self.assertEqual(R.groups_for(["african food", "photo"]), [])
        self.assertEqual(R.significant("Fresh African food"), frozenset())
        self.assertTrue(R.judge("anything", []).ok)  # no filter without queries

    def test_strict_queries_run_from_the_strictest_to_the_plain_query(self):
        self.assertEqual(R.strict_queries(["garri", "cassava"], "Nigerian", food=True),
                         ["garri Nigerian food", "garri food", "garri", "cassava Nigerian food", "cassava food", "cassava"])
        self.assertEqual(R.strict_queries(["kente stole"], "Ghanaian", food=False), ["kente stole"])
        self.assertEqual(R.strict_queries(["jollof"], "West African", food=True), ["jollof food", "jollof"])


class DatasetTest(unittest.TestCase):
    def test_generated_demo_items_have_product_names_not_a_demo_label(self):
        demo = [p for p in D.PRODUCTS if p.key.startswith("demo-")]
        self.assertTrue(demo)
        for p in demo:
            self.assertNotIn("demo", p.title.lower(), p.title)
            self.assertTrue(p.title.startswith("AfroTransact Brand "))
            self.assertFalse(any("demo" in q for q in p.queries), p.queries)
            self.assertFalse(any("demo" in b.lower() for b in p.bullets), p.bullets)
        self.assertEqual(D.demo_title("Perfumes & fragrances"), "AfroTransact Brand Perfumes & Fragrances")

    def test_every_product_has_a_filterable_query(self):
        for p in D.PRODUCTS:
            self.assertTrue(R.groups_for(p.queries), f"{p.key}: queries are all generic words: {p.queries}")

    def test_food_products_search_with_the_cuisine_first(self):
        garri = next(p for p in D.PRODUCTS if p.key == "ijebu-garri")
        self.assertEqual(D.image_queries(garri)[0], "garri Nigerian food")


class LibraryFilterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def cand(self, n, title, categories=()):
        return Candidate(source="wikimedia", download_url=f"https://img.example/{n}.png", page_url=f"https://commons.example/{n}",
                         title=title, author="A", author_url="", license="CC BY 4.0", license_url="", width=1600, height=1200,
                         mime="image/png", extra={"categories": list(categories)})

    def lib(self, results):
        class Src:
            name = "wikimedia"

            def __init__(self, r):
                self.r, self.asked = r, []

            def search(self, q, limit, orientation):
                self.asked.append(q)
                return list(self.r.get(q, []))

        src = Src(results)
        return ImageLibrary(self.tmp / "c", [src], download=lambda url: png(1600, 1200, seed=int(url.rsplit("/", 1)[-1].split(".")[0])),
                            log=lambda m: None), src

    def test_downloads_only_relevant_photos_and_reports_what_was_turned_away(self):
        lib, src = self.lib({"garri Nigerian food": [self.cand(1, "Ijebu basketball player"), self.cand(2, "Crowd")],
                             "garri": [self.cand(3, "Garri and soup", ["Food of Nigeria"])]})
        got = lib.images_for("ijebu-garri", ["garri Nigerian food", "garri"], 1, orientation="square", require=["garri"])
        self.assertEqual([i.title for i in got], ["Garri and soup"])
        self.assertEqual(got[0].keywords_matched, "garri")
        self.assertEqual(got[0].query, "garri")
        why = dict(lib.rejected["ijebu-garri"])
        self.assertIn("basketball", why["Ijebu basketball player"])
        self.assertIn("about crowd", why["Crowd"])
        self.assertIn("do not mention", dict(lib.rejected["ijebu-garri"] + [("Plain", R.judge("Plain", R.groups_for(["garri"])).reason)])["Plain"])

    def test_no_relevant_photo_means_no_image_rather_than_a_wrong_one(self):
        lib, _ = self.lib({"kuli kuli": [self.cand(1, "Mosque at dusk"), self.cand(2, "Sunset")]})
        self.assertEqual(lib.images_for("kuli-kuli", ["kuli kuli"], 2, require=["kuli kuli peanut snack"]), [])

    def test_a_photo_cached_by_an_older_looser_run_is_checked_again(self):
        lib, _ = self.lib({"garri": [self.cand(3, "Garri and soup")]})
        lib.images_for("garri", ["garri"], 1)  # no filter: caches it
        lib.save()
        again, src = self.lib({"garri": [self.cand(4, "Garri bowl")]})
        again.manifest = lib.manifest
        got = again.images_for("garri", ["garri"], 1, require=["cassava"])  # the cached "Garri and soup" is not about cassava
        self.assertEqual(got, [])

    def test_your_own_files_are_never_filtered(self):
        folder = self.tmp / "mine"
        folder.mkdir()
        (folder / "ijebu-garri.png").write_bytes(png(1600, 1200, seed=9))
        lib = ImageLibrary(self.tmp / "c2", [], folder, log=lambda m: None)
        got = lib.images_for("ijebu-garri", ["garri"], 1, require=["something else entirely"])
        self.assertEqual([i.source for i in got], ["local"])


class ReviewTest(unittest.TestCase):
    def img(self, title, source="wikimedia", matched="garri"):
        return ImageFile(path="/x", source=source, title=title, author="A", author_url="", license="CC BY 4.0", license_url="",
                         page_url="https://commons.example/x", width=1, height=1, mime="image/png", sha256="s", keywords_matched=matched)

    def test_lists_every_product_with_its_photo_and_flags_the_doubtful(self):
        r = ImageReview()
        r.add("ijebu-garri", "Ijebu Garri, 5 lb", [self.img("Garri and soup")], [("Ijebu basketball player", "about basketball")])
        r.add("kuli-kuli", "Kuli Kuli", [], [("Mosque", "about mosque")])
        r.add("old", "Old cached", [self.img("Some photo", matched="")])
        r.add("mine", "Mine", [self.img("my file", source="local", matched="")])
        self.assertEqual({k: v.status for k, v in r.rows.items()}, {"ijebu-garri": "ok", "kuli-kuli": "NO IMAGE", "old": "check", "mine": "yours"})
        self.assertEqual([x.key for x in r.flagged()], ["kuli-kuli", "old"])
        md, csv_text = r.to_markdown(), r.to_csv()
        for needle in ("Ijebu Garri, 5 lb", "Garri and soup", "Ijebu basketball player", "NO IMAGE", "SEED_IMAGES_DIR"):
            self.assertIn(needle, md)
        self.assertIn("Garri and soup", csv_text)
        self.assertEqual(r.summary(), "4 products reviewed: 2 ok, 1 to check, 1 without an image")


if __name__ == "__main__":
    unittest.main()
