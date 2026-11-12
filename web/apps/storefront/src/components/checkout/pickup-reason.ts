import { type CheckoutPackage } from '@afrotransact/api-client';

type Reason = NonNullable<CheckoutPackage['pickupUnavailableReason']>;

/**
 * The shopper-facing words for why a package has no pickup option, only when they help: the seller doesn't offer it, a
 * delivery address is needed, or nothing is near the address. Internal reasons (no AfroTransact point, a failed lookup)
 * say nothing, so the shopper never sees system wording. Returns a message key, or null for no message.
 */
export function pickupReasonKey(reason: Reason | undefined): 'co.pickupNone.seller' | 'co.pickupNone.address' | 'co.pickupNone.area' | null {
  switch (reason) {
    case 'SELLER_NOT_ENABLED':
      return 'co.pickupNone.seller';
    case 'NEEDS_ADDRESS':
      return 'co.pickupNone.address';
    case 'TOO_FAR':
    case 'OTHER_AREA':
      return 'co.pickupNone.area';
    default:
      return null;
  }
}
