import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { createStaffApiClient, isApiError } from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import {
  type StaffSearchClickThrough,
  type StaffSearchMarkets,
  type StaffSearchQueryReport,
  type StaffSynonymList,
  type StaffSynonymRule
} from '../src/staff-search';

type S = components['schemas'];

const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });

const window7 = { market: 'us', from: '2026-10-28', to: '2026-11-03' };

describe('Search analytics schemas stay aligned with contracts/openapi/gateway-bff-staff.yaml', () => {
  it('assigns to the generated types', () => {
    expectTypeOf<StaffSearchMarkets>().toExtend<S['StaffSearchMarkets']>();
    expectTypeOf<StaffSearchQueryReport>().toExtend<S['StaffSearchQueryReport']>();
    expectTypeOf<StaffSearchClickThrough>().toExtend<S['StaffSearchClickThrough']>();
    expectTypeOf<StaffSynonymList>().toExtend<S['StaffSynonymList']>();
    expectTypeOf<StaffSynonymRule>().toExtend<S['StaffSynonymRule']>();
  });
});

describe('Search analytics MSW mock (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-11-03T10:00:00Z'));
  });

  it('shows the module to roles with a search permission only', async () => {
    const keys = async (roles?: string) => (await client(roles).getSession()).navGroups.flatMap((g) => g.modules.map((m) => m.key));
    expect(await keys()).toContain('search');
    expect(await keys('platform.marketing')).toContain('search');
    expect(await keys('platform.catalog_manager')).toContain('search');
    expect(await keys('platform.customer_support')).not.toContain('search');
    await expect(client('platform.customer_support').search.markets()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('names the market and says who can edit synonyms', async () => {
    const admin = await client().search.markets();
    expect(admin.items).toEqual([{ code: 'us', name: 'United States' }]);
    expect(admin.canEditSynonyms).toBe(true);
    expect((await client('platform.marketing').search.markets()).canEditSynonyms).toBe(false);
  });

  it('is deterministic and ranks, filters and pages top queries', async () => {
    const api = client().search;
    const a = await api.queries('top-queries', { ...window7, size: 5 });
    const b = await api.queries('top-queries', { ...window7, size: 5 });
    expect(b).toEqual(a);
    expect(a.items.map((i) => i.query).slice(0, 3)).toEqual(['shea butter', 'kente', 'gari']);
    expect(a.items).toHaveLength(5);
    expect(a.total).toBeGreaterThan(20);
    expect(a.marketName).toBe('United States');
    expect(a.from).toBe('2026-10-28');
    expect(a.totals.searches).toBeGreaterThan(a.items[0]?.searches ?? 0);

    const page2 = await api.queries('top-queries', { ...window7, size: 5, page: 2 });
    expect(page2.items.map((i) => i.query)).not.toContain('shea butter');

    const filtered = await api.queries('top-queries', { ...window7, q: 'agbada' });
    expect(filtered.items.map((i) => i.query).sort()).toEqual(['agbada', 'agbada kids', 'kid agbada set']);

    const byQuery = await api.queries('top-queries', { ...window7, sort: ['query,asc'], size: 3 });
    expect(byQuery.items.map((i) => i.query)).toEqual(['agbada', 'agbada kids', 'ankara fabric']);
  });

  it('lists only queries that found nothing under no results, most frequent first', async () => {
    const r = await client().search.queries('zero-results', window7);
    expect(r.items.every((i) => i.zeroResults > 0)).toBe(true);
    const counts = r.items.map((i) => i.zeroResults);
    expect(counts).toEqual([...counts].sort((x, y) => y - x));
    const names = r.items.map((i) => i.query);
    expect(names).toContain('agbada kids');
    expect(names).toContain('suya spise');
    expect(names).not.toContain('shea butter');
    const full = r.items.find((i) => i.query === 'suya spise');
    expect(full?.zeroResultRate).toBe(1);
    expect(full?.clicks).toBe(0);
  });

  it('answers click-through by day, with the whole window as the trend', async () => {
    const r = await client().search.clickThrough({ ...window7, size: 3 });
    expect(r.items.map((d) => d.day)).toEqual(['2026-11-03', '2026-11-02', '2026-11-01']);
    expect(r.trend).toHaveLength(7);
    expect(r.trend[0]?.day).toBe('2026-10-28');
    expect(r.total).toBe(7);
    expect(r.totals.day).toBe('total');
    expect(r.totals.searches).toBe(r.trend.reduce((n, d) => n + d.searches, 0));
    const asc = await client().search.clickThrough({ ...window7, sort: ['ctr,asc'], size: 1 });
    expect(asc.items[0]?.ctr).toBe(Math.min(...r.trend.map((d) => d.ctr)));
  });

  it('refuses bad markets and windows as field errors', async () => {
    const api = client().search;
    const field = async (p: Promise<unknown>) => (await p.then(() => null, (e: unknown) => (isApiError(e) ? e.problem.errors : null))) ?? {};
    expect(await field(api.queries('top-queries', { market: 'zz' }))).toHaveProperty('market');
    expect(await field(api.queries('top-queries', { from: 'yesterday' }))).toHaveProperty('from');
    expect(await field(api.clickThrough({ from: '2026-11-03', to: '2026-10-01' }))).toHaveProperty('from');
    expect(await field(api.clickThrough({ from: '2024-01-01', to: '2026-01-01' }))).toHaveProperty('to');
  });

  it('adds and deletes synonym rules, validating the words', async () => {
    const api = client().search;
    const before = await api.synonyms();
    expect(before.canEdit).toBe(true);
    expect(before.items.find((r) => r.terms.includes('garri'))?.terms).toEqual(['gari', 'garri', 'cassava flakes']);

    const rule = await api.addSynonym([' agbada  kids ', 'grand boubou kids']);
    expect(rule).toEqual({ id: 'agbada-kids', terms: ['agbada kids', 'grand boubou kids'] });
    const again = await api.addSynonym(['agbada kids', 'dashiki kids']);
    expect(again.id).toBe('agbada-kids-2');
    expect((await api.synonyms()).items).toHaveLength(before.items.length + 2);

    await api.deleteSynonym(again.id);
    await expect(api.deleteSynonym(again.id)).rejects.toSatisfy((e) => isApiError(e) && e.status === 404);

    for (const terms of [['agbada'], ['agbada', 'AGBADA'], ['a, b', 'c']]) {
      await expect(api.addSynonym(terms)).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && Boolean(e.problem.errors?.terms));
    }
  });

  it('keeps viewing and editing apart', async () => {
    const viewer = client('platform.marketing').search;
    expect((await viewer.synonyms()).canEdit).toBe(false);
    await expect(viewer.addSynonym(['a', 'b'])).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(viewer.deleteSynonym('gari')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(client('platform.catalog_manager').search.addSynonym(['kente cloth', 'kente'])).resolves.toMatchObject({ id: 'kente-cloth' });
  });
});
