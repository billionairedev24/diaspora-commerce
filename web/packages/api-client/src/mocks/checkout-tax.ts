/* Mock of the checkout tax estimate (gateway-bff priceTax): the stored decision per line, the delivery charge
   allocated over a package's lines by item value, blocked lines. Same rules as the BFF (Go) and orders (Java): the
   allocation is tested against tools/checkout/shipping-allocation-vectors.json.
   OPEN LEGAL DECISION 5: the mixed-basket delivery attribution method must be approved by the tax adviser. */
import { type CheckoutBlockedLine, type CheckoutPackage, type CheckoutTaxLine, type ShippingTaxAllocation, type TaxEstimateStatus, type TaxUnavailableReason } from '../schemas/shopper';
import { isGroceryProductType } from './service';

/** Product types whose tax decision is not definite yet (held for tax approval): such a line is blocked at checkout. */
export const MOCK_BLOCKED_PRODUCT_TYPES = new Set(['gift-cards', 'digital-downloads', 'event-tickets', 'tutoring']);

export type MockTaxStatus = CheckoutTaxLine['status'];

/** Exempt groceries, blocked held categories, everything else taxable (the mock stands in for tax-returns' decision). */
export function mockLineStatus(productType: string | undefined): MockTaxStatus {
  if (productType !== undefined && MOCK_BLOCKED_PRODUCT_TYPES.has(productType)) return 'BLOCKED';
  return isGroceryProductType(productType) ? 'EXEMPT' : 'TAXABLE';
}

export type AllocationMethod = 'PROPORTIONAL' | 'FIRST_LINE';

/** The delivery charge on each line: floor(total*w/sum) with spare minor units to the largest remainder (ties: earliest). */
export function allocateShipping(total: number, weights: number[], method: AllocationMethod = 'PROPORTIONAL'): number[] {
  const out = weights.map(() => 0);
  if (total <= 0 || weights.length === 0) return out;
  const w = weights.map((x) => Math.max(0, x));
  const sum = w.reduce((a, b) => a + b, 0);
  if (method === 'FIRST_LINE' || sum === 0) {
    out[0] = total;
    return out;
  }
  const rem = w.map((x, i) => {
    const product = BigInt(total) * BigInt(x);
    out[i] = Number(product / BigInt(sum));
    return Number(product % BigInt(sum));
  });
  for (let left = total - out.reduce((a, b) => a + b, 0); left > 0; left -= 1) {
    let best = 0;
    for (let i = 1; i < rem.length; i += 1) if ((rem[i] ?? 0) > (rem[best] ?? 0)) best = i;
    out[best] = (out[best] ?? 0) + 1;
    rem[best] = -1;
  }
  return out;
}

export interface MockTaxInput {
  packages: CheckoutPackage[];
  state?: string;
  city?: string;
  /** Delivery charge per package after the membership waiver and any coupon shipping discount. */
  shippingByPackage: Record<string, number>;
  /** The coupon's item discount for the whole order (spread over the lines by value). */
  discountMinor: number;
  hasAddress: boolean;
  /** The ZIP code of the destination (the address's, or the one typed before an address exists). */
  zip?: string;
}

/** Plain words for the tax row without an estimate (the BFF's labels; the storefront words them again per locale). Never "calculated later". */
export const TAX_LABELS = {
  NEEDS_ZIP: 'Enter your ZIP code to see tax',
  ADDRESS_INCOMPLETE: 'Add your state and ZIP code to this address to see tax',
  BLOCKED_ITEMS: "Remove the items we can't sell yet to see your tax",
  NO_ITEMS: 'Choose items to see tax',
  SERVICE_UNAVAILABLE: "We couldn't work out your tax right now. Try again.",
  FACTS_UNAVAILABLE: "We couldn't work out your tax right now. Try again."
} as const satisfies Record<TaxUnavailableReason, string>;

export interface MockTax {
  taxMinor: number;
  taxRateLabel: string;
  taxEstimateStatus: TaxEstimateStatus;
  taxUnavailableReason?: TaxUnavailableReason;
  taxZip?: string;
  taxJurisdictionLabel: string;
  taxLines: CheckoutTaxLine[];
  blockedLines: CheckoutBlockedLine[];
  canPlaceOrder: boolean;
  shippingTaxAllocation: ShippingTaxAllocation | undefined;
}

const STATE_RATE = 0.0625;
const LOCAL_RATE = 0.02;

/** Plain wording, no ids and no tax terms (the storefront words it again per locale). */
export const blockedMessage = (title: string) => `We can't sell ${title} yet. We're still checking its details. Remove it from your cart to continue, or try again later.`;

