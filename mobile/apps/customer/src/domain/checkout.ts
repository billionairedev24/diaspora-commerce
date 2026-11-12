/* Checkout rules shared by the screen and the payment adapters (pure; unit-tested). */
import { definitiveFailure, isApiError, isCartMismatch } from '@afrotransact/api-client';
import { type components } from '@afrotransact/api-client/generated/gateway-bff';

export type CheckoutSummary = components['schemas']['CheckoutSummary'];
export type PlaceOrderRequest = components['schemas']['PlaceOrderRequest'];
export type OrderConfirmation = components['schemas']['OrderConfirmation'];
/** An option an order can be placed with (listing rules section 3: no express; quote_required is never payable). */
export type PlaceableOption = PlaceOrderRequest['deliverySelections'][number]['option'];
/** An option the checkout page can show a package: the placeable ones and quote_required (outside every delivery zone). */
export type DeliveryOptionKey = CheckoutSummary['packages'][number]['deliveryOptions'][number]['key'];
export type Wallet = NonNullable<PlaceOrderRequest['wallet']>;

/** What the shopper reads when placing the order failed. A cart mismatch (a line gone or reduced, the cart emptied) is a
    plain "your cart changed" with a way back to the cart, never the problem's detail, which can name an internal ATIN. */
export const CART_CHANGED_MESSAGE = 'Your cart changed, please review it.';

/** Placing was refused because an item can't be sold yet (no definite tax decision): plain words, never the server's detail. */
export const ITEM_BLOCKED_MESSAGE = 'Some items in your order can’t be sold yet. Remove them to place your order.';

export function checkoutFailure(e: unknown): { message: string; cartChanged: boolean } {
  if (isCartMismatch(e)) return { message: CART_CHANGED_MESSAGE, cartChanged: true };
  if (isApiError(e) && e.code === 'CHECKOUT_ITEM_BLOCKED') return { message: ITEM_BLOCKED_MESSAGE, cartChanged: false };
  return { message: isApiError(e) ? e.message : e instanceof Error ? e.message : 'Payment failed. Try again.', cartChanged: false };
}

/** Orders over $100 (and wallet payments) are confirmed with Face ID (S165). */
export const DEVICE_AUTH_THRESHOLD_MINOR = 100_00;

export function requiresDeviceAuth(totalMinor: number, wallet?: Wallet | null): boolean {
  return totalMinor > DEVICE_AUTH_THRESHOLD_MINOR || wallet === 'apple_pay';
}

export type BlockedLine = NonNullable<CheckoutSummary['blockedLines']>[number];

/** Items that can't be sold yet: the order can't be placed while one remains; the shopper removes it. */
export function blockedLines(summary: CheckoutSummary): BlockedLine[] {
  return summary.blockedLines ?? [];
}

export function canPlaceOrder(summary: CheckoutSummary): boolean {
  return blockedLines(summary).length === 0 && summary.canPlaceOrder !== false;
}

/** Why delivery stops the order (a package to quote, or an unknown fee), in plain words; null when nothing does. */
export function deliveryBlock(summary: CheckoutSummary, choices: Record<string, PackageChoice>): string | null {
  const quote = summary.packages.some((p) => choices[p.packageId]?.option === 'quote_required');
  return quote ? QUOTE_REQUIRED_MESSAGE : (summary.deliveryBlockedReason ?? null);
}

/** The option an order is placed with: quote_required is never payable (validateChoices stops it), so it maps to pickup. */
export const placeable = (option: DeliveryOptionKey): PlaceableOption => (option === 'quote_required' ? 'pickup' : option);

/** Plain words naming the item and what happens next: no ids and no tax terms (the server's own message is English only). */
export function blockedItemText(line: Pick<BlockedLine, 'title'>): string {
  return `We can’t sell ${line.title || 'an item'} yet. We’re still checking its details. Remove it from this order to continue, or try again later.`;
}

/** "Estimated tax" with where it was worked out, e.g. "Estimated tax · Houston, TX". */
export function taxLabel(summary: CheckoutSummary): string {
  return summary.taxJurisdictionLabel ? `Estimated tax · ${summary.taxJurisdictionLabel}` : 'Estimated tax';
}

/** What the tax row shows: the amount only for a real estimate, never "$0.00" for a tax that is not known. */
export function taxValue(summary: CheckoutSummary, money: (minor: number, currency: string) => string): string {
  if (summary.taxEstimateStatus === 'ESTIMATED') return money(summary.taxMinor, summary.currency);
  return summary.taxEstimateStatus === 'NEEDS_ADDRESS' ? 'Add your address' : 'Calculated when you place your order';
}

