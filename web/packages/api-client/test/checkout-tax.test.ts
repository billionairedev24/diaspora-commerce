/* The mock checkout tax estimate: stored decision per line, delivery allocated over a package's lines by item value,
   blocked lines. The allocation must agree with orders (Java) and gateway-bff (Go): shared vectors. */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { type CheckoutPackage } from '../src';
import { allocateShipping, type AllocationMethod, blockedMessage, mockCheckoutTax, mockLineStatus } from '../src/mocks/checkout-tax';

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../tools/checkout/shipping-allocation-vectors.json'), 'utf8')) as {
  cases: { name: string; method: AllocationMethod; totalMinor: number; weightsMinor: number[]; expectedMinor: number[] }[];
};

const line = (id: string, title: string, productType: string, total: number) =>
  ({ lineId: id, atin: `ATIN-${id}`, offerId: `of-${id}`, title, productType, lineTotalMinor: total, priceMinor: total, quantity: 1 }) as unknown as CheckoutPackage['lines'][number];
const pkg = (id: string, ...lines: CheckoutPackage['lines']) => ({ packageId: id, lines }) as unknown as CheckoutPackage;

describe('shipping allocation (shared vectors with orders and gateway-bff)', () => {
  it.each(vectors.cases)('$name', (c) => {
    expect(allocateShipping(c.totalMinor, c.weightsMinor, c.method)).toEqual(c.expectedMinor);
  });
});

describe('mock checkout tax', () => {
  const base = { state: 'TX', city: 'Houston', zip: '77002', discountMinor: 0, hasAddress: true };

  it('statuses: groceries exempt, general goods taxable, held categories blocked', () => {
    expect(mockLineStatus('spices')).toBe('EXEMPT');
    expect(mockLineStatus('home-decor')).toBe('TAXABLE');
    expect(mockLineStatus('gift-cards')).toBe('BLOCKED');
  });

  it('a mixed basket splits the delivery between its exempt and taxable lines', () => {
    const t = mockCheckoutTax({ ...base, packages: [pkg('a', line('ex', 'Egusi', 'pantry', 1000), line('tx', 'Sauce pan', 'home-decor', 3000))], shippingByPackage: { a: 800 } });
    expect(t.taxLines.map((l) => [l.status, l.shippingMinor])).toEqual([['EXEMPT', 200], ['TAXABLE', 600]]);
    // (3000 + 600) × 8.25 %
    expect(t.taxMinor).toBe(297);
    expect(t.taxRateLabel).toBe('8.25%');
    expect(t.taxJurisdictionLabel).toBe('Houston, TX');
    expect(t.canPlaceOrder).toBe(true);
    expect(t.shippingTaxAllocation).toMatchObject({ method: 'PROPORTIONAL', approved: false });
    expect(t.shippingTaxAllocation?.note).toContain('open legal decision');
  });

  it('a delivery tied only to exempt lines is not taxed', () => {
    const t = mockCheckoutTax({ ...base, packages: [pkg('a', line('l1', 'Egusi', 'pantry', 2500), line('l2', 'Rice', 'grains', 1500))], shippingByPackage: { a: 499 } });
    expect(t.taxMinor).toBe(0);
    expect(t.taxLines.every((l) => l.status === 'EXEMPT')).toBe(true);
    expect(t.taxLines.reduce((n, l) => n + l.shippingMinor, 0)).toBe(499);
  });

  it('a blocked line is listed with a plain message and Remove; the rest is still estimated', () => {
    const t = mockCheckoutTax({ ...base, packages: [pkg('a', line('ok', 'Sauce pan', 'home-decor', 3000), line('gc', 'Gift card', 'gift-cards', 2500))], shippingByPackage: { a: 0 } });
    expect(t.canPlaceOrder).toBe(false);
    expect(t.blockedLines).toHaveLength(1);
    const b = t.blockedLines[0];
    expect(b).toMatchObject({ lineId: 'gc', title: 'Gift card', action: 'REMOVE', packageId: 'a' });
    expect(b?.message).toBe(blockedMessage('Gift card'));
    expect(b?.message).not.toMatch(/tax|ATIN|of-gc/i);
    // a basket with a line that has no definite decision has no figure at all
    expect(t.taxMinor).toBe(0);
    expect(t.taxEstimateStatus).toBe('UNAVAILABLE');
    expect(t.taxUnavailableReason).toBe('BLOCKED_ITEMS');
    expect(t.taxLines[1]).toMatchObject({ status: 'BLOCKED', taxMinor: 0 });
  });

  it('outside Texas (no rules) the quote fails: the retry state, never $0.00, 0% or "calculated later"; with no address it asks for a ZIP code', () => {
    const p = [pkg('a', line('l1', 'Sauce pan', 'home-decor', 3000))];
    const ny = mockCheckoutTax({ ...base, state: 'NY', city: 'Albany', zip: '12207', packages: p, shippingByPackage: { a: 500 } });
    expect(ny).toMatchObject({ taxMinor: 0, taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'SERVICE_UNAVAILABLE', taxRateLabel: "We couldn't work out your tax right now. Try again.", canPlaceOrder: false });
    expect(mockCheckoutTax({ ...base, hasAddress: false, packages: p, shippingByPackage: {} })).toMatchObject({
      taxMinor: 0,
      taxEstimateStatus: 'NEEDS_ADDRESS',
      taxUnavailableReason: 'NEEDS_ZIP',
      taxRateLabel: 'Enter your ZIP code to see tax',
      taxLines: [],
      taxJurisdictionLabel: ''
    });
  });

  it('an address without a state or ZIP code has no estimate and says what to add', () => {
    const p = [pkg('a', line('l1', 'Sauce pan', 'home-decor', 3000))];
    expect(mockCheckoutTax({ ...base, state: '', zip: '77002', packages: p, shippingByPackage: {} })).toMatchObject({ taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'ADDRESS_INCOMPLETE', canPlaceOrder: false });
    expect(mockCheckoutTax({ ...base, state: 'TX', zip: '', packages: p, shippingByPackage: {} })).toMatchObject({ taxUnavailableReason: 'ADDRESS_INCOMPLETE' });
  });

  it('the estimate carries the ZIP code it was worked out for', () => {
    const p = [pkg('a', line('l1', 'Sauce pan', 'home-decor', 3000))];
    expect(mockCheckoutTax({ ...base, zip: '77002', packages: p, shippingByPackage: { a: 0 } })).toMatchObject({ taxEstimateStatus: 'ESTIMATED', taxZip: '77002' });
  });
});
