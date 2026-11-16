"""The publish-gate answers (listing.py), the recorded catalog schemas (testdata), `make seed-check`, and the seeder paths
that depend on them: photo retries, ad campaigns without products, the first-party sign-in preflight."""
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace

import gen_testdata
import seed_check
from seedlib import dataset as D
from seedlib import listing as L
from seedlib.http import ApiError
from seedlib.images.model import ImageFile, sniff
from seedlib.labelimage import label_png
from seedlib.media import ImageRejected
from seedlib.seeder import Seeder, create_body

from tests.helpers import png
from tests.test_seeder import FakeHttp, seeder

CATS, TYPES, RULES = seed_check.load(seed_check.TESTDATA)
BY_ID = {c["id"]: c for c in CATS.values()}


def rules_of(path):
    return L.Rules(RULES[L.category_code(CATS[path], BY_ID)])


def product(key):
    return next(p for p in D.PRODUCTS if p.key == key)


class FixturesTest(unittest.TestCase):
    def test_the_recorded_schemas_match_their_sources(self):
        self.assertEqual(gen_testdata.main(["--check"]), 0, "run python3 tools/seed/gen_testdata.py")

    def test_the_rule_field_table_is_read_from_the_catalogs_enum(self):
        fields = gen_testdata.rule_fields()
        self.assertEqual(len(fields), 30)
        self.assertEqual(fields["shelf_life"]["attributeKey"], "shelf")
        self.assertIn("months", fields["shelf_life"]["units"])
        self.assertEqual(fields["storage"]["options"][0], "Room temperature")
        self.assertTrue(fields["package_size"]["perVariant"])

    def test_every_category_in_the_tree_has_rules_or_falls_back_to_general(self):
        for node in CATS.values():
            if node["level"] == "PRODUCT_TYPE":
                self.assertIn(L.category_code(node, BY_ID), set(RULES) | {node["code"]})


