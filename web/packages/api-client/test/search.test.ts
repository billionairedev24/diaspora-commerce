import { beforeEach, describe, expect, it } from 'vitest';

import { fromApiSearchParams,parseSearchUrl, SearchQuerySchema, toApiSearchParams, toSearchUrl } from '../src';
import { resetDb, search } from '../src/mocks';

const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-10-09T10:00:00');
const q = (input: Parameters<typeof SearchQuerySchema.parse>[0]) => SearchQuerySchema.parse(input);

describe('mock search with filters and facets', () => {
  beforeEach(() => { resetDb(); });

  it('filters by fulfilment, seller kind, community, rating and price', () => {
    const at = search(q({ fulfilment: 'AFROTRANSACT', size: 60 }), hou, now);
    expect(at.total).toBeGreaterThan(0);
    expect(at.hits.every((h) => h.fulfilment === 'AFROTRANSACT')).toBe(true);
    const platform = search(q({ sellerKind: 'PLATFORM', size: 60 }), hou, now);
    expect(platform.hits.every((h) => h.sellerKind === 'PLATFORM')).toBe(true);
    const nigerian = search(q({ community: 'Nigerian', size: 60 }), hou, now);
    expect(nigerian.hits.every((h) => h.community === 'Nigerian')).toBe(true);
    const cheap = search(q({ price: 'u10', size: 60 }), hou, now);
    expect(cheap.hits.every((h) => h.priceMinor < 1000)).toBe(true);
    const rated = search(q({ minRating: 4.8, size: 60 }), hou, now);
    expect(rated.hits.every((h) => h.rating >= 4.8)).toBe(true);
  });

  it('returns disjunctive facet counts', () => {
    const all = search(q({ community: 'Nigerian' }), hou, now);
    const nigerianBucket = all.facets.community.find((b) => b.value === 'Nigerian');
    // Community counts ignore the community filter itself, so other communities still show counts.
    expect(all.facets.community.filter((b) => b.count > 0).length).toBeGreaterThan(1);
    expect(nigerianBucket?.count).toBe(all.total);
  });

  it('paginates', () => {
    const p0 = search(q({ size: 12, page: 0 }), hou, now);
    const p1 = search(q({ size: 12, page: 1 }), hou, now);
    expect(p0.pageCount).toBeGreaterThan(1);
    expect(p0.hits[0]?.atin).not.toBe(p1.hits[0]?.atin);
  });

  it('sorts by price', () => {
    const r = search(q({ sort: 'price_asc', size: 60 }), hou, now);
    const prices = r.hits.map((h) => h.priceMinor);
    expect(prices).toEqual([...prices].sort((a, b) => a - b));
  });

  it('returns an empty page for nonsense', () => {
    expect(search(q({ q: 'qqqqzzzz' }), hou, now).total).toBe(0);
  });

  it('labels low stock at 5 units or fewer', () => {
    const r = search(q({ q: 'gele' }), hou, now);
    const gele = r.hits.find((h) => /Gele/.test(h.title));
    expect(gele?.availableQty).toBe(3);
    expect(gele?.lowStock).toBe(true);
    expect(gele?.stockStatus).toBe('LOW_STOCK');
  });
});

describe('search URL state', () => {
  it('round-trips through the storefront URL', () => {
    const state = q({ q: 'jollof', department: 'Groceries', fulfilment: 'AFROTRANSACT', sellerKind: 'PLATFORM', minRating: 4.5, price: '10-25', maxDays: 1, sort: 'price_asc', page: 2 });
    const url = toSearchUrl(state);
    expect(url.toString()).toBe('q=jollof&dept=Groceries&fulfilment=afrotransact&soldBy=platform&rating=4.5&price=10-25&delivery=1&sort=price_asc&page=3');
    expect(parseSearchUrl(url)).toEqual(state);
  });

  it('ignores invalid URL values', () => {
    const parsed = parseSearchUrl(new URLSearchParams('sort=bogus&price=cheap&page=-4&rating=abc'));
    expect(parsed.sort).toBe('relevance');
    expect(parsed.price).toBeNull();
    expect(parsed.page).toBe(0);
    expect(parsed.minRating).toBeNull();
  });

  it('round-trips through the BFF query string', () => {
    const state = q({ q: 'rice', community: 'Indian', maxDays: 1, page: 1 });
    expect(fromApiSearchParams(toApiSearchParams(state))).toEqual(state);
  });
});
