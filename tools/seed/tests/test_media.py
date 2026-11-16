"""MediaUploader: media's signed-upload flow (request URL → PUT bytes → complete) and reuse of earlier uploads."""
import tempfile
import unittest
from pathlib import Path

from seedlib.http import ApiError
from seedlib.images.model import ImageFile
from seedlib.media import ImageRejected, ImageTooSmall, MediaUploader
from seedlib.state import State

from tests.helpers import png


class FakeMedia:
    def __init__(self, form_field=None, verdict="PASSED"):
        self.calls, self.assets, self.form_field, self.verdict = [], {}, form_field, verdict

    def post(self, path, body=None, **kw):
        self.calls.append(("POST", path, body))
        if path == "/v1/uploads":
            aid = f"a{len(self.assets) + 1}"
            self.assets[aid] = {"id": aid, "status": "PENDING"}
            up = {"method": "PUT", "url": f"http://minio.local/media/quarantine/{aid}?sig=1",
                  "headers": {"Content-Type": body["contentType"], "Content-Length": str(body["size"])}}
            if self.form_field:
                up["formField"] = self.form_field
            return {"asset": self.assets[aid], "upload": up}
        aid = path.split("/")[3]
        done = {"id": aid, "url": f"http://localhost:9000/media/product-image/{aid}.png", "status": "FLAGGED",
                "width": 1200, "height": 1200, "bytes": 99, "contentType": "image/png",
                "moderation": {"verdict": self.verdict}}
        self.assets[aid] = done
        return done

    def get(self, path, **kw):
        self.calls.append(("GET", path, None))
        aid = path.rsplit("/", 1)[-1]
        if aid not in self.assets:
            raise ApiError("GET", path, 404, {"code": "media.not-found"})
        return self.assets[aid]


class MediaUploaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        path = self.tmp / "shea.png"
        path.write_bytes(png(1200, 1200))
        self.img = ImageFile(path=str(path), source="wikimedia", title="Shea", author="A", author_url="",
                             license="CC0", license_url="", page_url="", width=1200, height=1200, mime="image/png",
                             sha256="abc123")
        self.puts = []

    def put(self, url, data, headers):
        self.puts.append((url, data, headers))
        return 200

    def test_uploads_once_then_reuses_the_asset(self):
        media, state = FakeMedia(), State(self.tmp / "state.json")
        up = MediaUploader(media, state, put=self.put)

        asset = up.upload(self.img, "product-image")
        self.assertEqual(asset, {"id": "a1", "url": "http://localhost:9000/media/product-image/a1.png", "width": 1200,
                                 "height": 1200, "bytes": 99, "contentType": "image/png"})
        req = media.calls[0]
        self.assertEqual(req[2], {"purpose": "product-image", "fileName": "wikimedia-abc123.png",
                                  "contentType": "image/png", "size": len(Path(self.img.path).read_bytes())})
        self.assertEqual(self.puts[0][0], "http://minio.local/media/quarantine/a1?sig=1")
        self.assertEqual(self.puts[0][1], Path(self.img.path).read_bytes())
        self.assertEqual(self.puts[0][2]["Content-Type"], "image/png")
        state.save()

        again = MediaUploader(media, State(self.tmp / "state.json"), put=self.put)
        self.assertEqual(again.upload(self.img, "product-image")["id"], "a1")
        self.assertEqual((again.uploaded, again.reused, len(self.puts)), (0, 1, 1))
        self.assertEqual(again.upload(self.img, "storefront")["id"], "a2", "another purpose is another asset")

    def test_a_cached_asset_whose_object_is_gone_is_uploaded_again(self):
        """After a reset the media record may survive while the object store is empty: the cached url must serve."""
        media, state = FakeMedia(), State(self.tmp / "state.json")
        first = MediaUploader(media, state, put=self.put, head=lambda url: True)
        first.upload(self.img, "product-image")

        gone = MediaUploader(media, state, put=self.put, head=lambda url: False)
        gone.upload(self.img, "product-image")
        self.assertEqual((gone.uploaded, gone.reused, len(self.puts)), (1, 0, 2))

        unknown = MediaUploader(media, state, put=self.put, head=lambda url: None)  # store unreachable: trust the record
        unknown.upload(self.img, "product-image")
        self.assertEqual((unknown.uploaded, unknown.reused), (0, 1))

    def test_a_recreated_object_store_drops_the_whole_cache_with_one_line(self):
        media, state = FakeMedia(), State(self.tmp / "state.json")
        first = MediaUploader(media, state, put=self.put, head=lambda url: True)
        imgs = []
        for n in range(5):
            path = self.tmp / f"p{n}.png"
            path.write_bytes(png(1200, 1200, n))
            imgs.append(ImageFile(path=str(path), source="wikimedia", title=f"P{n}", author="", author_url="", license="CC0",
                                  license_url="", page_url="", width=1200, height=1200, mime="image/png", sha256=f"h{n}"))
            first.upload(imgs[-1], "product-image")
        logs, heads = [], []
        again = MediaUploader(media, state, log=logs.append, put=self.put,
                              head=lambda url: heads.append(url) or False)
        for img in imgs:
            again.upload(img, "product-image")
        self.assertEqual(len(logs), 1, "one summary line, not one per file")
        self.assertIn("5 cached upload(s) dropped", logs[0])
        self.assertEqual(len(heads), 1, "the cache is validated with ONE cheap HEAD")
        self.assertEqual((again.uploaded, again.reused), (5, 0))

    def test_a_photo_media_would_refuse_for_its_size_is_not_uploaded(self):
        small = ImageFile(path=self.img.path, source="wikimedia", title="Tiny", author="", author_url="", license="CC0",
                          license_url="", page_url="", width=400, height=300, mime="image/png", sha256="tiny")
        media = FakeMedia()
        with self.assertRaises(ImageTooSmall):
            MediaUploader(media, State(self.tmp / "s.json"), put=self.put).upload(small, "product-image")
        self.assertEqual(media.calls, [], "checked locally, nothing was sent")

    def test_media_refusing_the_photo_on_complete_is_an_image_rejected(self):
        class Refusing(FakeMedia):
            def post(self, path, body=None, **kw):
                if path.endswith("/complete"):
                    raise ApiError("POST", path, 422, {"code": "media.rejected", "detail": "asset not published (REJECTED)"})
                return super().post(path, body, **kw)

        with self.assertRaises(ImageRejected) as ctx:
            MediaUploader(Refusing(), State(self.tmp / "s.json"), put=self.put).upload(self.img, "product-image")
        self.assertIs(ctx.exception.img, self.img)

    def test_the_label_slot_is_sent_and_cached_apart(self):
        media = FakeMedia()
        up = MediaUploader(media, State(self.tmp / "s.json"), put=self.put)
        up.upload(self.img, "product-image", slot="LABEL")
        self.assertEqual(media.calls[0][2]["slot"], "LABEL")
        up.upload(self.img, "product-image")
        self.assertEqual(up.uploaded, 2)

    def test_multipart_when_the_store_wants_a_form_field(self):
        up = MediaUploader(FakeMedia(form_field="file"), State(self.tmp / "s.json"), put=self.put)
        up.upload(self.img, "product-image")

        _, body, headers = self.puts[0]
        self.assertTrue(headers["Content-Type"].startswith("multipart/form-data; boundary="))
        self.assertNotIn("Content-Length", headers)
        self.assertIn(b'name="file"; filename="wikimedia-abc123.png"', body)

    def test_held_or_blocked_images_are_errors(self):
        up = MediaUploader(FakeMedia(verdict="HELD"), State(self.tmp / "s.json"), put=self.put)
        with self.assertRaises(ApiError) as ctx:
            up.upload(self.img, "product-image")
        self.assertIn("moderation HELD", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
