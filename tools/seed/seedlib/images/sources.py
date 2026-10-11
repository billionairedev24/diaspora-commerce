"""Image search APIs: Wikimedia Commons (no key), Pexels (PEXELS_API_KEY), Unsplash (UNSPLASH_ACCESS_KEY).

Each source turns an API response into `Candidate`s; `parse_*` are pure functions so they are unit-tested against
fixture JSON (tools/seed/tests/fixtures). Only photos (JPEG/PNG/WebP) under an accepted license are returned.
"""
from __future__ import annotations

import html
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from typing import Any, Protocol

from . import licenses
from .model import Candidate

USER_AGENT = "AfroTransactSeeder/1.0 (https://github.com/billionairedev24/diaspora-commerce; local demo data)"
PHOTO_MIMES = ("image/jpeg", "image/png", "image/webp")
# File titles that are almost never product photos.
_NOT_PHOTOS = re.compile(r"\b(logo|map|flag|diagram|chart|icon|coat of arms|seal|svg|drawing|illustration|"
                         r"screenshot|poster|stamp|banknote|coin)\b", re.IGNORECASE)

Fetch = Callable[[str, dict[str, str]], Any]


def http_json(url: str, headers: dict[str, str]) -> Any:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=20) as res:
        return json.loads(res.read().decode("utf-8"))


class Source(Protocol):
    name: str

    def search(self, query: str, limit: int, orientation: str) -> list[Candidate]: ...


def _strip_html(s: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s or ""))).strip()


def _first_href(s: str) -> str:
    m = re.search(r'href="([^"]+)"', s or "")
    if not m:
        return ""
    href = html.unescape(m.group(1))
    return "https:" + href if href.startswith("//") else href


# ------------------------------------------------------------------------------------------------ Wikimedia Commons
WIKIMEDIA_API = "https://commons.wikimedia.org/w/api.php"


def wikimedia_params(query: str, limit: int = 20, width: int = 1600) -> dict[str, str]:
    """Query string of the documented generator=search request (File namespace 6, bitmap files only)."""
    return {"action": "query", "generator": "search", "gsrnamespace": "6", "gsrsearch": f"{query} filetype:bitmap",
            "gsrlimit": str(limit), "prop": "imageinfo", "iiprop": "url|size|mime|extmetadata",
            "iiurlwidth": str(width), "format": "json"}


def _meta_reader(meta: dict[str, Any]) -> Callable[[str], str]:
    """extmetadata values are {"value": …, "source": …}; returns key → value text."""
    def mv(key: str) -> str:
        v = meta.get(key) or {}
        return str(v.get("value", "")) if isinstance(v, dict) else str(v)
    return mv


def parse_wikimedia(payload: dict[str, Any]) -> list[Candidate]:
    """Candidates in search-rank order from an action=query&generator=search&prop=imageinfo response.

    Handles both formatversion 1 (`query.pages` is an object keyed by page id) and 2 (a list). Uses the thumbnail
    (`thumburl`, `iiurlwidth` wide) when the API made one, else the original file.
    """
    pages = (payload or {}).get("query", {}).get("pages", {})
    items = list(pages.values()) if isinstance(pages, dict) else list(pages)
    items.sort(key=lambda p: p.get("index", 1 << 30))
    out: list[Candidate] = []
    for page in items:
        infos = page.get("imageinfo") or []
        if not infos:
            continue
        info = infos[0]
        mime = info.get("mime", "")
        title = page.get("title", "").removeprefix("File:")
        if mime not in PHOTO_MIMES or _NOT_PHOTOS.search(title):
            continue
        meta = info.get("extmetadata") or {}
        mv = _meta_reader(meta)
        lic = licenses.canonical(mv("LicenseShortName"), mv("License"))
        if not lic:
            continue
        thumb = info.get("thumburl")
        url = thumb or info.get("url", "")
        width = int(info.get("thumbwidth") or info.get("width") or 0) if thumb else int(info.get("width") or 0)
        height = int(info.get("thumbheight") or info.get("height") or 0) if thumb else int(info.get("height") or 0)
        if not url or not width or not height:
            continue
        artist = mv("Artist")
        name = _strip_html(mv("ObjectName")) or re.sub(r"\.[A-Za-z0-9]+$", "", title).replace("_", " ")
        out.append(Candidate(
            source="wikimedia", download_url=url, page_url=info.get("descriptionurl", ""), title=name,
            author=_strip_html(artist) or _strip_html(mv("Credit")) or "Unknown author",
            author_url=_first_href(artist), license=lic, license_url=licenses.license_url(lic, mv("LicenseUrl")),
            width=width, height=height, mime=mime))
    return out


