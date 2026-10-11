#!/usr/bin/env python3
"""Welcome imagery for the Expo apps: a free-licensed photo (and, for the driver app, a short clip) from Pexels.

    python3 tools/seed/mobile_hero.py                    # both apps: photo for each, clip for the driver app
    python3 tools/seed/mobile_hero.py --app driver --video
    python3 tools/seed/mobile_hero.py --dry-run          # show what would be downloaded
    python3 tools/seed/mobile_hero.py --remove           # back to the drawn brand art

Needs PEXELS_API_KEY (your shell, or .local/secrets.env, or --env-file; free key: https://www.pexels.com/api/). Files land in
mobile/apps/<app>/assets/hero/ (gitignored): hero.jpg, hero.mp4 and ATTRIBUTION.json (photographer, page, licence).
metro.config.js picks them up automatically (restart Metro with --clear: make mobile, or npx expo start --clear);
without them the apps show their drawn brand art. Pexels License: free to use, no attribution required; it is
recorded anyway, as for the demo product photos. docs/engineering/mobile-on-your-phone.md, "Welcome imagery".
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from seedlib.images.model import Candidate  # noqa: E402
from seedlib.images.sources import USER_AGENT, http_json  # noqa: E402

PHOTO_API = "https://api.pexels.com/v1/search"
VIDEO_API = "https://api.pexels.com/videos/search"

# What each welcome screen should show (design: AfroTransact Mobile App, Driver), best query first.
PHOTO_QUERIES = {
    "customer": ["african family dinner table", "african market shopping", "family sharing meal jollof",
                 "african grocery shopping"],
    "driver": ["delivery man handing package", "courier delivering parcel door", "delivery driver van",
               "black man delivery package"],
}
VIDEO_QUERIES = {"driver": ["delivery driver driving", "courier delivering package", "driving city night"]}
MIN_PHOTO_HEIGHT = 1200
MAX_VIDEO_SECONDS = 20
MAX_VIDEO_WIDTH = 1080

Fetch = Callable[[str, dict[str, str]], Any]


@dataclass
class VideoCandidate:
    download_url: str
    page_url: str
    author: str
    author_url: str
    width: int
    height: int
    duration: int
    license: str = "Pexels License"
    license_url: str = "https://www.pexels.com/license/"


def parse_pexels_videos(payload: dict[str, Any]) -> list[VideoCandidate]:
    """Candidates from GET /videos/search: short clips, the largest MP4 rendition no wider than 1080 px."""
    out = []
    for v in (payload or {}).get("videos", []):
        duration = int(v.get("duration") or 0)
        if not duration or duration > MAX_VIDEO_SECONDS:
            continue
        files = [f for f in v.get("video_files") or [] if f.get("file_type") == "video/mp4" and f.get("link")
                 and 0 < int(f.get("width") or 0) <= MAX_VIDEO_WIDTH]
        if not files:
            continue
        best = max(files, key=lambda f: int(f.get("width") or 0) * int(f.get("height") or 0))
        user = v.get("user") or {}
        out.append(VideoCandidate(download_url=best["link"], page_url=v.get("url", ""), author=user.get("name", ""),
                                  author_url=user.get("url", ""), width=int(best.get("width") or 0),
                                  height=int(best.get("height") or 0), duration=duration))
    return out


def pick_photo(candidates: list[Candidate]) -> Candidate | None:
    """The first portrait photo tall enough for a full-bleed phone hero (search rank order)."""
    for c in candidates:
        if c.height >= c.width and c.height >= MIN_PHOTO_HEIGHT:
            return c
    return None


def pick_video(candidates: list[VideoCandidate]) -> VideoCandidate | None:
    for c in candidates:
        if c.height >= c.width:
            return c
    return candidates[0] if candidates else None


def parse_pexels_portrait(payload: dict[str, Any], max_height: int = 1800) -> list[Candidate]:
    """Candidates from GET /v1/search sized for a phone hero: the original, scaled to at most max_height px tall."""
    out = []
    for p in (payload or {}).get("photos", []):
        original = (p.get("src") or {}).get("original")
        w, h = int(p.get("width") or 0), int(p.get("height") or 0)
        if not original or not w or not h:
            continue
        scale = min(max_height / h, 1.0)
        url = f"{original}?auto=compress&cs=tinysrgb&h={max_height}" if scale < 1 else original
        out.append(Candidate(
            source="pexels", download_url=url, page_url=p.get("url", ""), title=(p.get("alt") or "Pexels photo").strip(),
            author=p.get("photographer", ""), author_url=p.get("photographer_url", ""), license="Pexels License",
            license_url="https://www.pexels.com/license/", width=round(w * scale), height=round(h * scale)))
    return out


def search_photos(key: str, query: str, fetch: Fetch = http_json) -> list[Candidate]:
    params = {"query": query, "orientation": "portrait", "size": "large", "per_page": "15"}
    return parse_pexels_portrait(fetch(PHOTO_API + "?" + urllib.parse.urlencode(params), {"Authorization": key}))


def search_videos(key: str, query: str, fetch: Fetch = http_json) -> list[VideoCandidate]:
    params = {"query": query, "orientation": "portrait", "size": "medium", "per_page": "15"}
    return parse_pexels_videos(fetch(VIDEO_API + "?" + urllib.parse.urlencode(params), {"Authorization": key}))


def secrets_file_value(name: str, path: Path) -> str:
    """NAME=value from a shell-style env file (quotes stripped); '' when absent."""
    if not path.is_file():
        return ""
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s*(?:export\s+)?" + re.escape(name) + r"=(.*)$", line)
        if m:
            return m.group(1).strip().strip("'\"")
    return ""


def api_key(env: dict[str, str], env_file: Path | None = None) -> str:
    return env.get("PEXELS_API_KEY", "").strip() or secrets_file_value(
        "PEXELS_API_KEY", env_file or ROOT / ".local/secrets.env")


def attribution(app: str, photo: Candidate | None, video: VideoCandidate | None) -> dict[str, Any]:
    out: dict[str, Any] = {"app": app, "source": "pexels"}
    if photo:
        out["photo"] = {"file": "hero.jpg", "title": photo.title, "author": photo.author, "author_url": photo.author_url,
                        "page_url": photo.page_url, "license": photo.license, "license_url": photo.license_url}
    if video:
        out["video"] = {"file": "hero.mp4", **{k: v for k, v in asdict(video).items() if k != "download_url"}}
    return out


def download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(req, timeout=120) as res, tmp.open("wb") as f:
        shutil.copyfileobj(res, f)
    tmp.replace(dest)


def hero_dir(app: str) -> Path:
    return ROOT / "mobile" / "apps" / app / "assets" / "hero"


def run(args: argparse.Namespace, env: dict[str, str], fetch: Fetch = http_json) -> int:
    apps = [args.app] if args.app else ["customer", "driver"]
    if args.remove:
        for app in apps:
            if hero_dir(app).exists():
                shutil.rmtree(hero_dir(app))
                print(f"  {app}: hero media removed (the drawn brand art shows again after a Metro restart with --clear)")
        return 0
    key = api_key(env, getattr(args, "env_file", None))
    if not key:
        print("PEXELS_API_KEY is not set (shell or .local/secrets.env; free key: https://www.pexels.com/api/). "
              "The apps keep their drawn brand art.", file=sys.stderr)
        return 1
    for app in apps:
        photo = next((p for q in PHOTO_QUERIES[app] if (p := pick_photo(search_photos(key, q, fetch)))), None)
        want_video = app in VIDEO_QUERIES and (args.video or not args.app)
        video = next((v for q in VIDEO_QUERIES.get(app, []) if (v := pick_video(search_videos(key, q, fetch)))),
                     None) if want_video else None
        if not photo and not video:
            print(f"  {app}: nothing suitable found; keeping the drawn art", file=sys.stderr)
            continue
        if args.dry_run:
            if photo:
                print(f"  {app}: photo {photo.page_url} by {photo.author} ({photo.license})")
            if video:
                print(f"  {app}: clip  {video.page_url} by {video.author} ({video.duration}s, {video.width}x{video.height})")
            continue
        d = hero_dir(app)
        d.mkdir(parents=True, exist_ok=True)
        if photo:
            download(photo.download_url, d / "hero.jpg")
        if video:
            download(video.download_url, d / "hero.mp4")
        (d / "ATTRIBUTION.json").write_text(json.dumps(attribution(app, photo, video), indent=2) + "\n", encoding="utf-8")
        print(f"  {app}: {', '.join(n for n, x in (('hero.jpg', photo), ('hero.mp4', video)) if x)} → "
              f"{d.relative_to(ROOT)} (credits in ATTRIBUTION.json)")
    if not args.dry_run:
        print("Restart Metro with --clear to pick them up (make mobile, or npx expo start --clear in the app).")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--app", choices=["customer", "driver"], help="only this app (default: both)")
    ap.add_argument("--video", action="store_true", help="with --app driver: also fetch a short clip")
    ap.add_argument("--dry-run", action="store_true", help="search and print, download nothing")
    ap.add_argument("--remove", action="store_true", help="delete the downloaded media (back to the drawn art)")
    ap.add_argument("--env-file", type=Path, help="where to look for PEXELS_API_KEY (default .local/secrets.env)")
    return run(ap.parse_args(argv), dict(os.environ))


if __name__ == "__main__":
    sys.exit(main())
