import { expect, test } from '@playwright/test';

/* SEO drift checks against the production build. This server runs WITHOUT APP_ENV=prod (like uat, dev, previews and the 8-day
   trial), so it must behave as a non-indexable environment on every surface a crawler can see. Prod behaviour (robots allow
   rules, sitemap) is covered by test/seo-env-safety.test.ts and test/seo-sitemap.test.ts; tools/ci/seo_probe.py checks the
   deployed hosts. No browser is needed for most of this: plain HTTP requests, as a crawler would send them. */

const ATIN = 'AT000000P2';

test('a non-prod server: robots.txt disallows everything and names no sitemap', async ({ request }) => {
  const res = await request.get('/robots.txt');
  expect(res.status()).toBe(200);
  expect(await res.text()).toBe('User-agent: *\nDisallow: /\n');
  expect(res.headers()['x-robots-tag']).toMatch(/noindex/);
});

test('a non-prod server: no sitemap index and no child sitemaps', async ({ request }) => {
  for (const path of ['/sitemap.xml', '/sitemaps/products-1.xml', '/sitemaps/content-1.xml']) {
    const res = await request.get(path);
    expect(res.status(), path).toBe(404);
    expect(await res.text(), path).not.toContain('<loc>');
  }
});

test('a non-prod server: every response carries X-Robots-Tag noindex (pages, 404s, API proxy) and the markup says noindex too', async ({ request }) => {
  for (const path of ['/', `/p/${ATIN}`, '/s?dept=Groceries', '/help', '/cart', '/no/such/page', '/api/bff/v1/site-config']) {
    const res = await request.get(path);
    expect(res.headers()['x-robots-tag'], path).toMatch(/noindex/);
    if ((res.headers()['content-type'] ?? '').includes('text/html')) expect(await res.text(), path).toMatch(/<meta name="robots" content="noindex/);
  }
});

test('an unknown product is a real 404 (not a soft 200)', async ({ request }) => {
  expect((await request.get('/p/AT0000NOPE')).status()).toBe(404);
  expect((await request.get('/p/not-an-atin')).status()).toBe(404);
});

test('product page: unique title, description, one canonical, Open Graph and JSON-LD are in the first HTML response (no JavaScript)', async ({ request }) => {
  const html = await (await request.get(`/p/${ATIN}?utm_source=newsletter&sort=price_asc`)).text();
  expect(html).toMatch(/<title>[^<]{3,}<\/title>/);
  expect(html).toMatch(/<meta name="description" content="[^"]{20,}"/);
  expect(html.match(/<link rel="canonical"/g)).toHaveLength(1);
  expect(html).toMatch(/<link rel="canonical" href="[^"]*\/p\/AT000000P2"/);
  expect(html).not.toMatch(/<link rel="canonical" href="[^"]*utm_source/);
  expect(html).toMatch(/<meta property="og:title"/);
  expect(html).toMatch(/<meta name="twitter:card"/);
  const blocks = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => JSON.parse(m[1]!) as Record<string, unknown>);
  const types = blocks.map((b) => b['@type']);
  expect(types).toContain('Product');
  expect(types).toContain('BreadcrumbList');
  const product = blocks.find((b) => b['@type'] === 'Product') as { name: string; sku: string; offers?: { price: string; priceCurrency: string; availability: string } };
  expect(product.sku).toBe(ATIN);
  expect(product.name.length).toBeGreaterThan(2);
  expect(product.offers?.price).toMatch(/^\d+\.\d{2}$/);
  expect(product.offers?.priceCurrency).toMatch(/^[A-Z]{3}$/);
  expect(product.offers?.availability).toMatch(/^https:\/\/schema\.org\/(InStock|OutOfStock|LimitedAvailability)$/);
  // owner rule: no tax claims, carrier names or "AfroTransact delivers/collects" in structured data
  expect(JSON.stringify(blocks)).not.toMatch(/\b(tax|UPS|FedEx|USPS|DHL)\b|AfroTransact (delivers|collects)/i);
});

test('home page: one canonical whatever tracking parameters the visit carried; Organization and WebSite (SearchAction) in the first HTML', async ({ request }) => {
  const html = await (await request.get('/?utm_source=x&gclid=y')).text();
  expect(html.match(/<link rel="canonical"/g)).toHaveLength(1);
  expect(html).not.toMatch(/<link rel="canonical" href="[^"]*(utm_source|gclid)/);
  const blocks = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => JSON.parse(m[1]!) as Record<string, unknown>);
  expect(blocks.map((b) => b['@type']).sort()).toEqual(['Organization', 'WebSite']);
  const site = blocks.find((b) => b['@type'] === 'WebSite') as { potentialAction: { target: { urlTemplate: string }; 'query-input': string } };
  expect(site.potentialAction.target.urlTemplate).toContain('/s?q={search_term_string}');
});

test('a faceted search is noindex with the category as canonical (and a free-text search noindex)', async ({ request }) => {
  const facet = await (await request.get('/s?dept=Groceries&sort=price_asc')).text();
  expect(facet).toMatch(/<meta name="robots" content="noindex/);
  expect(facet).toMatch(/<link rel="canonical" href="[^"]*\/s\?dept=Groceries"/);
  const free = await (await request.get('/s?q=jollof')).text();
  expect(free).toMatch(/<meta name="robots" content="noindex/);
});

test('product images sit in a box with a reserved aspect ratio, so nothing shifts when they load', async ({ request }) => {
  // (The mock catalog has no photos, so the <img> attributes are unit-tested in test/seo-images.test.tsx.)
  expect(await (await request.get(`/p/${ATIN}`)).text()).toMatch(/aspect-ratio:\s*1 \/ 1/);
});

test('a category page has its own canonical, with BreadcrumbList and crawlable pagination links in the first HTML', async ({ request }) => {
  const html = await (await request.get('/s?dept=Groceries')).text();
  expect(html).toMatch(/<link rel="canonical" href="[^"]*\/s\?dept=Groceries"/);
  expect(html).toMatch(/<h1[^>]*>Groceries<\/h1>/);
  expect(html).toMatch(/"@type":"BreadcrumbList"/);
  // pagination: a plain link to page 2, and page 2 names itself canonical
  expect(html).toMatch(/<nav[^>]*aria-label="Result pages"[\s\S]*href="\/s\?dept=Groceries&(amp;)?page=2"/);
  const p2 = await (await request.get('/s?dept=Groceries&page=2')).text();
  expect(p2).toMatch(/<link rel="canonical" href="[^"]*\/s\?dept=Groceries&(amp;)?page=2"/);
});
