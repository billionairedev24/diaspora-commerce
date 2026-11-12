/* The mock gateway's dynamic home (S429): what shows and what is hidden at 0 / 10 / 100 / 10,000 products, the sponsored cap,
   the community threshold, and visit-seed variety (different seeds differ, one seed is stable, no seed is the crawler default). */
import { describe, expect, it } from 'vitest';

import { type Home, HomeSchema } from '../src/schemas/home';
import { SiteNavigationSchema } from '../src/schemas/site-nav';
import { composeDynamicHome, DEFAULT_SEED, defaultHomeSettings, fixtureCatalog, navigationOf } from '../src/mocks/home-dynamic';

const NOW = new Date('2026-11-12T12:00:00Z');
const HOUSTON = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true, source: 'chosen' as const };

function page(size: number, over: { seed?: string; signedIn?: boolean; hasZip?: boolean } = {}): Home {
  const out = composeDynamicHome({ catalog: fixtureCatalog(size), settings: defaultHomeSettings(), seed: over.seed ?? DEFAULT_SEED, now: NOW, signedIn: over.signedIn ?? false, loc: HOUSTON, hasZip: over.hasZip ?? true }).home;
  return HomeSchema.parse(out);
}
const types = (h: Home) => h.blocks.map((b) => b.type);
const modules = (h: Home) => h.blocks.map((b) => b.tracking?.module).filter(Boolean);
const atins = (h: Home) => h.blocks.flatMap((b) => [...(b.rail?.items ?? []), ...(b.products ?? [])].map((p) => p.atin));

describe('fixture catalogues', () => {
  it('0 products: text-only brand hero, sell band and Plus; no tile or rail', () => {
    const h = page(0, { hasZip: false });
    expect(types(h)).toEqual(['brand-hero', 'membership-promo', 'sell-band']);
    expect(h.hero).toEqual([]);
    const brand = h.blocks[0];
    expect(brand?.imageUrl ?? null).toBeNull();
  });

  it('10 products: department tiles for the 3 departments that exist; no deals, communities or top sellers', () => {
    const h = page(10, { hasZip: false });
    const m = modules(h);
    expect(m).toContain('departments');
    expect(h.blocks.find((b) => b.type === 'department-tiles')?.cells).toHaveLength(3);
    for (const hidden of ['deals', 'communities', 'top-sellers', 'groceries', 'pre-order']) expect(m).not.toContain(hidden);
    // 2 providers and no ZIP: below the market-wide minimum of 3, so Services is hidden.
    expect(m).not.toContain('services');
  });

  it('10 products with a ZIP: providers in the visitor city make Services near you appear', () => {
    const h = page(10, { hasZip: true });
    const near = h.blocks.find((b) => b.type === 'service-cards');
    expect(near?.titleKey).toBe('home.services.near');
    expect(near?.services?.every((x) => x.city === 'Houston')).toBe(true);
  });

  it('100 products: communities, deals, top sellers, groceries and pre-order join; Services is "popular" without a ZIP', () => {
    const h = page(100, { hasZip: false });
    for (const m of ['departments', 'deals', 'communities', 'top-sellers', 'groceries', 'new-arrivals', 'pre-order', 'services']) expect(modules(h)).toContain(m);
    expect(h.blocks.find((b) => b.type === 'service-cards')?.titleKey).toBe('home.services.popular');
    expect(h.hero.length).toBeGreaterThan(0);
  });

  it('10,000 products: the page is capped at 12 modules and no product appears twice', () => {
    const h = page(10000, { seed: 'visit-a' });
    expect(h.blocks.length).toBeLessThanOrEqual(12);
    const all = atins(h);
    expect(new Set(all).size).toBe(all.length);
  });

  it('never shows 0 reviews, a photo-less product or a placeholder image', () => {
    for (const n of [10, 100, 10000]) {
      const h = page(n, { seed: 's' });
      for (const b of h.blocks) for (const p of b.rail?.items ?? []) expect(p.imageUrl).toMatch(/^https:\/\/img\./);
      const top = h.blocks.find((b) => b.tracking?.module === 'top-sellers');
      for (const p of top?.rail?.items ?? []) expect(p.reviewCount).toBeGreaterThan(0);
    }
  });
});

