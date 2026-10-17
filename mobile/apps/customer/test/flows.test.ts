/* The customer journeys end to end through the app's real API layer (auth fetch, Zod-validated BFF client, JSON
   requesters) against the in-app mock backend: what every screen does when you tap through it, minus the pixels. */
import { SearchQuerySchema } from '@afrotransact/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createCustomerApi } from '../src/api/client';
import { resetMockDb } from '../src/api/mock/db';
import { mockFetch } from '../src/api/mock/router';

const api = () => createCustomerApi({ getAccessToken: async () => 'tok', refreshAccessToken: async () => null, onSessionExpired: () => undefined }, mockFetch);

describe('shopping: browse → product → cart → checkout → order', () => {
  beforeEach(() => resetMockDb());

  it('finds products, adds one, checks out and tracks the order', async () => {
    const a = api();
    const found = await a.bff.search(SearchQuerySchema.parse({ q: 'shito', size: 24 }));
    const hit = found.hits[0];
    expect(hit).toBeDefined();
    if (!hit) return;

    const detail = await a.bff.getProduct(hit.atin);
    expect(detail.product.title).toBe(hit.title);
    expect(detail.buyBox?.offerId).toBe(hit.offerId);

    const emptyBefore = await a.bff.getCart();
    const cart = await a.bff.addToCart({ offerId: hit.offerId, quantity: 2 });
    expect(cart.itemCount).toBe(emptyBefore.itemCount + 2);
    const line = cart.packages.flatMap((p) => p.lines).find((l) => l.atin === hit.atin);
    expect(line?.quantity).toBe(2);
    if (!line) return;

    const bumped = await a.bff.updateCartLine(line.lineId, { quantity: 3 });
    expect(bumped.packages.flatMap((p) => p.lines).find((l) => l.atin === hit.atin)?.quantity).toBe(3);

    const summary = await a.checkout.summary();
    expect(summary.packages.length).toBeGreaterThan(0);
    expect(summary.addresses.length).toBeGreaterThan(0);
    expect(summary.paymentMethods.length).toBeGreaterThan(0);
    const address = summary.addresses.find((x) => x.isDefault) ?? summary.addresses[0];

    const order = await a.checkout.placeOrder(
      {
        addressId: address?.id ?? '',
        paymentMethodId: summary.paymentMethods[0]?.id ?? '',
        deliverySelections: summary.packages.map((p) => ({ packageId: p.packageId, option: p.deliveryOptions[0]?.key ?? 'carrier', preferences: { handover: 'hand' } }))
      } as Parameters<typeof a.checkout.placeOrder>[0],
      'co_flow_test_0000000001'
    );
    expect(order.orderNumber).toBeTruthy();
    expect((await a.bff.getCart()).itemCount).toBe(0);

    const orders = await a.bff.getOrders();
    expect(orders[0]?.orderNumber).toBe(order.orderNumber);
    const tracking = await a.account.tracking(order.orderNumber);
    expect(tracking.shipments.length).toBeGreaterThan(0);
  });

  it('removes a line and shows an empty cart', async () => {
    const a = api();
    const hit = (await a.bff.search(SearchQuerySchema.parse({ q: 'rice', size: 24 }))).hits[0];
    if (!hit) throw new Error('fixture');
    const cart = await a.bff.addToCart({ offerId: hit.offerId, quantity: 1 });
    const line = cart.packages.flatMap((p) => p.lines).find((l) => l.atin === hit.atin);
    expect(line).toBeDefined();
    const after = await a.bff.removeCartLine(line?.lineId ?? '');
    expect(after.packages.flatMap((p) => p.lines).some((l) => l.atin === hit.atin)).toBe(false);
  });

  it('refuses checkout on an empty cart with a problem the screen can explain', async () => {
    const a = api();
    for (const l of (await a.bff.getCart()).packages.flatMap((p) => p.lines)) await a.bff.removeCartLine(l.lineId);
    await expect(a.checkout.summary()).rejects.toMatchObject({ status: 409 });
  });
});

describe('returns', () => {
  beforeEach(() => resetMockDb());

  it('starts a return and lists it', async () => {
    const a = api();
    const orders = await a.bff.getOrders();
    const o = orders[0];
    expect(o).toBeDefined();
    if (!o) return;
    const t = await a.account.tracking(o.orderNumber);
    const item = t.shipments.flatMap((s) => s.items)[0];
    const rma = await a.account.startReturn({ orderNumber: o.orderNumber, atin: item?.atin ?? '', reason: 'DAMAGED' });
    expect(rma.rmaId).toMatch(/^RMA-/);
    expect((await a.bff.getReturns()).some((r) => r.rmaId === rma.rmaId)).toBe(true);
  });
});

