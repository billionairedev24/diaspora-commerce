/* The staff-set shipping charge (listing rules D16, section 3 "Launch shipping"): the web mock of orders' shipping
   settings. ONE flat fee for Delivery by AfroTransact and for AfroTransact collecting from a store, an optional
   free-above threshold, and the seller-carrier switch (off at launch). `deliveryFee` is the one formula; the same numbers
   are tested against tools/checkout/shipping-fee-vectors.json (orders, gateway-bff and the customer app use them too). */

export interface MockShippingSettings {
  currency: 'USD';
  version: number;
  /** Delivery by AfroTransact / AfroTransact collects from the store and delivers: one flat fee, in minor units. */
  flatFeeMinor: number;
  /** Package items subtotal from which delivery is free; null = never free. */
  freeAboveMinor: number | null;
  /** Offer seller-shipped carrier options at checkout (off at launch; AfroTransact sets all shipping). */
  sellerCarrierShippingEnabled: boolean;
}

const LAUNCH_DEFAULT: MockShippingSettings = { currency: 'USD', version: 1, flatFeeMinor: 599, freeAboveMinor: null, sellerCarrierShippingEnabled: false };

let current: MockShippingSettings = { ...LAUNCH_DEFAULT };

export const getShippingSettings = (): MockShippingSettings => current;

/** A staff edit (the staff mock calls it): the next version, shoppers see it at once. */
export function setShippingSettings(patch: Partial<Omit<MockShippingSettings, 'currency' | 'version'>>): MockShippingSettings {
  current = { ...current, ...patch, version: current.version + 1 };
  return current;
}

export function resetShippingSettings(): void {
  current = { ...LAUNCH_DEFAULT };
}

/** The delivery charge of one package whose items cost `itemsMinor`: the flat fee, free at or above the threshold. */
export function deliveryFee(itemsMinor: number, s: Pick<MockShippingSettings, 'flatFeeMinor' | 'freeAboveMinor'> = current): number {
  return s.freeAboveMinor !== null && itemsMinor >= s.freeAboveMinor ? 0 : s.flatFeeMinor;
}
