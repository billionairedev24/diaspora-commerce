import { z } from 'zod';

import { MinorSchema } from './common';

/** AFROTRANSACT = Fulfilled by AfroTransact; SELLER = Ships from seller; AFROTRANSACT_DELIVERY = "AfroTransact Delivery from
 *  <store city>" (the seller packs, an AfroTransact driver collects at the store; priced by distance at checkout, S255). */
export const FulfilmentSchema = z.enum(['AFROTRANSACT', 'SELLER', 'AFROTRANSACT_DELIVERY']);
export type Fulfilment = z.infer<typeof FulfilmentSchema>;

export const SellerKindSchema = z.enum(['PLATFORM', 'SELLER']);
export type SellerKind = z.infer<typeof SellerKindSchema>;

/** IN_STOCK above 5 units, LOW_STOCK at 1–5 ("Only N left — order soon"), OUT_OF_STOCK at 0. */
export const StockStatusSchema = z.enum(['IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK']);
export type StockStatus = z.infer<typeof StockStatusSchema>;

export const LOW_STOCK_THRESHOLD = 5;

export const AtinSchema = z.string().regex(/^AT[0-9A-Z]{8}$/, 'Invalid ATIN');

export const ArrivalReasonSchema = z.enum(['NO_PLACE', 'NO_ORIGIN', 'NO_SERVICE', 'UNAVAILABLE']);
export type ArrivalReason = z.infer<typeof ArrivalReasonSchema>;

/**
 * How an offer or package is delivered and, only with a real basis, when. `basis` PROMISE: the promise service dated it from
 * the seller's handling time, the ship-from ZIP and the shopper's place (`label` is a window, "Nov 18 – 20"). `basis` NONE: no
 * real basis, `label` is empty, `reason` says what is missing; show the method, never a date.
 */
export const DeliveryPromiseSchema = z.object({
  /** Calendar days to the earliest date; meaningless unless `basis` is PROMISE. */
  days: z.number().int().min(0),
  /** Window label, e.g. "Nov 18 – 20" or "Wed, Nov 18"; EMPTY when `basis` is NONE. */
  label: z.string(),
  /** The method, plain "Delivery". */
  prefix: z.string(),
  free: z.boolean(),
  feeMinor: MinorSchema,
  /** The promise service's order-by countdown, e.g. "Order within 4 hrs 12 mins". */
  cutoff: z.string().nullable(),
  /** Absent (an older server) means NONE: never a date. */
  basis: z.enum(['PROMISE', 'NONE']).optional(),
  reason: ArrivalReasonSchema.optional(),
  earliestDate: z.string().optional(),
  latestDate: z.string().optional()
});
export type DeliveryPromise = z.infer<typeof DeliveryPromiseSchema>;

export const SellerSchema = z.object({
  id: z.string(),
  name: z.string(),
  kind: SellerKindSchema,
  city: z.string()
});
export type Seller = z.infer<typeof SellerSchema>;

export const OfferSchema = z.object({
  offerId: z.string(),
  seller: SellerSchema,
  priceMinor: MinorSchema,
  listPriceMinor: MinorSchema.nullable(),
  currency: z.string(),
  fulfilment: FulfilmentSchema,
  availableQty: z.number().int().min(0),
  stockStatus: StockStatusSchema,
  delivery: DeliveryPromiseSchema
});
export type Offer = z.infer<typeof OfferSchema>;

export const ProductSummarySchema = z.object({
  atin: AtinSchema,
  title: z.string(),
  brand: z.string(),
  community: z.string(),
  department: z.string(),
  unit: z.string(),
  rating: z.number().min(0).max(5),
  reviewCount: z.number().int().min(0),
  imageUrl: z.string().nullable(),
  /** Buy-box offer. */
  offerId: z.string(),
  priceMinor: MinorSchema,
  listPriceMinor: MinorSchema.nullable(),
  currency: z.string(),
  sellerName: z.string(),
  sellerKind: SellerKindSchema,
  sellerCity: z.string(),
  fulfilment: FulfilmentSchema,
  availableQty: z.number().int().min(0),
  inStock: z.boolean(),
  lowStock: z.boolean(),
  stockStatus: StockStatusSchema,
  delivery: DeliveryPromiseSchema,
  offerCount: z.number().int().min(1),
  minOfferPriceMinor: MinorSchema,
  /** Eligible for the shopper's pre-order window (catalog preorderEligible, category in the window, not shipsNow). */
  preorderEligible: z.boolean().optional(),
  sponsor: z
    .object({
      advertiser: z.string(),
      // Search ads (S240): campaign, the slot it won, the click token and why it was shown.
      campaignId: z.string().optional(),
      slot: z.number().int().min(1).optional(),
      adToken: z.string().optional(),
      /** Home sponsored products: the search the ad came from (send with the click). */
      searchId: z.string().optional(),
      reason: z
        .object({
          targeting: z.enum(['KEYWORD', 'CATEGORY']).catch('KEYWORD'),
          matched: z.string(),
          qualityScore: z.number(),
          auctionRank: z.number().int(),
          competitors: z.number().int(),
          eligibility: z.array(z.string()),
          summary: z.string()
        })
        .optional()
    })
    .nullable()
});
export type ProductSummary = z.infer<typeof ProductSummarySchema>;

