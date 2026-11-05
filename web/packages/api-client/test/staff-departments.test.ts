import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  ApiError,
  createStaffApiClient,
  staffDepartmentKeys,
  type StaffDepartmentRequest,
  type StaffDepartmentRequestPage,
  type StaffOrgDepartments
} from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Department requests: the Zod schemas stay aligned with the staff contract, and the mock BFF behaves like the Go one
   (filters, tabs, decisions, the reason a rejection needs, the override that must change something). */

const NOW = new Date('2026-11-03T12:00:00Z');
const client = (roles = 'platform.operations_manager') =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });

type S = components['schemas'];

describe('Zod schemas stay aligned with contracts/openapi/gateway-bff-staff.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<StaffDepartmentRequestPage>().toExtend<S['StaffDepartmentRequestPage']>();
    expectTypeOf<StaffDepartmentRequest>().toExtend<S['StaffDepartmentRequest']>();
    expectTypeOf<StaffOrgDepartments>().toExtend<S['StaffOrgDepartments']>();
  });

  it('has key factories per query', () => {
    expect(staffDepartmentKeys.requests({ status: 'PENDING' })).toEqual(['staff', 'departments', 'requests', { status: 'PENDING' }]);
    expect(staffDepartmentKeys.org('org-1')).toEqual(['staff', 'departments', 'org', 'org-1']);
  });
});

