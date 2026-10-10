import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  createStaffApiClient,
  type Customer360,
  type CustomerSearch,
  type ExportJob,
  isApiError,
  type RecordDetail,
  type ResourceKey,
  type ResourcePage,
  type StaffDashboard,
  type StaffSession,
  type SupportActionResult
} from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb, validateForm } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

type S = components['schemas'];

const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });

const moduleKeys = (s: StaffSession) => s.navGroups.flatMap((g) => g.modules.map((m) => m.key));

describe('Staff Zod schemas stay aligned with contracts/openapi/gateway-bff-staff.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<StaffSession>().toExtend<S['StaffSession']>();
    expectTypeOf<StaffDashboard>().toExtend<S['StaffDashboard']>();
    expectTypeOf<ResourcePage>().toExtend<S['ResourcePage']>();
    expectTypeOf<RecordDetail>().toExtend<S['RecordDetail']>();
    expectTypeOf<ExportJob>().toExtend<S['ExportJob']>();
    expectTypeOf<CustomerSearch>().toExtend<S['CustomerSearch']>();
    expectTypeOf<Customer360>().toExtend<S['Customer360']>();
    expectTypeOf<SupportActionResult>().toExtend<S['SupportActionResult']>();
    expectTypeOf<ResourceKey>().toEqualTypeOf<S['ResourceKey']>();
  });

  it('generated file carries the do-not-edit banner', () => {
    const text = readFileSync(resolve(__dirname, '../src/generated/gateway-bff-staff.ts'), 'utf8');
    expect(text.startsWith('// GENERATED from contracts/openapi/gateway-bff-staff.yaml')).toBe(true);
  });
});