export const VariantOptionSchema = z.object({
  atin: AtinSchema,
  label: z.string(),
  selected: z.boolean(),
  available: z.boolean(),
  priceMinor: MinorSchema,
  /** The variant's value per dimension as the seller wrote it ({ Size: 'UK 12', Color: 'Indigo adire' }). */
  options: z.record(z.string(), z.string()).default({})
});
export type VariantOption = z.infer<typeof VariantOptionSchema>;

/** The seller's size chart of a sized product. Values are in `unitSystem`; `alternateValues` are the other unit, for a cm / in switch. */
export const ProductSizeChartSchema = z.object({
  name: z.string(),
  unitSystem: z.enum(['CM', 'IN']),
  alternateUnit: z.enum(['CM', 'IN']),
  columns: z.array(z.object({ key: z.string(), label: z.string(), unit: z.enum(['CM', 'IN']) })),
  rows: z.array(
    z.object({
      size: z.string(),
      values: z.record(z.string(), z.object({ min: z.number(), max: z.number() })),
      alternateValues: z.record(z.string(), z.object({ min: z.number(), max: z.number() }))
    })
  ),
  /** The product's own fit note, else the chart's. */
  fitNote: z.enum(['RUNS_SMALL', 'TRUE_TO_SIZE', 'RUNS_LARGE']).nullable(),
  howToMeasure: z.string().nullable()
});
export type ProductSizeChart = z.infer<typeof ProductSizeChartSchema>;

export const ProductDetailSchema = z.object({
  /** Null for products that are not sized (only fashion product types carry a size chart). */
  sizeChart: ProductSizeChartSchema.nullable().default(null),
  product: z.object({
    atin: AtinSchema,
    title: z.string(),
    brand: z.string(),
    community: z.string(),
    department: z.string(),
    unit: z.string(),
    bullets: z.array(z.string()),
    rating: z.number().min(0).max(5),
    reviewCount: z.number().int().min(0),
    images: z.array(z.object({ url: z.string().nullable(), alt: z.string() })),
    variants: z.array(VariantOptionSchema),
    /** Eligible for the shopper's pre-order window (see ProductSummary.preorderEligible). */
    preorderEligible: z.boolean().optional()
  }),
  /** Winning offer; null when nothing is purchasable. */
  buyBox: OfferSchema.nullable(),
  otherOffers: z.array(OfferSchema),
  shipsFrom: z.string(),
  soldBy: z.string(),
  returnsPolicy: z.string(),
  unitPriceLabel: z.string()
});
export type ProductDetail = z.infer<typeof ProductDetailSchema>;

/** A plain name@domain.tld address, like the BFF accepts: no display name, quotes, spaces or non-ASCII; at most 254 characters. */
export function isPlainEmail(value: string): boolean {
  const v = value.trim();
  return v.length <= 254 && /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/.test(v) && !/(^\.|\.\.|\.@)/.test(v) && /\.[A-Za-z]{2,}$|\.xn--[a-z0-9-]+$/i.test(v);
}
export const StockAlertRequestSchema = z.object({ email: z.string().refine(isPlainEmail, 'Enter a valid email address, like name@example.com').optional() });
export type StockAlertRequest = z.infer<typeof StockAlertRequestSchema>;
export const StockAlertSchema = z.object({ atin: AtinSchema, status: z.enum(['subscribed']) });
export type StockAlert = z.infer<typeof StockAlertSchema>;
