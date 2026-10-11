"""MediaUploader: media's signed-upload flow (request URL → PUT bytes → complete) and reuse of earlier uploads."""
import tempfile
import unittest
from pathlib import Path

from seedlib.http import ApiError
from seedlib.images.model import ImageFile
from seedlib.media import MediaUploader
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
