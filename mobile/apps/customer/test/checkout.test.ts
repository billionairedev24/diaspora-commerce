import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { ApiError } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { blockedItemText, blockedLines, buildPlaceOrderRequest, canPlaceOrder, CART_CHANGED_MESSAGE, checkoutFailure, type CheckoutSummary, defaultChoices, deliveryBlock, idempotencyKey, ITEM_BLOCKED_MESSAGE, keyAfterFailure, paymentSheetIntent, QUOTE_REQUIRED_MESSAGE, requiresDeviceAuth, taxLabel, taxNote, totalFor, validateChoices } from '../src/domain/checkout';
import { onRequestQuote } from '../src/domain/quote';
import { allocateShipping, type AllocationMethod } from '../src/domain/shippingAllocation';
import { deliveryFee } from '../src/domain/shippingFee';

const promise = { days: 0, label: 'Today', prefix: 'Delivery by AfroTransact', free: false, feeMinor: 599, cutoff: null };
const summary = {
  packages: [
    {
      packageId: 'pkg-1', seller: { id: 's', name: 'AfroTransact Retail', kind: 'PLATFORM', city: 'Houston' }, fulfilment: 'AFROTRANSACT',
      heading: 'Shipped by AfroTransact', shipsFrom: 'Houston FC', delivery: promise,
      deliveryOptions: [
        { key: 'afrotransact_delivery', label: 'Delivery by AfroTransact', eta: 'Today 6–8 pm', feeMinor: 599, free: false },
        { key: 'pickup', label: 'Pickup at an AfroTransact point', eta: 'Today', feeMinor: 0, free: true }
      ],
      lines: [], itemCount: 1, subtotalMinor: 5000, freeShippingNudge: null, pickupPoints: []
    }
  ],
  addresses: [], paymentMethods: [], subtotalMinor: 5000, shippingMinor: 0, taxMinor: 413, taxRateLabel: '8.25% TX', totalMinor: 5413, currency: 'usd', sellerCount: 1
} as unknown as CheckoutSummary;

describe('checkout rules', () => {
  it('asks for Face ID above $100 and for Apple Pay', () => {
    expect(requiresDeviceAuth(10000)).toBe(false);
    expect(requiresDeviceAuth(10001)).toBe(true);
    expect(requiresDeviceAuth(500, 'apple_pay')).toBe(true);
    expect(requiresDeviceAuth(500, 'google_pay')).toBe(false);
  });

  it('defaults to delivery by AfroTransact and re-totals for pickup', () => {
    const ch = defaultChoices(summary);
    expect(ch['pkg-1']?.option).toBe('afrotransact_delivery');
    expect(totalFor(summary, ch)).toBe(5000 + 599 + 413);
    expect(totalFor(summary, { 'pkg-1': { option: 'pickup' } })).toBe(5000 + 413);
  });

  it('a package outside every delivery zone offers quote_required: not payable, with pickup beside it', () => {
    const far = { ...summary.packages[0], packageId: 'pkg-far', deliveryOptions: [
      { key: 'quote_required', label: 'Too far for standard delivery. Ask us for a quote', eta: '', feeMinor: 0, free: false },
      { key: 'pickup', label: 'Pickup at an AfroTransact point', eta: 'Today', feeMinor: 0, free: true }
    ] };
    const sum = { ...summary, packages: [far], canPlaceOrder: false, deliveryBlockedReason: QUOTE_REQUIRED_MESSAGE } as unknown as CheckoutSummary;
    const ch = defaultChoices(sum);
    expect(ch['pkg-far']?.option).toBe('quote_required');
    expect(validateChoices(sum, ch)).toBe(QUOTE_REQUIRED_MESSAGE);
    expect(deliveryBlock(sum, ch)).toMatch(/Ask us for a quote/);
    expect(canPlaceOrder(sum)).toBe(false);
    expect(totalFor(sum, ch)).toBe(5000 + 413); // no delivery price is invented
    expect(validateChoices(sum, { 'pkg-far': { option: 'pickup', pickupPointId: 'pp-1' } })).toBeNull();
    expect(deliveryBlock(sum, { 'pkg-far': { option: 'pickup', pickupPointId: 'pp-1' } })).toBe(QUOTE_REQUIRED_MESSAGE); // the summary still says so until it is re-read
    expect(onRequestQuote(far as unknown as Parameters<typeof onRequestQuote>[0])).toBeUndefined(); // the stub the delivery quote flow replaces
  });

  it('does not default to pickup when a delivery option exists', () => {
    const seller = { ...summary.packages[0], packageId: 'pkg-2', deliveryOptions: [
      { key: 'afrotransact_delivery', label: 'AfroTransact collects from the store and delivers', eta: '3 days', feeMinor: 599, free: false },
      { key: 'pickup', label: 'Collect from the store', eta: 'Tomorrow', feeMinor: 0, free: true }
    ] };
    const onlyPickup = { ...summary.packages[0], packageId: 'pkg-3', deliveryOptions: [{ key: 'pickup', label: 'Pickup', eta: 'Tomorrow', feeMinor: 0, free: true }], pickupPoints: [{ id: 'pp-9', name: 'Shop', address: 'x', hours: 'y', ready: 'z', setBy: 'seller' }] };
    const ch = defaultChoices({ ...summary, packages: [seller, onlyPickup] } as unknown as CheckoutSummary);
    expect(ch['pkg-2']).toEqual({ option: 'afrotransact_delivery' });
    expect(ch['pkg-3']).toEqual({ option: 'pickup', pickupPointId: 'pp-9' });
  });

  it('requires a pickup point for pickup', () => {
    expect(validateChoices(summary, { 'pkg-1': { option: 'pickup' } })).toMatch(/pickup point/);
    expect(validateChoices(summary, { 'pkg-1': { option: 'pickup', pickupPointId: 'pp-1' } })).toBeNull();
    expect(validateChoices(summary, {})).toMatch(/delivery option/);
  });

  it('builds the place-order request and the payment sheet intent', () => {
    const req = buildPlaceOrderRequest('addr-1', { 'pkg-1': { option: 'afrotransact_delivery', preferences: { handover: 'hand' } } }, { paymentMethodId: 'pm_1' });
    expect(req).toEqual({ addressId: 'addr-1', paymentMethodId: 'pm_1', deliverySelections: [{ packageId: 'pkg-1', option: 'afrotransact_delivery', preferences: { handover: 'hand', contact: 'text' } }] });
    expect(buildPlaceOrderRequest('a', { p: { option: 'afrotransact_delivery', preferences: { handover: 'door' } } }, { paymentMethodId: 'pm' }).deliverySelections[0]?.preferences).toEqual({ handover: 'door', contact: 'text' });
    expect(buildPlaceOrderRequest('a', { p: { option: 'carrier' } }, { paymentMethodId: 'pm' }).deliverySelections[0]?.preferences).toBeUndefined();
    expect(paymentSheetIntent(summary, 5413)).toEqual({ amount: 5413, currencyCode: 'USD' });
  });

  it('makes idempotency keys within 16–64 characters', () => {
    expect(idempotencyKey(() => '0b9e2c5e-1111-4a3b-9c8d-123456789abc')).toMatch(/^co_[0-9a-f]{32}$/);
    expect(idempotencyKey(() => 'x').length).toBe(16);
    expect(idempotencyKey(() => 'y'.repeat(100)).length).toBe(64);
  });
});

