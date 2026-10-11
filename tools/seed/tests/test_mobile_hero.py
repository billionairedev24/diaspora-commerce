"""tools/seed/mobile_hero.py: picking and crediting the welcome photo / clip for the Expo apps (no network)."""
import argparse
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import mobile_hero

PHOTOS = {"photos": [
    {"id": 1, "width": 6000, "height": 4000, "url": "https://www.pexels.com/photo/landscape-1/", "photographer": "A",
     "photographer_url": "https://www.pexels.com/@a", "alt": "Wide shot", "src": {"original": "https://images.pexels.com/1.jpeg"}},
    {"id": 2, "width": 3000, "height": 4500, "url": "https://www.pexels.com/photo/courier-2/", "photographer": "Kemi",
     "photographer_url": "https://www.pexels.com/@kemi", "alt": "Courier hands over a parcel",
     "src": {"original": "https://images.pexels.com/2.jpeg"}},
]}
VIDEOS = {"videos": [
    {"id": 7, "duration": 45, "url": "https://www.pexels.com/video/long-7/", "user": {"name": "L"},
     "video_files": [{"file_type": "video/mp4", "width": 1080, "height": 1920, "link": "https://v/long.mp4"}]},
    {"id": 8, "duration": 12, "url": "https://www.pexels.com/video/driving-8/", "user": {"name": "Tolu", "url": "https://www.pexels.com/@tolu"},
     "video_files": [
         {"file_type": "video/mp4", "width": 2160, "height": 3840, "link": "https://v/4k.mp4"},
         {"file_type": "video/mp4", "width": 720, "height": 1280, "link": "https://v/720.mp4"},
         {"file_type": "video/mp4", "width": 1080, "height": 1920, "link": "https://v/1080.mp4"},
         {"file_type": "video/webm", "width": 1080, "height": 1920, "link": "https://v/1080.webm"}]},
]}


class MobileHeroTest(unittest.TestCase):
    def test_picks_the_first_tall_portrait_photo_scaled_for_a_phone(self):
        photo = mobile_hero.pick_photo(mobile_hero.parse_pexels_portrait(PHOTOS))
        self.assertEqual(photo.page_url, "https://www.pexels.com/photo/courier-2/")
        self.assertEqual((photo.width, photo.height), (1200, 1800))
        self.assertEqual(photo.download_url, "https://images.pexels.com/2.jpeg?auto=compress&cs=tinysrgb&h=1800")
        self.assertEqual(photo.license, "Pexels License")

    def test_picks_a_short_clip_and_its_largest_mp4_up_to_1080_wide(self):
        clips = mobile_hero.parse_pexels_videos(VIDEOS)
        self.assertEqual([c.page_url for c in clips], ["https://www.pexels.com/video/driving-8/"])
        clip = mobile_hero.pick_video(clips)
        self.assertEqual((clip.download_url, clip.width, clip.height, clip.duration), ("https://v/1080.mp4", 1080, 1920, 12))

    def test_attribution_records_author_page_and_licence(self):
        photo = mobile_hero.pick_photo(mobile_hero.parse_pexels_portrait(PHOTOS))
        clip = mobile_hero.pick_video(mobile_hero.parse_pexels_videos(VIDEOS))
        a = mobile_hero.attribution("driver", photo, clip)
        self.assertEqual(a["photo"]["author"], "Kemi")
        self.assertEqual(a["photo"]["license_url"], "https://www.pexels.com/license/")
        self.assertEqual(a["video"]["author"], "Tolu")
        self.assertNotIn("download_url", a["video"])

    def test_reads_the_key_from_the_secrets_file_when_the_shell_has_none(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "secrets.env"
            f.write_text("# x\nexport PEXELS_API_KEY='abc123'\nOTHER=1\n")
            self.assertEqual(mobile_hero.api_key({}, f), "abc123")
            self.assertEqual(mobile_hero.api_key({"PEXELS_API_KEY": "shell"}, f), "shell")

    def test_without_a_key_nothing_is_fetched(self):
        args = argparse.Namespace(app=None, video=False, dry_run=False, remove=False)
        fetch = mock.Mock()
        with mock.patch.object(mobile_hero, "ROOT", Path(tempfile.mkdtemp())):
            self.assertEqual(mobile_hero.run(args, {}, fetch), 1)
        fetch.assert_not_called()

    def test_dry_run_searches_portrait_media_and_writes_nothing(self):
        args = argparse.Namespace(app="driver", video=True, dry_run=True, remove=False)
        seen = []

        def fetch(url, headers):
            seen.append(url)
            self.assertEqual(headers, {"Authorization": "k"})
            return VIDEOS if "/videos/" in url else PHOTOS

        with tempfile.TemporaryDirectory() as d, mock.patch.object(mobile_hero, "ROOT", Path(d)):
            self.assertEqual(mobile_hero.run(args, {"PEXELS_API_KEY": "k"}, fetch), 0)
            self.assertFalse((Path(d) / "mobile").exists())
        self.assertTrue(all("orientation=portrait" in u for u in seen))
        self.assertTrue(any("/videos/search" in u for u in seen))


if __name__ == "__main__":
    unittest.main()
