import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  type BookingTakeHome,
  createSellerApiClient,
  createStaffApiClient,
  type FeePlan,
  type FeeRateQuote,
  isApiError,
  type NegotiatedPlan,
  type NegotiatedRate,
  type NegotiatedRatePage,
  type SellerRateCard,
  staffFeeRateKeys
} from '../src';
import { type components as Seller } from '../src/generated/gateway-bff-seller';
import { type components as Staff } from '../src/generated/gateway-bff-staff';
import { PROVIDER_ORG, resetSellerDb } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

const staff = (roles: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });
const seller = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: {}, fetch: (req) => handleSellerMockRequest(req) });

describe('fee-rate schemas stay aligned with the BFF contracts', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<NegotiatedRate>().toExtend<Staff['schemas']['NegotiatedRate']>();
    expectTypeOf<NegotiatedRatePage>().toExtend<Staff['schemas']['NegotiatedRatePage']>();
    expectTypeOf<FeeRateQuote>().toExtend<Staff['schemas']['FeeRateQuote']>();
    expectTypeOf<NegotiatedPlan>().toExtend<Staff['schemas']['NegotiatedPlan']>();
    expectTypeOf<FeePlan>().toExtend<Staff['schemas']['FeePlan']>();
    expectTypeOf<SellerRateCard>().toExtend<Seller['schemas']['SellerRateCard']>();
    expectTypeOf<BookingTakeHome>().toExtend<Seller['schemas']['BookingTakeHome']>();
  });

  it('keys the queries by filter so tables refetch per page', () => {
    expect(staffFeeRateKeys.negotiated({ page: 0, size: 25 })).toEqual(['staff', 'fee-rates', 'negotiated', { page: 0, size: 25 }]);
    expect(staffFeeRateKeys.calculator('org-1', 'groceries', 'PRODUCT')).toEqual(['staff', 'fee-rates', 'calculator', 'org-1', 'groceries', 'PRODUCT']);
  });
});

describe('staff mocks: plan rates, connection fees, negotiated rates and the calculator', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('seeds the advertised plan rates and checks them against the feature text', async () => {
    const fees = await staff('platform.finance').getFees();
    expect(fees.planWarnings).toEqual([]);
    expect(fees.plans.find((p) => p.code === 'seller_growth')).toMatchObject({ advertised: { minBps: 800, maxBps: 1200 }, configured: { minBps: 800, maxBps: 1200 } });
    expect(fees.current?.connectionFee?.planRates.find((r) => r.planCode === 'provider_studio')?.bps).toBe(800);
    expect(fees.current?.planRates.find((r) => r.planCode === 'seller_essentials')).toMatchObject({ category: '*', bps: 1500, label: 'Other categories' });
  });

  it('negotiates, lists, overlaps, ends and calculates', async () => {
    const api = staff('platform.finance').feeRates;
    const page = await api.negotiatedRates({ page: 0, size: 25 });
    expect(page.items.map((r) => r.status)).toContain('EXPIRED');
    expect(page.canConfigure).toBe(true);
    await expect(api.createNegotiatedRate({ orgId: 'org-lagos', type: 'COMMISSION', category: 'fashion', rateBps: 900, startsAt: null, endsAt: null, reason: '', negotiatedBy: null, reference: null }))
      .rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    const created = await api.createNegotiatedRate({ orgId: 'org-lagos', type: 'COMMISSION', category: 'Fashion', rateBps: 900, startsAt: null, endsAt: null,
      reason: 'Exclusive fashion line', negotiatedBy: 'Ada', reference: 'SF-9' });
    expect(created).toMatchObject({ category: 'fashion', status: 'ACTIVE', orgName: 'Lagos Threads', canEnd: true });
    await expect(api.createNegotiatedRate({ orgId: 'org-lagos', type: 'COMMISSION', category: 'fashion', rateBps: 800, startsAt: null, endsAt: null, reason: 'Again', negotiatedBy: null, reference: null }))
      .rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    expect(await api.calculate('org-lagos', 'fashion', 'PRODUCT')).toMatchObject({ bps: 900, source: 'OVERRIDE' });
    expect(await api.calculate('org-lagos', 'groceries', 'PRODUCT')).toMatchObject({ bps: 600, source: 'PLAN', planName: 'Seller Pro', explanation: 'Plan rate: Seller Pro' });
    expect(await api.calculate('org-braids', 'hair', 'SERVICE')).toMatchObject({ bps: 500, source: 'OVERRIDE', exampleFeeMinor: 500 });
    const ended = await api.endNegotiatedRate(created.id, 'Line discontinued');
    expect(ended).toMatchObject({ status: 'ENDED', endReason: 'Line discontinued', canEnd: false });
    expect(ended.audit.map((a) => a.action)).toEqual(['CREATED', 'ENDED']);
    expect(await api.calculate('org-lagos', 'fashion', 'PRODUCT')).toMatchObject({ bps: 1000, source: 'PLAN' });
    await expect(staff('platform.auditor').feeRates.createNegotiatedRate({ orgId: 'org-lagos', type: 'COMMISSION', category: null, rateBps: 1, startsAt: null, endsAt: null,
      reason: 'Not allowed', negotiatedBy: null, reference: null })).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('creates a private plan at a negotiated price and invites the organisation', async () => {
    const plan = await staff('platform.finance').feeRates.negotiatePlan('org-kente', { basePlanCode: 'seller_growth', monthlyCents: 2900, quarterlyCents: null, annualCents: null,
      reason: 'Two-year agreement', reference: 'SF-77' });
    expect(plan).toMatchObject({ planCode: 'seller_growth_org_kente', visibility: 'INVITE_ONLY', annualCents: 29000, quarterlyCents: 8265 });
  });
});

describe('seller mocks: plan, negotiated rates and connection fees', () => {
  beforeEach(() => {
    resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('shows the plan, the negotiated rate until when, and the provider take-home', async () => {
    const api = seller();
    const fees = await api.getFees();
    expect(fees.planName).toBe('Seller Growth');
    expect(fees.current.categories.find((c) => c.source === 'OVERRIDE')?.negotiatedUntil).toBe('2026-12-31T23:59:59Z');
    await api.setActiveOrg(PROVIDER_ORG);
    const requests = await api.listBookingRequests();
    const first = requests[0];
    expect(first?.takeHome?.connectionFeeBps).toBe(700);
    expect(first ? first.takeHome?.keepMinor : 0).toBe((first?.priceMinor ?? 0) - (first?.takeHome?.connectionFeeMinor ?? 0) - (first?.takeHome?.cardFeesMinor ?? 0));
    const earnings = await api.getEarnings(undefined, undefined);
    expect(earnings.connectionFee?.planName).toBe('Provider Studio');
  });
});