export function mockCheckoutTax(input: MockTaxInput): MockTax {
  const { packages, state, city, shippingByPackage, discountMinor, hasAddress, zip } = input;
  // Nothing selected (the BFF says this before it looks at the address): never a $0.00 estimate for an empty order.
  if (packages.length === 0) {
    return { taxMinor: 0, taxRateLabel: TAX_LABELS.NO_ITEMS, taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'NO_ITEMS', taxJurisdictionLabel: '', taxLines: [], blockedLines: [], canPlaceOrder: false, shippingTaxAllocation: undefined };
  }
  if (!hasAddress) {
    return { taxMinor: 0, taxRateLabel: TAX_LABELS.NEEDS_ZIP, taxEstimateStatus: 'NEEDS_ADDRESS', taxUnavailableReason: 'NEEDS_ZIP', taxJurisdictionLabel: '', taxLines: [], blockedLines: [], canPlaceOrder: false, shippingTaxAllocation: undefined };
  }
  if (!state?.trim() || !zip?.trim()) {
    return { taxMinor: 0, taxRateLabel: TAX_LABELS.ADDRESS_INCOMPLETE, taxEstimateStatus: 'UNAVAILABLE', taxUnavailableReason: 'ADDRESS_INCOMPLETE', taxJurisdictionLabel: '', taxLines: [], blockedLines: [], canPlaceOrder: false, shippingTaxAllocation: undefined };
  }
  const tx = state === 'TX';
  const lines = packages.flatMap((p) => p.lines.map((l) => ({ p, l })));
  const discounts = allocateShipping(discountMinor, lines.map(({ l }) => l.lineTotalMinor));
  let at = 0;
  const taxLines: CheckoutTaxLine[] = [];
  const blockedLines: CheckoutBlockedLine[] = [];
  let taxMinor = 0;
  for (const p of packages) {
    const net = p.lines.map((l, i) => Math.max(0, l.lineTotalMinor - (discounts[at + i] ?? 0)));
    const ship = allocateShipping(shippingByPackage[p.packageId] ?? 0, net);
    p.lines.forEach((l, i) => {
      const status = mockLineStatus(l.productType);
      const shippingMinor = ship[i] ?? 0;
      if (status === 'BLOCKED') {
        taxLines.push({ lineId: l.lineId, status, taxMinor: 0, shippingMinor });
        blockedLines.push({ lineId: l.lineId, offerId: l.offerId, atin: l.atin, title: l.title, packageId: p.packageId, message: blockedMessage(l.title), action: 'REMOVE' });
        return;
      }
      // Texas taxes delivery with the sale it belongs to; a delivery tied only to exempt lines is 0%.
      const tax = status === 'TAXABLE' && tx ? Math.round(((net[i] ?? 0) + shippingMinor) * (STATE_RATE + LOCAL_RATE)) : 0;
      taxMinor += tax;
      // The mock stands in for tax-returns: an exempt line carries that decision's own words (category + rule wording).
      const exemptReason = status === 'EXEMPT' ? `${l.department}: no sales tax applies. Standard qualifying item.` : undefined;
      taxLines.push({ lineId: l.lineId, status, taxMinor: tax, shippingMinor, ...(exemptReason ? { exemptReason } : {}) });
    });
    at += p.lines.length;
  }
  // Only Texas has rules (as in tax-returns): elsewhere the quote fails (the retry state), and with a blocked line there is no
  // figure; never "$0.00", "0%" or "calculated later".
  const estimated = tx && blockedLines.length === 0;
  const anyTaxable = taxLines.some((l) => l.status === 'TAXABLE');
  const reason: TaxUnavailableReason | undefined = blockedLines.length > 0 ? 'BLOCKED_ITEMS' : tx ? undefined : 'SERVICE_UNAVAILABLE';
  return {
    taxMinor: estimated ? taxMinor : 0,
    taxRateLabel: reason ? TAX_LABELS[reason] : anyTaxable ? `${((STATE_RATE + LOCAL_RATE) * 100).toFixed(2)}%` : '',
    taxEstimateStatus: estimated ? 'ESTIMATED' : 'UNAVAILABLE',
    ...(reason ? { taxUnavailableReason: reason } : { taxZip: zip }),
    taxJurisdictionLabel: estimated ? [city, state].filter(Boolean).join(', ') : '',
    taxLines,
    blockedLines,
    canPlaceOrder: estimated,
    shippingTaxAllocation: {
      method: 'PROPORTIONAL',
      approved: false,
      note: 'Delivery charges are attributed to the lines of a package by item value. For packages that mix taxable and exempt items this method is an open legal decision and must be approved by the tax adviser before production.'
    }
  };
}
