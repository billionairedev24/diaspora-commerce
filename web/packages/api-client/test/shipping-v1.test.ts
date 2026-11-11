import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { ApiError, createApiClient } from '../src';
import { resetDb, resetShippingSettings, setShippingSettings } from '../src/mocks';
import { deliveryOptions, deliveryPromise } from '../src/mocks/delivery';
import { mockServer } from '../src/mocks/node';

/* Launch shipping (listing rules D16, D21, D22): the shopper estimate in the web mock equals the amount orders charges
   (tools/checkout/shipping-fee-vectors.json is shared with orders, gateway-bff and the customer app), there is no express
   and no carrier while the staff switch is off, an address outside every delivery zone gets quote_required, and a
   pre-order package keeps its own fee. */

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../tools/checkout/shipping-fee-vectors.json'), 'utf8')) as {
  settings: { name: string; flatFeeMinor: number; freeAboveMinor: number | null; carts: { itemsMinor: number; afrotransactDeliveryMinor: number; pickupMinor: number }[] }[];
};

async function signedIn() {
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
  const api = createApiClient(opts);
  const res = await opts.fetch(new Request(api.loginUrl('/account'), { redirect: 'manual' }));
  if (res.status !== 302) throw new Error('expected the hosted sign-in redirect');
  return api;
}

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => {
  resetDb();
  resetShippingSettings();
});

const NOW = new Date('2026-11-11T12:00:00Z');

describe('the shopper estimate equals what orders charges (shared fee vectors)', () => {
  it('every package kind offers the same flat delivery fee for every cart value and setting', () => {
    expect(vectors.settings.length).toBeGreaterThanOrEqual(4);
    for (const set of vectors.settings) {
      setShippingSettings({ flatFeeMinor: set.flatFeeMinor, freeAboveMinor: set.freeAboveMinor });
      for (const c of set.carts) {
        for (const fulfilment of ['AFROTRANSACT', 'AFROTRANSACT_DELIVERY', 'SELLER'] as const) {
          const opts = deliveryOptions({ from: { fulfilment, city: 'Houston, TX' }, sellerName: 'Seller', slowestDays: 2, subtotalMinor: c.itemsMinor }, 'Houston, TX', NOW);
          expect(opts.map((o) => o.key), `${set.name} ${fulfilment}`).toEqual(['afrotransact_delivery']);
          expect(opts[0]?.feeMinor, `${set.name} ${fulfilment} items ${c.itemsMinor}`).toBe(c.afrotransactDeliveryMinor);
          expect(opts[0]?.free).toBe(c.afrotransactDeliveryMinor === 0);
        }
      }
    }
  });

  it('the checkout total uses the flat fee for delivery and 0 for pickup, whatever staff set', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    const pkg = (await api.getCheckoutSummary('ad1')).packages[0];
    expect(pkg?.deliveryOptions.map((o) => o.label)).toEqual(['Delivery by AfroTransact', 'Pickup at an AfroTransact point']);
    for (const set of vectors.settings) {
      setShippingSettings({ flatFeeMinor: set.flatFeeMinor, freeAboveMinor: set.freeAboveMinor });
      const sum = await api.getCheckoutSummary('ad1', `${pkg?.packageId}:afrotransact_delivery`);
      const expected = set.carts.find((c) => c.itemsMinor >= (pkg?.subtotalMinor ?? 0))?.afrotransactDeliveryMinor;
      const items = sum.packages[0]?.subtotalMinor ?? 0;
      const want = set.freeAboveMinor !== null && items >= set.freeAboveMinor ? 0 : set.flatFeeMinor;
      expect(sum.shippingMinor, `${set.name} (vector ${String(expected)})`).toBe(want);
      expect((await api.getCheckoutSummary('ad1', `${pkg?.packageId}:pickup`)).shippingMinor).toBe(0);
    }
  });
});

describe('no express and no carrier while the staff switch is off', () => {
  it('a seller package is delivered by AfroTransact; the carrier option appears only behind the switch', () => {
    const seller = { from: { fulfilment: 'SELLER' as const, city: 'Houston, TX' }, sellerName: 'Seller', slowestDays: 3, subtotalMinor: 2000 };
    expect(deliveryOptions(seller, 'Houston, TX', NOW).map((o) => o.key)).toEqual(['afrotransact_delivery']);
    setShippingSettings({ sellerCarrierShippingEnabled: true });
    expect(deliveryOptions(seller, 'Houston, TX', NOW).map((o) => o.key)).toEqual(['carrier', 'afrotransact_delivery']);
    expect(deliveryOptions({ ...seller, from: { fulfilment: 'AFROTRANSACT' as const, city: 'Houston, TX' } }, 'Houston, TX', NOW).map((o) => o.key)).toEqual(['afrotransact_delivery']);
  });

  it('the product page promise names AfroTransact, shows the flat fee and has no express line', () => {
    const p = deliveryPromise({ fulfilment: 'AFROTRANSACT', city: 'Houston, TX' }, 'Houston, TX', NOW);
    expect(p).toMatchObject({ prefix: 'Delivery by AfroTransact', feeMinor: 599, free: false });
    expect(p).not.toHaveProperty('expressLabel');
  });
});

describe('an address outside every delivery zone', () => {
  it('offers one delivery option, quote_required, disabled for payment, with pickup beside it', async () => {
    const api = await signedIn();
    await api.setDeliveryLocation({ zip: '20910' });
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 1 });
    const addr = await api.createAddress({ name: 'Ada Obi', phone: '+18065550142', line1: '1 Main St', city: 'Silver Spring', state: 'MD', zip: '20910', isDefault: false });
    const sum = await api.getCheckoutSummary(addr.id);
    const pkg = sum.packages[0];
    expect(pkg?.deliveryOptions[0]).toMatchObject({ key: 'quote_required', label: 'Too far for standard delivery. Ask us for a quote', feeMinor: 0, free: false });
    expect(pkg?.deliveryOptions.filter((o) => o.key !== 'pickup')).toHaveLength(1);
    expect(sum.canPlaceOrder).toBe(false);
    expect(sum.deliveryBlockedReason).toContain('Ask us for a quote');
    expect(sum.shippingMinor).toBe(0);
    await expect(
      api.placeOrder({ addressId: addr.id, paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: pkg?.packageId ?? '', option: 'quote_required' as never }] }, 'checkout-quote-key-0001')
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 422 && Object.keys(e.fieldErrors).some((k) => k.startsWith('deliverySelections')));
  });
});

describe('the retired options are refused in plain words', () => {
  it('a carrier selection is told the options changed; express is no longer an option at all', async () => {
    const api = await signedIn();
    await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity: 2 });
    const pkg = (await api.getCheckoutSummary('ad1')).packages[0];
    await expect(
      api.placeOrder({ addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: pkg?.packageId ?? '', option: 'carrier' }] }, 'checkout-retired-carrier-0001')
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 422 && Object.values(e.fieldErrors).join(' ').includes('delivery options have changed'));
    await expect(
      api.placeOrder({ addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: pkg?.packageId ?? '', option: 'express' as never }] }, 'checkout-retired-express-0001')
    ).rejects.toSatisfy((e) => e instanceof ApiError && e.status === 422);
  });
});
