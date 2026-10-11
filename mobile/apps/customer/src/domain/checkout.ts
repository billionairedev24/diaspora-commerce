/* Checkout rules shared by the screen and the payment adapters (pure; unit-tested). */
import { definitiveFailure } from '@afrotransact/api-client';
import { type components } from '@afrotransact/api-client/generated/gateway-bff';

export type CheckoutSummary = components['schemas']['CheckoutSummary'];
export type PlaceOrderRequest = components['schemas']['PlaceOrderRequest'];
export type OrderConfirmation = components['schemas']['OrderConfirmation'];
export type DeliveryOptionKey = PlaceOrderRequest['deliverySelections'][number]['option'];
export type Wallet = NonNullable<PlaceOrderRequest['wallet']>;

/** Orders over $100 (and wallet payments) are confirmed with Face ID (S165). */
export const DEVICE_AUTH_THRESHOLD_MINOR = 100_00;

export function requiresDeviceAuth(totalMinor: number, wallet?: Wallet | null): boolean {
  return totalMinor > DEVICE_AUTH_THRESHOLD_MINOR || wallet === 'apple_pay';
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

export function validateChoices(summary: CheckoutSummary, choices: Record<string, PackageChoice>): string | null {
  for (const p of summary.packages) {
    const ch = choices[p.packageId];
    if (!ch) return `Choose a delivery option for ${p.heading}.`;
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
      option: ch.option,
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
