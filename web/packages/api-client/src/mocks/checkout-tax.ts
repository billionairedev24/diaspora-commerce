/* Mock of the checkout tax estimate (gateway-bff priceTax): the stored decision per line, the delivery charge
   allocated over a package's lines by item value, blocked lines. Same rules as the BFF (Go) and orders (Java): the
   allocation is tested against tools/checkout/shipping-allocation-vectors.json.
   OPEN LEGAL DECISION 5: the mixed-basket delivery attribution method must be approved by the tax adviser. */
import { type CheckoutBlockedLine, type CheckoutPackage, type CheckoutTaxLine, type ShippingTaxAllocation } from '../schemas/shopper';
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
}

export interface MockTax {
  taxMinor: number;
  taxRateLabel: string;
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
  const { packages, state, city, shippingByPackage, discountMinor, hasAddress } = input;
  if (!hasAddress) {
    return { taxMinor: 0, taxRateLabel: 'Add your address to see an estimate', taxJurisdictionLabel: '', taxLines: [], blockedLines: [], canPlaceOrder: true, shippingTaxAllocation: undefined };
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
      taxLines.push({ lineId: l.lineId, status, taxMinor: tax, shippingMinor });
    });
    at += p.lines.length;
  }
  const label = tx ? `${((STATE_RATE + LOCAL_RATE) * 100).toFixed(2)}%` : 'No sales tax';
  return {
    taxMinor,
    taxRateLabel: label,
    taxJurisdictionLabel: [city, state].filter(Boolean).join(', '),
    taxLines,
    blockedLines,
    canPlaceOrder: blockedLines.length === 0,
    shippingTaxAllocation: {
      method: 'PROPORTIONAL',
      approved: false,
      note: 'Delivery charges are attributed to the lines of a package by item value. For packages that mix taxable and exempt items this method is an open legal decision and must be approved by the tax adviser before production.'
    }
  };
}
