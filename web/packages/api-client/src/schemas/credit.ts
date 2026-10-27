import { z } from 'zod';

/* AfroTransact credit and gift cards (S320, S321, ADR 0006) — contracts/openapi/gateway-bff.yaml, tag Credit.
   Credit only buys products and services: nothing here pays it out, refunds it to a card or moves it to someone else. */

const MinorSchema = z.number().int();
const nullableString = z.string().nullable();

export const CREDIT_KINDS = ['GRANT', 'SPEND', 'REFUND', 'ADJUSTMENT'] as const;
export const CREDIT_SOURCES = ['REFERRAL', 'GIFT_CARD', 'MEMBERSHIP', 'REFUND', 'STAFF', 'PROMO', 'ORDER'] as const;

export const CreditEntrySchema = z.object({
  id: z.string(),
  kind: z.enum(CREDIT_KINDS),
  source: z.enum(CREDIT_SOURCES),
  /** Signed: credit added is positive, credit spent negative. */
  amountMinor: MinorSchema,
  currency: z.string(),
  balanceAfterMinor: MinorSchema,
  createdAt: z.string()
});
export type CreditEntry = z.infer<typeof CreditEntrySchema>;

export const CreditAccountSchema = z.object({
  balanceMinor: MinorSchema,
  currency: z.string(),
  entries: z.array(CreditEntrySchema),
  /** Pass as `before` for older entries; null on the last page. */
  nextBefore: nullableString
});
export type CreditAccount = z.infer<typeof CreditAccountSchema>;

/** What the shopper's credit would pay of the checkout order if used. */
export const CheckoutCreditSchema = z.object({
  availableMinor: MinorSchema,
  appliedMinor: MinorSchema,
  /** What the card pays then; 0 means no payment method is needed. */
  cardDueMinor: MinorSchema
});
export type CheckoutCredit = z.infer<typeof CheckoutCreditSchema>;

export const CreditRefundChoiceSchema = z.object({
  orderNumber: z.string(),
  cardRefundsAsCredit: z.boolean(),
  cardPartMinor: MinorSchema,
  currency: z.string()
});
export type CreditRefundChoice = z.infer<typeof CreditRefundChoiceSchema>;

export const GiftCardConfigSchema = z.object({
  minMinor: MinorSchema,
  maxMinor: MinorSchema,
  currency: z.string(),
  /** The Stripe publishable key the buy page's Payment Element is created with. */
  publishableKey: z.string(),
  presetsMinor: z.array(MinorSchema),
  /** Staff-written terms by language; empty means the page shows its built-in terms. */
  terms: z.record(z.string(), z.string())
});
export type GiftCardConfig = z.infer<typeof GiftCardConfigSchema>;

export const GIFT_CARD_STATUSES = ['PENDING_PAYMENT', 'ACTIVE', 'REDEEMED', 'VOID', 'PAYMENT_FAILED'] as const;

export const GiftCardSchema = z.object({
  id: z.string(),
  status: z.enum(GIFT_CARD_STATUSES),
  amountMinor: MinorSchema,
  currency: z.string(),
  recipientEmail: z.string(),
  recipientName: nullableString,
  senderName: nullableString,
  message: nullableString,
  sendAt: z.string(),
  deliveryStatus: z.enum(['SCHEDULED', 'SENT']),
  sentAt: nullableString,
  redeemed: z.boolean(),
  createdAt: z.string()
});
export type GiftCard = z.infer<typeof GiftCardSchema>;

export const GiftCardPageSchema = z.object({ items: z.array(GiftCardSchema), nextCursor: nullableString });
export type GiftCardPage = z.infer<typeof GiftCardPageSchema>;

export const GiftCardPurchaseSchema = GiftCardSchema.extend({
  /** Confirm the payment with Stripe Elements; null when it was already paid. */
  clientSecret: nullableString,
  publishableKey: z.string()
});
export type GiftCardPurchase = z.infer<typeof GiftCardPurchaseSchema>;

export const GIFT_CARD_LOCALES = ['en', 'fr', 'es', 'ar', 'yo'] as const;

export const GiftCardPurchaseRequestSchema = z.object({
  amountMinor: MinorSchema.positive(),
  recipientEmail: z.string().max(254),
  recipientName: z.string().max(120).optional(),
  senderName: z.string().max(120).optional(),
  message: z.string().max(500).optional(),
  /** When to email the card; omit to send it now (at most a year ahead). */
  sendAt: z.string().optional(),
  locale: z.enum(GIFT_CARD_LOCALES).optional()
});
export type GiftCardPurchaseRequest = z.infer<typeof GiftCardPurchaseRequestSchema>;

export const GiftCardRedeemRequestSchema = z.object({ code: z.string().min(1).max(64) });
export type GiftCardRedeemRequest = z.infer<typeof GiftCardRedeemRequestSchema>;

export const GiftCardRedemptionSchema = z.object({ redeemedMinor: MinorSchema, balanceMinor: MinorSchema, currency: z.string() });
export type GiftCardRedemption = z.infer<typeof GiftCardRedemptionSchema>;
