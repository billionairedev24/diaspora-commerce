import { beforeEach, describe, expect, it } from 'vitest';

import { type Home, HomeSchema, type ProductSummary, RecentlyViewedSchema } from '../src';
import { activeHero, atinOf, eligibleForHome, home, recentlyViewed, resetDb } from '../src/mocks';

const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-11-04T12:00:00Z');

beforeEach(() => resetDb());

/** Every product a block shows (rails, sponsored rails, deals, spotlights, brand strips). */
const productsOf = (h: Home) => h.blocks.flatMap((b) => [...(b.rail?.items ?? []), ...(b.products ?? [])]);
const railsOf = (h: Home) => h.blocks.filter((b) => b.rail).map((b) => b.rail!);

describe('mock home rule tiles', () => {
  it('fills rule cells with eligible products: real photo, title, product link, no repeats', () => {
    const h = home(hou, now);
    HomeSchema.parse(h);
    const ruled = h.tiles.filter((t) => t.cells.some((c) => c.atin));
    expect(ruled.length).toBeGreaterThan(0);
    const atins = ruled.flatMap((t) => t.cells.map((c) => c.atin));
    expect(new Set(atins).size).toBe(atins.length);
    for (const t of ruled) {
      for (const c of t.cells) {
        expect(c.href).toBe(`/p/${c.atin}`);
        expect(c.imageUrl).toMatch(/^https:\/\//);
        expect(c.label.length).toBeGreaterThan(0);
      }
    }
  });

  it('keeps fixed-image tiles as content has them', () => {
    const fixed = home(hou, now).tiles.find((t) => t.id === 't1');
    expect(fixed?.cells.map((c) => c.label)).toEqual(['Nigerian', 'Indian', 'Jamaican', 'Ethiopian']);
    expect(fixed?.cells.every((c) => c.atin === undefined)).toBe(true);
  });

  it('only shows in-stock products with real photos', () => {
    const card = (over: Partial<ProductSummary>) => ({ inStock: true, imageUrl: 'https://img.afrotransact.dev/products/x.webp', ...over }) as ProductSummary;
    expect(eligibleForHome(card({}))).toBe(true);
    for (const imageUrl of [null, '', 'data:image/png;base64,AA', 'https://x.dev/placeholder.png', 'https://x.dev/logo.svg', 'https://x.dev/icons/box.png']) {
      expect(eligibleForHome(card({ imageUrl }))).toBe(false);
    }
    expect(eligibleForHome(card({ inStock: false }))).toBe(false);
    expect(productsOf(home(hou, now)).every(eligibleForHome)).toBe(true);
  });
});

describe('mock home hero schedule', () => {
  const slide = (id: string, extra: Partial<Home['hero'][number]> = {}): Home['hero'][number] => ({
    id,
    theme: 'ink',
    kicker: 'k',
    title: id,
    sub: 's',
    cta: 'c',
    href: '/',
    label: 'AfroTransact',
    imageUrl: null,
    ...extra
  });

  it('serves only slides inside their window, in order', () => {
    const slides = [
      slide('open'),
      slide('over', { endsAt: '2026-11-01T00:00:00Z' }),
      slide('later', { startsAt: '2026-12-01T00:00:00Z' }),
      slide('now', { startsAt: '2026-11-01T00:00:00Z', endsAt: '2026-11-30T00:00:00Z' })
    ];
    expect(activeHero(slides, now).map((s) => s.id)).toEqual(['open', 'now']);
    expect(activeHero(slides, new Date('2026-12-02T00:00:00Z')).map((s) => s.id)).toEqual(['open', 'later']);
  });
});

describe('mock personalised rails', () => {
  it('shows "Based on your orders" to a shopper with orders, never to guests', () => {
    expect(railsOf(home(hou, now)).some((r) => r.id === 'based-on-orders')).toBe(false);
    const mine = railsOf(home(hou, now, 'u_adaeze'))[0];
    expect(mine?.id).toBe('based-on-orders');
    expect(mine?.items.length).toBeGreaterThan(0);
    // Her past orders held p15, p1 and p17: they are not offered again.
    const offered = mine?.items.map((p) => p.atin) ?? [];
    for (const id of ['p15', 'p1', 'p17']) expect(offered).not.toContain(atinOf(id));
  });

  it('recently viewed keeps order, drops unknown ATINs and duplicates, and is empty without ATINs', () => {
    const [a, b] = [atinOf('p11'), atinOf('p9')];
    const cards = recentlyViewed([b, a, b, 'ATNOPE0000'], hou, now);
    expect(cards.map((c) => c.atin)).toEqual([b, a]);
    expect(RecentlyViewedSchema.parse({ items: cards }).items).toHaveLength(2);
    expect(recentlyViewed([], hou, now)).toEqual([]);
  });
});

describe('mock home follows the deliver-to ZIP', () => {
  const nj = { zip: '08817', city: 'Edison', state: 'NJ', label: 'Edison 08817', serviceable: true };
  const cells = (h: Home) => h.tiles.flatMap((t) => t.cells.map((c) => c.atin ?? c.label));
  const near = (h: Home) => railsOf(h).find((r) => r.id === 'r-near');
  const shownAtins = (h: Home) => [...h.tiles.flatMap((t) => t.cells.map((c) => c.atin)), ...productsOf(h).map((p) => p.atin)].filter(Boolean);

  it('targets hero slides by ZIP prefix', () => {
    const ids = (loc: typeof hou) => home(loc, now).hero.map((h) => h.id);
    expect(ids(hou)).toContain('h-tx');
    expect(ids(hou)).not.toContain('h-nj');
    expect(ids(nj)).toContain('h-nj');
    expect(ids(nj)).not.toContain('h-tx');
  });

  it('changes tiles and rails with the ZIP, and puts nearby sellers first', () => {
    const a = home(hou, now);
    const b = home(nj, now);
    expect(cells(a)).not.toEqual(cells(b));
    expect(near(a)?.title).toBe('Shipping from near Houston');
    expect(near(b)?.title).toBe('Shipping from near Edison');
    expect(near(a)?.items.map((p) => p.atin)).not.toEqual(near(b)?.items.map((p) => p.atin));
    expect(near(a)?.items[0]?.sellerCity.endsWith('TX')).toBe(true);
    expect(near(b)?.items[0]?.sellerCity.endsWith('NJ')).toBe(true);
  });

  it('is the same on every load of a visit, differs by day, and shows a product once', () => {
    const visit = (d: Date) => JSON.stringify([cells(home(hou, d, 'u1')), railsOf(home(hou, d, 'u1')).map((r) => r.items.map((p) => p.atin))]);
    expect(visit(now)).toBe(visit(new Date('2026-11-04T20:00:00Z')));
    expect(cells(home(hou, now))).not.toEqual(cells(home(hou, new Date('2026-11-05T12:00:00Z'))));
    for (const loc of [hou, nj]) {
      const atins = shownAtins(home(loc, now));
      expect(new Set(atins).size).toBe(atins.length);
    }
  });
});

describe('mock home composition', () => {
  const types = (h: Home) => h.blocks.map((b) => b.type);

  it('carries every block type, parses against the schema and ends with the client-side recently viewed marker', () => {
    const h = home(hou, now, 'u_adaeze');
    HomeSchema.parse(h);
    for (const t of ['rail', 'based-on-orders', 'sponsored-products', 'brand-banner', 'ad-row', 'advertorial', 'deals', 'community-spotlight', 'occasions', 'services-near-you', 'membership-promo', 'preorders']) {
      expect(types(h), t).toContain(t);
    }
    expect(types(h).at(-1)).toBe('recently-viewed');
  });

  it('marks every paid module sponsored with its advertiser, and nothing else', () => {
    const paid = ['banner', 'brand-banner', 'sponsored-products', 'ad-row', 'advertorial'];
    for (const b of home(hou, now).blocks) {
      expect(b.sponsored, b.type).toBe(paid.includes(b.type));
      if (b.sponsored) expect(b.advertiser?.length, b.type).toBeGreaterThan(0);
    }
  });

  it('shows the sign-in band to guests only, and discounted deals only', () => {
    expect(types(home(hou, now))).toContain('signin-promo');
    expect(types(home(hou, now, 'u_adaeze'))).not.toContain('signin-promo');
    const deals = home(hou, now).blocks.find((b) => b.type === 'deals');
    expect(deals?.rail?.items.every((p) => (p.listPriceMinor ?? 0) > p.priceMinor)).toBe(true);
    expect(Date.parse(deals?.endsAt ?? '')).toBeGreaterThan(now.getTime());
  });

  it('hides rails with fewer than three products (the mock catalogue is small; the gateway uses four) and sponsored rails with fewer than two', () => {
    for (const b of home(hou, now).blocks) {
      if (b.type === 'rail') expect(b.rail?.items.length).toBeGreaterThanOrEqual(3);
      if (b.type === 'sponsored-products') expect(b.rail?.items.length).toBeGreaterThanOrEqual(2);
    }
  });
});
