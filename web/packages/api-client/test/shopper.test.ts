import { afterAll, beforeAll, beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  type Address,
  ApiError,
  type BookingCreated,
  type CheckoutSummary,
  createApiClient,
  type CustomerBooking,
  type HelpRequest,
  type HelpRequestPage,
  type LolaEvent,
  type OrderConfirmation,
  type OrderDetail,
  type PaymentMethod,
  type ProductSummary,
  type ProviderPage,
  readSse,
  type SearchPage,
  SearchQuerySchema,
  type SecurityOverview,
  type ServiceDetail,
  type ServiceSearch,
  toLolaEvent,
  uploadHelpEvidence
} from '../src';
import { type components } from '../src/generated/gateway-bff';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

type S = components['schemas'];

function browserLikeClient() {
  let cookie = '';
  const opts = {
    baseUrl: 'http://bff.test',
    fetch: async (req: Request) => {
      if (cookie) req.headers.set('cookie', cookie);
      const res = await fetch(req);
      const set = res.headers.get('set-cookie');
      if (set) cookie = set.split(';')[0] ?? '';
      return res;
    }
  };
  return { api: createApiClient(opts), fetch: opts.fetch };
}

/** Hosted sign-in (the mock BFF signs the demo shopper in at the login start, like identity's page would). */
async function signedIn() {
  const { api, fetch: fetchLike } = browserLikeClient();
  const res = await fetchLike(new Request(api.loginUrl('/account'), { redirect: 'manual' }));
  if (res.status !== 302) throw new Error('expected the hosted sign-in redirect');
  return api;
}

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('shopper schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<Address>().toExtend<S['Address']>();
    expectTypeOf<PaymentMethod>().toExtend<S['PaymentMethod']>();
    expectTypeOf<OrderDetail>().toExtend<S['OrderDetail']>();
    expectTypeOf<HelpRequest>().toExtend<S['HelpRequest']>();
    expectTypeOf<HelpRequestPage>().toExtend<S['HelpRequestPage']>();
    expectTypeOf<SecurityOverview>().toExtend<S['SecurityOverview']>();
    expectTypeOf<CheckoutSummary['packages'][number]['deliveryOptions'][number]>().toExtend<S['DeliveryOption']>();
    expectTypeOf<OrderConfirmation['status']>().toExtend<S['OrderConfirmation']['status']>();
    expectTypeOf<ServiceSearch>().toExtend<S['ServiceSearch']>();
    expectTypeOf<ServiceDetail>().toExtend<S['ServiceDetail']>();
    expectTypeOf<ProviderPage>().toExtend<S['ProviderPage']>();
    expectTypeOf<CustomerBooking>().toExtend<S['CustomerBooking']>();
    expectTypeOf<BookingCreated>().toExtend<S['BookingCreated']>();
    expectTypeOf<NonNullable<ProductSummary['sponsor']>>().toExtend<NonNullable<S['ProductSummary']['sponsor']>>();
    expectTypeOf<SearchPage['searchId']>().toExtend<S['SearchPage']['searchId']>();
  });
});

describe('Lola SSE parsing', () => {
  it('decodes events split across chunks, CRLF and multi-line data', async () => {
    const enc = new TextEncoder();
    const chunks = ['event: tok', 'en\r\ndata: {"text":"Hel', 'lo"}\r\n\r\n: keep-alive\n\nevent:done\ndata:{"sessionId":"lola_12345678",\ndata:"message":"Hello"}\n\n'];
    const body = new ReadableStream<Uint8Array>({ start(c) { chunks.forEach((x) => c.enqueue(enc.encode(x))); c.close(); } });
    const events: LolaEvent[] = [];
    for await (const m of readSse(body)) {
      const e = toLolaEvent(m);
      if (e) events.push(e);
    }
    expect(events.map((e) => e.type)).toEqual(['token', 'done']);
    expect(events[0]?.type === 'token' && events[0].data.text).toBe('Hello');
    expect(events[1]?.type === 'done' && events[1].data.suggestions).toEqual([]);
  });
});