describe('department requests mock', () => {
  beforeEach(() => resetStaffDb(NOW));
  const api = () => client().departments;
  const names = (page: StaffDepartmentRequestPage) => page.items.map((r) => `${r.orgName} / ${r.departmentName}`);

  it('lists the open requests oldest first, by name, with the count of requests waiting', async () => {
    const page = await api().requests({});
    expect(names(page)).toEqual(['Kente Naturals / Groceries & Food', "Ade's Kitchen / Fashion", 'Mama Efe Braids / Beauty & Personal Care']);
    expect(page).toMatchObject({ total: 3, pending: 3, canDecide: true, truncated: false });
    expect(page.items[0]).toMatchObject({ requestedByName: 'Kofi Mensah', status: 'PENDING', decidedByName: null });
  });

  it('lists approved and rejected requests together on the decided tab, newest decision first', async () => {
    const page = await api().requests({ status: 'DECIDED' });
    expect(names(page)).toEqual(['Nile Beauty / Jewellery & Accessories', 'Mama Efe Braids / Groceries & Food', 'Kente Naturals / Jewellery & Accessories']);
    expect(page.items[0]).toMatchObject({ status: 'APPROVED', decidedByName: 'Amara Diallo' });
    expect(page.pending).toBe(3);
    expect(names(await api().requests({ status: 'REJECTED' }))).toEqual(['Mama Efe Braids / Groceries & Food']);
  });

  it('filters by department name, organisation name and the days the request was made, then sorts and pages', async () => {
    expect(names(await api().requests({ department: 'beauty' }))).toEqual(['Mama Efe Braids / Beauty & Personal Care']);
    expect(names(await api().requests({ organisation: 'ADE' }))).toEqual(["Ade's Kitchen / Fashion"]);
    expect(names(await api().requests({ from: '2026-10-29', to: '2026-10-29' }))).toEqual(["Ade's Kitchen / Fashion"]);
    const second = await api().requests({ sort: ['organisation,asc'], size: 2, page: 2 });
    expect(names(second)).toEqual(['Mama Efe Braids / Beauty & Personal Care']);
    expect(second).toMatchObject({ total: 3, page: 2 });
  });

  it('refuses a bad filter with field errors', async () => {
    const e = await api().requests({ from: '2026-11-02', to: '2026-11-01' }).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect((e as ApiError).fieldErrors.from).toBeTruthy();
  });

  it('shows the compliance prompts with whether the organisation satisfies them', async () => {
    const page = await api().requests({});
    const id = (org: string) => page.items.find((r) => r.orgName === org)?.id ?? '';
    const kente = await api().request(id('Kente Naturals'));
    expect(kente.prompts).toEqual([]); // Groceries asks for nothing on file any more
    const efe = await api().request(id('Mama Efe Braids'));
    expect(efe.prompts).toEqual([{ requirement: 'BEAUTY_LABELLING', label: 'Beauty product labelling confirmed', satisfied: false }]);
    expect(kente.currentDepartments.map((d) => d.name)).toEqual(['Fashion', 'Home & Art']);
    const ade = await api().request(id("Ade's Kitchen"));
    expect(ade.prompts).toEqual([]);
    expect(ade.note).toContain('ankara tote bags');
    const decided = await api().requests({ status: 'REJECTED' });
    const rejected = await api().request(decided.items[0]?.id ?? '');
    expect(rejected.prompts).toEqual([]);
    expect(rejected.decisionNote).toBe('Tell us more about what you will sell, then ask again.');
  });

  it('approves with an optional note and the department joins the set', async () => {
    const id = (await api().requests({ organisation: 'kente' })).items[0]?.id ?? '';
    const done = await api().approve(id, '  Permit seen by phone. ');
    expect(done).toMatchObject({ status: 'APPROVED', decisionNote: 'Permit seen by phone.', decidedByName: 'Ifeoma Okafor' });
    expect(done.currentDepartments.map((d) => d.name)).toEqual(['Fashion', 'Groceries & Food', 'Home & Art']);
    expect((await api().requests({})).pending).toBe(2);
    const bare = await api().approve((await api().requests({ organisation: 'ade' })).items[0]?.id ?? '', '');
    expect(bare).toMatchObject({ status: 'APPROVED', decisionNote: null });
  });

  it('rejects only with a reason, and a request already decided is a calm 409', async () => {
    const id = (await api().requests({ organisation: 'efe' })).items[0]?.id ?? '';
    const refused = await api().reject(id, '  ').catch((x: unknown) => x);
    expect(refused).toBeInstanceOf(ApiError);
    expect((refused as ApiError).fieldErrors.reason).toBeTruthy();
    const done = await api().reject(id, 'Add your labelling photos first.');
    expect(done).toMatchObject({ status: 'REJECTED', decisionNote: 'Add your labelling photos first.' });
    const again = await api().approve(id, '').catch((x: unknown) => x);
    expect(again).toMatchObject({ status: 409, code: 'ALREADY_DONE' });
  });

  it('lists an organisation’s departments by name and overrides them with a reason, only when something changes', async () => {
    const org = await api().orgDepartments('org-ade');
    expect(org).toMatchObject({ orgName: "Ade's Kitchen", exempt: false, canEdit: true, pendingRequests: 1 });
    expect(org.departments.map((d) => d.name)).toEqual(['Groceries & Food']);
    expect(org.catalog.length).toBeGreaterThan(4);

    const unchanged = await api().setOrgDepartments('org-ade', ['groceries'], 'Nothing changes at all').catch((x: unknown) => x);
    expect((unchanged as ApiError).fieldErrors.departments).toBeTruthy();
    const short = await api().setOrgDepartments('org-ade', ['groceries', 'fashion'], 'short').catch((x: unknown) => x);
    expect((short as ApiError).fieldErrors.reason).toBeTruthy();

    const saved = await api().setOrgDepartments('org-ade', ['fashion', 'groceries'], 'Seller added tailoring, confirmed by phone');
    expect(saved.departments.map((d) => d.name)).toEqual(['Fashion', 'Groceries & Food']);
    const retail = await api().orgDepartments('org-retail');
    expect(retail).toMatchObject({ exempt: true, canEdit: false });
  });

  it('keeps people without the permission out, and lets a reviewer read but not decide', async () => {
    const nobody = await client('platform.marketing').departments.requests({}).catch((x: unknown) => x);
    expect(nobody).toMatchObject({ status: 403 });
  });
});
