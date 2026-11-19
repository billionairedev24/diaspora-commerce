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
    # Relevance: the words of the product's query that the photo's title or categories contain, and the query that found it.
    keywords_matched: str = ""
    query: str = ""

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


MIME_EXT = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/avif": ".avif"}


def _avif(data: bytes) -> tuple[str, int, int] | None:
    """AVIF is an ISO base media file whose ftyp brand is avif or avis (HEIC brands are not AVIF); its size is the largest ispe box
    (read from the header: nothing is decoded)."""
    if len(data) < 16 or data[4:8] != b"ftyp":
        return None
    size = struct.unpack(">I", data[:4])[0]
    end = size if 16 <= size <= len(data) else min(len(data), 64)
    brands = [data[8:12]] + [data[o:o + 4] for o in range(16, end - 3, 4)]
    if any(b in (b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis") for b in brands) or not any(b in (b"avif", b"avis") for b in brands):
        return None
    best: tuple[int, int, int] = (0, 0, 0)

    def walk(buf: bytes, depth: int) -> None:
        nonlocal best
        off = 0
        while off + 8 <= len(buf):
            n = struct.unpack(">I", buf[off:off + 4])[0]
            kind = buf[off + 4:off + 8]
            hdr = 8
            if n == 0:
                n = len(buf) - off
            elif n == 1 and off + 16 <= len(buf):
                n, hdr = struct.unpack(">Q", buf[off + 8:off + 16])[0], 16
            if n < hdr or off + n > len(buf):
                return
            body = buf[off + hdr:off + n]
            if kind == b"meta" and depth < 4 and len(body) > 4:
                walk(body[4:], depth + 1)
            elif kind in (b"iprp", b"ipco") and depth < 4:
                walk(body, depth + 1)
            elif kind == b"ispe" and len(body) >= 12:
                w, h = struct.unpack(">II", body[4:12])
                if w * h > best[0]:
                    best = (w * h, w, h)
            off += n

    walk(data, 0)
    return ("image/avif", best[1], best[2]) if best[0] else None


def sniff(data: bytes) -> tuple[str, int, int] | None:
    """(mime, width, height) of a JPEG, PNG, WebP or AVIF from its bytes (never from the file name); None for anything else."""
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return _avif(data)
    if data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) >= 24:
        w, h = struct.unpack(">II", data[16:24])
        return "image/png", w, h
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP" and len(data) >= 25:
        chunk = data[12:16]
        if chunk == b"VP8X" and len(data) >= 30:
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
