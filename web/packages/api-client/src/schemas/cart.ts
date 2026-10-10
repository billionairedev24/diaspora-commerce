import { z } from 'zod';

import { AtinSchema, DeliveryPromiseSchema, FulfilmentSchema, SellerSchema, StockStatusSchema } from './catalog';
import { MinorSchema } from './common';

export const MAX_LINE_QUANTITY = 99;

export const CartLineSchema = z.object({
  lineId: z.string(),
  atin: AtinSchema,
  offerId: z.string(),
  title: z.string(),
  unit: z.string(),
  department: z.string(),
  imageUrl: z.string().nullable(),
  priceMinor: MinorSchema,
  lineTotalMinor: MinorSchema,
  quantity: z.number().int().min(1),
  /** Highest quantity the shopper may set: min(available stock, 99). */
  maxQuantity: z.number().int().min(0),
  availableQty: z.number().int().min(0),
  stockStatus: StockStatusSchema,
  selected: z.boolean(),
  /** Perishable (catalog): AfroTransact Delivery and pickup need a handover code, no safe place. */
  perishable: z.boolean().optional()
});
export type CartLine = z.infer<typeof CartLineSchema>;

export const DeliveryOptionSchema = z.object({
  key: z.enum(['carrier', 'express', 'afrotransact_delivery', 'pickup']),
  label: z.string(),
  eta: z.string(),
  feeMinor: MinorSchema,
  free: z.boolean(),
  /** Checkout only: promise dates for this option. */
  earliestDate: z.string().optional(),
  latestDate: z.string().optional(),
  cutoffLabel: z.string().optional()
});
export type DeliveryOption = z.infer<typeof DeliveryOptionSchema>;

export const CartPackageSchema = z.object({
  packageId: z.string(),
  seller: SellerSchema,
  fulfilment: FulfilmentSchema,
  /** "Shipped by AfroTransact from Houston FC" / "Ships from Lagos Foods NJ, Newark, NJ". */
  heading: z.string(),
  shipsFrom: z.string(),
  delivery: DeliveryPromiseSchema,
  deliveryOptions: z.array(DeliveryOptionSchema),
  lines: z.array(CartLineSchema),
  itemCount: z.number().int().min(0),
  subtotalMinor: MinorSchema,
  freeShippingNudge: z.object({ remainingMinor: MinorSchema, thresholdMinor: MinorSchema }).nullable()
});
export type CartPackage = z.infer<typeof CartPackageSchema>;

export const SavedItemSchema = z.object({
  savedId: z.string(),
  atin: AtinSchema,
  offerId: z.string(),
  title: z.string(),
  imageUrl: z.string().nullable(),
  priceMinor: MinorSchema,
  available: z.boolean()
});
export type SavedItem = z.infer<typeof SavedItemSchema>;

export const CartSchema = z.object({
  cartId: z.string(),
  currency: z.string(),
  /** One package per seller and fulfilment method. */
  packages: z.array(CartPackageSchema),
  saved: z.array(SavedItemSchema),
  /** Units across all lines (header badge). */
  itemCount: z.number().int().min(0),
  /** Units on selected lines (subtotal and checkout). */
  selectedCount: z.number().int().min(0),
  subtotalMinor: MinorSchema,
  allSelected: z.boolean(),
  sellerCount: z.number().int().min(0)
});
export type Cart = z.infer<typeof CartSchema>;

export const AddToCartRequestSchema = z.object({ offerId: z.string().min(1), quantity: z.number().int().min(1).max(MAX_LINE_QUANTITY) });
export type AddToCartRequest = z.infer<typeof AddToCartRequestSchema>;

export const UpdateCartLineRequestSchema = z
  .object({ quantity: z.number().int().min(1).max(MAX_LINE_QUANTITY).optional(), selected: z.boolean().optional() })
  .refine((v) => v.quantity !== undefined || v.selected !== undefined, 'Provide quantity or selected');
export type UpdateCartLineRequest = z.infer<typeof UpdateCartLineRequestSchema>;

export const SelectAllRequestSchema = z.object({ selected: z.boolean() });
