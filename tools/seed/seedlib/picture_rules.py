"""The platform picture rules as the seeder reads them (docs/engineering/picture-rules.md).

One setting (profile strict, startup or testing, plus knobs) decides how many photos a product needs and how large they must be.
The seeder asks catalog for it (GET /v1/public/picture-rules, public) before it picks photos, so the demo data follows the rule in
force instead of a fixed Amazon-style one: under `strict` a product needs a main photo of 1000 px and is skipped without one; under
`startup` the photo may be 500 px; under `testing` a product with no usable photo is still created, without a photo, and the run
says so. When catalog cannot be asked the strict standard is used, which is the safe side for demo data.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class PictureRules:
    profile: str = "strict"
    min_images: int = 1
    min_long: int = 1000
    min_short: int = 500
    max_bytes: int = 8 * 1024 * 1024
    allow_placeholders: bool = False
    require_real_photo_for_storefront: bool = True

    @property
    def photo_optional(self) -> bool:
        return self.min_images == 0

    def describe(self) -> str:
        photos = "photos optional" if self.photo_optional else f"at least {self.min_images} photo{'s' if self.min_images > 1 else ''}"
        return f"{self.profile}: {photos}, main photo {self.min_long} px longest side, {self.min_short} px shortest side"


STRICT = PictureRules()


def from_json(doc: dict[str, Any]) -> PictureRules:
    k = doc.get("knobs") or {}
    return PictureRules(
        profile=str(doc.get("profile") or "strict"),
        min_images=int(k.get("minImages", 1)),
        min_long=int(k.get("minLongSidePx", 1000)),
        min_short=int(k.get("minShortSidePx", 500)),
        max_bytes=int(k.get("maxMegabytes", 8)) * 1024 * 1024,
        allow_placeholders=bool(k.get("allowPlaceholders", False)),
        require_real_photo_for_storefront=bool(k.get("requireRealPhotoForStorefront", True)),
    )


def fetch(get: Callable[[str], Any], log: Callable[[str], None] = print) -> PictureRules:
    """The rules in force, or the strict standard when catalog cannot be asked."""
    try:
        rules = from_json(get("/v1/public/picture-rules"))
    except Exception as e:  # any failure: the strict standard is the safe side for demo data
        log(f"  ! picture rules not readable ({e}); using the strict standard")
        return STRICT
    log(f"  picture rules in force: {rules.describe()}")
    return rules
