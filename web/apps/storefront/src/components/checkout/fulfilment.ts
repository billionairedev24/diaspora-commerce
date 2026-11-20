import { type CheckoutPackage, type DeliveryOption } from '@afrotransact/api-client';

/**
 * How one package can reach the shopper, worked out from what the checkout summary says (no guessing):
 *  - choose: delivery and pick up are both possible; the shopper picks, delivery is the default.
 *  - delivery: only delivery.
 *  - pickup-only: pick up is the one way (the seller offers it only, or delivery can't reach the address).
 *  - none: neither; an honest dead end with the ways out (change address, remove the item).
 */
export type FulfilmentMode = 'choose' | 'delivery' | 'pickup-only' | 'none';

/** Why delivery is not offered, in the two cases the shopper can act on differently: the seller, or the address. */
export type NoDeliveryWhy = 'seller' | 'address' | null;

export type Fulfilment = {
  mode: FulfilmentMode;
  /** Delivery options that can be paid for now (never quote_required, never when the store can't serve the address). */
  delivery: DeliveryOption[];
  pickup: boolean;
  why: NoDeliveryWhy;
};

const isPayableDelivery = (o: DeliveryOption) => o.key !== 'pickup' && o.key !== 'quote_required';

export function fulfilmentOf(p: CheckoutPackage): Fulfilment {
  // A pre-order is delivered (or collected) on its window's date, priced by its own block: not a choice made here.
  if (p.preorder) return { mode: 'delivery', delivery: [], pickup: false, why: null };
  const undeliverable = p.localDelivery?.available === false;
  const delivery = undeliverable ? [] : p.deliveryOptions.filter(isPayableDelivery);
  const pickup = p.deliveryOptions.some((o) => o.key === 'pickup') && (p.pickupPoints?.length ?? 0) > 0;
  const mode: FulfilmentMode = delivery.length > 0 ? (pickup ? 'choose' : 'delivery') : pickup ? 'pickup-only' : 'none';
  // The server says why (deliveryUnavailableReason); an older answer without it is read from the same facts.
  const addressReason = p.deliveryUnavailableReason === 'ADDRESS' || undeliverable || p.deliveryOptions.some((o) => o.key === 'quote_required');
  const why: NoDeliveryWhy = delivery.length > 0 ? null : addressReason ? 'address' : p.deliveryUnavailableReason === 'SELLER_PICKUP_ONLY' || p.fulfilment === 'SELLER' ? 'seller' : null;
  return { mode, delivery, pickup, why };
}
