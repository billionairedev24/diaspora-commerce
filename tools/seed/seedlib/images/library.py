"""Finds photos for a product or campaign: your own folder first, then the cache, then the image APIs.

Order for every request:
  1. SEED_IMAGES_DIR (`local_dir`): files named after the product slug (`ofada-rice.jpg`, `ofada-rice-2.jpg`,
     `ofada-rice/…`) or after one of its keywords (`jollof rice.jpg`, `jollof-rice_1.png`).
  2. The manifest of earlier runs (`<cache>/manifest.json`): re-runs work offline once photos were downloaded.
  3. The APIs, in order: Pexels (PEXELS_API_KEY), Unsplash (UNSPLASH_ACCESS_KEY), Wikimedia Commons.
A photo is used once across the whole dataset, so two products never share a picture.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable, Sequence
from pathlib import Path

from .model import MIME_EXT, Candidate, ImageFile, sniff
from .sources import USER_AGENT, Source

MAX_BYTES = 8 * 1024 * 1024
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")

Download = Callable[[str], bytes]


def http_download(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as res:
        data = res.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("larger than 8 MB")
    return data


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def _wants(c: Candidate | ImageFile, orientation: str, min_edge: int) -> bool:
    w, h = c.width, c.height
    if not w or not h or min(w, h) < min_edge:
        return False
    aspect = w / h
    if orientation == "square":
        return 0.62 <= aspect <= 1.6
    if orientation == "landscape":
        return aspect >= 1.2
    return True


class ImageLibrary:
    def __init__(self, cache_dir: str | os.PathLike[str], sources: Sequence[Source] = (),
                 local_dir: str | os.PathLike[str] | None = None, download: Download = http_download,
                 log: Callable[[str], None] = print, offline: bool = False):
        self.cache = Path(cache_dir)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.sources = list(sources)
        self.local_dir = Path(local_dir) if local_dir else None
        self.download = download
        self.log = log
        self.offline = offline
        self.manifest_path = self.cache / "manifest.json"
        self.manifest = self._load()
        self.used: dict[str, str] = {}  # sha256 -> key that uses it (one photo, one product)
        self.attribution: dict[str, ImageFile] = {}
        self.failed_sources: set[str] = set()

    # ------------------------------------------------------------------------------------------- manifest
    def _load(self) -> dict:
        try:
            data = json.loads(self.manifest_path.read_text())
            if isinstance(data, dict):
                data.setdefault("keys", {})
                data.setdefault("queries", {})
                return data
        except (OSError, ValueError):
            pass
        return {"keys": {}, "queries": {}}

    def save(self) -> None:
        fd, tmp = tempfile.mkstemp(dir=self.cache, suffix=".json")
        with os.fdopen(fd, "w") as f:
            json.dump(self.manifest, f, indent=1, sort_keys=True)
        os.replace(tmp, self.manifest_path)

    # ------------------------------------------------------------------------------------------- public
    def images_for(self, key: str, queries: Iterable[str], count: int, *, orientation: str = "any",
                   min_edge: int = 0, main_min_edge: int = 0, network: bool = True) -> list[ImageFile]:
        """Up to `count` photos for `key`; the first has at least `main_min_edge` pixels on its shorter side.
        `network=False` only looks at SEED_IMAGES_DIR and the cache (attribution of records made earlier)."""
        queries = [q for q in queries if q]
        picked = self._local(key, queries, count, main_min_edge)
        if picked:
            return self._claim(key, picked)
        cached = self._cached(key, count, main_min_edge)
        if cached:
            return self._claim(key, cached)
        picked = []
        for source in self.sources:
            if source.name in self.failed_sources or self.offline or not network:
                continue
            for q in queries:
                for cand in self._search(source, q, orientation):
                    if len(picked) >= count:
                        break
                    need = main_min_edge if not picked else min_edge
                    if not _wants(cand, orientation, need):
                        continue
                    img = self._fetch(cand)
                    if img is None or img.sha256 in self.used or any(p.sha256 == img.sha256 for p in picked):
                        continue
                    if min(img.width, img.height) < need:
                        continue
                    picked.append(img)
                if len(picked) >= count or source.name in self.failed_sources:
                    break
            if len(picked) >= count:
                break
        if picked:
            self.manifest["keys"][key] = [p.to_json() for p in picked]
        return self._claim(key, picked)

    def write_attribution(self, md_path: str | os.PathLike[str], json_path: str | os.PathLike[str],
                          usage: dict[str, list[str]], images: Iterable[ImageFile] | None = None) -> None:
        """ATTRIBUTION.md (human) and attribution.json (machine) for `images` (default: the ones used by this run)."""
        rows = sorted(images if images is not None else self.attribution.values(), key=lambda i: (i.source, i.title))
        by_sha = {sha: keys for sha, keys in usage.items()}
        intro = ("Images used by `make seed` (tools/seed). Wikimedia Commons files are CC0, public domain, CC BY or "
                 "CC BY-SA: keep this credit with them; CC BY-SA requires derivatives to be shared alike. Pexels and "
                 "Unsplash photos are under their own licenses. `local` files came from SEED_IMAGES_DIR.")
        lines = ["# Demo image attribution", "", intro, ""]
        for img in rows:
            used_by = ", ".join(by_sha.get(img.sha256, [])) or "-"
            if img.source == "local":
                lines.append(f"- **{img.title}** — local file `{img.path}` (SEED_IMAGES_DIR). Used by: {used_by}")
            else:
                author = f"[{img.author}]({img.author_url})" if img.author_url else img.author
                lines.append(f"- **{img.title}** — {author}, [{img.license}]({img.license_url}), "
                             f"source: {img.page_url} ({img.source}). Used by: {used_by}")
        Path(md_path).write_text("\n".join(lines) + "\n")
        Path(json_path).write_text(json.dumps([dict(i.to_json(), usedBy=by_sha.get(i.sha256, []))
                                               for i in rows], indent=1))

    # ------------------------------------------------------------------------------------------- internals
    def _claim(self, key: str, imgs: list[ImageFile]) -> list[ImageFile]:
        for img in imgs:
            self.used.setdefault(img.sha256, key)
            self.attribution[img.sha256] = img
        return imgs

    def _cached(self, key: str, count: int, main_min_edge: int) -> list[ImageFile]:
        entries = self.manifest["keys"].get(key) or []
        files = [ImageFile.from_json(e) for e in entries]
        files = [f for f in files if Path(f.path).is_file() and f.source != "local"]
        if not files or files[0].min_edge < main_min_edge:
            return []
        return files[:count]

    def _search(self, source: Source, query: str, orientation: str) -> list[Candidate]:
        qkey = f"{source.name}:{orientation}:{query}"
        hit = self.manifest["queries"].get(qkey)
        if hit is not None:
            return [Candidate(**c) for c in hit]
        try:
            found = source.search(query, 20, orientation)
        except (urllib.error.URLError, OSError, ValueError) as e:
            self.failed_sources.add(source.name)
            self.log(f"  ! {source.name} unreachable ({e}); skipping it for the rest of this run")
            return []
        self.manifest["queries"][qkey] = [c.__dict__ for c in found]
        return found

    def _fetch(self, c: Candidate) -> ImageFile | None:
        h = hashlib.sha1(c.download_url.encode()).hexdigest()[:20]
        folder = self.cache / c.source
        folder.mkdir(parents=True, exist_ok=True)
        existing = next((p for p in folder.glob(h + ".*")), None)
        if existing:
            data = existing.read_bytes()
        else:
            try:
                data = self.download(c.download_url)
            except (urllib.error.URLError, OSError, ValueError) as e:
                self.log(f"  ! download failed {c.download_url[:90]}: {e}")
                return None
            if c.source == "unsplash":
                tracker = next((s for s in self.sources if s.name == "unsplash"), None)
                if tracker is not None and hasattr(tracker, "track_download"):
                    tracker.track_download(c)
        info = sniff(data)
        if info is None:
            return None
        mime, w, h_px = info
        path = existing or folder / (h + MIME_EXT[mime])
        if not existing:
            path.write_bytes(data)
        return ImageFile(path=str(path), source=c.source, title=c.title, author=c.author, author_url=c.author_url,
                         license=c.license, license_url=c.license_url, page_url=c.page_url, width=w, height=h_px,
                         mime=mime, sha256=hashlib.sha256(data).hexdigest())

    def _local(self, key: str, queries: list[str], count: int, main_min_edge: int) -> list[ImageFile]:
        if not self.local_dir or not self.local_dir.is_dir():
            return []
        names = [slug(key)] + [slug(q) for q in queries]
        def order(p: Path) -> tuple[str, int, str]:  # ofada-rice.jpg, ofada-rice-2.jpg, ofada-rice-3.jpg …
            m = re.search(r"[-_ ]?(\d+)$", p.stem)
            return (str(p.parent), int(m.group(1)) if m else 0, p.name)

        files = sorted((p for p in self.local_dir.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTS), key=order)
        matched: list[Path] = []
        for name in names:
            for p in files:
                rel = p.relative_to(self.local_dir)
                stem = slug(re.sub(r"[-_ ]?\d+$", "", p.stem))
                folder = slug(rel.parts[0]) if len(rel.parts) > 1 else ""
                if (stem == name or folder == name) and p not in matched:
                    matched.append(p)
            if matched:
                break
        out: list[ImageFile] = []
        for p in matched:
            data = p.read_bytes()
            info = sniff(data)
            if info is None or len(data) > MAX_BYTES:
                self.log(f"  ! {p}: not a JPEG/PNG/WebP under 8 MB, skipped")
                continue
            mime, w, h = info
            sha = hashlib.sha256(data).hexdigest()
            if sha in self.used and self.used[sha] != key:
                continue
            out.append(ImageFile(path=str(p), source="local", title=p.stem.replace("-", " ").replace("_", " "),
                                 author="", author_url="", license="provided via SEED_IMAGES_DIR", license_url="",
                                 page_url="", width=w, height=h, mime=mime, sha256=sha))
            if len(out) >= count:
                break
        if out and out[0].min_edge < main_min_edge:
            big = next((i for i in out if i.min_edge >= main_min_edge), None)
            if big is None:
                self.log(f"  ! {key}: SEED_IMAGES_DIR images are under {main_min_edge}px on the short side "
                         "(catalog needs a main image of at least 1000 x 1000)")
                return []
            out.remove(big)
            out.insert(0, big)
        return out
