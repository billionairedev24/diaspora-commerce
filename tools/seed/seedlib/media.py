"""Uploads images through media's signed-upload flow: POST /v1/uploads → PUT the bytes → POST /complete.

Assets are remembered per (sha256, purpose) in the seed state, so re-runs reuse them instead of uploading again
(media's perceptual hash would only note the duplicate, but there is no reason to store a photo twice). A remembered
asset is reused only while media still knows it AND its public URL still serves: after `make reset` (or only the object
store being wiped) the cached id/url are stale and the image is uploaded again.
"""
from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from .http import ApiError, Http, multipart, put_bytes, url_exists
from .images.library import MEDIA_MIN_LONG_EDGE, MEDIA_MIN_SHORT_EDGE, usable_size
from .images.model import ImageFile
from .state import State


class ImageRejected(ApiError):
    """media refused this photo (too small, blurry, prohibited ...): the caller bans it and tries another candidate."""

    def __init__(self, img: ImageFile, cause: ApiError):
        super().__init__(cause.method, cause.url, cause.status, cause.body, str(cause))
        self.img = img


class ImageTooSmall(ValueError):
    """The photo fails media's size rules; caught before any upload."""

    def __init__(self, img: ImageFile, message: str):
        super().__init__(message)
        self.img = img


class MediaUploader:
    def __init__(self, media: Http, state: State, log: Callable[[str], None] = print,
                 put: Callable[[str, bytes, dict[str, str]], int] = put_bytes,
                 head: Callable[[str], bool | None] = url_exists):
        self.media, self.state, self.log, self.put, self.head = media, state, log, put, head
        self.uploaded = 0
        self.reused = 0
        self.cache_dropped = 0  # cached uploads forgotten because the object store was recreated
        self._cache_checked = False

    def check_cache(self) -> None:
        """Validates the upload cache once, cheaply: HEAD the public URL of one cached asset. When the object store no
        longer has it (MinIO recreated, `make reset`) every cached upload is stale: drop them all and say so in ONE line."""
        if self._cache_checked:
            return
        self._cache_checked = True
        for key, known in list(self.state.assets.items()):
            url = known.get("url") or ""
            if url and self.head(url) is False:
                self.cache_dropped = len(self.state.assets)
                self.state.assets.clear()
                self.log(f"  ! the object store was recreated: {self.cache_dropped} cached upload(s) dropped, photos are "
                         "uploaded again")
            return

    @staticmethod
    def check_size(img: ImageFile) -> None:
        """Refuses locally a photo media would reject for its size, before any upload."""
        if not usable_size(img.width, img.height):
            raise ImageTooSmall(img, f"photo is {img.width}x{img.height} px: media needs at least {MEDIA_MIN_SHORT_EDGE} px "
                                     f"on the short side and {MEDIA_MIN_LONG_EDGE} px on the long side")

    def upload(self, img: ImageFile, purpose: str, slot: str = "") -> dict[str, Any]:
        """The READY (or quality-FLAGGED) public asset for `img`: {id, url, width, height, bytes, contentType}.
        Raises ImageRejected when media refuses the photo, ImageTooSmall when it is too small to try."""
        self.check_size(img)
        self.check_cache()
        key = f"{purpose}:{slot}:{img.sha256}" if slot else f"{purpose}:{img.sha256}"
        known = self.state.assets.get(key)
        if known:
            try:
                asset = self.media.get(f"/v1/assets/{known['id']}", retries=1)
                if asset and asset.get("url") and asset.get("status") in ("READY", "FLAGGED"):
                    if self.head(asset["url"]) is not False:
                        self.reused += 1
                        return self._summary(asset)
            except ApiError as e:
                if e.status not in (403, 404):
                    raise
        data = Path(img.path).read_bytes()
        name = Path(img.path).name
        if img.source != "local":
            name = f"{img.source}-{img.sha256[:12]}{Path(img.path).suffix}"
        request = {"purpose": purpose, "fileName": name, "contentType": img.mime, "size": len(data)}
        if slot:
            request["slot"] = slot
        res = self.media.post("/v1/uploads", request)
        asset, up = res["asset"], res["upload"]
        headers = dict(up.get("headers") or {})
        if up.get("formField"):
            body, ctype = multipart(up["formField"], name, img.mime, data)
            headers = {k: v for k, v in headers.items() if k.lower() not in ("content-type", "content-length")}
            headers["Content-Type"] = ctype
            self.put(up["url"], body, headers)
        else:
            headers.setdefault("Content-Type", img.mime)
            self.put(up["url"], data, headers)
        try:
            done = self.media.post(f"/v1/assets/{asset['id']}/complete")
        except ApiError as e:
            if e.status == 422:
                raise ImageRejected(img, e) from None
            raise
        verdict = (done.get("moderation") or {}).get("verdict")
        if not done.get("url") or done.get("status") not in ("READY", "FLAGGED") or verdict not in (None, "PASSED"):
            raise ImageRejected(img, ApiError("POST", f"/v1/assets/{asset['id']}/complete", 422, done,
                                              f"asset not published (status {done.get('status')}, moderation {verdict}, "
                                              f"flags {done.get('flags')})"))
        self.state.assets[key] = {"id": done["id"], "url": done["url"], "source": img.source}
        self.uploaded += 1
        return self._summary(done)

    @staticmethod
    def _summary(a: dict[str, Any]) -> dict[str, Any]:
        return {"id": a["id"], "url": a["url"], "width": int(a.get("width") or 0), "height": int(a.get("height") or 0),
                "bytes": int(a.get("bytes") or 0), "contentType": a.get("contentType") or "image/jpeg"}
