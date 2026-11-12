/* A placement refused because the total would be higher than the one shown (409 CHECKOUT_TOTAL_CHANGED): the new numbers are read
   from the problem, and a new Idempotency-Key may be made for the confirmed attempt. */
import { describe, expect, it } from 'vitest';

import { ApiError, CHECKOUT_TOTAL_CHANGED, definitiveFailure, totalChangeOf } from '../src';

const problem = (extra: Record<string, unknown>, code = CHECKOUT_TOTAL_CHANGED) =>
  new ApiError({ type: 'about:blank', title: 'Your total changed', status: 409, code, detail: 'Your total changed before we could charge you.', ...extra });

describe('totalChangeOf', () => {
  it('reads the new numbers and the fresh lock', () => {
    const e = problem({
      previousTotalMinor: 2813,
      totalMinor: 2900,
      taxMinor: 301,
      shippingMinor: 599,
      subtotalMinor: 2000,
      discountMinor: 0,
      currency: 'USD',
      taxQuoteId: 'lock-1',
      taxQuoteExpiresAt: '2027-01-01T00:00:00Z'
    });
    expect(totalChangeOf(e)).toEqual({
      previousTotalMinor: 2813,
      totalMinor: 2900,
      taxMinor: 301,
      shippingMinor: 599,
      subtotalMinor: 2000,
      discountMinor: 0,
      currency: 'USD',
      taxQuoteId: 'lock-1',
      taxQuoteExpiresAt: '2027-01-01T00:00:00Z'
    });
    // nothing was charged and the attempt is over: the confirmed order uses a NEW Idempotency-Key
    expect(definitiveFailure(e)).toBe(true);
  });

  it('is null for any other problem or error', () => {
    expect(totalChangeOf(problem({ totalMinor: 1 }, 'STOCK_CHANGED'))).toBeNull();
    expect(totalChangeOf(new Error('x'))).toBeNull();
    // numbers missing: not usable as a total to confirm
    expect(totalChangeOf(problem({}))).toBeNull();
  });
});