/** Says when some or all items carry no tax (mixed or exempt baskets); null when there is nothing to say. */
export function taxNote(summary: CheckoutSummary): string | null {
  const lines = summary.taxLines ?? [];
  const exempt = lines.filter((l) => l.status === 'EXEMPT').length;
  if (exempt === 0) return null;
  return lines.some((l) => l.status === 'TAXABLE') ? 'No tax on some of your items, or on their delivery.' : 'No tax on these items, or on their delivery.';
}

export interface PackageChoice {
  option: DeliveryOptionKey;
  pickupPointId?: string;
  preferences?: NonNullable<PlaceOrderRequest['deliverySelections'][number]['preferences']>;
}

/** Default choice per package: the first free delivery option, else the first delivery option (pickup is only
    pre-selected when it is the only option, with its first pickup point). */
export function defaultChoices(summary: CheckoutSummary): Record<string, PackageChoice> {
  const out: Record<string, PackageChoice> = {};
  for (const p of summary.packages) {
    const delivered = p.deliveryOptions.filter((o) => o.key !== 'pickup');
    const pick = delivered.find((o) => o.free) ?? delivered[0];
    if (pick) out[p.packageId] = { option: pick.key };
    else if (p.deliveryOptions[0]) out[p.packageId] = { option: 'pickup', ...(p.pickupPoints?.[0] ? { pickupPointId: p.pickupPoints[0].id } : {}) };
  }
  return out;
}

/** Total for the selected delivery options (the summary total assumes the default options). */
export function totalFor(summary: CheckoutSummary, choices: Record<string, PackageChoice>): number {
  let shipping = 0;
  for (const p of summary.packages) {
    const key = choices[p.packageId]?.option;
    const opt = p.deliveryOptions.find((o) => o.key === key) ?? p.deliveryOptions[0];
    shipping += opt ? opt.feeMinor : 0;
  }
  return summary.subtotalMinor + shipping + summary.taxMinor;
}

/** Plain words for a package too far for standard delivery: the shopper asks for a quote, picks up or changes the address. */
export const QUOTE_REQUIRED_MESSAGE = 'Part of your order is too far for standard delivery. Ask us for a quote, choose pickup, or choose another address.';

export function validateChoices(summary: CheckoutSummary, choices: Record<string, PackageChoice>): string | null {
  for (const p of summary.packages) {
    const ch = choices[p.packageId];
    if (!ch) return `Choose a delivery option for ${p.heading}.`;
    if (ch.option === 'quote_required') return QUOTE_REQUIRED_MESSAGE;
    if (ch.option === 'pickup' && !ch.pickupPointId) return `Choose a pickup point for ${p.heading}.`;
  }
  return null;
}

export function buildPlaceOrderRequest(
  addressId: string,
  choices: Record<string, PackageChoice>,
  payment: { paymentMethodId: string } | { wallet: Wallet }
): PlaceOrderRequest {
  return {
    addressId,
    ...payment,
    deliverySelections: Object.entries(choices).map(([packageId, ch]) => ({
      packageId,
      option: placeable(ch.option),
      ...(ch.pickupPointId ? { pickupPointId: ch.pickupPointId } : {}),
      ...(ch.option === 'afrotransact_delivery'
        ? { preferences: { handover: 'hand' as const, contact: 'text' as const, ...ch.preferences } }
        : ch.preferences ? { preferences: ch.preferences } : {})
    }))
  };
}

/** Stripe PaymentSheet deferred-intent configuration built from the BFF checkout summary. */
export function paymentSheetIntent(summary: CheckoutSummary, totalMinor: number): { amount: number; currencyCode: string } {
  return { amount: totalMinor, currencyCode: summary.currency.toUpperCase() };
}

/** Idempotency-Key for POST /v1/checkout/orders (16–64 chars): one per checkout attempt, reused on retry. */
/**
 * After a failed purchase attempt: keep the Idempotency-Key (a retry replays the same order and PaymentIntent, so the
 * customer is never charged twice) unless the failure is definitive. A failure is definitive when the request was
 * refused (a 4xx, not "still in progress") or orders reports the known order failed; network errors, timeouts, 5xx and
 * PaymentSheet errors keep the key. `status` asks orders about the known order, if any.
 */
export async function keyAfterFailure(key: string | null, error: unknown, status?: () => Promise<{ status: string }>): Promise<string | null> {
  if (definitiveFailure(error)) return null;
  if (!status) return key;
  try {
    await status();
    return key;
  } catch (e) {
    return definitiveFailure(e) ? null : key;
  }
}

export function idempotencyKey(random: () => string): string {
  const key = `co_${random().replace(/-/g, '')}`;
  return key.length > 64 ? key.slice(0, 64) : key.padEnd(16, '0');
}
