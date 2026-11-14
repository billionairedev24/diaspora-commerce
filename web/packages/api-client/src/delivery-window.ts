/**
 * The one rule for showing a delivery date: only a window the promise service made from real inputs (`basis` PROMISE with a
 * label). Anything else (basis NONE, an older server that sends no basis, an empty label) has no date, and nothing may show one:
 * show the method, or the "date shown at checkout" note when the place is what is missing.
 */
export type DeliveryWindowLike = { basis?: 'PROMISE' | 'NONE'; label: string; reason?: string };

export function hasDeliveryWindow(p: DeliveryWindowLike): boolean {
  return p.basis === 'PROMISE' && p.label.trim() !== '';
}

/** True when the date is missing only because the shopper's place is not known yet (the cart says it is shown at checkout). */
export function deliveryWindowNeedsPlace(p: DeliveryWindowLike): boolean {
  return !hasDeliveryWindow(p) && p.reason === 'NO_PLACE';
}