class AnswersTest(unittest.TestCase):
    def test_what_the_owners_log_complained_about_is_answered_from_the_schema(self):
        p = product("ofada-rice")
        cat = CATS[p.path]
        rules, ptype = rules_of(p.path), TYPES[cat["productType"]]
        bare = create_body(p, cat, ptype, None, [seed_check.FAKE_IMAGE], None, [], None)
        refused = L.check_listing(rules, ptype, bare)
        self.assertIn("attributes.net_weight", refused)
        self.assertIn("variants[0].packaging", refused)
        body = create_body(p, cat, ptype, rules, [seed_check.FAKE_IMAGE], None, [], None)
        self.assertEqual(L.check_listing(rules, ptype, body), {})
        self.assertEqual(body["attributes"]["net_weight"], "5 lb", "from the product's own unit")
        self.assertRegex(body["attributes"]["shelf"], r"^\d+ (days|months|years)$")
        self.assertTrue(body["listing"]["description"].startswith("Ofada Rice, Destoned."))
        self.assertEqual(len({v["packaging"]["weightLb"] for v in body["variants"]}), 2, "per variant: 2 lb and 5 lb")

    def test_every_dataset_product_passes_the_publish_gate_or_is_skipped_for_its_category(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = seed_check.main([])
        self.assertEqual(code, 0, out.getvalue())
        self.assertIn("0 refused", out.getvalue())

    def test_a_label_photo_goes_where_the_rules_ask_for_one_and_only_there(self):
        needs = [p for p in D.PRODUCTS if CATS.get(p.path) and rules_of(p.path).expects("label_photo")]
        self.assertTrue(needs, "some category asks for a label photo")
        p = needs[0]
        cat = CATS[p.path]
        rules, ptype = rules_of(p.path), TYPES[cat["productType"]]
        no_label = create_body(p, cat, ptype, rules, [seed_check.FAKE_IMAGE], None, [], None)
        self.assertIn("labelPhoto", L.check_listing(rules, ptype, no_label))
        with_label = create_body(p, cat, ptype, rules, [seed_check.FAKE_IMAGE], dict(seed_check.FAKE_IMAGE), [], None)
        self.assertEqual(with_label["images"][-1]["role"], "LABEL")
        self.assertNotIn("labelPhoto", L.check_listing(rules, ptype, with_label))

    def test_a_gtin_only_where_the_barcode_is_required_and_it_is_valid(self):
        gtins = []
        for p in D.PRODUCTS:
            if p.path not in CATS:
                continue
            outcome, _, body = seed_check.check_product(p, CATS, TYPES, RULES)
            if body is None:
                continue
            status = rules_of(p.path).status("product_id")
            for v in body["variants"]:
                if v.get("gtin") and TYPES[CATS[p.path]["productType"]]["identifierRule"] != "ISBN":
                    gtins.append(v["gtin"])
                    self.assertEqual(status, "required", p.key)
                if status == "required" and TYPES[CATS[p.path]["productType"]]["identifierRule"] != "ISBN":
                    self.assertTrue(v.get("gtin"), p.key)
        self.assertTrue(gtins)
        for g in gtins:
            self.assertEqual(len(g), 13)
            self.assertTrue(g.startswith("200"), "the restricted-circulation prefix: clearly not a real company")
            self.assertTrue(L._gtin_valid(g))
        self.assertEqual(L.gtin13("a"), L.gtin13("a"))
        self.assertNotEqual(L.gtin13("a"), L.gtin13("b"))

    def test_measures_options_and_packages(self):
        self.assertEqual(L.measures("5 lb bag, 8 oz tin, 1.5 L"), [(5.0, "lb"), (8.0, "oz"), (1.5, "l")])
        self.assertEqual(L.pick("Refrigerate after opening", ["Room temperature", "Cool and dry", "Refrigerate", "Freeze"]), "Refrigerate")
        self.assertEqual(L.pick("nope", ["a", "b"]), "a")
        pkg = L.package_for(["2 lb"])
        self.assertEqual(pkg["weightLb"], 2.2)
        self.assertIsNone(L._package_problem(pkg))
        self.assertIsNone(L._package_problem(L.package_for(["500 kg"])), "capped to the carrier limits")
        self.assertIsNone(L._package_problem(L.package_for([])))

    def test_dangerous_goods_follow_the_words_of_the_listing(self):
        p = SimpleNamespace(title="Soy candle gift set", queries=(), bullets=())
        self.assertTrue(L.DANGEROUS.search(p.title))
        self.assertFalse(L.DANGEROUS.search("Ofada Rice, Destoned"))

    def test_the_label_png_is_a_valid_large_image_and_differs_per_product(self):
        a, b = label_png("moringa"), label_png("shea-butter")
        self.assertEqual(sniff(a), ("image/png", 900, 1200))
        self.assertEqual(a, label_png("moringa"))
        self.assertNotEqual(a, b)
        self.assertLess(len(a), 400_000)

    def test_the_cli_runs_and_exits_zero(self):
        res = subprocess.run([sys.executable, str(Path(seed_check.__file__))], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("seed-check:", res.stdout)


class PhotoRetryTest(unittest.TestCase):
    def img(self, n):
        return ImageFile(path=f"/tmp/x{n}.png", source="wikimedia", title=f"T{n}", author="", author_url="", license="CC0",
                         license_url="", page_url="", width=1200, height=1200, mime="image/png", sha256=f"sha{n}")

    def test_a_refused_photo_is_banned_and_another_candidate_is_uploaded(self):
        s = seeder()
        offered = [[self.img(1)], [self.img(2)]]
        s.library.images_for = lambda *a, **k: offered.pop(0) if offered else []
        refused = []

        def upload(img, purpose, slot=""):
            if img.sha256 == "sha1":
                refused.append(img)
                raise ImageRejected(img, ApiError("POST", "/v1/assets/a/complete", 422, {"code": "media.rejected"},
                                                  "asset not published (REJECTED: photo is too small)"))
            return {"id": "a2", "url": "http://localhost:9000/x.png", "width": 1200, "height": 1200, "bytes": 9,
                    "contentType": "image/png"}

        s.uploader.upload = upload
        imgs, assets = s._upload_images(product("ofada-rice"))
        self.assertEqual([i.sha256 for i in imgs], ["sha2"])
        self.assertEqual(len(assets), 1)
        self.assertIn("sha1", s.library.banned)

    def test_no_candidate_left_gives_up_after_the_attempts(self):
        s = seeder()
        s.library.images_for = lambda *a, **k: [self.img(1)]

        def upload(img, purpose, slot=""):
            raise ImageRejected(img, ApiError("POST", "/c", 422, {}, "REJECTED"))

        s.uploader.upload = upload
        self.assertEqual(s._upload_images(product("ofada-rice")), ([], []))


class AdsAndSignInTest(unittest.TestCase):
    def test_ads_are_skipped_with_one_message_when_there_are_no_products(self):
        logs = []
        s = seeder()
        s.log = logs.append
        s.svc["catalog"].on("GET", "/v1/products", lambda *a: {"items": [], "total": 0})
        s.svc["content"].on("GET", "/v1/ads/campaigns", lambda *a: [])
        s.step_ads()
        self.assertEqual(len([m for m in logs if "skipped" in m]), 1)
        self.assertTrue(any("no products yet" in m for m in logs))
        self.assertFalse([c for c in s.svc["content"].calls if c[0] == "POST"])
        self.assertEqual(s.report.problems, [])

    def test_ads_are_created_once_products_exist(self):
        s = seeder()
        s.svc["catalog"].on("GET", "/v1/products", lambda path, body, kw: {"items": [], "total": 5})
        s.svc["content"].on("GET", "/v1/ads/campaigns", lambda *a: [])
        created = []
        s.svc["content"].on("POST", "/v1/ads/campaigns", lambda path, body, kw: created.append(body) or body)
        s.products = {p.key: {"atin": f"B0{n:08d}"} for n, p in enumerate(D.PRODUCTS)}
        s.step_ads()
        self.assertTrue(created)
        for body in created:
            self.assertTrue(1 <= len(body["atins"]) <= 50)

    def test_the_first_party_check_names_the_cause_in_one_sentence(self):
        s = seeder()
        s.svc["identity"] = FakeHttp()
        from seedlib import seeder as seeder_module

        class Users:
            def __init__(self, *a):
                pass

            def client_token(self):
                return "t"

            def sign_in(self, token, email):
                raise ApiError("POST", "http://localhost:8100/v1/auth/password", 403,
                               {"code": "identity.first-party-client-required"})

        original = seeder_module.TestUsers
        seeder_module.TestUsers = Users
        try:
            problem = s.first_party_problem()
        finally:
            seeder_module.TestUsers = original
        self.assertIn("first-party-client-required", problem)
        self.assertIn("restart core", problem)


if __name__ == "__main__":
    unittest.main()
