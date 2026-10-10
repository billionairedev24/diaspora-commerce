import { describe, expect, it } from 'vitest';

import { buildPlaceOrderRequest, type CheckoutSummary, defaultChoices, idempotencyKey, paymentSheetIntent, requiresDeviceAuth, totalFor, validateChoices } from '../src/domain/checkout';

const promise = { days: 0, label: 'Today', prefix: 'FREE delivery', free: true, feeMinor: 0, expressLabel: null, cutoff: null };
const summary = {
  packages: [
    {
      packageId: 'pkg-1', seller: { id: 's', name: 'AfroTransact Retail', kind: 'PLATFORM', city: 'Houston' }, fulfilment: 'AFROTRANSACT',
      heading: 'Shipped by AfroTransact', shipsFrom: 'Houston FC', delivery: promise,
      deliveryOptions: [
        { key: 'afrotransact_delivery', label: 'AfroTransact Delivery', eta: 'Today 6–8 pm', feeMinor: 0, free: true },
        { key: 'express', label: 'Next Day Air', eta: 'Tomorrow', feeMinor: 1299, free: false },
        { key: 'pickup', label: 'Pickup', eta: 'Today', feeMinor: 0, free: true }
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

  it('defaults to free options and re-totals for upgrades', () => {
    const ch = defaultChoices(summary);
    expect(ch['pkg-1']?.option).toBe('afrotransact_delivery');
    expect(totalFor(summary, ch)).toBe(5413);
    expect(totalFor(summary, { 'pkg-1': { option: 'express' } })).toBe(5413 + 1299);
  });

  it('does not default to pickup when a delivery option exists', () => {
    const seller = { ...summary.packages[0], packageId: 'pkg-2', deliveryOptions: [
      { key: 'carrier', label: 'USPS', eta: '3 days', feeMinor: 599, free: false },
      { key: 'pickup', label: 'Pickup', eta: 'Tomorrow', feeMinor: 0, free: true }
    ] };
    const onlyPickup = { ...summary.packages[0], packageId: 'pkg-3', deliveryOptions: [{ key: 'pickup', label: 'Pickup', eta: 'Tomorrow', feeMinor: 0, free: true }], pickupPoints: [{ id: 'pp-9', name: 'Shop', address: 'x', hours: 'y', ready: 'z', setBy: 'seller' }] };
    const ch = defaultChoices({ ...summary, packages: [seller, onlyPickup] } as unknown as CheckoutSummary);
    expect(ch['pkg-2']).toEqual({ option: 'carrier' });
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