describe('idempotency key after a failed attempt (no double charge)', () => {
  const problem = (status: number, code?: string) => new ApiError({ type: 'about:blank', title: 't', status, ...(code ? { code } : {}) });
  const awaiting = async () => ({ status: 'awaiting_payment' });
  const failed = async (): Promise<{ status: string }> => {
    throw problem(402, 'PAYMENT_DECLINED');
  };
  const offline = async (): Promise<{ status: string }> => {
    throw new TypeError('Network request failed');
  };

  it('keeps the key after a network error, a timeout, a 5xx or a PaymentSheet error', async () => {
    expect(await keyAfterFailure('co_1', new TypeError('Network request failed'))).toBe('co_1');
    expect(await keyAfterFailure('co_1', problem(503))).toBe('co_1');
    expect(await keyAfterFailure('co_1', new Error('Your card was declined.'), awaiting)).toBe('co_1');
    expect(await keyAfterFailure('co_1', new Error('Your card was declined.'), offline)).toBe('co_1');
    expect(await keyAfterFailure('co_1', problem(409, 'CHECKOUT_IN_PROGRESS'))).toBe('co_1');
  });

  it('makes a new key only once the attempt is definitely over', async () => {
    expect(await keyAfterFailure('co_1', problem(422, 'VALIDATION_FAILED'))).toBeNull();
    expect(await keyAfterFailure('co_1', problem(409, 'IDEMPOTENCY_CONFLICT'))).toBeNull();
    expect(await keyAfterFailure('co_1', new Error('Your card was declined.'), failed)).toBeNull();
  });
});

