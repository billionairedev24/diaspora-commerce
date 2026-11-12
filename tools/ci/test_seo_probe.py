"""Unit tests for tools/ci/seo_probe.py with a fake network: python3 -m unittest tools/ci/test_seo_probe.py"""
import io
import contextlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import seo_probe  # noqa: E402

PROD = "afrotransact.com"
URLSET = b'<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">%s</urlset>'
INDEX = b'<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><sitemap><loc>https://afrotransact.com/sitemaps/products-1.xml</loc></sitemap></sitemapindex>'
PRODUCT_HTML = b'<link rel="canonical" href="https://afrotransact.com/p/AT00000001"/><script type="application/ld+json">{"@type":"Product"}</script>'


def net(overrides=None):
    pages = {
        f"https://{PROD}/robots.txt": (200, {}, f"User-agent: *\nAllow: /\nDisallow: /cart\n\nSitemap: https://{PROD}/sitemap.xml\n".encode()),
        f"https://{PROD}/sitemap.xml": (200, {}, INDEX),
        f"https://{PROD}/sitemaps/products-1.xml": (200, {}, URLSET % b"<url><loc>https://afrotransact.com/p/AT00000001</loc></url>"),
        f"https://{PROD}/p/AT00000001": (200, {}, PRODUCT_HTML),
    }
    for env_host in ("uat.afrotransact.com", "dev.afrotransact.com", "test.afrotransact.com"):
        pages[f"https://{env_host}/robots.txt"] = (200, {}, b"User-agent: *\nDisallow: /\n")
        pages[f"https://{env_host}/sitemap.xml"] = (404, {}, b"")
        pages[f"https://{env_host}/"] = (200, {"x-robots-tag": "noindex, nofollow"}, b"<html>")
    pages.update(overrides or {})
    return lambda url: pages.get(url, (0, {}, b""))


def run(fetch, *args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = seo_probe.main(list(args), fetch)
    return code, out.getvalue()


class Probe(unittest.TestCase):
    def test_healthy_deployment_passes(self):
        code, out = run(net(), "--min-urls", "1")
        self.assertEqual(code, 0, out)

    def test_trial_that_is_indexable_fails(self):
        code, out = run(net({"https://test.afrotransact.com/": (200, {}, b"<html>")}), "--env", "trial")
        self.assertEqual(code, 1)
        self.assertIn("test.afrotransact.com/ is indexable", out)

    def test_nonprod_with_a_sitemap_or_open_robots_fails(self):
        code, out = run(net({"https://uat.afrotransact.com/sitemap.xml": (200, {}, INDEX), "https://uat.afrotransact.com/robots.txt": (200, {}, b"User-agent: *\nAllow: /\nSitemap: x\n")}), "--env", "uat")
        self.assertEqual(code, 1)
        self.assertIn("uat.afrotransact.com/sitemap.xml answers 200", out)
        self.assertIn("robots.txt is not 'Disallow: /'", out)

    def test_prod_sitemap_drop_below_floor_fails(self):
        code, out = run(net(), "--env", "prod", "--min-urls", "500")
        self.assertEqual(code, 1)
        self.assertIn("below the floor of 500", out)

    def test_prod_listed_url_that_is_noindex_or_has_two_canonicals_fails(self):
        code, out = run(net({"https://afrotransact.com/p/AT00000001": (200, {"x-robots-tag": "noindex"}, PRODUCT_HTML)}), "--env", "prod")
        self.assertIn("noindex but listed", out)
        two = PRODUCT_HTML + b'<link rel="canonical" href="https://afrotransact.com/x"/>'
        code, out = run(net({"https://afrotransact.com/p/AT00000001": (200, {}, two)}), "--env", "prod")
        self.assertIn("2 canonical links", out)

    def test_prod_robots_that_blocks_everything_fails(self):
        code, out = run(net({"https://afrotransact.com/robots.txt": (200, {}, b"User-agent: *\nDisallow: /\n")}), "--env", "prod")
        self.assertEqual(code, 1)

    def test_unreachable_env_is_skipped_unless_strict(self):
        self.assertEqual(run(lambda url: (0, {}, b""))[0], 0)
        self.assertEqual(run(lambda url: (0, {}, b""), "--strict")[0], 1)

    def test_backoffice_without_noindex_fails(self):
        code, out = run(net({"https://seller-uat.afrotransact.com/": (200, {}, b"<html>")}), "--env", "uat")
        self.assertIn("seller-uat.afrotransact.com/ answers 200 without noindex", out)

    def test_hosts_come_from_the_map(self):
        import json
        m = json.loads(seo_probe.MAP.read_text())
        self.assertEqual(seo_probe.hosts_of(m, "prod")["storefront"], m["zone"])
        self.assertEqual(seo_probe.hosts_of(m, "trial")["seller"], f"seller-test.{m['zone']}")


if __name__ == "__main__":
    unittest.main()
