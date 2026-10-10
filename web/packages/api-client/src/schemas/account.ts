import { z } from 'zod';

import { MinorSchema } from './common';

/* Account resources (addresses, payment methods, orders, returns). Pages ship in the next phase;
   the schemas exist so the contract, mocks and generated types stay aligned. */

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
  type: z.enum(['card', 'us_bank_account', 'cashapp']),
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
  shipments: z.array(z.object({ shipmentId: z.string(), label: z.string(), status: z.string(), items: z.string() }))
});
export type OrderSummary = z.infer<typeof OrderSummarySchema>;

export const ReturnSummarySchema = z.object({
  rmaId: z.string(),
  orderNumber: z.string(),
  title: z.string(),
  status: z.string(),
  refundMinor: MinorSchema
});
export type ReturnSummary = z.infer<typeof ReturnSummarySchema>;

export const PageOfSchema = <T extends z.ZodType>(item: T) => z.object({ items: z.array(item), nextCursor: z.string().nullable() });