describe('addresses', () => {
  beforeEach(() => resetMockDb());
  const input = { name: 'Adaeze Okafor', phone: '+17135550142', line1: '500 Main St', city: 'Houston', state: 'TX', zip: '77002' };

  it('adds, makes default, edits and removes an address', async () => {
    const a = api();
    const before = await a.bff.getAddresses();
    const created = await a.bff.createAddress(input);
    expect((await a.bff.getAddresses()).length).toBe(before.length + 1);

    await a.bff.setDefaultAddress(created.id);
    const list = await a.bff.getAddresses();
    expect(list.filter((x) => x.isDefault).map((x) => x.id)).toEqual([created.id]);

    const edited = await a.bff.updateAddress(created.id, { ...input, line1: '600 Main St' });
    expect(edited.line1).toBe('600 Main St');

    await a.bff.deleteAddress(created.id);
    const after = await a.bff.getAddresses();
    expect(after.some((x) => x.id === created.id)).toBe(false);
    expect(after.filter((x) => x.isDefault)).toHaveLength(1);
  });

  it('asks before keeping an address it cannot verify', async () => {
    const a = api();
    const e = await a.bff.createAddress({ ...input, line1: '0 Nowhere Rd' }).catch((x: unknown) => x);
    expect(e).toMatchObject({ status: 422 });
    expect((e as { fieldErrors: Record<string, string> }).fieldErrors.line1).toBeTruthy();
    const kept = await a.bff.createAddress({ ...input, line1: '0 Nowhere Rd', acceptUnverified: true });
    expect(kept.verified).toBe(false);
  });
});

describe('bookings: find a pro → pick a time → book → cancel', () => {
  beforeEach(() => resetMockDb());

  it('books a service in an open slot and cancels with the refund quoted first', async () => {
    const a = api();
    const found = await a.bookings.searchServices();
    const svc = found.services?.[0];
    expect(svc?.serviceId).toBeTruthy();
    const serviceId = svc?.serviceId ?? '';
    const days = await a.bookings.days(serviceId);
    const day = days.find((d) => (d.slots ?? 0) > 0);
    expect(day).toBeDefined();
    const slots = await a.bookings.slots(serviceId, day?.date ?? '');
    expect(slots.length).toBeGreaterThan(0);

    const before = (await a.bookings.mine()).length;
    const created = await a.bookings.create({ serviceId, startAt: slots[0]?.start ?? '' } as Parameters<typeof a.bookings.create>[0], 'bk_flow_test_0000000001');
    expect(created).toBeDefined();
    const mine = await a.bookings.mine();
    expect(mine.length).toBe(before + 1);

    const id = mine.find((b) => b.service?.serviceId === serviceId)?.id ?? '';
    const quote = await a.bookings.quote(id);
    expect(quote.refundMinor ?? 0).toBeLessThanOrEqual(quote.depositMinor ?? 0);
    const cancelled = await a.bookings.cancel(id, 'bk_flow_cancel_000000001', 'Plans changed');
    expect(cancelled).toBeDefined();
  });
});

describe('account', () => {
  beforeEach(() => resetMockDb());

  it('loads the signed-in shopper, payment methods and a setup intent for adding a card', async () => {
    const a = api();
    const me = await a.bff.getMe();
    expect(me.authenticated).toBe(true);
    expect((await a.bff.getPaymentMethods()).length).toBeGreaterThan(0);
    expect((await a.account.createSetupIntent()).clientSecret).toContain('seti_');
  });

  it('registers and removes a push device', async () => {
    const a = api();
    await a.identity.registerDevice({ token: 'ExponentPushToken[x]', provider: 'fcm', app: 'customer' } as Parameters<typeof a.identity.registerDevice>[0]);
    await a.identity.unregisterDevice('ExponentPushToken[x]');
  });
});

describe('a phone has no global crypto (Hermes)', () => {
  it('still makes API calls: the correlation id does not need crypto.randomUUID', async () => {
    vi.stubGlobal('crypto', undefined);
    try {
      expect((await api().bff.getCart()).itemCount).toBeGreaterThanOrEqual(0);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
