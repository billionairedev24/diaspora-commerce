"""Uploads images through media's signed-upload flow: POST /v1/uploads → PUT the bytes → POST /complete.

Assets are remembered per (sha256, purpose) in the seed state, so re-runs reuse them instead of uploading again
(media's perceptual hash would only note the duplicate, but there is no reason to store a photo twice).
"""
from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any

from .http import ApiError, Http, multipart, put_bytes
from .images.model import ImageFile
from .state import State


class MediaUploader:
    def __init__(self, media: Http, state: State, log: Callable[[str], None] = print,
                 put: Callable[[str, bytes, dict[str, str]], int] = put_bytes):
        self.media, self.state, self.log, self.put = media, state, log, put
        self.uploaded = 0
        self.reused = 0

    def upload(self, img: ImageFile, purpose: str) -> dict[str, Any]:
        """The READY (or quality-FLAGGED) public asset for `img`: {id, url, width, height, bytes, contentType}."""
        key = f"{purpose}:{img.sha256}"
        known = self.state.assets.get(key)
        if known:
            try:
                asset = self.media.get(f"/v1/assets/{known['id']}", retries=1)
                if asset and asset.get("url") and asset.get("status") in ("READY", "FLAGGED"):
                    self.reused += 1
                    return self._summary(asset)
            except ApiError as e:
                if e.status not in (403, 404):
                    raise
        data = Path(img.path).read_bytes()
        name = Path(img.path).name
        if img.source != "local":
            name = f"{img.source}-{img.sha256[:12]}{Path(img.path).suffix}"
        res = self.media.post("/v1/uploads", {"purpose": purpose, "fileName": name, "contentType": img.mime,
                                              "size": len(data)})
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
        done = self.media.post(f"/v1/assets/{asset['id']}/complete")
        verdict = (done.get("moderation") or {}).get("verdict")
        if not done.get("url") or done.get("status") not in ("READY", "FLAGGED") or verdict not in (None, "PASSED"):
            raise ApiError("POST", f"/v1/assets/{asset['id']}/complete", 422, done,
                           f"asset not published (status {done.get('status')}, moderation {verdict}, "
                           f"flags {done.get('flags')})")
        self.state.assets[key] = {"id": done["id"], "url": done["url"], "source": img.source}
        self.uploaded += 1
        return self._summary(done)

    @staticmethod
    def _summary(a: dict[str, Any]) -> dict[str, Any]:
        return {"id": a["id"], "url": a["url"], "width": int(a.get("width") or 0), "height": int(a.get("height") or 0),
                "bytes": int(a.get("bytes") or 0), "contentType": a.get("contentType") or "image/jpeg"}
