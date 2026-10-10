import { z } from 'zod';

import { MinorSchema } from './common';

export const FulfilmentSchema = z.enum(['AFROTRANSACT', 'SELLER']);
export type Fulfilment = z.infer<typeof FulfilmentSchema>;

export const SellerKindSchema = z.enum(['PLATFORM', 'SELLER']);
export type SellerKind = z.infer<typeof SellerKindSchema>;

/** IN_STOCK above 5 units, LOW_STOCK at 1–5 ("Only N left — order soon"), OUT_OF_STOCK at 0. */
export const StockStatusSchema = z.enum(['IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK']);
export type StockStatus = z.infer<typeof StockStatusSchema>;

export const LOW_STOCK_THRESHOLD = 5;

export const AtinSchema = z.string().regex(/^AT[0-9A-Z]{8}$/, 'Invalid ATIN');

export const DeliveryPromiseSchema = z.object({
  /** Business days until delivery for the shopper's deliver-to ZIP (0 = today). */
  days: z.number().int().min(0),
  /** Human label, e.g. "Tomorrow, Oct 11" or "Wednesday, Oct 14". */
  label: z.string(),
  /** "FREE delivery" or "Delivery". */
  prefix: z.string(),
  free: z.boolean(),
  feeMinor: MinorSchema,
  /** Fastest option for AfroTransact-fulfilled offers ("Or fastest delivery Today"). */
  expressLabel: z.string().nullable(),
  /** Order-by cut-off note, e.g. "Order within 4 hrs 12 mins." */
  cutoff: z.string().nullable()
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
  sponsor: z.object({ advertiser: z.string() }).nullable()
});
export type ProductSummary = z.infer<typeof ProductSummarySchema>;

export const VariantOptionSchema = z.object({
  atin: AtinSchema,
  label: z.string(),
  selected: z.boolean(),
  available: z.boolean(),
  priceMinor: MinorSchema
});
export type VariantOption = z.infer<typeof VariantOptionSchema>;

export const ProductDetailSchema = z.object({
  product: z.object({
    atin: AtinSchema,
    title: z.string(),
    brand: z.string(),
    community: z.string(),
    department: z.string(),
    unit: z.string(),
    origin: z.string(),
    bullets: z.array(z.string()),
    rating: z.number().min(0).max(5),
    reviewCount: z.number().int().min(0),
    images: z.array(z.object({ url: z.string().nullable(), alt: z.string() })),
    variants: z.array(VariantOptionSchema)
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

export const StockAlertRequestSchema = z.object({ email: z.email().optional() });
export type StockAlertRequest = z.infer<typeof StockAlertRequestSchema>;
export const StockAlertSchema = z.object({ atin: AtinSchema, status: z.enum(['subscribed']) });
export type StockAlert = z.infer<typeof StockAlertSchema>;
