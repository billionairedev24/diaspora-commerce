/** Stripe cannot charge less than 0.50: credit leaves at least this on the card, or pays everything. */
export const MIN_CARD_MINOR = 50;

/**
 * How an order total is paid when AfroTransact credit is used (S320), the same rule payments applies when the order is
 * placed: credit first, never more than the balance; a remainder under 0.50 cannot go to a card, so credit then keeps
 * 0.50 back for it (or, on a tiny order it cannot cover, stays unused).
 */
export function creditSplit(balanceMinor: number, totalMinor: number): { appliedMinor: number; cardDueMinor: number } {
  let applied = Math.max(0, Math.min(balanceMinor, totalMinor));
  const rest = totalMinor - applied;
  if (rest > 0 && rest < MIN_CARD_MINOR) applied = Math.max(0, totalMinor - MIN_CARD_MINOR);
  return { appliedMinor: applied, cardDueMinor: totalMinor - applied };
}
