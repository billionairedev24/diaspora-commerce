import { beforeEach, describe, expect, it } from 'vitest';

import { HomeSchema, pickRailFill, type ProductSummary, RAIL_FILL_TARGET, type RailFill, type RailFillCandidate, railFillLabelKey,RecentlyViewedSchema } from '../src';
import { applyMockRailFills, home, recentlyViewed, recentlyViewedFill, resetDb } from '../src/mocks';

const card = (atin: string, department = 'Groceries'): ProductSummary =>
  ({ atin, title: atin, department, inStock: true, imageUrl: `https://img.afrotransact.dev/products/${atin}.webp` }) as ProductSummary;
const many = (prefix: string, n: number, department?: string) => Array.from({ length: n }, (_, i) => card(`${prefix}${i}`, department));
const paid = (id: string, over: Partial<RailFill> = {}): RailFillCandidate => ({ tile: { kind: 'placement', id, sponsored: true, advertiser: 'Acme', title: `Promo ${id}`, href: '/s?q=x', ...over } });
const editorial = (id: string): RailFillCandidate => ({ tile: { kind: 'placement', id, sponsored: false, title: `Editorial ${id}`, href: '/s?q=y' } });

beforeEach(() => resetDb());

describe('pickRailFill priority order', () => {
  it('leaves a full rail alone', () => {
    const r = pickRailFill({ items: many('A', RAIL_FILL_TARGET), relaxed: many('R', 3), placements: [paid('p')] });
    expect(r.tier).toBe('none');
    expect(r.fill).toBeNull();
    expect(r.items).toHaveLength(RAIL_FILL_TARGET);
  });

  it('1. fills with more relevant products first, before any ad', () => {
    const r = pickRailFill({ items: many('A', 3), relaxed: many('R', 10), placements: [paid('p')] });
    expect(r.tier).toBe('products');
    expect(r.fill).toBeNull();
    expect(r.items).toHaveLength(RAIL_FILL_TARGET);
  });

  it('never repeats a product of the rail or of the page', () => {
    const items = many('A', 3);
    const r = pickRailFill({ items, relaxed: [items[0] as ProductSummary, card('U'), ...many('R', 8)], used: new Set(['U']) });
    const ids = r.items.map((p) => p.atin);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).not.toContain('U');
  });

  it('2. a Sponsored tile beats an editorial one, whatever their priority, and names its advertiser', () => {
    const r = pickRailFill({ items: many('A', 3), placements: [{ ...editorial('e'), priority: 99 }, paid('p')] });
    expect(r.tier).toBe('sponsored');
    expect(r.fill).toMatchObject({ id: 'p', sponsored: true, advertiser: 'Acme' });
    expect(railFillLabelKey(r.fill as RailFill)).toBe('home.sponsoredTag');
  });

  it('2b. the sponsored-products auction counts as a Sponsored tile, drops products already in the rail, and must be labelled', () => {
    const items = many('A', 3);
    const auction = (): RailFill => ({ kind: 'sponsored-products', sponsored: true, advertiser: 'Marketplace sellers', title: 'x', href: '/s', products: [items[0] as ProductSummary, card('S1')] });
    const r = pickRailFill({ items, auction, placements: [editorial('e')] });
    expect(r.tier).toBe('sponsored');
    expect(r.fill?.products?.map((p) => p.atin)).toEqual(['S1']);
    const unlabelled = (): RailFill => ({ kind: 'sponsored-products', sponsored: true, title: 'x', href: '/s', products: [card('S1')] });
    expect(pickRailFill({ items, auction: unlabelled, placements: [editorial('e')] }).tier).toBe('placement');
  });

  it('3. an editorial tile is not labelled Sponsored', () => {
    const r = pickRailFill({ items: many('A', 3), placements: [editorial('e')] });
    expect(r.tier).toBe('placement');
    expect(r.fill?.sponsored).toBe(false);
    expect(railFillLabelKey(r.fill as RailFill)).toBeNull();
  });

  it('targets by department and priority, and rotates among equals', () => {
    const fashion = { ...paid('fashion'), departments: ['Fashion'] };
    const top = { ...paid('top'), priority: 5 };
    expect(pickRailFill({ items: many('A', 3), department: 'groceries', placements: [fashion, paid('low'), top] }).fill?.id).toBe('top');
    expect(pickRailFill({ items: many('A', 3), department: 'Fashion', placements: [fashion] }).fill?.id).toBe('fashion');
    expect(pickRailFill({ items: many('A', 3), placements: [paid('a'), paid('b')], rotate: 1 }).fill?.id).toBe('b');
  });

  it('4. nothing eligible: no tile and no placeholder, the rail keeps its own items', () => {
    const r = pickRailFill({ items: many('A', 3), placements: [{ tile: { kind: 'placement', sponsored: false, title: '', href: '' } }] });
    expect(r.tier).toBe('collapse');
    expect(r.fill).toBeNull();
    expect(r.items).toHaveLength(3);
  });
});

describe('mock BFF', () => {
  const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };

  it('serves a schema-valid home whose short rails are filled or left alone, never an empty tile', () => {
    const h = home(hou, new Date('2026-11-04T12:00:00Z'));
    expect(() => HomeSchema.parse(h)).not.toThrow();
    for (const b of h.blocks) {
      const fill = b.rail?.fill;
      if (!fill) continue;
      expect(fill.title.length).toBeGreaterThan(0);
      if (fill.sponsored) expect(fill.advertiser).toBeTruthy();
      expect(b.rail?.items.length).toBeLessThan(RAIL_FILL_TARGET);
    }
  });

  it('applyMockRailFills dedupes against the page and skips sponsored rails', () => {
    const pool = many('P', 12);
    const blocks = [
      { id: 'a', type: 'rail', sponsored: false, rail: { id: 'a', title: 'A', href: '/', items: pool.slice(0, 3) } },
      { id: 'b', type: 'rail', sponsored: false, rail: { id: 'b', title: 'B', href: '/', items: pool.slice(3, 5) } },
      { id: 'sp', type: 'sponsored-products', sponsored: true, rail: { id: 'sp', title: 'S', href: '/', items: pool.slice(5, 6) } }
    ];
    applyMockRailFills(blocks, pool, { placements: [] });
    const shown = blocks.flatMap((b) => b.rail.items.map((p) => p.atin));
    expect(new Set(shown).size).toBe(shown.length);
    expect(blocks[2]?.rail.items).toHaveLength(1);
  });

  it('recently viewed gets a tile but never other products', () => {
    const items = recentlyViewed(['AT00000P01'], hou, new Date('2026-11-04T12:00:00Z'));
    const fill = recentlyViewedFill(items, hou, new Date('2026-11-04T12:00:00Z'));
    expect(() => RecentlyViewedSchema.parse({ items, fill: fill ?? undefined })).not.toThrow();
    expect(recentlyViewedFill([], hou, new Date())).toBeNull();
  });
});
