import { beforeEach, describe, expect, it } from 'vitest';

import { type Home, HomeSchema, type ProductSummary, RecentlyViewedSchema } from '../src';
import { activeHero, atinOf, eligibleForHome, home, recentlyViewed, resetDb } from '../src/mocks';

const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-11-04T12:00:00Z');

beforeEach(() => resetDb());

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
    expect(home(hou, now).rails.every((r) => r.items.length > 0 && r.items.every(eligibleForHome))).toBe(true);
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
    expect(home(hou, now).rails.some((r) => r.id === 'based-on-orders')).toBe(false);
    const mine = home(hou, now, 'u_adaeze').rails[0];
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
