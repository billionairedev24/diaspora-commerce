import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  type Availability,
  type BillingView,
  type BookingCard,
  type CalendarView,
  type CancellationPolicy,
  type ComplianceSummary,
  createSellerApiClient,
  type Dashboard,
  type Earnings,
  type InventoryView,
  isApiError,
  type LabelPurchase,
  type ListingPage,
  type ListingRow,
  type OnboardingStatus,
  type PayoutsView,
  type ProductTypeSchema,
  type PublicPage,
  type RateQuotes,
  type SellerFeesView,
  type SellerOrderDetail,
  type SellerOrderPage,
  type SellerSession,
  type ServiceOffer,
  type TeamView
} from '../src';
import { type components } from '../src/generated/gateway-bff-seller';
import { MOCK_ROLE_COOKIE, PENDING_ORG, PROVIDER_ORG, resetSellerDb, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

type S = components['schemas'];

const client = (cookie?: string) =>
  createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: cookie ? { cookie } : {}, fetch: (req) => handleSellerMockRequest(req) });

const keysOf = (s: SellerSession) => s.navGroups.flatMap((g) => g.modules.map((m) => m.key));

describe('Seller Zod schemas stay aligned with contracts/openapi/gateway-bff-seller.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<SellerSession>().toExtend<S['SellerSession']>();
    expectTypeOf<ComplianceSummary>().toExtend<S['ComplianceSummary']>();
    expectTypeOf<OnboardingStatus>().toExtend<S['OnboardingStatus']>();
    expectTypeOf<Dashboard>().toExtend<S['Dashboard']>();
    expectTypeOf<SellerOrderPage>().toExtend<S['SellerOrderPage']>();
    expectTypeOf<SellerOrderDetail>().toExtend<S['SellerOrderDetail']>();
    expectTypeOf<RateQuotes>().toExtend<S['RateQuotes']>();
    expectTypeOf<LabelPurchase>().toExtend<S['LabelPurchase']>();
    expectTypeOf<ListingPage>().toExtend<S['ListingPage']>();
    expectTypeOf<ListingRow>().toExtend<S['ListingRow']>();
    expectTypeOf<ProductTypeSchema>().toExtend<S['ProductTypeSchema']>();
    expectTypeOf<InventoryView>().toExtend<S['InventoryView']>();
    expectTypeOf<PayoutsView>().toExtend<S['PayoutsView']>();
    expectTypeOf<SellerFeesView>().toExtend<S['SellerFeesView']>();
    expectTypeOf<BillingView>().toExtend<S['BillingView']>();
    expectTypeOf<TeamView>().toExtend<S['TeamView']>();
    expectTypeOf<CalendarView>().toExtend<S['CalendarView']>();
    expectTypeOf<BookingCard>().toExtend<S['BookingCard']>();
    expectTypeOf<Availability>().toExtend<S['Availability']>();
    expectTypeOf<ServiceOffer>().toExtend<S['ServiceOffer']>();
    expectTypeOf<CancellationPolicy>().toExtend<S['CancellationPolicy']>();
    expectTypeOf<Earnings>().toExtend<S['Earnings']>();
    expectTypeOf<PublicPage>().toExtend<S['PublicPage']>();
  });

  it('generated file carries the do-not-edit banner', () => {
    const text = readFileSync(resolve(__dirname, '../src/generated/gateway-bff-seller.ts'), 'utf8');
    expect(text.startsWith('// GENERATED from contracts/openapi/gateway-bff-seller.yaml')).toBe(true);
  });
});

