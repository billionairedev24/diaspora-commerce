import { isApiError } from '@afrotransact/api-client';

/**
 * Opening Stripe verification for the first time creates the seller's Stripe account behind the call, so it can take
 * a few seconds and, rarely, fail for a moment (Stripe busy, a dropped connection). The seller must not have to press
 * "Try again" for that: transient failures are retried here, a bounded number of times with growing waits, and only then
 * is the failure shown (with a friendly message and a button). Refusals (a 4xx with a reason) are never retried.
 */
export const stripeRetry = { delaysMs: [800, 2000] as number[] };

/** A failure that can pass by itself: no answer at all, a timeout, or the BFF reporting its upstream unavailable (502/503/504). */
export function isTransientFailure(e: unknown): boolean {
  if (!isApiError(e)) return true;
  return e.status === 502 || e.status === 503 || e.status === 504 || e.code === 'UPSTREAM_UNAVAILABLE';
}

/** Runs `attempt`, retrying transient failures after each wait in {@link stripeRetry}; `onRetry` is told before every wait. */
export async function withTransientRetry<T>(attempt: () => Promise<T>, onRetry?: (n: number) => void, shouldStop?: () => boolean): Promise<T> {
  for (let n = 0; ; n++) {
    try {
      return await attempt();
    } catch (e) {
      const wait = stripeRetry.delaysMs[n];
      if (wait === undefined || !isTransientFailure(e) || shouldStop?.()) throw e;
      onRetry?.(n + 1);
      await new Promise((resolve) => setTimeout(resolve, wait));
    }
  }
}