describe('mock BFF — account, checkout, help requests, bookings, Lola', () => {
  it('places an order with a saved card, confirms the payment and cancels a shipment', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    const sum = await api.getCheckoutSummary();
    expect(sum.publishableKey).toBe('pk_test_mock');
    const pkg = sum.packages[0];
    expect(pkg?.deliveryOptions.map((o) => o.key)).toEqual(expect.arrayContaining(['carrier', 'afrotransact_delivery', 'pickup']));
    expect(pkg?.pickupPoints?.[0]?.hours).toBeTruthy();
    expect(pkg?.deliveryOptions[0]?.earliestDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const conf = await api.placeOrder(
      { addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: pkg?.packageId ?? '', option: 'afrotransact_delivery', preferences: { handover: 'hand' } }] },
      'checkout-test-key-0001'
    );
    expect(conf.status).toBe('awaiting_payment');
    expect(conf.paymentClientSecret).toContain('_secret_');
    expect(conf.shipments[0]?.deliveryCode).toMatch(/^\d{4}$/);
    const again = await api.placeOrder({ addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [] }, 'checkout-test-key-0001');
    expect(again.orderNumber).toBe(conf.orderNumber); // idempotent replay
    const placed = await api.confirmCheckoutPayment(conf.orderNumber);
    expect(placed.status).toBe('placed');
    expect((await api.getCart()).itemCount).toBe(0);
    const order = await api.getOrder(conf.orderNumber);
    expect(order.shipments[0]?.cancellable).toBe(true);
    const cancelled = await api.cancelShipment(conf.orderNumber, order.shipments[0]?.shipmentId ?? '');
    expect(cancelled.status).toBe('cancelled');
  });

  it('prices a platform coupon on checkout summary and lowers the total (mock WELCOME10)', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    const base = await api.getCheckoutSummary('ad1');
    const withCode = await api.getCheckoutSummary('ad1', undefined, 'WELCOME10');
    expect(withCode.coupon).toMatchObject({ code: 'WELCOME10', applicable: true, funding: 'platform' });
    expect(withCode.discountMinor).toBeGreaterThan(0);
    expect(withCode.totalMinor).toBeLessThan(base.totalMinor);
    expect(withCode.coupon?.sellerShares?.[0]?.funding).toBe('platform');
  });

  it('quotes AfroTransact Delivery from the seller’s store per address and refuses an out-of-range one (S255)', async () => {
    const api = await signedIn();
    const product = await api.getProduct('AT00000P23');
    expect(product.buyBox).toMatchObject({ fulfilment: 'AFROTRANSACT_DELIVERY', seller: { name: "Ama's Market", city: 'Houston, TX' } });
    const cart = await api.addToCart({ offerId: product.buyBox?.offerId ?? '', quantity: 1 });
    const pkg = cart.packages.find((p) => p.fulfilment === 'AFROTRANSACT_DELIVERY');
    expect(pkg?.heading).toBe('AfroTransact Delivery from Houston, TX');
    expect(pkg?.freeShippingNudge).toBeNull();
    const near = await api.getCheckoutSummary('ad1');
    const local = near.packages[0];
    expect(local?.localDelivery).toMatchObject({ available: true, chargeMinor: 1128, roadMiles: 7.4, fromCity: 'Houston, TX', unavailableReason: null });
    expect(local?.deliveryOptions).toHaveLength(1);
    expect(near.shippingMinor).toBe(1128);
    const far = await api.getCheckoutSummary('ad2');
    expect(far.packages[0]?.localDelivery).toMatchObject({ available: false, chargeMinor: null, unavailableReason: 'OUT_OF_RANGE' });
    expect(far.packages[0]?.localDelivery?.message).toContain('too far for AfroTransact Delivery');
    expect(far.shippingMinor).toBe(0);
    const selection = [{ packageId: local?.packageId ?? '', option: 'afrotransact_delivery' as const }];
    await expect(api.placeOrder({ addressId: 'ad2', paymentMethodId: 'pm_visa4242', deliverySelections: selection }, 'checkout-local-key-0001')).rejects.toSatisfy(
      (e) => e instanceof ApiError && e.status === 422 && e.code === 'DELIVERY_OUT_OF_RANGE' && !!e.fieldErrors.deliverySelections
    );
    const conf = await api.placeOrder({ addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: selection }, 'checkout-local-key-0002');
    await api.confirmCheckoutPayment(conf.orderNumber);
    const order = await api.getOrder(conf.orderNumber);
    expect(order.shipments[0]?.localDelivery).toEqual({ roadMiles: 7.4, durationMinutes: 18, chargeMinor: 1128 });
  });

  it('reports a problem with photos, replays an Idempotency-Key and keeps requests private to the account', async () => {
    const api = await signedIn();
    expect((await api.getOrder('AT-2026-104221')).shipments[0]?.returnable).toBe(true);
    const progress: number[] = [];
    const photo = new Blob(['jpeg-bytes'], { type: 'image/jpeg' }) as Blob & { name: string };
    Object.defineProperty(photo, 'name', { value: 'cracked-jar.jpg' });
    const put = async (_url: string, _init: unknown, onProgress: (f: number) => void) => {
      onProgress(0.5);
      return { ok: true, status: 200 };
    };
    const asset = await uploadHelpEvidence(api, photo, { onProgress: (f) => progress.push(f), put });
    expect(asset.status).toBe('READY');
    expect(progress).toEqual([0.5, 1]);
    await expect(api.requestHelpEvidenceUpload({ fileName: 'a.pdf', contentType: 'application/pdf', size: 10 })).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 422);
    const body = { orderNumber: 'AT-2026-104221', type: 'DAMAGED_ITEM' as const, description: 'The jar arrived cracked and leaking.', evidenceAssetIds: [asset.assetId] };
    await expect(api.createHelpRequest({ ...body, evidenceAssetIds: ['not-mine'] }, 'help-key-000000001')).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 422);
    await expect(api.createHelpRequest({ ...body, orderNumber: 'AT-2026-NOPE' }, 'help-key-000000001')).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 404);
    const created = await api.createHelpRequest(body, 'help-key-000000001');
    expect(created).toMatchObject({ status: 'RECEIVED', evidenceCount: 1, orderNumber: 'AT-2026-104221', resolution: null });
    expect((await api.createHelpRequest(body, 'help-key-000000001')).id).toBe(created.id);
    const page = await api.getHelpRequests();
    expect(page.items.map((r) => r.id)).toEqual(expect.arrayContaining([created.id, 'help-9001']));
    expect((await api.getHelpRequest('help-9001')).resolution).toMatchObject({ outcome: 'REFUNDED', refundMinor: 1899 });
    await expect(api.getHelpRequest('help-nope')).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 404);
  });

  it('books a service with a deposit, then cancels with the quoted refund', async () => {
    const api = await signedIn();
    const search = await api.searchServices({ q: 'braids', zip: '77042' });
    const svc = search.services[0];
    expect(svc?.providerName).toBe('Braids by Ama');
    const days = await api.getServiceDays(svc?.serviceId ?? '', { days: 14 });
    const day = days.find((d) => d.slots > 0);
    const slots = await api.getServiceSlots(svc?.serviceId ?? '', { date: day?.date ?? '' });
    const created = await api.bookService({ serviceId: svc?.serviceId ?? '', startAt: slots[0]?.start ?? '', mode: 'IN_PERSON' });
    expect(created.payment?.clientSecret).toContain('_secret_');
    expect(created.booking.awaitingPayment).toBe(true);
    const synced = await api.syncBookingPayment(created.booking.id);
    expect(synced.status).toBe('REQUESTED');
    const quote = await api.getBookingCancellationQuote(created.booking.id);
    const res = await api.cancelBooking(created.booking.id);
    expect(res.applied.refundMinor).toBe(quote.refundMinor);
    expect(res.booking.canCancel).toBe(false);
    const page = await api.getProviderPage(svc?.orgId ?? '');
    expect(page.services.length).toBeGreaterThan(0);
  });

  it('streams a Lola turn with a confirmable add-to-cart proposal and suggestion chips', async () => {
    const api = await signedIn();
    const events: LolaEvent[] = [];
    await api.chatWithLola({ message: 'jollof seasoning' }, (e) => events.push(e));
    const types = events.map((e) => e.type);
    expect(types[0]).toBe('tool_call');
    expect(types).toContain('action_proposal');
    expect(types.filter((t) => t === 'token').length).toBeGreaterThan(3);
    const done = events.at(-1);
    expect(done?.type).toBe('done');
    if (done?.type === 'done') expect(done.data.suggestions.length).toBeGreaterThan(0);
    const guest = browserLikeClient().api;
    await expect(guest.chatWithLola({ message: 'hi' }, () => undefined)).rejects.toMatchObject({ status: 401 });
  });

  it('manages login & security', async () => {
    const api = await signedIn();
    const sec = await api.getSecurity();
    expect(sec.mfa.totp).toBe(true);
    await expect(api.changePassword({ currentPassword: 'nope', newPassword: 'A-much-longer-passw0rd' })).rejects.toMatchObject({ status: 422 });
    await api.changePassword({ currentPassword: 'shop2026', newPassword: 'A-much-longer-passw0rd' });
    expect((await api.revokeOtherSessions()).revoked).toBe(1);
    const prefs = await api.saveNotificationPreferences({ email: true, sms: false, push: true, marketingOptIn: true });
    expect(prefs.sms).toBe(false);
  });
});

