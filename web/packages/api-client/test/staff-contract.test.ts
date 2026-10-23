import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';

import {
  createStaffApiClient,
  type Customer360,
  type CustomerSearch,
  type DeliveryQuote,
  type DriverSuggestions,
  type ExportJob,
  type FeeOrgSearch,
  type FeeScheduleView,
  type FeesOverview,
  formatBps,
  isApiError,
  type NotificationPreview,
  type NotificationTemplateDetail,
  type NotificationTemplateList,
  type NotificationTemplateVersion,
  type NotificationTestSendResult,
  parsePercent,
  type PickupAssignmentList,
  type PickupAssignResult,
  type PickupList,
  quoteTariff,
  type RecordDetail,
  type ResourceKey,
  type ResourcePage,
  type ShipFromLocation,
  type StaffBillingGrantPage,
  type StaffBillingOrgSearch,
  type StaffBillingPanel,
  type StaffBillingReasonCode,
  type StaffBillingSettings,
  type StaffCreatePlanInput,
  type StaffDashboard,
  type StaffPlan,
  type StaffPlanFeeCollection,
  type StaffPlanMigrationPreview,
  type StaffPlanMigrationResult,
  type StaffPlanPrices,
  type StaffRewardPreview,
  type StaffRewardRule,
  type StaffRewardRuleInput,
  type StaffSession,
  type StaffTrialCampaign,
  type StaffTrialCampaignInput,
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
    expectTypeOf<FeesOverview>().toExtend<S['FeesOverview']>();
    expectTypeOf<FeeScheduleView>().toExtend<S['FeeScheduleView']>();
    expectTypeOf<FeeOrgSearch>().toExtend<S['FeeOrgSearch']>();
    expectTypeOf<DeliveryQuote>().toExtend<S['DeliveryQuote']>();
    expectTypeOf<PickupList>().toExtend<S['PickupList']>();
    expectTypeOf<DriverSuggestions>().toExtend<S['DriverSuggestions']>();
    expectTypeOf<PickupAssignResult>().toExtend<S['PickupAssignResult']>();
    expectTypeOf<PickupAssignmentList>().toExtend<S['PickupAssignmentList']>();
    expectTypeOf<ShipFromLocation>().toExtend<S['ShipFromLocation']>();
    expectTypeOf<NotificationTemplateList>().toExtend<S['NotificationTemplateList']>();
    expectTypeOf<NotificationTemplateDetail>().toExtend<S['NotificationTemplateDetail']>();
    expectTypeOf<NotificationPreview>().toExtend<S['NotificationPreview']>();
    expectTypeOf<NotificationTemplateVersion>().toExtend<S['NotificationTemplateVersion']>();
    expectTypeOf<NotificationTestSendResult>().toExtend<S['NotificationTestSendResult']>();
    // Plans & billing (S292–S297)
    expectTypeOf<StaffPlan>().toExtend<S['StaffPlan']>();
    expectTypeOf<StaffPlanPrices>().toExtend<S['StaffPlanPrices']>();
    expectTypeOf<StaffCreatePlanInput>().toExtend<S['StaffCreatePlanRequest']>();
    expectTypeOf<StaffPlanMigrationPreview>().toExtend<S['StaffPlanMigrationPreview']>();
    expectTypeOf<StaffPlanMigrationResult>().toExtend<S['StaffPlanMigrationResult']>();
    expectTypeOf<StaffTrialCampaign>().toExtend<S['StaffTrialCampaign']>();
    expectTypeOf<StaffTrialCampaignInput>().toExtend<S['StaffTrialCampaignRequest']>();
    expectTypeOf<StaffRewardRule>().toExtend<S['StaffRewardRule']>();
    expectTypeOf<StaffRewardRuleInput>().toExtend<S['StaffRewardRuleRequest']>();
    expectTypeOf<StaffRewardPreview>().toExtend<S['StaffRewardPreview']>();
    expectTypeOf<StaffBillingGrantPage>().toExtend<S['StaffBillingGrantPage']>();
    expectTypeOf<StaffBillingReasonCode>().toExtend<S['StaffBillingReasonCode']>();
    expectTypeOf<StaffBillingOrgSearch>().toExtend<S['StaffBillingOrgSearch']>();
    expectTypeOf<StaffBillingPanel>().toExtend<S['StaffBillingPanel']>();
    // How sellers pay their plan (S298)
    expectTypeOf<StaffPlanFeeCollection>().toExtend<S['StaffPlanFeeCollection']>();
    expectTypeOf<StaffBillingSettings>().toExtend<S['StaffBillingSettings']>();
  });

  it('generated file carries the do-not-edit banner', () => {
    const text = readFileSync(resolve(__dirname, '../src/generated/gateway-bff-staff.ts'), 'utf8');
    expect(text.startsWith('// GENERATED from contracts/openapi/gateway-bff-staff.yaml')).toBe(true);
  });
});