describe('staff MSW mocks (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('filters the sidebar by role and lands on the dashboard', async () => {
    const admin = await client().getSession();
    expect(admin.landing).toBe('dash');
    expect(admin.navGroups.map((g) => g.label)).toEqual(['Overview', 'Commerce', 'Partners', 'Finance', 'Growth', 'Trust & compliance', 'Settings']);
    expect(admin.refundLimitMinor).toBeNull();

    const support = await client('platform.customer_support').getSession();
    const keys = moduleKeys(support);
    expect(keys).toContain('support');
    expect(keys).toContain('orders');
    expect(keys).not.toContain('rbac');
    expect(keys).not.toContain('fees');
    expect(support.refundLimitMinor).toBe(25_000);

    const marketing = await client('platform.marketing').getSession();
    expect(moduleKeys(marketing)).toContain('marketing');
    expect(moduleKeys(marketing)).not.toContain('payouts');
  });

  it('rejects customers and guests', async () => {
    await expect(client('customer').getSession()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403 && e.code === 'NOT_STAFF');
    await expect(client('guest').getSession()).rejects.toSatisfy((e) => isApiError(e) && e.status === 401);
  });

  it('serves every table with columns and server-side paging', async () => {
    const api = client();
    const keys: ResourceKey[] = ['users', 'roles', 'applications', 'orders', 'products', 'moderation', 'claims', 'returns', 'payouts', 'inventory', 'purchase-orders', 'routes',
      'support-cases', 'audit', 'markets', 'site-settings', 'legal-documents', 'announcements', 'placements', 'identity-providers', 'fee-schedules', 'tax-rates'];
    for (const key of keys) {
      const page = await api.listResource(key, { page: 0, size: 5 });
      expect(page.columns.length).toBeGreaterThan(0);
      expect(page.columns.reduce((s, c) => s + c.width, 0)).toBeLessThanOrEqual(100);
      const first = page.rows[0];
      if (first) {
        const sheet = await api.getRecord(key, first.id);
        expect(sheet.sections.length).toBeGreaterThan(0);
      }
    }
    const orders = await api.listResource('orders', { page: 0, size: 10 });
    expect(orders.total).toBeNull();
    expect(orders.nextCursor).not.toBeNull();
    const next = await api.listResource('orders', { page: 0, size: 10, cursor: orders.nextCursor ?? undefined });
    expect(next.rows[0]?.id).not.toBe(orders.rows[0]?.id);
  });

  it('gates actions by permission and enforces them server-side', async () => {
    const auditor = client('platform.auditor');
    const sheet = await auditor.getRecord('payouts', 'pay-700');
    expect(sheet.actions.every((a) => !a.allowed)).toBe(true);
    await expect(auditor.runAction('payouts', 'pay-700', 'approve', {})).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);

    const finance = client('platform.finance');
    const approved = await finance.runAction('payouts', 'pay-700', 'approve', {});
    expect(approved.status).toBe('APPROVED');
    expect(approved.activity[0]?.text).toMatch(/Approved/);
  });

  it('runs the two-step application review with different people', async () => {
    const api = client('platform.trust_safety');
    const step1 = await api.runAction('applications', 'app-200', 'review-approve', { note: 'Documents check out' });
    expect(step1.status).toBe('AWAITING_FINAL_APPROVAL');
    const final = step1.actions.find((a) => a.key === 'final-approve');
    expect(final?.allowed).toBe(false);
    expect(final?.reason).toMatch(/different approver/);
  });

  it('validates create and edit forms', async () => {
    const api = client();
    await expect(api.createRecord('tax-rates', { state: 'Texas', zipPrefix: '77', name: 'Bad', rate: 0.5 })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422 && !!e.fieldErrors.state && !!e.fieldErrors.zipPrefix && !!e.fieldErrors.rate
    );
    const created = await api.createRecord('tax-rates', { state: 'TX', zipPrefix: '772', name: 'Pasadena', rate: 0.02 });
    expect(created.id).toBe('local:TX:772');
    const edited = await api.updateRecord('markets', 'CA', { status: 'LIVE', launchNote: 'Live in Ontario' }, null);
    expect(edited.status).toBe('LIVE');
    expect(validateForm({ title: 't', submitLabel: 's', fields: [{ key: 'a', label: 'A', type: 'email', required: true }] }, { a: 'nope' })).toEqual({ a: 'Enter a valid email' });
  });

  it('builds small exports immediately and serves them behind a download link', async () => {
    const api = client();
    const job = await api.createExport({ resource: 'orders', format: 'csv' });
    expect(job.status).toBe('READY');
    expect(job.async).toBe(false);
    expect(job.rows).toBe(42);
    expect(job.expiresAt).not.toBeNull();
    const list = await api.listExports();
    expect(list.items[0]?.id).toBe(job.id);
    const res = await handleStaffMockRequest(new Request(`http://staff.test/api/bff/v1/staff/exports/${job.id}/download`));
    expect(res.status).toBe(302);
  });

  it('runs the support console within the refund limit', async () => {
    const api = client('platform.customer_support');
    const hits = await api.searchCustomers('amara');
    expect(hits.items[0]?.id).toBe('cus-1000');
    const c360 = await api.getCustomer('cus-1000');
    expect(c360.orders.length).toBeGreaterThan(0);
    expect(c360.unavailable).toContain('bookings');
    expect(c360.can.refund).toBe(true);
    const order = c360.orders[0];
    if (!order) throw new Error('no order');
    await expect(api.refund({ customerId: 'cus-1000', orderNumber: order.orderNumber, amountMinor: 30_000, reason: 'Late delivery', via: 'ORDER' }, 'key-00000001')).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 403 && e.code === 'REFUND_LIMIT'
    );
    const ok = await api.refund({ customerId: 'cus-1000', orderNumber: order.orderNumber, amountMinor: 1_500, reason: 'Late delivery', via: 'ORDER' }, 'key-00000002');
    expect(ok.audited).toBe(true);
    const after = await api.getCustomer('cus-1000');
    expect(after.audit[0]?.action).toBe('refund.issued');
  });
});
