"""Image candidates (search results) and files (downloaded or provided), with their attribution."""
from __future__ import annotations

import struct
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass
class Candidate:
    """One search result from an image API, before download."""

    source: str  # wikimedia | pexels | unsplash
    download_url: str
    page_url: str  # human page for the photo (attribution link)
    title: str
    author: str
    author_url: str
    license: str  # canonical short name, e.g. "CC BY-SA 4.0", "CC0", "Public domain", "Pexels License"
    license_url: str
    width: int  # pixel size of the file at download_url
    height: int
    mime: str = "image/jpeg"
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def min_edge(self) -> int:
        return min(self.width, self.height)

    @property
    def aspect(self) -> float:
        return self.width / self.height if self.height else 0.0


@dataclass
class ImageFile:
    """An image on disk ready to upload, with where it came from."""

    path: str
    source: str  # wikimedia | pexels | unsplash | local
    title: str
    author: str
    author_url: str
    license: str
    license_url: str
    page_url: str
    width: int
    height: int
    mime: str
    sha256: str

    @property
    def min_edge(self) -> int:
        return min(self.width, self.height)

    def credit(self) -> str:
        """One-line credit: "Title" by Author, License (source page)."""
        if self.source == "local":
            return f"{self.title} (SEED_IMAGES_DIR, provided by you)"
        by = f" by {self.author}" if self.author else ""
        return f"\"{self.title}\"{by}, {self.license} ({self.page_url})"

    def to_json(self) -> dict[str, Any]:
        return asdict(self)

    @staticmethod
    def from_json(d: dict[str, Any]) -> ImageFile:
        return ImageFile(**{k: d.get(k, "") if k not in ("width", "height") else int(d.get(k) or 0)
                            for k in ImageFile.__dataclass_fields__})


MIME_EXT = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


def sniff(data: bytes) -> tuple[str, int, int] | None:
    """(mime, width, height) of a JPEG, PNG or WebP from its bytes; None for anything else."""
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        w, h = struct.unpack(">II", data[16:24])
        return "image/png", w, h
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and len(data) >= 30:
        chunk = data[12:16]
        if chunk == b"VP8X":
            w = 1 + int.from_bytes(data[24:27], "little")
            h = 1 + int.from_bytes(data[27:30], "little")
            return "image/webp", w, h
        if chunk == b"VP8 " and len(data) >= 30:
            w, h = struct.unpack("<HH", data[26:30])
            return "image/webp", w & 0x3FFF, h & 0x3FFF
        if chunk == b"VP8L" and len(data) >= 25:
            b = data[21:25]
            w = 1 + (((b[1] & 0x3F) << 8) | b[0])
            h = 1 + (((b[3] & 0xF) << 10) | (b[2] << 2) | ((b[1] & 0xC0) >> 6))
            return "image/webp", w, h
        return None
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seg = struct.unpack(">H", data[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return "image/jpeg", w, h
            i += 2 + seg
        return None
    return None
