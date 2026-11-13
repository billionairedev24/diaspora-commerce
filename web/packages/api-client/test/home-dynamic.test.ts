/* The mock gateway's dynamic home (S429): what shows and what is hidden at 0 / 10 / 100 / 10,000 products, the sponsored cap,
   the community threshold, and visit-seed variety (different seeds differ, one seed is stable, no seed is the crawler default). */
import { describe, expect, it } from 'vitest';

import { homeVisitHeaders, isCrawlerAgent, recordLoad, seenKeysForRequest } from '../src/home-visit';
import { composeDynamicHome, DEFAULT_SEED, defaultHomeSettings, fixtureCatalog, navigationOf, resolveCommunity, seedOfVisit, seenKey, sitemapOf, visitOfHeaders } from '../src/mocks/home-dynamic';
import { type Home, HomeSchema } from '../src/schemas/home';
import { SiteNavigationSchema } from '../src/schemas/site-nav';

const NOW = new Date('2026-11-12T12:00:00Z');
const HOUSTON = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true, source: 'chosen' as const };

function compose(size: number, over: { seed?: string; signedIn?: boolean; hasZip?: boolean; seen?: Set<string>; now?: Date } = {}) {
  return composeDynamicHome({ catalog: fixtureCatalog(size), settings: defaultHomeSettings(), seed: over.seed ?? DEFAULT_SEED, seen: over.seen, now: over.now ?? NOW, signedIn: over.signedIn ?? false, loc: HOUSTON, hasZip: over.hasZip ?? true });
}
function page(size: number, over: Parameters<typeof compose>[1] = {}): Home {
  return HomeSchema.parse(compose(size, over).home);
}
const types = (h: Home) => h.blocks.map((b) => b.type);
const modules = (h: Home) => h.blocks.map((b) => b.tracking?.module).filter(Boolean);
const atins = (h: Home) => h.blocks.flatMap((b) => [...(b.rail?.items ?? []), ...(b.products ?? [])].map((p) => p.atin));

describe('fixture catalogues', () => {
  it('0 products: text-only brand hero, Plus and the sell band; no tile or rail', () => {
    const h = page(0, { hasZip: false });
    expect(types(h)).toEqual(['brand-hero', 'membership-promo', 'sell-band']);
    expect(h.hero).toEqual([]);
    const brand = h.blocks[0];
    expect(brand).toMatchObject({ textOnly: true, titleKey: 'home.brandHero.title', ctaKey: 'home.brandHero.cta', href: '/sell' });
    expect(brand?.imageUrl ?? null).toBeNull();
    expect(h.blocks.find((b) => b.type === 'sell-band')).toMatchObject({ titleKey: 'home.sellBand.title' });
  });

  it('10 products: department tiles for the 3 departments that exist, Services for the 2 providers; no deals, communities or top sellers', () => {
    const h = page(10, { hasZip: false });
    const m = modules(h);
    expect(m).toContain('department-tiles');
    expect(h.blocks.find((b) => b.type === 'department-tiles')?.cells).toHaveLength(3);
    for (const hidden of ['deals', 'communities', 'top-sellers', 'groceries-rail', 'preorder']) expect(m).not.toContain(hidden);
    expect(h.blocks.find((b) => b.type === 'service-cards')?.titleKey).toBe('home.services.popular.title');
  });

  it('10 products with a ZIP: providers in the visitor city make Services near you appear', () => {
    const near = page(10, { hasZip: true }).blocks.find((b) => b.type === 'service-cards');
    expect(near?.titleKey).toBe('home.services.nearYou.title');
    expect(near?.titleParams).toEqual({ city: 'Houston' });
    expect(near?.services?.every((x) => x.city === 'Houston')).toBe(true);
  });

  it('100 products: communities, deals, top sellers, groceries and pre-order join; Services is "popular" without a ZIP', () => {
    const h = page(100, { hasZip: false });
    for (const m of ['department-tiles', 'deals', 'communities', 'top-sellers', 'groceries-rail', 'new-arrivals', 'preorder', 'services-near-you']) expect(modules(h)).toContain(m);
    expect(h.blocks.find((b) => b.type === 'service-cards')?.titleKey).toBe('home.services.popular.title');
    expect(h.hero.length).toBeGreaterThan(0);
    expect(types(h)).not.toContain('brand-hero');
  });

  it('10,000 products: capped at 12 modules, generated "Top in" rails are not adjacent, no product twice', () => {
    const h = page(10000, { seed: 'v|a' });
    expect(h.blocks.length).toBeLessThanOrEqual(12);
    const all = atins(h);
    expect(new Set(all).size).toBe(all.length);
    const topIn = h.blocks.map((b, i) => (b.module === 'department-rail' ? i : -1)).filter((i) => i >= 0);
    expect(topIn.length).toBeGreaterThan(0);
    for (let i = 1; i < topIn.length; i++) expect((topIn[i] as number) - (topIn[i - 1] as number)).toBeGreaterThan(1);
    expect(h.blocks.at(-1)?.type).toBe('sell-band');
  });

  it('never shows 0 reviews, a photo-less product or a placeholder image, and sends keys not English sentences', () => {
    for (const n of [10, 100, 10000]) {
      const h = page(n, { seed: 's|1' });
      for (const b of h.blocks) {
        for (const p of b.rail?.items ?? []) expect(p.imageUrl).toMatch(/^https:\/\/img\./);
        if (!b.sponsored) {
          expect(b.title ?? '').toBe('');
          expect(b.titleKey ?? b.type).toBeTruthy();
        }
      }
      for (const p of h.blocks.find((b) => b.module === 'top-sellers')?.rail?.items ?? []) expect(p.reviewCount).toBeGreaterThan(0);
    }
  });
});

