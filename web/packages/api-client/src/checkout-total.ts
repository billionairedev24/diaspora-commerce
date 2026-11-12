import { z } from 'zod';

import { isApiError } from './errors';

/** The problem code of an order that would cost more than the total the shopper saw (409; nothing was charged). */
export const CHECKOUT_TOTAL_CHANGED = 'CHECKOUT_TOTAL_CHANGED';

const MinorField = z.number().int();

/** The new numbers a CHECKOUT_TOTAL_CHANGED problem carries, and a fresh quote lock for them. */
const TotalChangeSchema = z.object({
  previousTotalMinor: MinorField,
  totalMinor: MinorField,
  taxMinor: MinorField,
  shippingMinor: MinorField.default(0),
  subtotalMinor: MinorField.default(0),
  discountMinor: MinorField.default(0),
  currency: z.string().default('USD'),
  taxQuoteId: z.string().optional(),
  taxQuoteExpiresAt: z.string().optional()
});
export type TotalChange = z.infer<typeof TotalChangeSchema>;

/**
 * The new total when Place order was refused because it would cost more than the total shown (409 CHECKOUT_TOTAL_CHANGED), else
 * null. Show the new total and ask the shopper to confirm BEFORE charging anything; confirming places the order again with a NEW
 * Idempotency-Key and the problem's taxQuoteId (definitiveFailure already says a new key may be made).
 */
export function totalChangeOf(error: unknown): TotalChange | null {
  if (!isApiError(error) || error.code !== CHECKOUT_TOTAL_CHANGED) return null;
  const parsed = TotalChangeSchema.safeParse(error.problem);
  return parsed.success ? parsed.data : null;
}