describe('mock BFF — services in search', () => {
  it('a text search also returns the services matching its words, on the first page only', async () => {
    const api = browserLikeClient().api;
    const first = await api.search(SearchQuerySchema.parse({ q: 'braids' }));
    expect(first.services.length).toBeGreaterThan(0);
    expect(first.services.every((s) => s.name.toLowerCase().includes('braids') || s.category.toLowerCase().includes('braids') || s.providerName.toLowerCase().includes('braids'))).toBe(true);
    const nothing = await api.search(SearchQuerySchema.parse({ q: 'zzzznothing' }));
    expect(nothing.services).toEqual([]);
  });
});

describe('mock BFF — sponsored search (S240)', () => {
  it('labels ads with the reason and records clicks once per search and product', async () => {
    const api = browserLikeClient().api;
    const page = await api.search(SearchQuerySchema.parse({ q: 'shea' }));
    expect(page.searchId).toBeTruthy();
    const hit = page.hits[0];
    expect(hit?.sponsor).toMatchObject({ advertiser: 'Tamale Shea Co-op', slot: 1 });
    expect(hit?.sponsor?.reason?.summary).toContain('shea');
    if (!hit) throw new Error('no hits');
    const first = await api.recordSearchClick({ searchId: page.searchId ?? '', q: 'shea', atin: hit.atin, position: 1, adToken: hit.sponsor?.adToken });
    expect(first.sponsored).toBe(true);
    expect(first).toMatchObject({ recorded: true, duplicate: false });
    expect((await api.recordSearchClick({ searchId: page.searchId ?? '', atin: hit.atin, position: 1 })).duplicate).toBe(true);
  });
});