describe('communities', () => {
  it('appear only when 3 or more have 8 products and 2 sellers; the navigation follows the same counts', () => {
    expect(navigationOf(fixtureCatalog(10), defaultHomeSettings()).communities).toEqual([]);
    const nav = SiteNavigationSchema.parse(navigationOf(fixtureCatalog(100), defaultHomeSettings()));
    expect(nav.communities.length).toBeGreaterThanOrEqual(3);
    const tiles = page(100).blocks.find((b) => b.type === 'community-tiles');
    // Home and navigation agree: every tile is a navigation community, and its label and href come from one key.
    for (const c of tiles?.cells ?? []) {
      const n = nav.communities.find((x) => x.key === c.key);
      expect(n?.label).toBe(c.label);
      expect(c.href).toBe(`/communities/${c.key}`);
    }
  });

  it('a hidden community is not resolvable; a case variant is a redirect to the canonical key', () => {
    const settings = defaultHomeSettings();
    expect(resolveCommunity(fixtureCatalog(10), settings, 'nigerian')).toBeNull();
    expect(resolveCommunity(fixtureCatalog(100), settings, 'nigerian')).toMatchObject({ canonical: true, community: { key: 'nigerian' } });
    expect(resolveCommunity(fixtureCatalog(100), settings, 'Nigerian')).toMatchObject({ canonical: false, community: { key: 'nigerian' } });
    expect(resolveCommunity(fixtureCatalog(100), settings, 'atlantis')).toBeNull();
  });

  it('the sitemap lists exactly the navigation communities and departments with products', () => {
    const catalog = fixtureCatalog(100);
    const nav = navigationOf(catalog, defaultHomeSettings());
    const map = sitemapOf(catalog, defaultHomeSettings());
    expect(map.communities.map((c) => c.href)).toEqual(nav.communities.map((c) => c.href));
    expect(sitemapOf(fixtureCatalog(10), defaultHomeSettings()).communities).toEqual([]);
  });

  it('staff can raise the threshold: the footer list empties with the home module', () => {
    const settings = defaultHomeSettings();
    settings.modules.communities = { ...settings.modules.communities!, unitMinProducts: 5000 };
    expect(navigationOf(fixtureCatalog(10000), settings).communities).toEqual([]);
    expect(composeDynamicHome({ catalog: fixtureCatalog(10000), settings, seed: DEFAULT_SEED, now: NOW, signedIn: false, loc: HOUSTON, hasZip: true }).home.blocks.some((b) => b.type === 'community-tiles')).toBe(false);
  });
});

