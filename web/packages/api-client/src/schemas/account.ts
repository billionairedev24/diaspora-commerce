import { z } from 'zod';

import { MinorSchema } from './common';

/* Account resources: addresses, saved payment methods, orders and returns (summaries). Detail screens,
   security, checkout and services live in ./shopper. */

export const AddressSchema = z.object({
  id: z.string(),
  name: z.string(),
  phone: z.string(),
  line1: z.string(),
  line2: z.string(),
  city: z.string(),
  state: z.string(),
  zip: z.string(),
  isDefault: z.boolean(),
  verified: z.boolean()
});
export type Address = z.infer<typeof AddressSchema>;

export const PaymentMethodSchema = z.object({
  id: z.string(),
  type: z.enum(['card', 'us_bank_account', 'cashapp', 'link', 'amazon_pay']),
  brand: z.string().nullable(),
  last4: z.string().nullable(),
  label: z.string(),
  expires: z.string().nullable(),
  isDefault: z.boolean()
});
export type PaymentMethod = z.infer<typeof PaymentMethodSchema>;

export const OrderSummarySchema = z.object({
  orderNumber: z.string(),
  placedAt: z.string(),
  totalMinor: MinorSchema,
  currency: z.string(),
  shipTo: z.string(),
  status: z.string(),
  shipments: z.array(
    z.object({
      shipmentId: z.string(),
      label: z.string(),
      status: z.string(),
      items: z.string(),
      cancellable: z.boolean().optional(),
      cancellableUntil: z.string().nullable().optional(),
      carrier: z.string().nullable().optional(),
      trackingNumber: z.string().nullable().optional(),
      promisedBy: z.string().nullable().optional(),
      deliveredAt: z.string().nullable().optional(),
      deliveryCode: z.string().nullable().optional(),
      returnable: z.boolean().optional(),
      returnWindowEndsAt: z.string().nullable().optional(),
      problemReportOnly: z.boolean().optional()
    })
  )
});
export type OrderSummary = z.infer<typeof OrderSummarySchema>;

export const ReturnSummarySchema = z.object({
  rmaId: z.string(),
  orderNumber: z.string(),
  title: z.string(),
  status: z.string(),
  refundMinor: MinorSchema,
  number: z.string().optional(),
  method: z.enum(['DROP_OFF', 'DRIVER_PICKUP', 'NO_RETURN']).optional(),
  reason: z.string().optional(),
  refundState: z.string().optional(),
  currency: z.string().optional(),
  cancellable: z.boolean().optional(),
  createdAt: z.string().nullable().optional(),
  labelUrl: z.string().nullable().optional(),
  qrPayload: z.string().nullable().optional(),
  handoverCode: z.string().nullable().optional(),
  dropOffBy: z.string().nullable().optional(),
  carrier: z.string().nullable().optional(),
  trackingNumber: z.string().nullable().optional()
});
export type ReturnSummary = z.infer<typeof ReturnSummarySchema>;

export const PageOfSchema = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item), nextCursor: z.string().nullable() });
