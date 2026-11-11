"""The review report of `make seed`: every product, the photo it got (title, source, licence, page) and what the
relevance filter turned away, so the owner can check the pictures and replace the wrong ones (SEED_IMAGES_DIR).
"""
from __future__ import annotations

import csv
import io
from dataclasses import dataclass, field
from pathlib import Path

from .model import ImageFile


@dataclass
class Row:
    key: str
    title: str
    images: list[ImageFile] = field(default_factory=list)
    rejected: list[tuple[str, str]] = field(default_factory=list)  # (photo title, why)
    note: str = ""

    @property
    def status(self) -> str:
        if not self.images:
            return "NO IMAGE"
        if all(i.source == "local" for i in self.images):
            return "yours"
        if any(not i.keywords_matched for i in self.images if i.source != "local"):
            return "check"  # chosen without a keyword match (an older cached photo)
        return "ok"


class ImageReview:
    def __init__(self) -> None:
        self.rows: dict[str, Row] = {}

    def add(self, key: str, title: str, images: list[ImageFile], rejected: list[tuple[str, str]] | None = None, note: str = "") -> None:
        self.rows[key] = Row(key, title, list(images), list(rejected or []), note)

    def flagged(self) -> list[Row]:
        return [r for r in self.rows.values() if r.status in ("NO IMAGE", "check")]

    def to_markdown(self) -> str:
        lines = ["# Demo product image review", "",
                 "Every product with the photo the seeder chose. **Check** rows were chosen without a keyword match; "
                 "**NO IMAGE** rows were skipped (nothing relevant found). To replace a photo, put a file named after the "
                 "product key (`ofada-rice.jpg`, `ofada-rice-2.jpg`) in `SEED_IMAGES_DIR` and seed a fresh database.", "",
                 "| Status | Product | Key | Photo (title) | Source / licence | Matched | Turned away |",
                 "|---|---|---|---|---|---|---|"]
        for r in sorted(self.rows.values(), key=lambda r: (r.status != "NO IMAGE", r.status != "check", r.key)):
            if not r.images:
                lines.append(f"| {r.status} | {r.title} | `{r.key}` | - | - | - | {_short(r.rejected)} |")
            for i, img in enumerate(r.images):
                src = img.source if img.source == "local" else f"[{img.source} · {img.license}]({img.page_url})"
                lines.append(f"| {r.status if i == 0 else ''} | {r.title if i == 0 else ''} | {'`' + r.key + '`' if i == 0 else ''} | "
                             f"{img.title} | {src} | {img.keywords_matched or '-'} | {_short(r.rejected) if i == 0 else ''} |")
        return "\n".join(lines) + "\n"

    def to_csv(self) -> str:
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["status", "key", "product", "photo_title", "source", "license", "page_url", "matched_keywords", "turned_away"])
        for r in sorted(self.rows.values(), key=lambda r: r.key):
            if not r.images:
                w.writerow([r.status, r.key, r.title, "", "", "", "", "", _short(r.rejected)])
            for img in r.images:
                w.writerow([r.status, r.key, r.title, img.title, img.source, img.license, img.page_url, img.keywords_matched, _short(r.rejected)])
        return buf.getvalue()

    def write(self, md_path: str | Path, csv_path: str | Path) -> None:
        Path(md_path).write_text(self.to_markdown())
        Path(csv_path).write_text(self.to_csv())

    def summary(self) -> str:
        n = len(self.rows)
        no = sum(1 for r in self.rows.values() if r.status == "NO IMAGE")
        chk = sum(1 for r in self.rows.values() if r.status == "check")
        return f"{n} products reviewed: {n - no - chk} ok, {chk} to check, {no} without an image"


def _short(rejected: list[tuple[str, str]], n: int = 3) -> str:
    return "; ".join(f"{t[:40]} ({why[:40]})" for t, why in rejected[:n]).replace("|", "/")
