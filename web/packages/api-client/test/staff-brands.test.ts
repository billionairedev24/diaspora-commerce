import { beforeEach, describe, expect, it } from 'vitest';

import {
  attributeProblems,
  brandChanged,
  createStaffApiClient,
  sellerFormPreview,
  splitBrandList,
  type StaffBrand,
  type TaxonomyAttribute,
  type TaxonomyNode
} from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Catalog → Categories → Brands: the review queue with the seller named, approve / reject / retire / merge with a reason
   and the loaded version, adding one brand or a pasted list, editing name and scope, and the brand attribute of a type. */

const api = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  }).taxonomy;

const attr = (key: string, label: string, extra: Partial<TaxonomyAttribute> = {}): TaxonomyAttribute => ({
  key, label, kind: 'TEXT', options: [], units: [], required: false, section: 'DETAILS', help: null, applies: true, ...extra
});

const nodeId = (nodes: TaxonomyNode[], path: string): string => {
  const n = nodes.find((x) => x.path === path);
  if (!n) throw new Error(`no node ${path}`);
  return n.id;
};

async function rejects(call: Promise<unknown>, expected: Record<string, unknown>): Promise<void> {
  const refusal = await call.then(
    () => {
      throw new Error('expected the call to be refused');
    },
    (e: { status: number; problem?: { code?: string; errors?: Record<string, string> } }) => ({ status: e.status, code: e.problem?.code, errors: e.problem?.errors ?? {} })
  );
  expect(refusal).toMatchObject(expected);
}

beforeEach(() => resetStaffDb(new Date('2026-10-24T12:00:00Z')));