describe('communities', () => {
  it('appear only when 3 or more have 8 products and 2 sellers; the footer navigation follows the same counts', () => {
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

  it('a department with nothing in it is not in the navigation', () => {
    const nav = navigationOf(fixtureCatalog(10), defaultHomeSettings());
    expect(nav.departments.map((d) => d.name)).not.toContain('Electronics');
    expect(nav.departments.every((d) => d.count > 0)).toBe(true);
    expect(nav.servicesAvailable).toBe(true);
    expect(navigationOf(fixtureCatalog(0), defaultHomeSettings()).servicesAvailable).toBe(false);
  });
});

describe('sponsored cap', () => {
  it('shows at most 1 sponsored module per 3 organic ones', () => {
    for (const n of [0, 10, 100, 10000]) {
      const h = page(n, { seed: 'cap' });
      const paid = h.blocks.filter((b) => b.sponsored).length;
      const organic = h.blocks.length - paid;
      expect(paid).toBeLessThanOrEqual(Math.floor(organic / 3));
    }
  });
});

describe('visit seed', () => {
  const fingerprint = (h: Home) => JSON.stringify([modules(h), atins(h), h.blocks.flatMap((b) => b.cells?.map((c) => c.label) ?? [])]);

  it('one seed is stable on refresh', () => {
    expect(fingerprint(page(100, { seed: 'v1' }))).toBe(fingerprint(page(100, { seed: 'v1' })));
    expect(page(100, { seed: 'v1' }).seed).toBe('v1');
  });

  it('two visits with different seeds show different item subsets', () => {
    const a = new Set(atins(page(100, { seed: 'visit-a' })));
    const b = new Set(atins(page(100, { seed: 'visit-b' })));
    const overlap = [...a].filter((x) => b.has(x)).length;
    expect(a.size).toBeGreaterThan(10);
    expect(overlap).toBeLessThan(a.size);
    expect(fingerprint(page(100, { seed: 'visit-a' }))).not.toBe(fingerprint(page(100, { seed: 'visit-b' })));
  });

  it('a crawler (no seed) always sees the deterministic default', () => {
    expect(fingerprint(page(10000))).toBe(fingerprint(page(10000, { seed: DEFAULT_SEED })));
  });

  it('with 10 products there is nothing to vary: every seed shows the same items', () => {
    expect(new Set(atins(page(10, { seed: 'a' })))).toEqual(new Set(atins(page(10, { seed: 'b' }))));
  });
});

describe('decisions explain every module', () => {
  it('hidden modules carry a reason and a sentence that names the module', () => {
    const d = composeDynamicHome({ catalog: fixtureCatalog(10), settings: defaultHomeSettings(), seed: DEFAULT_SEED, now: NOW, signedIn: false, loc: HOUSTON, hasZip: false }).decisions;
    const communities = d.find((x) => x.module === 'communities');
    expect(communities).toMatchObject({ shown: false, reason: 'not-enough-units', label: 'Shop by community', position: null });
    expect(communities?.detail).toContain('needs 3 communities with 8 eligible products and 2 sellers each; 0 qualify');
    expect(d.filter((x) => x.shown).every((x) => x.position !== null)).toBe(true);
  });

  it('schedule and as-of date: a module scheduled later is hidden before it starts and shown after', () => {
    const settings = defaultHomeSettings();
    settings.modules = settings.modules.map((m) => (m.key === 'deals' ? { ...m, startsAt: '2026-12-01T00:00:00Z' } : m));
    const at = (iso: string) => composeDynamicHome({ catalog: fixtureCatalog(100), settings, seed: DEFAULT_SEED, now: new Date(iso), signedIn: false, loc: HOUSTON, hasZip: true }).decisions.find((x) => x.module === 'deals');
    expect(at('2026-11-12T00:00:00Z')).toMatchObject({ shown: false, reason: 'scheduled-later' });
    expect(at('2026-12-02T00:00:00Z')).toMatchObject({ shown: true });
  });
});
