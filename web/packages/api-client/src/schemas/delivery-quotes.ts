import { z } from 'zod';

/* Ask for a delivery quote (listing rules D20) — contracts/openapi/gateway-bff.yaml, tag Delivery quotes. When the address is
   outside the delivery zones the shopper asks, customer care agrees an amount, and the shopper checks out with the quote.
   The shopper sees a plain status and the agreed amount, never who agreed it or why. */

const nullableString = z.string().nullable();

export const QUOTE_CONTACTS = ['EMAIL', 'PHONE', 'SMS'] as const;
/** How customer care may reach the shopper about a quote. */
export type QuoteContact = (typeof QUOTE_CONTACTS)[number];

export const QUOTE_STATUSES = ['REQUESTED', 'IN_PROGRESS', 'READY', 'EXPIRED', 'DECLINED', 'USED'] as const;
export type QuoteStatus = (typeof QUOTE_STATUSES)[number];
export const QUOTE_STATUS_LABELS = ['Requested', 'Being quoted', 'Quote ready', 'Expired', 'Declined', 'Used'] as const;

export const QuoteRequestSchema = z.object({
  id: z.string(),
  reference: z.string(),
  status: z.enum(QUOTE_STATUSES),
  /** The plain word to show: Requested, Being quoted, Quote ready, Expired, Declined (or Used). */
  statusLabel: z.enum(QUOTE_STATUS_LABELS),
  /** The agreed delivery charge; null until the quote is ready. */
  amountMinor: z.number().int().nullable(),
  currency: z.string(),
  /** When a ready quote stops being usable. */
  expiresAt: nullableString,
  /** The quote can be applied at checkout right now. */
  usable: z.boolean(),
  /** City, state and ZIP of the address. */
  place: z.string(),
  packages: z.array(z.object({ packageId: z.string(), sellerName: nullableString, fulfilment: z.string(), itemCount: z.number().int() })),
  lines: z.array(z.object({ variantAtin: z.string(), title: nullableString, packageId: z.string(), quantity: z.number().int(), unitPriceMinor: z.number().int() })),
  contact: z.enum(QUOTE_CONTACTS),
  note: nullableString,
  createdAt: z.string(),
  updatedAt: z.string(),
  /** Where it stands and what to do next, in plain words. */
  message: z.string()
});
export type QuoteRequest = z.infer<typeof QuoteRequestSchema>;

export const QuoteRequestPageSchema = z.object({ items: z.array(QuoteRequestSchema) });
export type QuoteRequestPage = z.infer<typeof QuoteRequestPageSchema>;

export const NewQuoteRequestBodySchema = z.object({
  addressId: z.string().min(1),
  lines: z.array(z.object({ variantAtin: z.string(), quantity: z.number().int().min(1) })).optional(),
  contact: z.enum(QUOTE_CONTACTS).optional(),
  note: z.string().max(500).optional()
});
export type NewQuoteRequestBody = z.infer<typeof NewQuoteRequestBodySchema>;