describe('the brand registry', () => {
  it('lists the review queue with the seller named and counts per status', async () => {
    const queue = await api().brands({ status: ['PENDING'] });
    expect(queue.brands.map((b) => b.name).sort()).toEqual(['Adire Lagos', 'Cotedivoire', 'Nikee', 'Shea Gold']);
    expect(queue.brands.find((b) => b.name === 'Cotedivoire')).toMatchObject({ createdByName: 'Lagos Threads', products: 2, sellers: 1, scope: [{ path: 'Fashion', level: 'DEPARTMENT' }] });
    expect(queue.counts).toMatchObject({ PENDING: 4, APPROVED: 4 });
    expect((await api().brands({ q: 'LEVIS' })).brands.map((b) => b.name)).toEqual(['Levi’s']);
    expect(JSON.stringify(queue)).not.toContain('org_');
  });

  it('approves, rejects and retires with a reason and the loaded version, and audits each', async () => {
    const queue = await api().brands({ status: ['PENDING'] });
    const cote = queue.brands.find((b) => b.name === 'Cotedivoire') as StaffBrand;
    await rejects(api().approveBrand(cote.id, 'ok', cote.version), { status: 422, errors: { reason: expect.any(String) } });
    await rejects(api().approveBrand(cote.id, 'Real Lagos label', cote.version + 5), { status: 412, code: 'VERSION_CONFLICT' });
    const approved = await api().approveBrand(cote.id, 'Real Lagos label', cote.version);
    expect(approved).toMatchObject({ status: 'APPROVED', version: cote.version + 1, decisionReason: 'Real Lagos label' });
    await rejects(api().approveBrand(cote.id, 'Again', approved.version), { status: 409 });
    const shea = queue.brands.find((b) => b.name === 'Shea Gold') as StaffBrand;
    expect((await api().rejectBrand(shea.id, 'Not a real brand', shea.version)).status).toBe('REJECTED');
    const nike = (await api().brands({ q: 'nike', status: ['APPROVED'] })).brands[0] as StaffBrand;
    expect((await api().retireBrand(nike.id, 'Closed down', nike.version)).status).toBe('RETIRED');
    const audit = (await api().audit()).items;
    expect(audit.map((a) => a.action)).toEqual(expect.arrayContaining(['BRAND_APPROVED', 'BRAND_REJECTED', 'BRAND_RETIRED']));
    expect(audit.every((a) => a.reason.length >= 3)).toBe(true);
  });

  it('merges a typo into an approved brand; the target grows and the typo leaves the queue', async () => {
    const queue = await api().brands({ status: ['PENDING'] });
    const typo = queue.brands.find((b) => b.name === 'Nikee') as StaffBrand;
    const nike = (await api().brands({ q: 'nike', status: ['APPROVED'] })).brands[0] as StaffBrand;
    await rejects(api().mergeBrand(typo.id, typo.id, 'Self', typo.version), { status: 422, errors: { targetId: expect.any(String) } });
    await rejects(api().mergeBrand(typo.id, queue.brands[0]?.id ?? '', 'Pending target', typo.version), { status: 422 });
    const merged = await api().mergeBrand(typo.id, nike.id, 'Typo of Nike', typo.version);
    expect(merged).toMatchObject({ status: 'MERGED_INTO', mergedIntoName: 'Nike' });
    expect((await api().brands({ status: ['PENDING'] })).brands.map((b) => b.name)).not.toContain('Nikee');
    expect((await api().brands({ q: 'nike', status: ['APPROVED'] })).brands[0]?.products).toBe(nike.products + typo.products);
  });

  it('adds one brand or a pasted list, edits name and scope, and refuses a form with no change', async () => {
    const nodes = (await api().tree()).nodes;
    const fashion = nodeId(nodes, 'Fashion');
    const women = nodeId(nodes, 'Fashion › Women');
    await rejects(api().createBrand({ name: 'Kente Co', scope: [], reason: 'Real label' }), { status: 422, errors: { scope: expect.any(String) } });
    await rejects(api().createBrand({ name: 'nike', scope: [fashion], reason: 'Already there' }), { status: 409, code: 'catalog.brand-exists' });
    const one = await api().createBrand({ name: 'Kente Co', scope: [fashion], reason: 'Real label' });
    expect(one).toMatchObject({ status: 'APPROVED', createdByName: null });
    const bulk = await api().createBrands({ text: 'Studio One\nstudio one, Nike; X; Studio Two', scope: [fashion], reason: 'Starter list' });
    expect(bulk).toMatchObject({ created: 2, existing: 2, invalid: 1 });
    expect(splitBrandList('Studio One\nstudio  one, Nike; X;; Studio Two')).toEqual(['Studio One', 'Nike', 'X', 'Studio Two']);
    await rejects(api().updateBrand(one.id, { name: 'Kente Co', reason: 'No change' }, one.version), { status: 422 });
    expect(brandChanged(one, 'Kente Co', [fashion])).toBe(false);
    expect(brandChanged(one, ' kente  co', [fashion])).toBe(true);
    expect(brandChanged(one, 'Kente Co', [fashion, women])).toBe(true);
    const edited = await api().updateBrand(one.id, { name: 'Kente Studio', scope: [fashion, women], reason: 'Wider and renamed' }, one.version);
    expect(edited).toMatchObject({ name: 'Kente Studio', version: one.version + 1 });
    expect(edited.scope.map((s) => s.path).sort()).toEqual(['Fashion', 'Fashion › Women']);
    await rejects(api().updateBrand(one.id, { name: 'Late', reason: 'Stale edit' }, one.version), { status: 412 });
  });

  it('needs the taxonomy permission and the Edit level', async () => {
    await rejects(api('platform.operations_manager').brands(), { status: 403 });
    await rejects(api('platform.product_owner').brands(), { status: 403 });
    expect((await api('platform.catalog_manager').brands()).canEdit).toBe(true);
  });

  it('shows the brand attribute of a type as the brand field and checks its definition', async () => {
    const dress = await api().productType('dress');
    expect(dress.attributes.find((a) => a.key === 'brand')).toMatchObject({ kind: 'BRAND', label: 'Brand or label', options: ['UNBRANDED', 'HANDMADE'] });
    const preview = sellerFormPreview(dress).flatMap((s) => s.fields);
    expect(preview.find((f) => f.key === 'brand')).toMatchObject({ control: 'brand', label: 'Brand or label' });
    const bad: TaxonomyAttribute[] = [attr('maker', 'Maker', { kind: 'BRAND' }), attr('brand', 'Brand', { kind: 'BRAND', options: ['GENERIC'] })];
    expect(attributeProblems(bad, { food: false, perishable: false, regulated: false, preorder: false })).toMatchObject({
      'attributes[0].key': expect.any(String),
      'attributes[1].options': expect.any(String)
    });
  });
});