describe('seller MSW mocks (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  });

  it('serves every Seller Center screen and keeps navigation role-aware', async () => {
    const api = client();
    const session = await api.getSession();
    expect(session.portal).toBe('SELLER_CENTER');
    expect(keysOf(session)).toEqual(['home', 'orders', 'listings', 'inventory', 'storefront', 'payouts', 'fees', 'billing', 'account-health', 'team']);
    expect(session.navGroups.map((g) => g.label)).toEqual(['Overview', 'Commerce', 'Growth', 'Finance', 'Trust & compliance', 'Settings']);
    await expect(api.getCompliance()).resolves.toMatchObject({ showBanner: true, tone: 'warning', openItems: 2 });
    const dash = await api.getDashboard(30);
    expect(dash.charts.map((c) => c.id)).toEqual(['sales', 'top-products', 'fulfilment', 'stock']);
    const orders = await api.listOrders({ limit: 10 });
    expect(orders.items).toHaveLength(10);
    expect(orders.nextCursor).toBe('10');
    await api.listListings({ page: 0, size: 25 });
    await api.getInventory(false);
    await api.getPayouts();
    const fees = await api.getFees();
    expect(fees.current.categories.find((c) => c.source === 'OVERRIDE')?.category).toBe('groceries');
    expect(fees.upcoming?.fbaPerUnitMinor).toBe(350);
    await api.getBilling();
    const team = await api.getTeam();
    expect(team.roles).toHaveLength(7);
    await api.getPublicPage();
    await api.getCategoryTree();

    const fulfil = client(`${MOCK_ROLE_COOKIE}=seller.fulfilment`);
    const keys = keysOf(await fulfil.getSession());
    expect(keys).toContain('orders');
    expect(keys).not.toContain('billing');
    await expect(fulfil.getBilling()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('lets sellers only confirm, pack and mark ready, and buys a label once per key', async () => {
    const api = client();
    const row = (await api.listOrders({ status: 'PLACED', limit: 1 })).items[0];
    if (!row) throw new Error('fixture has a placed order');
    await expect(api.updateShipmentStatus(row.orderNumber, row.shipmentId, 'PACKED')).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    for (const st of ['CONFIRMED', 'PACKED', 'READY'] as const) {
      const o = await api.updateShipmentStatus(row.orderNumber, row.shipmentId, st);
      expect(o.shipments[0]?.status).toBe(st);
    }
    const from = { name: 'Ade’s Kitchen', phone: null, line1: '1 Bellaire Blvd', line2: null, city: 'Houston', state: 'TX', postalCode: '77036', country: 'US' };
    const rates = await api.quoteRates(row.orderNumber, row.shipmentId, { weightLb: 3 }, from);
    const q = rates.quotes[0];
    if (!q) throw new Error('quotes');
    const label = await api.buyLabel(row.orderNumber, row.shipmentId, { carrier: q.carrier, service: q.service, parcel: { weightLb: 3 }, shipFrom: from }, 'key-123456789');
    expect(label.order.shipments[0]?.canBuyLabel).toBe(false);
    const again = await api.buyLabel(row.orderNumber, row.shipmentId, { carrier: q.carrier, service: q.service, parcel: { weightLb: 3 }, shipFrom: from }, 'key-123456789');
    expect(again.trackingNumber).toBe(label.trackingNumber);
  });

  it('submits a product for review or pending category approval', async () => {
    const api = client();
    const base = {
      title: 'Ankara Wrap Dress with Puff Sleeves', attributes: { brand: 'Lagos Textile Hub' }, images: [{ url: 'https://img.afrotransact.dev/a.webp', contentType: 'image/webp' }],
      variationTheme: 'Size', variants: [{ options: { Size: 'M' }, sellerSku: 'ANK-M', gtinExempt: true, priceMinor: 3900, quantity: 10 }]
    };
    await expect(api.submitProduct({ ...base, categoryId: 'pt-dresses' })).resolves.toMatchObject({ status: 'IN_REVIEW' });
    const req = await api.requestCategory({ parentPath: 'Fashion › Women', name: 'Bridal aso-ebi sets', exampleProduct: '6-piece set', detailsNeeded: 'Pieces, colour' });
    await expect(api.submitProduct({ ...base, categoryRequestId: req.id })).resolves.toMatchObject({ status: 'PENDING_CATEGORY_APPROVAL' });
  });

  it('switches organisations: Provider Hub tools and onboarding-only before approval', async () => {
    const api = client();
    const provider = await api.setActiveOrg(PROVIDER_ORG);
    expect(provider.portal).toBe('PROVIDER_HUB');
    expect(keysOf(provider)).toContain('calendar');
    expect(keysOf(provider)).not.toContain('orders');
    const cal = await api.getCalendar('2026-10-05', '2026-10-18');
    expect(cal.days).toHaveLength(14);
    const reqs = await api.listBookingRequests();
    const first = reqs[0];
    if (!first) throw new Error('fixture has requests');
    await expect(api.actOnBooking(first.id, { action: 'DECLINE' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    await expect(api.actOnBooking(first.id, { action: 'CONFIRM' })).resolves.toMatchObject({ status: 'CONFIRMED' });
    const earnings = await api.getEarnings(undefined, undefined);
    const t = earnings.totals;
    expect(t.netMinor).toBe(t.collectedMinor - t.commissionMinor - t.cardFeesMinor - t.lateCancelFeesMinor);
    await expect(api.saveCancellationPolicy({ kind: 'CUSTOM', freeHours: 24, partialRefund: true, partialFromHours: 48, partialPercent: 50 })).rejects.toSatisfy(
      (e) => isApiError(e) && e.status === 422
    );
    await expect(api.getCancellationPolicy()).resolves.toMatchObject({ kind: 'MODERATE', label: 'Moderate' });

    const pending = await api.setActiveOrg(PENDING_ORG);
    expect(keysOf(pending)).toEqual(['onboarding']);
    await expect(api.listListings({ page: 0, size: 25 })).rejects.toSatisfy((e) => isApiError(e) && e.code === 'NOT_APPROVED');
    await expect(api.getOnboarding()).resolves.toMatchObject({ status: 'INFO_REQUESTED', canResume: true });
    await api.setActiveOrg(SELLER_ORG);
  });
});