class WikimediaSource:
    name = "wikimedia"

    def __init__(self, fetch: Fetch = http_json, width: int = 1600):
        self.fetch, self.width = fetch, width

    def search(self, query: str, limit: int = 20, orientation: str = "any") -> list[Candidate]:
        url = WIKIMEDIA_API + "?" + urllib.parse.urlencode(wikimedia_params(query, limit, self.width))
        return parse_wikimedia(self.fetch(url, {}))


# ------------------------------------------------------------------------------------------------ Pexels
PEXELS_API = "https://api.pexels.com/v1/search"


def parse_pexels(payload: dict[str, Any]) -> list[Candidate]:
    """Candidates from GET /v1/search (Pexels License: free to use, attribution appreciated and recorded)."""
    out = []
    for p in (payload or {}).get("photos", []):
        src = p.get("src") or {}
        url = src.get("large2x") or src.get("original")
        w, h = int(p.get("width") or 0), int(p.get("height") or 0)
        if not url or not w or not h:
            continue
        if url == src.get("large2x"):  # large2x is fitted into 1880 x 1300 (dpr 2 of 940 x 650)
            scale = min(1880 / w, 1300 / h, 1.0)
            w, h = int(w * scale), int(h * scale)
        out.append(Candidate(
            source="pexels", download_url=url, page_url=p.get("url", ""),
            title=(p.get("alt") or "Pexels photo").strip(), author=p.get("photographer", ""),
            author_url=p.get("photographer_url", ""), license="Pexels License",
            license_url="https://www.pexels.com/license/", width=w, height=h, mime="image/jpeg"))
    return out


class PexelsSource:
    name = "pexels"

    def __init__(self, api_key: str, fetch: Fetch = http_json):
        self.key, self.fetch = api_key, fetch

    def search(self, query: str, limit: int = 15, orientation: str = "any") -> list[Candidate]:
        params = {"query": query, "per_page": str(min(limit, 80))}
        if orientation in ("landscape", "square"):
            params["orientation"] = orientation
        return parse_pexels(self.fetch(PEXELS_API + "?" + urllib.parse.urlencode(params), {"Authorization": self.key}))


# ------------------------------------------------------------------------------------------------ Unsplash
UNSPLASH_API = "https://api.unsplash.com/search/photos"


def parse_unsplash(payload: dict[str, Any], width: int = 1600) -> list[Candidate]:
    """Candidates from GET /search/photos. Downloads use urls.raw resized to `width` (imgix parameters)."""
    out = []
    for p in (payload or {}).get("results", []):
        urls, user, links = p.get("urls") or {}, p.get("user") or {}, p.get("links") or {}
        w, h = int(p.get("width") or 0), int(p.get("height") or 0)
        if not urls.get("raw") or not w or not h:
            continue
        scale = min(width / w, 1.0)
        out.append(Candidate(
            source="unsplash", download_url=urls["raw"] + ("&" if "?" in urls["raw"] else "?") +
            f"w={width}&fm=jpg&q=80&fit=max", page_url=links.get("html", ""),
            title=(p.get("alt_description") or p.get("description") or "Unsplash photo").strip(),
            author=user.get("name", ""), author_url=(user.get("links") or {}).get("html", ""),
            license="Unsplash License", license_url="https://unsplash.com/license",
            width=int(w * scale), height=int(h * scale), mime="image/jpeg",
            extra={"download_location": links.get("download_location", "")}))
    return out


class UnsplashSource:
    name = "unsplash"

    def __init__(self, access_key: str, fetch: Fetch = http_json):
        self.key, self.fetch = access_key, fetch

    def search(self, query: str, limit: int = 15, orientation: str = "any") -> list[Candidate]:
        params = {"query": query, "per_page": str(min(limit, 30)), "content_filter": "high"}
        if orientation in ("landscape", "squarish", "square"):
            params["orientation"] = "squarish" if orientation == "square" else orientation
        return parse_unsplash(self.fetch(UNSPLASH_API + "?" + urllib.parse.urlencode(params),
                                         {"Authorization": "Client-ID " + self.key, "Accept-Version": "v1"}))

    def track_download(self, candidate: Candidate) -> None:
        """Unsplash API guideline: trigger the download endpoint whenever a photo is downloaded."""
        loc = candidate.extra.get("download_location")
        if loc:
            try:
                self.fetch(loc, {"Authorization": "Client-ID " + self.key})
            except (OSError, ValueError, urllib.error.URLError):
                pass
