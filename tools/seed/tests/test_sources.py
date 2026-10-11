"""Image API parsing against hand-written fixtures of the documented response shapes (tests/fixtures/README.md)."""
import json
import unittest
import urllib.parse

from seedlib.images import licenses
from seedlib.images.sources import (
    PexelsSource,
    UnsplashSource,
    WikimediaSource,
    parse_pexels,
    parse_unsplash,
    parse_wikimedia,
    wikimedia_params,
)

from tests.helpers import FIXTURES


def load(name):
    return json.loads((FIXTURES / name).read_text())


class WikimediaTest(unittest.TestCase):
    def test_keeps_only_openly_licensed_photos_in_search_rank_order(self):
        found = parse_wikimedia(load("wikimedia_search_jollof.json"))

        self.assertEqual([c.page_url.rsplit(":", 1)[-1] for c in found],
                         ["Party_jollof_in_a_pot.jpg", "Jollof_rice_with_fried_plantain.jpg", "Jollof_rice,_Accra.png"])
        cc0, by_sa, pd = found
        self.assertEqual((cc0.license, cc0.author, cc0.width, cc0.height), ("CC0", "Kofi Example", 1600, 1600))
        self.assertTrue(cc0.download_url.endswith("/1600px-Party_jollof_in_a_pot.jpg"))
        self.assertEqual(by_sa.license, "CC BY-SA 4.0")
        self.assertEqual(by_sa.author, "Ada Example")
        self.assertEqual(by_sa.author_url, "https://commons.wikimedia.org/wiki/User:Ada_Example")
        self.assertEqual(by_sa.title, "Jollof rice with fried plantain")
        self.assertEqual(by_sa.license_url, "https://creativecommons.org/licenses/by-sa/4.0")
        self.assertEqual((by_sa.width, by_sa.height), (1600, 1200), "thumbnail size, not the original's")
        self.assertEqual((pd.license, pd.author, pd.mime), ("Public domain", "Ama & Kwame", "image/png"))
        self.assertEqual((pd.width, pd.height), (1400, 1050), "no thumbnail: the original file")
        self.assertEqual(pd.license_url, "https://creativecommons.org/publicdomain/mark/1.0/")

    def test_formatversion_2_lists_pages(self):
        found = parse_wikimedia(load("wikimedia_search_formatversion2.json"))

        self.assertEqual([c.title for c in found], ["Kente weaver at Bonwire", "Kente cloth detail"])
        self.assertEqual([c.license for c in found], ["CC BY-SA 3.0", "CC BY 2.0"])
        self.assertEqual(found[0].license_url, "https://creativecommons.org/licenses/by-sa/3.0/")

    def test_request_follows_the_documented_query(self):
        params = wikimedia_params("kente cloth", limit=12, width=1600)

        self.assertEqual(params["action"], "query")
        self.assertEqual(params["generator"], "search")
        self.assertEqual(params["gsrnamespace"], "6")
        self.assertEqual(params["gsrsearch"], "kente cloth filetype:bitmap")
        self.assertEqual(params["prop"], "imageinfo")
        self.assertEqual(set(params["iiprop"].split("|")), {"url", "size", "mime", "extmetadata"})
        self.assertEqual((params["iiurlwidth"], params["format"], params["gsrlimit"]), ("1600", "json", "12"))

        seen = {}

        def fetch(url, headers):
            seen["url"], seen["headers"] = url, headers
            return load("wikimedia_search_jollof.json")

        out = WikimediaSource(fetch).search("jollof rice", 5)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(seen["url"]).query)
        self.assertTrue(seen["url"].startswith("https://commons.wikimedia.org/w/api.php?"))
        self.assertEqual(query["gsrsearch"], ["jollof rice filetype:bitmap"])
        self.assertEqual(len(out), 3)

    def test_empty_or_odd_payloads(self):
        self.assertEqual(parse_wikimedia({}), [])
        self.assertEqual(parse_wikimedia({"query": {"pages": {"1": {"title": "File:x.jpg"}}}}), [])


class PexelsUnsplashTest(unittest.TestCase):
    def test_pexels(self):
        found = parse_pexels(load("pexels_search.json"))

        first, second = found
        self.assertEqual((first.source, first.license, first.author), ("pexels", "Pexels License", "Abena Example"))
        self.assertIn("dpr=2", first.download_url)
        self.assertEqual((first.width, first.height), (866, 1300), "large2x is fitted into 1880 x 1300")
        self.assertEqual((second.width, second.height), (3000, 2000), "original keeps its size")
        self.assertEqual(second.title, "Pexels photo")

        seen = {}

        def fetch(url, headers):
            seen.update(url=url, headers=headers)
            return load("pexels_search.json")

        PexelsSource("key-123", fetch).search("shea butter", 10, "square")
        self.assertEqual(seen["headers"], {"Authorization": "key-123"})
        self.assertIn("orientation=square", seen["url"])

    def test_unsplash(self):
        found = parse_unsplash(load("unsplash_search.json"))

        self.assertEqual(len(found), 1, "results without urls.raw are skipped")
        c = found[0]
        self.assertEqual((c.author, c.license, c.width, c.height), ("Chidi Example", "Unsplash License", 1600, 1066))
        self.assertTrue(c.download_url.startswith("https://images.unsplash.com/photo-1600000000000-abcdef?ixid=XYZ"))
        self.assertIn("&w=1600&fm=jpg", c.download_url)

        calls = []

        def fetch(url, headers):
            calls.append((url, headers))
            return load("unsplash_search.json")

        src = UnsplashSource("acc-1", fetch)
        src.search("plantain chips", 5, "square")
        src.track_download(c)
        self.assertIn("orientation=squarish", calls[0][0])
        self.assertEqual(calls[0][1]["Authorization"], "Client-ID acc-1")
        self.assertEqual(calls[1][0], "https://api.unsplash.com/photos/Ab12Cd34/download?ixid=XYZ")


class LicensesTest(unittest.TestCase):
    def test_accepted(self):
        cases = {("CC0", ""): "CC0", ("", "cc0"): "CC0", ("Public domain", "pd"): "Public domain",
                 ("PD-US", ""): "Public domain", ("CC BY 4.0", "cc-by-4.0"): "CC BY 4.0",
                 ("CC BY-SA 3.0", ""): "CC BY-SA 3.0", ("", "cc-by-sa-2.5"): "CC BY-SA 2.5", ("CC BY", ""): "CC BY"}
        for (short, code), want in cases.items():
            self.assertEqual(licenses.canonical(short, code), want, (short, code))

    def test_rejected(self):
        for short, code in [("CC BY-NC 4.0", ""), ("CC BY-NC-SA 2.0", "cc-by-nc-sa-2.0"), ("CC BY-ND 4.0", ""),
                            ("GFDL", "gfdl"), ("Fair use", ""), ("", ""), ("All rights reserved", "")]:
            self.assertIsNone(licenses.canonical(short, code), (short, code))

    def test_urls(self):
        self.assertEqual(licenses.license_url("CC BY 2.0"), "https://creativecommons.org/licenses/by/2.0/")
        self.assertEqual(licenses.license_url("CC0"), "https://creativecommons.org/publicdomain/zero/1.0/")
        self.assertEqual(licenses.license_url("CC BY-SA 4.0", "//creativecommons.org/x"), "https://creativecommons.org/x")


if __name__ == "__main__":
    unittest.main()
