"""Test helpers: tiny valid image files built with the standard library (no Pillow)."""
from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # tools/seed
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def png(width: int, height: int, seed: int = 0) -> bytes:
    """A valid grey PNG of the given size (one zlib stream of empty-filter rows)."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    row = b"\x00" + bytes([(seed * 37 + x) % 256 for x in range(width)])
    raw = row * height
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b""))


def jpeg_header(width: int, height: int) -> bytes:
    """SOI, an APP0 segment and a baseline SOF0 with the size: enough for size sniffing."""
    app0 = b"\xff\xe0" + struct.pack(">H", 16) + b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    sof0 = b"\xff\xc0" + struct.pack(">HBHHB", 11, 8, height, width, 1) + b"\x01\x11\x00"
    return b"\xff\xd8" + app0 + sof0 + b"\xff\xd9"


def webp_vp8x(width: int, height: int) -> bytes:
    payload = b"VP8X" + struct.pack("<I", 10) + b"\x00\x00\x00\x00" + (width - 1).to_bytes(3, "little") + \
        (height - 1).to_bytes(3, "little")
    return b"RIFF" + struct.pack("<I", 4 + len(payload)) + b"WEBP" + payload