describe('navigation', () => {
  it('a department with nothing in it is not linked; Services appears only with a provider', () => {
    const nav = navigationOf(fixtureCatalog(10), defaultHomeSettings());
    expect(nav.all.map((d) => d.name)).not.toContain('Electronics');
    expect(nav.all.every((d) => d.count > 0 && d.href)).toBe(true);
    expect(nav.services.available).toBe(true);
    expect(navigationOf(fixtureCatalog(0), defaultHomeSettings())).toMatchObject({ bar: [], all: [], communities: [], services: { available: false }, deals: { available: false } });
  });

  it('the bar holds the largest 7; All lists every department with products; staff labels win', () => {
    const settings = defaultHomeSettings();
    settings.departmentLabels = { 'groceries-food': { en: 'Food', fr: 'Épicerie' } };
    const nav = navigationOf(fixtureCatalog(10000), settings);
    expect(nav.bar).toHaveLength(7);
    expect(nav.all.length).toBeGreaterThan(7);
    expect(nav.all.find((d) => d.key === 'groceries-food')).toMatchObject({ label: 'Food', labels: { en: 'Food', fr: 'Épicerie' } });
  });
});

describe('sponsored cap', () => {
  it('shows at most 1 sponsored module per 3 organic ones', () => {
    for (const n of [0, 10, 100, 10000]) {
      const h = page(n, { seed: 'cap|1' });
      const paid = h.blocks.filter((b) => b.sponsored).length;
      expect(paid).toBeLessThanOrEqual(Math.floor((h.blocks.length - paid) / 3));
    }
  });
});

describe('visit seed', () => {
  const fingerprint = (h: Home) => JSON.stringify([modules(h), atins(h), h.blocks.flatMap((b) => b.cells?.map((c) => c.label) ?? [])]);

  it('one seed is stable on refresh', () => {
    expect(fingerprint(page(100, { seed: 'v|1' }))).toBe(fingerprint(page(100, { seed: 'v|1' })));
  });

  it('two visits with different seeds show different item subsets (and the hero stays first)', () => {
    const a = page(10000, { seed: 'visitor|window-a' });
    const b = page(10000, { seed: 'visitor|window-b' });
    const A = new Set(atins(a));
    const overlap = [...A].filter((x) => atins(b).includes(x)).length;
    expect(A.size).toBeGreaterThan(20);
    expect(overlap).toBeLessThan(A.size * 0.8);
    expect(fingerprint(a)).not.toBe(fingerprint(b));
    expect(a.blocks[0]?.type).toBe(b.blocks[0]?.type);
  });

  it('a crawler (no seed) always sees the deterministic default and the page says so', () => {
    expect(fingerprint(page(10000))).toBe(fingerprint(page(10000, { seed: DEFAULT_SEED })));
    expect(page(10000).visit?.deterministic).toBe(true);
    expect(page(10000, { seed: 'a|b' }).visit?.deterministic).toBe(false);
  });

  it('items seen on earlier loads are drawn less often (novelty memory)', () => {
    const seen = new Set(page(10000, { seed: 'v|w' }).visit?.seen);
    expect(seen.size).toBeGreaterThan(20);
    const repeats = (memory: Set<string> | undefined) => {
      let n = 0;
      for (let i = 0; i < 12; i++) for (const id of atins(page(10000, { seed: `v|w${i}`, seen: memory }))) if (seen.has(seenKey(id))) n += 1;
      return n;
    };
    const withMemory = repeats(seen);
    const without = repeats(undefined);
    expect(without).toBeGreaterThan(0);
    expect(withMemory).toBeLessThan(without * 0.85);
  });

  it('one seller supplies at most 2 items of a module', () => {
    for (const b of page(10000, { seed: 'sellers|1' }).blocks) {
      const per = new Map<string, number>();
      for (const p of b.rail?.items ?? []) per.set(p.sellerName, (per.get(p.sellerName) ?? 0) + 1);
      for (const n of per.values()) expect(n).toBeLessThanOrEqual(2);
    }
  });

  it('with 10 products the same modules show whatever the seed', () => {
    expect(new Set(modules(page(10, { seed: 'a|1' })))).toEqual(new Set(modules(page(10, { seed: 'b|2' }))));
  });
});

