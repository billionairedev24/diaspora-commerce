"""A stand-in label photo for demo products whose category asks for one (ingredients and nutrition panel, Drug Facts,
Supplement Facts).

Pure standard library (zlib + struct): a 900 x 1200 PNG that looks like a printed panel (frame, heavy header bar, ruled rows
of text-like bars, a boxed footer), different for every product so media's duplicate check does not hold them together.
It is a placeholder for DEMO data: it says nothing and is not a real label. Replace it by uploading a real photo.
"""
from __future__ import annotations

import hashlib
import struct
import zlib

WIDTH, HEIGHT = 900, 1200
INK = (24, 24, 24)
PAPER = (255, 255, 255)
GREY = (200, 200, 200)


def _rng(seed: str):
    """Deterministic stream of ints from the seed (sha256 in counter mode)."""
    counter = 0
    while True:
        block = hashlib.sha256(f"{seed}:{counter}".encode()).digest()
        counter += 1
        yield from block


def label_png(seed: str) -> bytes:
    """The PNG bytes of the panel for `seed` (the product's key): the same seed always gives the same file."""
    pixels = [bytearray(bytes(PAPER) * WIDTH) for _ in range(HEIGHT)]
    rnd = _rng(seed)

    def rect(x0: int, y0: int, x1: int, y1: int, colour: tuple[int, int, int]) -> None:
        row = bytes(colour) * (x1 - x0)
        for y in range(max(0, y0), min(HEIGHT, y1)):
            pixels[y][x0 * 3:x1 * 3] = row

    def frame(x0: int, y0: int, x1: int, y1: int, t: int) -> None:
        rect(x0, y0, x1, y0 + t, INK)
        rect(x0, y1 - t, x1, y1, INK)
        rect(x0, y0, x0 + t, y1, INK)
        rect(x1 - t, y0, x1, y1, INK)

    frame(60, 60, WIDTH - 60, HEIGHT - 60, 8)
    rect(100, 110, WIDTH - 100, 200 + next(rnd) % 30, INK)  # the heading
    y = 260
    while y < HEIGHT - 330:
        thick = 6 if next(rnd) % 5 == 0 else 2
        rect(100, y, WIDTH - 100, y + thick, INK)  # a ruled line
        y += 26
        x = 110
        while x < WIDTH - 260:  # words
            w = 30 + next(rnd) % 90
            rect(x, y, min(x + w, WIDTH - 110), y + 18, INK if next(rnd) % 4 else GREY)
            x += w + 14
        y += 46
    frame(100, HEIGHT - 300, WIDTH - 100, HEIGHT - 120, 4)
    for i in range(3):
        rect(130, HEIGHT - 270 + i * 50, 130 + 300 + next(rnd) % 300, HEIGHT - 252 + i * 50, INK)

    raw = b"".join(b"\x00" + bytes(row) for row in pixels)

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))
