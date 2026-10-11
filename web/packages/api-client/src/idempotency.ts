import { isApiError } from './errors';

/**
 * When a client may make a NEW Idempotency-Key (docs/engineering/payments-idempotency.md).
 *
 * A key names one purchase attempt. It is kept across retries until the attempt is placed or has definitely failed,
 * so a retry after a timeout, a dropped connection, a 5xx or a Stripe confirmation error replays the same order and
 * the same PaymentIntent instead of creating a second one. Only an answer that proves the attempt is over (a 4xx
 * about this request: validation, declined, failed or voided order, key reused for another cart) allows a new key.
 * "Still settling" conflicts are retried with the same key.
 */
const SAME_KEY_CODES = new Set(['CHECKOUT_IN_PROGRESS', 'orders.checkout-in-progress', 'orders.late-add-in-flight']);

/** True when the error proves the attempt is over (a new key may be made). Network errors, timeouts and 5xx never do. */
export function definitiveFailure(error: unknown): boolean {
  if (!isApiError(error)) return false;
  const { status } = error;
  if (status < 400 || status >= 500 || status === 408 || status === 425 || status === 429) return false;
  return !(error.code && SAME_KEY_CODES.has(error.code));
}

/** The inverse, for readability at call sites: keep the key unless the failure is definitive. */
export const keepIdempotencyKey = (error: unknown): boolean => !definitiveFailure(error);
