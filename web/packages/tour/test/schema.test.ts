import { describe, expect, it } from 'vitest';

import { audienceAllows, toursFor,type TourSubject } from '../src/audience';
import { defineTour, keysOf, targetsOf, tourSchema } from '../src/schema';

const base = {
  id: 'demo',
  version: 1,
  title: 'x.tour.demo.title',
  summary: 'x.tour.demo.summary',
  steps: [
    { id: 'one', target: 'page-home', title: 'x.tour.demo.one.title', body: 'x.tour.demo.one.body' },
    { id: 'two', title: 'x.tour.demo.two.title', body: 'x.tour.demo.two.body', placement: 'center' as const }
  ]
};

describe('tour schema', () => {
  it('accepts a tour and fills defaults', () => {
    const tour = defineTour(base);
    expect(tour.steps[0]?.placement).toBe('bottom');
    expect(tour.audience).toEqual({});
  });

  it('rejects CSS selectors as targets: only data-tour ids are allowed', () => {
    for (const bad of ['.sidebar > a', '#main', 'div[data-x]', 'Page Home', '']) {
      const input = { ...base, steps: [{ ...base.steps[0], target: bad }] };
      expect(tourSchema.safeParse(input).success, bad).toBe(false);
    }
  });

  it('rejects text where an i18n key is expected', () => {
    const input = { ...base, steps: [{ ...base.steps[0], title: 'Welcome to your shop' }] };
    expect(tourSchema.safeParse(input).success).toBe(false);
  });

  it('rejects duplicate step ids, unknown fields, an empty tour and a version below 1', () => {
    expect(tourSchema.safeParse({ ...base, steps: [base.steps[0], { ...base.steps[0] }] }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, extra: true }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, steps: [] }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, version: 0 }).success).toBe(false);
  });

  it('accepts a route with a simple query, rejects other shapes', () => {
    expect(tourSchema.safeParse({ ...base, route: '/settings?section=tax&tab=tax-review' }).success).toBe(true);
    expect(tourSchema.safeParse({ ...base, route: '/orders#top' }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, route: '/orders?a b' }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, route: 'orders' }).success).toBe(false);
    expect(tourSchema.safeParse({ ...base, route: '/listings/new' }).success).toBe(true);
  });

  it('names the tour in the error thrown by defineTour', () => {
    expect(() => defineTour({ ...base, version: 0 })).toThrow(/Invalid tour "demo"/);
  });

  it('lists targets and i18n keys for the drift and catalogue checks', () => {
    const tour = defineTour({ ...base, steps: [{ ...base.steps[0]!, success: { target: 'new-product', when: 'click' } }, base.steps[1]!] });
    expect(targetsOf(tour).sort()).toEqual(['new-product', 'page-home']);
    expect(keysOf(tour)).toContain('x.tour.demo.two.body');
    expect(keysOf(tour)).toHaveLength(6);
  });
});

describe('audience gating', () => {
  const subject = (over: Partial<TourSubject> = {}): TourSubject => ({ userId: 'u1', roles: ['seller.owner'], permissions: ['catalog.moderate'], flags: ['approved'], ...over });

  it('an empty audience admits everyone', () => {
    expect(audienceAllows({}, subject({ roles: [], permissions: [], flags: [] }))).toBe(true);
  });

  it('roles: any of them', () => {
    expect(audienceAllows({ roles: ['seller.owner', 'seller.manager'] }, subject())).toBe(true);
    expect(audienceAllows({ roles: ['seller.fulfilment'] }, subject())).toBe(false);
  });

  it('permissions: all of them; anyPermissions: at least one', () => {
    expect(audienceAllows({ permissions: ['catalog.moderate'] }, subject())).toBe(true);
    expect(audienceAllows({ permissions: ['catalog.moderate', 'tax.review'] }, subject())).toBe(false);
    expect(audienceAllows({ anyPermissions: ['tax.review', 'catalog.moderate'] }, subject())).toBe(true);
    expect(audienceAllows({ anyPermissions: ['tax.review'] }, subject())).toBe(false);
  });

  it('flags: all of them (for example a provider who offers services)', () => {
    expect(audienceAllows({ flags: ['approved', 'offers-services'] }, subject())).toBe(false);
    expect(audienceAllows({ flags: ['approved', 'offers-services'] }, subject({ flags: ['approved', 'offers-services'] }))).toBe(true);
  });

  it('toursFor keeps only the tours the person may see, in order', () => {
    const a = defineTour({ ...base, id: 'a', audience: { permissions: ['tax.review'] } });
    const b = defineTour({ ...base, id: 'b' });
    const c = defineTour({ ...base, id: 'c', audience: { anyPermissions: ['catalog.moderate'] } });
    expect(toursFor([a, b, c], subject()).map((t) => t.id)).toEqual(['b', 'c']);
  });
});
