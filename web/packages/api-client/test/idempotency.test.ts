import { describe, expect, it } from 'vitest';

import { ApiError, definitiveFailure, keepIdempotencyKey } from '../src';

const problem = (status: number, code?: string) => new ApiError({ type: 'about:blank', title: 't', status, ...(code ? { code } : {}) });

describe('idempotency key policy', () => {
  it('keeps the key after network errors, timeouts, 5xx and Stripe confirmation errors', () => {
    for (const e of [new TypeError('Failed to fetch'), new Error('Your card was declined.'), problem(503), problem(502), problem(504), problem(408), problem(429), problem(425)]) {
      expect(keepIdempotencyKey(e)).toBe(true);
      expect(definitiveFailure(e)).toBe(false);
    }
  });

  it('keeps the key while the previous attempt is still being settled', () => {
    expect(keepIdempotencyKey(problem(409, 'CHECKOUT_IN_PROGRESS'))).toBe(true);
  });

  it('allows a new key only after a definitive 4xx', () => {
    for (const e of [problem(402, 'PAYMENT_DECLINED'), problem(422, 'VALIDATION_FAILED'), problem(409, 'IDEMPOTENCY_CONFLICT'), problem(409, 'STOCK_CHANGED')]) {
      expect(definitiveFailure(e)).toBe(true);
    }
  });
});