describe('staff MSW mocks (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    // The seed is dated; the handlers read the clock, so the clock is pinned next to it (windows are relative to "now").
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-10T15:30:00Z'));
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
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

  it('Delivery dispatch › Pickup points: staff add an AfroTransact counter customers can choose at checkout', async () => {
    const api = client();
    const before = await api.listResource('pickup-locations', { page: 0, size: 50 });
    expect(before.rows.length).toBeGreaterThan(0);
    const created = await api.createRecord('pickup-locations', { name: 'Katy counter', kind: 'PLATFORM_COUNTER', line1: '1 Mason Rd', city: 'Katy', state: 'TX', postalCode: '77449', days: 'MON_FRI', opens: '09:00', closes: '17:00', readyHours: 4, active: true });
    expect(created.title).toBe('Katy counter');
    const after = await api.listResource('pickup-locations', { page: 0, size: 50, status: 'ACTIVE' });
    expect(after.rows.map((r) => r.id)).toContain(created.id);
  });

  it('serves every table with columns and server-side paging', async () => {
    const api = client();
    const keys: ResourceKey[] = ['users', 'roles', 'applications', 'orders', 'products', 'moderation', 'claims', 'returns', 'payouts', 'inventory', 'purchase-orders', 'routes',
      'support-cases', 'audit', 'markets', 'site-settings', 'legal-documents', 'announcements', 'placements', 'identity-providers', 'fee-schedules', 'tax-rates', 'coupons', 'pickup-locations'];
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

  it('runs the fee-schedule editor: versions, Retail rule, validation, create and cancel', async () => {
    expect(parsePercent('12.5')).toBe(1250);
    expect(formatBps(1250)).toBe('12.50%');
    const auditor = client('platform.auditor');
    const ro = await auditor.getFees();
    expect(ro.canConfigure).toBe(false);
    expect(ro.schedules.every((s) => !s.cancellable)).toBe(true);
    await expect(auditor.cancelFeeSchedule('fee-4', 'Not mine to cancel')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);

    const api = client('platform.finance');
    const fees = await api.getFees();
    expect(fees.schedules.map((s) => s.status)).toEqual(['SCHEDULED', 'CANCELLED', 'ACTIVE', 'SUPERSEDED']);
    expect(fees.current?.version).toBe(2);
    expect(fees.base?.version).toBe(4);
    expect(fees.immediateAllowed).toBe(false);
    expect(fees.retail.note).toBe('No commission, no fees (merchant of record)');
    const retail = await api.searchFeeOrgs('retail');
    expect(retail.items[0]).toMatchObject({ retail: true, selectable: false });

    const base = fees.base;
    if (!base) throw new Error('no base');
    const draft = {
      effectiveFrom: new Date(new Date(fees.minEffectiveFrom).getTime() + 86_400_000).toISOString(),
      immediate: false,
      defaultCommissionBps: base.defaultCommissionBps,
      categoryRates: base.categoryRates.map((c) => ({ category: c.category, commissionBps: c.category === 'fashion' ? 1250 : c.commissionBps })),
      overrides: [{ orgId: fees.retail.orgId, category: null, commissionBps: 0 }],
      cardPctBps: base.cardPctBps,
      cardFixedMinor: base.cardFixedMinor,
      fbaPerUnitMinor: base.fbaPerUnitMinor,
      reverseCommissionOnRefund: true,
      note: 'Fashion to 12.50 %'
    };
    await expect(api.createFeeSchedule(draft)).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && !!e.fieldErrors['overrides.0.orgId']);
    const created = await api.createFeeSchedule({ ...draft, overrides: [{ orgId: 'org-lagos', category: 'fashion', commissionBps: 1000 }] });
    expect(created.status).toBe('SCHEDULED');
    expect(created.categoryRates.find((c) => c.category === 'fashion')?.commissionBps).toBe(1250);
    expect(created.overrides[0]).toMatchObject({ orgName: 'Lagos Threads', category: 'fashion', commissionBps: 1000 });
    const cancelled = await api.cancelFeeSchedule(created.id, 'Created by mistake');
    expect(cancelled.status).toBe('CANCELLED');
    await expect(api.cancelFeeSchedule('fee-2', 'Already in force')).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
  });

  it('edits AfroTransact Delivery pricing: validation, carried over when omitted, live quote (S255)', async () => {
    const api = client('platform.finance');
    const fees = await api.getFees();
    const base = fees.base;
    const dp = base?.deliveryPricing;
    if (!base || !dp) throw new Error('no delivery pricing on the base version');
    expect(dp.customerCharge).toMatchObject({ mode: 'DISTANCE', baseMinor: 499, perMileMinor: 85, minMinor: 599, maxMinor: 2499 });
    expect(dp.sellerOverrides[0]).toMatchObject({ orgName: 'Kente Naturals' });
    expect(quoteTariff(dp.customerCharge, 7.4)).toBe(1128);
    const draft = {
      effectiveFrom: new Date(new Date(fees.minEffectiveFrom).getTime() + 86_400_000).toISOString(),
      immediate: false,
      defaultCommissionBps: base.defaultCommissionBps,
      categoryRates: base.categoryRates.map((c) => ({ category: c.category, commissionBps: c.commissionBps })),
      overrides: [],
      cardPctBps: base.cardPctBps,
      cardFixedMinor: base.cardFixedMinor,
      fbaPerUnitMinor: base.fbaPerUnitMinor,
      reverseCommissionOnRefund: true,
      note: 'Delivery radius to 30 miles'
    };
    const tooFar = { enabled: true, maxRadiusMiles: 250, customerCharge: dp.customerCharge, sellerFee: dp.sellerFee, sellerOverrides: [{ orgId: fees.retail.orgId, sellerFee: dp.sellerFee ?? dp.customerCharge ?? { mode: 'FLAT' as const, flatPerPackageMinor: 0, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null } }] };
    await expect(api.createFeeSchedule({ ...draft, deliveryPricing: tooFar })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422 && !!e.fieldErrors['deliveryPricing.maxRadiusMiles'] && !!e.fieldErrors['deliveryPricing.sellerOverrides.0.orgId']
    );
    const carried = await api.createFeeSchedule(draft);
    expect(carried.deliveryPricing).toEqual(dp);
    const wider = await api.createFeeSchedule({ ...draft, effectiveFrom: new Date(new Date(carried.effectiveFrom).getTime() + 86_400_000).toISOString(),
      deliveryPricing: { ...dp, maxRadiusMiles: 30, sellerOverrides: [] } });
    expect(wider.deliveryPricing).toMatchObject({ maxRadiusMiles: 30, sellerOverrides: [] });
    const quote = await api.quoteDelivery({ sellerOrgId: 'org-kente', distanceMiles: 7.4, packages: 2 });
    expect(quote).toMatchObject({ available: true, customerChargeMinor: 1128, sellerFeeMinor: 300, sellerFeeSource: 'OVERRIDE' });
    expect((await api.quoteDelivery({ sellerOrgId: 'org-ade', distanceMiles: 31 })).unavailableReason).toBe('BEYOND_MAX_RADIUS');
  });

  it('suggests the nearest drivers for a seller pickup and assigns with a reason (S255)', async () => {
    const support = client('platform.customer_support');
    const viewOnly = await support.listPickups();
    expect(viewOnly.items.length).toBeGreaterThan(0);
    const dispatcher = client('platform.dispatcher');
    const list = await dispatcher.listPickups();
    expect(list.canAssign).toBe(true);
    const pickup = list.items[0];
    if (!pickup) throw new Error('no pickup');
    const s = await dispatcher.getPickupSuggestions(pickup.shipmentId);
    expect(s.drivers).toHaveLength(5);
    expect(s.drivers[0]).toMatchObject({ driverId: 'drv-tunde', roadMiles: 2.6, distanceMethod: 'ROAD_OSRM' });
    expect(s.drivers[4]).toMatchObject({ roadMiles: null, distanceMethod: 'STRAIGHT_LINE' });
    const second = s.drivers[1];
    if (!second) throw new Error('no second driver');
    await expect(dispatcher.assignPickup(pickup.shipmentId, { routeId: second.routeId, reason: 'no' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && !!e.fieldErrors.reason);
    const res = await dispatcher.assignPickup(pickup.shipmentId, { routeId: second.routeId, reason: 'Closer driver is at capacity' });
    expect(res).toMatchObject({ routeId: second.routeId, driverId: second.driverId, status: 'ASSIGNED' });
    const history = await dispatcher.getPickupAssignments(pickup.shipmentId);
    expect(history.items[0]).toMatchObject({ mode: 'DISPATCH_OVERRIDE', reason: 'Closer driver is at capacity' });
    expect((await dispatcher.listPickups()).items.some((p) => p.shipmentId === pickup.shipmentId)).toBe(false);
    expect((await dispatcher.getShipFromLocation('loc-mamatee-1')).pickupEnabled).toBe(true);
  });

  it('serves notification templates: list, detail with sample, previews, versions and test-send (S268)', async () => {
    const support = client('platform.customer_support');
    const ro = await support.listNotificationTemplates();
    expect(ro.canEdit).toBe(false);
    expect(ro.items.map((t) => t.key)).toContain('order.confirmed');
    const oc = ro.items.find((t) => t.key === 'order.confirmed');
    expect(oc).toMatchObject({ channels: ['email', 'sms', 'push'], locales: ['en'], latestVersion: 2, updatedBy: 'system' });
    expect((await support.listNotificationTemplates('security')).items.map((t) => t.key)).toEqual(['identity.verification']);
    await expect(client('platform.auditor').listNotificationTemplates()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);

    const detail = await support.getNotificationTemplate('order.confirmed');
    expect(detail.variants.map((v) => `${v.channel}:${v.versions.map((x) => x.version).join(',')}`)).toEqual(['email:2,1', 'sms:2,1', 'push:2,1']);
    expect(detail.sample).toMatchObject({ source: 'event', area: 'orders', eventTypes: ['orders.order.placed'] });
    expect(detail.sample.data.total).toBe('$48.00');
    await expect(support.getNotificationTemplate('no.such')).rejects.toSatisfy((e) => isApiError(e) && e.status === 404);

    const email = await support.previewNotificationTemplate('order.confirmed', { channel: 'email', data: detail.sample.data });
    expect(email).toMatchObject({ subject: 'Order AT-2026-7KQ3MD is confirmed', version: 2, sms: null });
    expect(email.html).toContain('Thanks for your order, Ada!');
    const v1 = await support.previewNotificationTemplate('order.confirmed', { channel: 'email', version: 1, data: detail.sample.data });
    expect(v1.subject).toBe('Order AT-2026-7KQ3MD confirmed');
    const booking = await support.getNotificationTemplate('booking.confirmed');
    const sms = await support.previewNotificationTemplate('booking.confirmed', { channel: 'sms', data: booking.sample.data });
    expect(sms.sms).toMatchObject({ encoding: 'UCS-2', segments: 2 });
    await expect(support.previewNotificationTemplate('order.confirmed', { channel: 'email', data: {} })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422 && e.fieldErrors.subject === 'Not in the sample data: orderNumber'
    );
    const draft = await support.previewNotificationTemplate('order.confirmed', { channel: 'sms', data: detail.sample.data, draft: { bodyText: 'Order {{.orderNumber}}: {{.total}}' } });
    expect(draft).toMatchObject({ text: 'Order AT-2026-7KQ3MD: $48.00', version: 0, sms: { encoding: 'GSM-7', segments: 1 } });
    const po = await support.getNotificationTemplate('retail.purchase_order.sent');
    const poText = (await support.previewNotificationTemplate('retail.purchase_order.sent', { channel: 'email', data: po.sample.data })).text;
    expect(poText).toContain('Incoterm: FOB');
    expect(poText).not.toContain('Payment terms');

    await expect(support.createNotificationTemplateVersion('order.confirmed', { locale: 'en', channel: 'sms', category: 'transactional', bodyText: 'Hi' })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 403
    );
    const editor = client('platform.marketing');
    expect((await editor.listNotificationTemplates()).canEdit).toBe(true);
    await expect(
      editor.createNotificationTemplateVersion('order.confirmed', { locale: 'en', channel: 'sms', category: 'transactional', bodyText: 'Hi {{.nickname}}' })
    ).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && e.fieldErrors.bodyText === 'Not in the sample data: nickname');
    await expect(
      editor.createNotificationTemplateVersion('order.confirmed', { locale: 'en', channel: 'sms', category: 'transactional', bodyText: 'Order confirmed. '.repeat(30) })
    ).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && /4 segments/.test(e.fieldErrors.bodyText ?? ''));
    const created = await editor.createNotificationTemplateVersion('order.confirmed', {
      locale: 'en', channel: 'sms', category: 'transactional', bodyText: 'AfroTransact: order {{.orderNumber}} ({{.total}}) is confirmed. Reply STOP to opt out.'
    });
    expect(created).toMatchObject({ version: 3, channel: 'sms', createdBy: 'stf-1' });
    expect((await editor.getNotificationTemplate('order.confirmed')).variants.find((v) => v.channel === 'sms')?.latestVersion).toBe(3);

    expect(await editor.testSendNotificationTemplate('order.confirmed', { channel: 'email', data: detail.sample.data })).toMatchObject({ to: 'ifeoma@afrotransact.com', channel: 'email' });
    await expect(editor.testSendNotificationTemplate('order.confirmed', { channel: 'push', data: {} })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && !!e.fieldErrors.channel);
    await expect(support.testSendNotificationTemplate('order.confirmed', { channel: 'email', data: detail.sample.data })).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('runs Plans & billing: catalogue versions, campaigns, rule preview, audited idempotent adjustments (S292–S297)', async () => {
    const finance = client('platform.finance').plans;
    expect(moduleKeys(await client('platform.finance').getSession())).toContain('plans');
    const auditorSession = await client('platform.auditor').getSession();
    expect(auditorSession.navGroups.flatMap((g) => g.modules).find((m) => m.key === 'plans')?.level).toBe('VIEW');
    expect(moduleKeys(await client('platform.marketing').getSession())).not.toContain('plans');

    const plans = await finance.plans();
    expect(plans.map((p) => p.code)).toEqual(['seller_essentials', 'seller_growth', 'seller_pro', 'provider_solo', 'provider_studio', 'provider_business']);
    const growth = plans[1];
    if (!growth) throw new Error('seeded');
    await expect(finance.updatePlan('seller_growth', { name: 'Growth', features: [], visibility: 'PUBLIC' }, growth.rowVersion + 1)).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    const draft = await finance.saveDraft('seller_growth', { monthlyCents: 4500, quarterlyCents: 12800, annualCents: 45000, trialDays: 30 });
    expect(draft.versions[0]).toMatchObject({ version: 2, status: 'DRAFT' });
    const published = await finance.publish('seller_growth');
    expect(published).toMatchObject({ currentVersion: 2, monthlyCents: 4500 });
    expect(published.versions.find((v) => v.version === 1)?.status).toBe('SUPERSEDED');
    expect((await finance.migrationPreview('seller_growth')).rows[0]).toMatchObject({ fromCents: 3900, toCents: 4500 });
    expect(plans.find((x) => x.code === 'seller_growth')?.entitlements).toMatchObject({ listings: 1000, aiListingWriter: true, lolaTier: 'GROWTH' });

    await expect(finance.createCampaign({ name: 'No code', trialDays: 30, eligiblePlans: [] })).rejects.toSatisfy((e) => isApiError(e) && !!e.fieldErrors.signupFrom);
    expect(await finance.createCampaign({ name: 'Pro 45', promoCode: 'pro45', trialDays: 45, eligiblePlans: ['seller_pro'] })).toMatchObject({ promoCode: 'PRO45', status: 'ACTIVE' });

    const rule = { name: 'Ten orders', metric: 'COMPLETED_ORDERS' as const, threshold: 10, rewardType: 'CREDIT' as const, creditCents: 2500, eligiblePlans: [] };
    const preview = await finance.previewRule(rule);
    expect(preview).toMatchObject({ evaluated: 4, qualifying: 2, pending: 0 });
    expect((await finance.createRule(rule)).rewardLabel).toBe('$25 credit');

    // S299: low-sales relief - decided once at window end, so sellers in their window are pending (with their sales so far).
    const relief = { name: 'Low-sales relief', kind: 'SELLER' as const, metric: 'GROSS_SALES' as const, threshold: 100000, comparison: 'LESS_THAN' as const,
      windowAnchor: 'SUBSCRIPTION_START' as const, withinDaysOfApproval: 30, rewardType: 'FREE_DAYS' as const, freeDays: 30, eligiblePlans: ['seller_growth'] };
    const reliefPreview = await finance.previewRule(relief);
    expect(reliefPreview).toMatchObject({ qualifying: 0, pending: 1 });
    expect(reliefPreview.rows[0]).toMatchObject({ orgId: 'org-mama-put', status: 'PENDING', value: 32000 });
    expect(reliefPreview.rows[0]?.checkAt).toBeTruthy();
    const saved = await finance.createRule(relief);
    expect(saved).toMatchObject({ comparison: 'LESS_THAN', windowAnchor: 'SUBSCRIPTION_START', checkAtWindowEnd: true, checkLeadHours: 24,
      conditionLabel: 'less than $1,000 in sales in the first 30 days of the subscription' });
    await expect(finance.createRule({ ...relief, withinDaysOfApproval: null })).rejects.toSatisfy((e) => isApiError(e) && !!e.fieldErrors.withinDaysOfApproval);

    const org = (await finance.searchOrgs('ade')).items[0]?.orgId ?? '';
    await expect(finance.credit(org, 2500, { reasonCode: 'OTHER' }, 'k-1')).rejects.toSatisfy((e) => isApiError(e) && !!e.fieldErrors.note);
    const credit = await finance.credit(org, 2500, { reasonCode: 'GOODWILL' }, 'k-2');
    expect(await finance.credit(org, 2500, { reasonCode: 'GOODWILL' }, 'k-2')).toMatchObject({ id: credit.id });
    const panel = await finance.panel(org);
    expect(panel.billing?.creditBalanceCents).toBe(2500);
    const open = panel.invoices.find((i) => i.status === 'open');
    if (!open) throw new Error('seeded open invoice');
    expect(await finance.waiveInvoice(org, open.id, { reasonCode: 'BILLING_ERROR' }, 'k-3')).toMatchObject({ type: 'WAIVE_INVOICE' });
    expect((await finance.grants({ orgId: org, type: 'CREDIT', page: 0, size: 25 })).total).toBe(1);

    // How the seller pays its plan (S298): a staff override needs a reason; the default for new sellers.
    expect(panel.planFeeCollection).toMatchObject({ mode: 'EARNINGS_FIRST', source: 'DEFAULT', available: true });
    expect(panel.invoices.find((i) => i.paidFromSalesCents > 0)).toMatchObject({ paidFromSalesCents: 2500, paidByCardCents: 1400 });
    expect(panel.collections[0]).toMatchObject({ status: 'CREDITED', withheldCents: 2500 });
    await expect(finance.overridePlanFeeCollection(org, 'CARD', { reasonCode: '' })).rejects.toSatisfy((e) => isApiError(e) && !!e.fieldErrors.reasonCode);
    const overridden = await finance.overridePlanFeeCollection(org, 'CARD', { reasonCode: 'SELLER_REQUEST', note: 'Books plan fees on the card' });
    expect(overridden).toMatchObject({ mode: 'CARD', source: 'STAFF' });
    expect(overridden.history[0]).toMatchObject({ previous: 'EARNINGS_FIRST', reasonCode: 'SELLER_REQUEST', note: 'Books plan fees on the card' });
    expect(await finance.billingSettings()).toMatchObject({ earningsCollectionEnabled: true, planFeeCollectionDefault: 'CARD' });
    expect(await finance.updateBillingSettings('EARNINGS_FIRST')).toMatchObject({ planFeeCollectionDefault: 'EARNINGS_FIRST' });

    const auditor = client('platform.auditor').plans;
    await expect(auditor.panel(org)).resolves.toMatchObject({ orgId: org });
    await expect(auditor.billingSettings()).resolves.toMatchObject({ planFeeCollectionDefault: 'EARNINGS_FIRST' });
    await expect(auditor.updateBillingSettings('CARD')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(auditor.overridePlanFeeCollection(org, 'EARNINGS_FIRST', { reasonCode: 'GOODWILL' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(auditor.freeDays(org, 5, { reasonCode: 'GOODWILL' }, 'k-4')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(auditor.publish('seller_pro')).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });
});
