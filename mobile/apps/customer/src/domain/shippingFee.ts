/* The delivery fee at launch (listing rules D16): AfroTransact sets all shipping. ONE flat fee (staff set it, default $5.99)
   for Delivery by AfroTransact and for AfroTransact collecting from a store, free above an optional threshold; pickup is
   always free. Same formula as orders (ShippingSettings.deliveryFee) and gateway-bff; shared vectors in
   tools/checkout/shipping-fee-vectors.json. The mock BFF uses it; against the real BFF the fee comes from the summary. */
export interface ShippingSettings {
  flatFeeMinor: number;
  /** Package items subtotal from which delivery is free; null = never free. */
  freeAboveMinor: number | null;
}

/** The launch default: $5.99, never free. */
export const LAUNCH_SHIPPING: ShippingSettings = { flatFeeMinor: 599, freeAboveMinor: null };

export function deliveryFee(itemsMinor: number, settings: ShippingSettings = LAUNCH_SHIPPING): number {
  return settings.freeAboveMinor !== null && itemsMinor >= settings.freeAboveMinor ? 0 : settings.flatFeeMinor;
}