describe('visit headers', () => {
  it('the seed is visitor + window; no visitor or a crawler is the default', () => {
    expect(seedOfVisit({ visitor: 'v1', window: 'w1' })).toBe('v1|w1');
    expect(seedOfVisit({ window: 'w1' })).toBe(DEFAULT_SEED);
    expect(seedOfVisit({ visitor: 'v1', window: 'w1', crawler: true })).toBe(DEFAULT_SEED);
  });

  it('round-trips through the headers', () => {
    const h = new Headers(homeVisitHeaders({ visitor: 'v1', window: 'w1', seen: ['aa', 'bb'], variant: 'b' }));
    expect(visitOfHeaders(h)).toEqual({ visitor: 'v1', window: 'w1', seen: ['aa', 'bb'], variant: 'b', crawler: false });
    expect(homeVisitHeaders({ crawler: true, visitor: 'v1' })).toEqual({ 'X-Crawler': '1' });
  });

  it('the guest seen cookie keeps this visit apart (a refresh never reshuffles) and the last 3 earlier loads', () => {
    let c = recordLoad('', 'w1', ['a', 'b']);
    expect(c).toBe('w1~a-b~');
    expect(seenKeysForRequest(c)).toEqual([]);
    expect(recordLoad(c, 'w1', ['a', 'b'])).toBe(c);
    for (const [w, k] of [['w2', 'c'], ['w3', 'd'], ['w4', 'e'], ['w5', 'f']] as const) c = recordLoad(c, w, [k]);
    expect(c).toBe('w5~f~e.d.c');
    expect(seenKeysForRequest(c)).toEqual(['e', 'd', 'c']);
    const big = Array.from({ length: 100 }, (_, i) => `k${i}`);
    let d = '';
    for (const w of ['a', 'b', 'c', 'd']) d = recordLoad(d, w, big);
    expect(seenKeysForRequest(d).length).toBeLessThanOrEqual(120);
  });

  it('known crawlers are recognised', () => {
    expect(isCrawlerAgent('Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)')).toBe(true);
    expect(isCrawlerAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120 Safari/537.36')).toBe(false);
  });
});

describe('decisions explain every module', () => {
  it('hidden modules carry a reason code and a sentence that names the module', () => {
    const d = compose(10, { hasZip: false }).decisions;
    expect(d.find((x) => x.module === 'communities')).toMatchObject({ shown: false, reason: 'not-enough-products', label: 'Shop by community', have: 0, need: 3 });
    expect(d.find((x) => x.module === 'communities')?.detail).toContain('needs 3 communities with 8 eligible products and 2 sellers each; 0 qualify');
    expect(d.find((x) => x.module === 'continue-shopping')).toMatchObject({ shown: false, reason: 'no-shopper-signal' });
    expect(d.filter((x) => x.shown).every((x) => x.order !== undefined)).toBe(true);
  });

  it('a switched-off module says staff did it', () => {
    const settings = defaultHomeSettings();
    settings.modules.deals = { ...settings.modules.deals!, enabled: false };
    const d = composeDynamicHome({ catalog: fixtureCatalog(100), settings, seed: DEFAULT_SEED, now: NOW, signedIn: false, loc: HOUSTON, hasZip: true }).decisions;
    expect(d.find((x) => x.module === 'deals')).toMatchObject({ shown: false, reason: 'disabled-by-staff' });
  });

  it('as-of date: a season in its window raises the module; outside it nothing changes', () => {
    const order = (iso: string) => compose(100, { now: new Date(iso) }).decisions.find((x) => x.module === 'deals')?.order;
    expect(order('2026-11-25T00:00:00Z')).toBeLessThan(order('2026-11-12T00:00:00Z') as number);
    expect(order('2026-11-12T00:00:00Z')).toBe(order('2026-10-01T00:00:00Z'));
  });

  it('a signed-in shopper sees continue shopping first after the hero', () => {
    const h = page(100, { signedIn: true });
    expect(h.blocks.map((b) => b.module).slice(0, 2)).toEqual(['continue-shopping', 'recently-viewed']);
  });
});
