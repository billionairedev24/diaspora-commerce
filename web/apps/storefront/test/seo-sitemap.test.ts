import { type SearchPage } from '@afrotransact/api-client';
import { describe, expect, it, vi } from 'vitest';

import { byteLength, childName, chunkUrls, countUrls, escapeXml, indexOfSet, lastmodOf, MAX_BYTES_PER_SITEMAP, MAX_URLS_PER_SITEMAP, parseChildName, renderIndex, renderUrlset, type SitemapUrl } from '@/lib/seo/sitemap';
import { createSitemapCache } from '@/lib/seo/sitemap-cache';
import { buildSitemapSet, collectCategories, collectProducts, type SeoApi } from '@/lib/seo/sitemap-source';

const ORIGIN = 'https://afrotransact.com';

/** Sitemap-protocol checks on a rendered child sitemap, parsed with a real XML parser. */
function validateUrlset(xml: string, opts: { origin: string }): string[] {
  const errors: string[] = [];
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.querySelector('parsererror')) return ['not well-formed XML'];
  if (doc.documentElement.localName !== 'urlset') errors.push('root is not urlset');
  const locs = [...doc.getElementsByTagName('loc')].filter((l) => l.parentElement?.localName === 'url').map((l) => l.textContent ?? '');
  const seen = new Set<string>();
  for (const loc of locs) {
    if (!loc.startsWith(`${opts.origin}/`) && loc !== opts.origin) errors.push(`foreign host: ${loc}`);
    if (loc.length > 2048) errors.push(`loc too long: ${loc.slice(0, 40)}`);
    if (/[\s<>"]/.test(loc)) errors.push(`bad characters: ${loc}`);
    if (seen.has(loc)) errors.push(`duplicate: ${loc}`);
    seen.add(loc);
    if (/\/(cart|checkout|account|signin|signup|orders|api)(\/|\?|$)/.test(new URL(loc).pathname + new URL(loc).search)) errors.push(`private URL: ${loc}`);
    const q = new URL(loc).searchParams;
    if (['q', 'sort', 'price', 'rating', 'fulfilment', 'soldBy', 'delivery', 'utm_source'].some((k) => q.has(k))) errors.push(`facet or tracking URL: ${loc}`);
  }
  for (const lm of doc.getElementsByTagName('lastmod')) if (Number.isNaN(Date.parse(lm.textContent ?? ''))) errors.push(`bad lastmod ${lm.textContent}`);
  if (locs.length > MAX_URLS_PER_SITEMAP) errors.push('more than 50,000 URLs');
  if (byteLength(xml) > 52_428_800) errors.push('more than 50 MB');
  return errors;
}

const urls = (n: number, f: (i: number) => Partial<SitemapUrl> = () => ({})): SitemapUrl[] => Array.from({ length: n }, (_, i) => ({ loc: `${ORIGIN}/p/AT${String(i).padStart(8, '0')}`, ...f(i) }));

describe('sitemap XML', () => {
  it('escapes XML, drops illegal characters, and parses', () => {
    expect(escapeXml(`a&b<c>"d'e\u0001`)).toBe('a&amp;b&lt;c&gt;&quot;d&apos;e');
    const xml = renderUrlset([{ loc: `${ORIGIN}/s?dept=Home+%26+Art&page=2`, lastmod: '2026-11-01T10:00:00.000Z', images: [{ loc: 'https://media.afrotransact.com/p/a&b.jpg', title: 'Rice <2kg>' }] }]);
    expect(validateUrlset(xml, { origin: ORIGIN })).toEqual([]);
    expect(xml).toContain('<loc>https://afrotransact.com/s?dept=Home+%26+Art&amp;page=2</loc>');
    expect(xml).toContain('<image:loc>https://media.afrotransact.com/p/a&amp;b.jpg</image:loc>');
    expect(xml).toContain('<image:title>Rice &lt;2kg&gt;</image:title>');
  });

  it('image entries (one main image per product) and hreflang alternates render in the right namespaces', () => {
    const xml = renderUrlset([{ loc: `${ORIGIN}/p/AT00000001`, images: [{ loc: 'https://media.afrotransact.com/a.jpg' }], alternates: [{ hreflang: 'fr', href: `${ORIGIN}/fr/p/AT00000001` }] }]);
    expect(xml).toContain('xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"');
    expect(xml).toContain('<xhtml:link rel="alternate" hreflang="fr" href="https://afrotransact.com/fr/p/AT00000001"/>');
    expect(new DOMParser().parseFromString(xml, 'application/xml').querySelector('parsererror')).toBeNull();
  });

  it('lastmod is only ever a real, non-future date', () => {
    expect(lastmodOf('2026-10-01T08:00:00Z')).toBe('2026-10-01T08:00:00.000Z');
    expect(lastmodOf(undefined)).toBeUndefined();
    expect(lastmodOf('')).toBeUndefined();
    expect(lastmodOf('not a date')).toBeUndefined();
    expect(lastmodOf(new Date(Date.now() + 10 * 24 * 3600 * 1000))).toBeUndefined();
    expect(renderUrlset(urls(2))).not.toContain('<lastmod>');
  });

  it('chunks at 50,000 URLs: 120,001 URLs make three children, in order, none over the limit', () => {
    const chunks = chunkUrls(urls(120_001));
    expect(chunks.map((c) => c.urls.length)).toEqual([50_000, 50_000, 20_001]);
    expect(chunks[0]!.urls[0]!.loc).toContain('AT00000000');
    expect(chunks[2]!.urls.at(-1)!.loc).toContain('AT00120000');
    for (const c of chunks) expect(c.bytes).toBeLessThan(MAX_BYTES_PER_SITEMAP);
  }, 30_000);

  it('chunks at the byte limit too: a tiny cap splits by size, and the recorded size is the real size', () => {
    const chunks = chunkUrls(urls(300, () => ({ images: [{ loc: 'https://media.afrotransact.com/p/a-fairly-long-image-name.jpg', title: 'Ofada rice' }] })), { maxBytes: 20_000 });
    expect(chunks.length).toBeGreaterThan(3);
    for (const c of chunks) {
      expect(c.bytes).toBeLessThanOrEqual(20_000);
      expect(c.bytes).toBe(byteLength(c.xml));
    }
    expect(chunks.reduce((n, c) => n + c.urls.length, 0)).toBe(300);
    // a single URL bigger than the cap still gets its own child rather than being dropped
    expect(chunkUrls(urls(2), { maxBytes: 10 }).map((c) => c.urls.length)).toEqual([1, 1]);
  });

  it('50,000 products with an image each stay under the 50 MB limit', () => {
    const [c] = chunkUrls(urls(50_000, () => ({ images: [{ loc: 'https://media.afrotransact.com/p/abcdefghij-1.jpg', title: 'A product title of typical length for the catalog' }] })));
    expect(c!.urls).toHaveLength(50_000);
    expect(c!.bytes).toBeLessThan(52_428_800);
  }, 30_000);

  it('index: one entry per child under /sitemaps/, lastmod only when a child has a real one, parseable names', () => {
    const set = { generatedAt: '2026-11-12T00:00:00.000Z', kinds: { products: chunkUrls(urls(3)), content: chunkUrls(urls(1, () => ({ lastmod: '2026-10-01T00:00:00.000Z' }))) } };
    const entries = indexOfSet(set, ORIGIN);
    expect(entries).toEqual([{ loc: `${ORIGIN}/sitemaps/products-1.xml` }, { loc: `${ORIGIN}/sitemaps/content-1.xml`, lastmod: '2026-10-01T00:00:00.000Z' }]);
    const xml = renderIndex(entries);
    const doc = new DOMParser().parseFromString(xml, 'application/xml');
    expect(doc.documentElement.localName).toBe('sitemapindex');
    expect(doc.getElementsByTagName('sitemap')).toHaveLength(2);
    expect(countUrls(set)).toBe(4);
    expect(parseChildName(childName('products', 12))).toEqual({ kind: 'products', n: 12 });
    for (const bad of ['products-0.xml', 'products.xml', '../products-1.xml', 'carts-1.xml', 'products-1.xml.gz', 'products-1']) expect(parseChildName(bad), bad).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------------------------------------------- source
const hit = (n: number, over: Record<string, unknown> = {}) => ({
  atin: `AT${String(n).padStart(8, '0')}`,
  title: `Product ${n}`,
  brand: 'B',
  community: 'Yoruba',
  department: 'Groceries',
  unit: '1',
  rating: 0,
  reviewCount: 0,
  imageUrl: `https://media.afrotransact.com/p/${n}.jpg`,
  offerId: 'o',
  priceMinor: 100,
  listPriceMinor: null,
  currency: 'USD',
  sellerName: 'S',
  sellerKind: 'SELLER' as const,
  sellerCity: 'Houston',
  fulfilment: 'SELLER' as const,
  availableQty: 5,
  inStock: true,
  lowStock: false,
  stockStatus: 'IN_STOCK' as const,
  delivery: { days: 1, label: '', prefix: '', free: true, feeMinor: 0, cutoff: null },
  offerCount: 1,
  minOfferPriceMinor: 100,
  sponsor: null,
  ...over
});

function page(hits: ReturnType<typeof hit>[], p = 0, pageCount = 1, total = hits.length): SearchPage {
  const b = (value: string, count: number) => ({ value, label: value, count });
  return {
    hits,
    total,
    page: p,
    size: 60,
    pageCount,
    facets: { department: [b('Groceries', 5), b('Empty', 0), b('Fashion', 2)], community: [b('Yoruba', 5), b('Igbo', 2)], fulfilment: [], sellerKind: [], rating: [], price: [], delivery: [] },
    deliverToLabel: '',
    tookMs: 1,
    services: []
  } as unknown as SearchPage;
}

function fakeApi(over: Partial<SeoApi> = {}): SeoApi {
  return {
    searchOrganic: vi.fn(async (q) => {
      if (q.size === 1) return page([]);
      if (q.department === 'Fashion') return page([hit(100)]);
      return q.page === 0 ? page([hit(1), hit(2, { inStock: false }), hit(3, { imageUrl: 'https://m.test/icons/x.png' }), hit(4, { sponsor: { advertiser: 'A' } }), hit(1), hit(5, { stockStatus: 'OUT_OF_STOCK', availableQty: 0 })], 0, 2, 7) : page([hit(6)], 1, 2, 7);
    }),
    getHelpArticles: vi.fn(async () => ({ items: [{ slug: 'shipping', topic: 'ORDERS_DELIVERY', topicTitle: '', title: 'Shipping', summary: '', updatedAt: '2026-10-05T00:00:00Z' }], topics: [], nextCursor: null })),
    getLegalIndex: vi.fn(async () => ({ documents: [{ key: 'privacy', title: 'Privacy', version: '1', effectiveDate: '2026-09-01', href: '/legal/privacy', draftBanner: false }] })),
    // One snapshot for the home, the navigation and the sitemap: Groceries and Fashion have products, Empty has none, and only
    // Yoruba and Igbo are above the community thresholds (a third, Akan, is not and is therefore absent everywhere).
    getSitemapSources: vi.fn(async () => ({
      departments: [
        { href: '/s?dept=Groceries', count: 5, lastmod: '2026-11-10T08:00:00Z' },
        { href: '/s?dept=Empty', count: 0 },
        { href: '/s?dept=Fashion', count: 2 }
      ],
      communities: [
        { href: '/communities/yoruba', count: 9, lastmod: '2026-11-11T08:00:00Z' },
        { href: '/communities/igbo', count: 8 }
      ]
    })),
    getNavigation: vi.fn(async () => ({
      bar: [],
      all: [],
      communities: [
        { key: 'yoruba', label: 'Yoruba', labels: { en: 'Yoruba' }, count: 9, sellers: 3, href: '/communities/yoruba' },
        { key: 'igbo', label: 'Igbo', labels: { en: 'Igbo' }, count: 8, sellers: 2, href: '/communities/igbo' }
      ],
      services: { available: true, href: '/services' },
      deals: { available: false },
      brand: { available: false }
    })),
    searchServices: vi.fn(async () => ({ services: [{ serviceId: 's1', orgId: 'o1', name: 'Tailoring', imageUrl: 'https://media.afrotransact.com/s1.jpg' }, { serviceId: 's2', orgId: 'o1', name: 'Hair', imageUrl: '' }] })),
    ...over
  } as unknown as SeoApi;
}

describe('sitemap source: live data through the gateway', () => {
  it('lists only live, in-stock products with a real photo, never sponsored duplicates or the same product twice, across pages', async () => {
    const { urls: got } = await collectProducts(fakeApi(), ORIGIN);
    expect(got.map((u) => u.loc)).toEqual([`${ORIGIN}/p/AT00000001`, `${ORIGIN}/p/AT00000006`, `${ORIGIN}/p/AT00000100`]);
    expect(got[0]!.images).toEqual([{ loc: 'https://media.afrotransact.com/p/1.jpg', title: 'Product 1' }]);
    // no update time in the search index: lastmod is left out, not invented
    expect(got.every((u) => u.lastmod === undefined)).toBe(true);
  });

  it('builds every kind; categories are departments and communities that have products (never an empty one); content has help and legal lastmods', async () => {
    const { set, failed } = await buildSitemapSet(fakeApi(), ORIGIN, new Date('2026-11-12T00:00:00Z'));
    expect(failed).toEqual([]);
    const locs = (k: 'products' | 'categories' | 'services' | 'content') => (set.kinds[k] ?? []).flatMap((c) => c.urls.map((u) => u.loc));
    expect(locs('categories')).toEqual([`${ORIGIN}/s?dept=Groceries`, `${ORIGIN}/s?dept=Fashion`, `${ORIGIN}/s?community=Yoruba`, `${ORIGIN}/s?community=Igbo`]);
    // the gateway's snapshot time is a real update time, so it is used; entries without one carry none
    const cats = (set.kinds['categories'] ?? []).flatMap((c) => c.urls);
    expect(cats.find((u) => u.loc.endsWith('dept=Groceries'))!.lastmod).toBe('2026-11-10T08:00:00.000Z');
    expect(cats.find((u) => u.loc.endsWith('community=Igbo'))!.lastmod).toBeUndefined();
    expect(locs('services')).toEqual([`${ORIGIN}/services/s1`, `${ORIGIN}/services/s2`, `${ORIGIN}/services/providers/o1`]);
    expect(locs('content')).toEqual(expect.arrayContaining([ORIGIN, `${ORIGIN}/membership`, `${ORIGIN}/help`, `${ORIGIN}/help/shipping`, `${ORIGIN}/legal/privacy`]));
    expect(set.kinds['content']![0]!.urls.find((u) => u.loc.endsWith('/help/shipping'))!.lastmod).toBe('2026-10-05T00:00:00.000Z');
    expect(set.kinds['stores']).toBeUndefined();
    // every URL of every child passes the sitemap-protocol checks and none is a cart/checkout/account/search/filter/tracking URL
    for (const chunks of Object.values(set.kinds)) for (const c of chunks ?? []) expect(validateUrlset(c.xml, { origin: ORIGIN })).toEqual([]);
  });

  it('a community the navigation does not list (below the thresholds) never reaches the sitemap, and the page counts come from the same snapshot', async () => {
    const api = fakeApi({
      getSitemapSources: vi.fn(async () => ({ departments: [], communities: [{ href: '/communities/akan', count: 3 }, { href: '/communities/yoruba', count: 9 }] })) as SeoApi['getSitemapSources']
    });
    expect((await collectCategories(api, ORIGIN)).map((u) => u.loc)).toEqual([`${ORIGIN}/s?community=Yoruba`]);
  });

  it('when the gateway snapshot fails the categories kind fails (the previous version is kept) instead of falling back to thin facet pages', async () => {
    const { set, failed } = await buildSitemapSet(fakeApi({ getSitemapSources: vi.fn(async () => Promise.reject(new Error('gateway down'))) as SeoApi['getSitemapSources'] }), ORIGIN);
    expect(failed).toEqual(['categories']);
    expect(set.kinds['categories']).toBeUndefined();
  });

  it('one failing source does not take the others down and is reported', async () => {
    const { set, failed } = await buildSitemapSet(fakeApi({ searchServices: vi.fn(async () => Promise.reject(new Error('bookings down'))) }), ORIGIN);
    expect(failed).toEqual(['services']);
    expect(set.kinds['services']).toBeUndefined();
    expect(set.kinds['products']).toBeDefined();
  });

  it('a removed product drops out with the next generation', async () => {
    const before = await collectProducts(fakeApi(), ORIGIN);
    const api = fakeApi({ searchOrganic: vi.fn(async (q) => (q.size === 1 ? page([]) : q.department === 'Fashion' ? page([]) : page([hit(1)]))) as SeoApi['searchOrganic'] });
    const after = await collectProducts(api, ORIGIN);
    expect(before.urls.length).toBeGreaterThan(after.urls.length);
    expect(after.urls.map((u) => u.loc)).toEqual([`${ORIGIN}/p/AT00000001`]);
  });
});

// ---------------------------------------------------------------------------------------------------------------- cache
describe('sitemap cache: scheduled regeneration, change invalidation, stale-if-error, count-drop guard', () => {
  const setOf = (n: number, kind: 'products' | 'content' = 'products') => ({ generatedAt: 'x', kinds: { [kind]: chunkUrls(urls(n)) } });
  const make = (builds: (() => { set: ReturnType<typeof setOf>; failed?: ('products' | 'content')[] } | Error)[], extra: { now?: () => number } = {}) => {
    const events: { event: string }[] = [];
    let i = 0;
    const build = vi.fn(async () => {
      const r = builds[Math.min(i++, builds.length - 1)]!();
      if (r instanceof Error) throw r;
      return { set: r.set, failed: r.failed ?? [], truncated: false };
    });
    return { cache: createSitemapCache({ build, ttlMs: 1000, report: (e) => events.push(e), ...extra }), build, events };
  };

  it('serves from memory within the TTL, regenerates after it and on invalidate, and shares one build between concurrent requests', async () => {
    let t = 0;
    const { cache, build } = make([() => ({ set: setOf(50) })], { now: () => t });
    await Promise.all([cache.get(), cache.get(), cache.get()]);
    expect(build).toHaveBeenCalledTimes(1);
    t = 999;
    await cache.get();
    expect(build).toHaveBeenCalledTimes(1);
    t = 1001;
    await cache.get();
    expect(build).toHaveBeenCalledTimes(2);
    cache.invalidate();
    await cache.get();
    expect(build).toHaveBeenCalledTimes(3);
  });

  it('a failing kind keeps its previous version; a failing gateway keeps the last good set; nothing yet = the error', async () => {
    let t = 0;
    const { cache, events } = make([() => ({ set: setOf(50) }), () => ({ set: { generatedAt: 'y', kinds: {} }, failed: ['products'] }), () => new Error('gateway down')], { now: () => t });
    expect(countUrls(await cache.get())).toBe(50);
    t = 2000;
    expect(countUrls(await cache.get())).toBe(50);
    expect(events).toContainEqual(expect.objectContaining({ event: 'sitemap.kind_failed', kind: 'products', keptPrevious: true }));
    t = 4000;
    expect(countUrls(await cache.get())).toBe(50);
    const first = make([() => new Error('down at boot')]);
    await expect(first.cache.get()).rejects.toThrow('down at boot');
  });

  it('a kind that collapses is held back (and reported) for three generations, then accepted as real', async () => {
    let t = 0;
    const small = () => ({ set: setOf(5) });
    const { cache, events } = make([() => ({ set: setOf(100) }), small, small, small, small], { now: () => t });
    expect(countUrls(await cache.get())).toBe(100);
    for (const expected of [100, 100, 100, 5]) {
      t += 2000;
      expect(countUrls(await cache.get())).toBe(expected);
    }
    expect(events.filter((e) => e.event === 'sitemap.count_drop')).toHaveLength(4);
  });

  it('a small catalog may legitimately shrink (below the floor nothing is held back)', async () => {
    let t = 0;
    const { cache } = make([() => ({ set: setOf(10) }), () => ({ set: setOf(1) })], { now: () => t });
    await cache.get();
    t = 2000;
    expect(countUrls(await cache.get())).toBe(1);
  });
});