describe('checkoutFailure', () => {
  const problem = (code: string, detail: string) => new ApiError({ type: 'about:blank', title: 'Unprocessable', status: 422, code, detail });

  it('shows a plain message with a way back to the cart for cart mismatches, never the ATIN text', () => {
    for (const code of ['orders.line-not-in-cart', 'orders.cart-changed', 'orders.cart-empty', 'orders.cart-not-checkoutable']) {
      expect(checkoutFailure(problem(code, 'ATG123 is not in your cart'))).toEqual({ message: CART_CHANGED_MESSAGE, cartChanged: true });
    }
  });

  it('keeps other messages as they are', () => {
    expect(checkoutFailure(problem('orders.address-not-found', 'Address a1 is not in your address book'))).toEqual({ message: 'Address a1 is not in your address book', cartChanged: false });
    expect(checkoutFailure(new Error('Your card was declined.')).message).toBe('Your card was declined.');
    expect(checkoutFailure('boom').message).toBe('Payment failed. Try again.');
  });
});

describe('items that can’t be sold yet and the tax lines', () => {
  const held = {
    ...summary,
    taxMinor: 0,
    taxJurisdictionLabel: 'Houston, TX',
    taxLines: [{ lineId: 'l1', status: 'BLOCKED', taxMinor: 0, shippingMinor: 0 }],
    blockedLines: [{ lineId: 'l1', offerId: 'of-1', atin: 'ATIN-1', title: 'Gift card', packageId: 'pkg-1', message: 'English from the server', action: 'REMOVE' }],
    canPlaceOrder: false
  } as unknown as CheckoutSummary;

  it('blocks placing while a line remains and names the item without ids or tax terms', () => {
    expect(canPlaceOrder(summary)).toBe(true);
    expect(canPlaceOrder(held)).toBe(false);
    expect(blockedLines(held)).toHaveLength(1);
    const text = blockedItemText(blockedLines(held)[0] as NonNullable<typeof held.blockedLines>[number]);
    expect(text).toBe('We can’t sell Gift card yet. We’re still checking its details. Remove it from this order to continue, or try again later.');
    expect(text).not.toMatch(/tax|ATIN|of-1/i);
  });

  it('a refused placement reads as plain words, never the server detail', () => {
    const e = new ApiError({ type: 'about:blank', title: 'x', status: 422, code: 'CHECKOUT_ITEM_BLOCKED', detail: 'We can’t sell ATG0000123 (orders)' });
    expect(checkoutFailure(e)).toEqual({ message: ITEM_BLOCKED_MESSAGE, cartChanged: false });
  });

  it('words the tax row with where it was worked out and says when items carry no tax', () => {
    expect(taxLabel(summary)).toBe('Estimated tax');
    expect(taxLabel(held)).toBe('Estimated tax · Houston, TX');
    expect(taxNote(summary)).toBeNull();
    const lines = (...status: string[]) => ({ ...summary, taxLines: status.map((s, i) => ({ lineId: `l${i}`, status: s, taxMinor: 0, shippingMinor: 0 })) }) as unknown as CheckoutSummary;
    expect(taxNote(lines('EXEMPT', 'TAXABLE'))).toBe('No tax on some of your items, or on their delivery.');
    expect(taxNote(lines('EXEMPT', 'EXEMPT'))).toBe('No tax on these items, or on their delivery.');
    expect(taxNote(lines('TAXABLE'))).toBeNull();
  });
});

describe('shipping allocation (shared vectors with orders, gateway-bff and the web mock)', () => {
  const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../tools/checkout/shipping-allocation-vectors.json'), 'utf8')) as {
    cases: { name: string; method: AllocationMethod; totalMinor: number; weightsMinor: number[]; expectedMinor: number[] }[];
  };
  it.each(vectors.cases)('$name', (c) => {
    expect(allocateShipping(c.totalMinor, c.weightsMinor, c.method)).toEqual(c.expectedMinor);
  });
});

describe('the delivery fee equals what orders charges (shared fee vectors)', () => {
  const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../tools/checkout/shipping-fee-vectors.json'), 'utf8')) as {
    settings: { name: string; flatFeeMinor: number; freeAboveMinor: number | null; carts: { itemsMinor: number; afrotransactDeliveryMinor: number; pickupMinor: number }[] }[];
  };

  it('is the flat fee, free at or above the threshold, for every vector', () => {
    expect(vectors.settings.length).toBeGreaterThanOrEqual(4);
    for (const set of vectors.settings) {
      for (const c of set.carts) {
        expect(deliveryFee(c.itemsMinor, { flatFeeMinor: set.flatFeeMinor, freeAboveMinor: set.freeAboveMinor }), `${set.name} items ${c.itemsMinor}`).toBe(c.afrotransactDeliveryMinor);
      }
    }
  });

  it('pickup is always free and the request never carries quote_required', () => {
    const req = buildPlaceOrderRequest('a1', { 'pkg-1': { option: 'pickup', pickupPointId: 'pp-1' } }, { paymentMethodId: 'pm_1' });
    expect(req.deliverySelections[0]?.option).toBe('pickup');
    expect(totalFor(summary, { 'pkg-1': { option: 'pickup' } }) - summary.subtotalMinor - summary.taxMinor).toBe(0);
  });
});
