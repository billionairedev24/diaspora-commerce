import { z } from 'zod';

/* AfroTransact membership (S323, ADR 0006) — contracts/openapi/gateway-bff.yaml, tag Membership. Tiers are what staff
   configure in Settings; a shopper joins one on a saved card. Credit a membership grants only buys products and services. */

const MinorSchema = z.number().int();
const nullableString = z.string().nullable();

export const MEMBERSHIP_BENEFIT_CODES = [
  'FREE_DELIVERY',
  'FREE_SHIPPING',
  'CASHBACK',
  'MEMBER_PRICES',
  'EARLY_ACCESS',
  'PRIORITY_SUPPORT',
  'BOOSTED_REWARDS',
  'MONTHLY_CREDIT'
] as const;
export type MembershipBenefitCode = (typeof MEMBERSHIP_BENEFIT_CODES)[number];

export const MembershipBenefitSchema = z.object({
  code: z.enum(MEMBERSHIP_BENEFIT_CODES),
  /** Goods subtotal an order needs (free delivery, free shipping, cashback). */
  minOrderMinor: MinorSchema.nullable(),
  /** The most the benefit is worth on one order; null for no cap. */
  capMinor: MinorSchema.nullable(),
  /** Hundredths of a percent: the cashback share, or the extra on referral and gift rewards. */
  percentBps: z.number().int().nullable(),
  /** Hours before everyone else a member can order from pre-order windows and delivery runs. */
  hours: z.number().int().nullable(),
  /** Credit granted each month. */
  amountMinor: MinorSchema.nullable()
});
export type MembershipBenefit = z.infer<typeof MembershipBenefitSchema>;

export const MembershipTierSchema = z.object({
  code: z.string(),
  name: z.string(),
  description: z.string(),
  /** Lower is the entry tier; a higher order is an upgrade. */
  sortOrder: z.number().int(),
  currency: z.string(),
  monthlyPriceMinor: MinorSchema,
  yearlyPriceMinor: MinorSchema,
  trialDays: z.number().int(),
  benefits: z.array(MembershipBenefitSchema)
});
export type MembershipTier = z.infer<typeof MembershipTierSchema>;

export const MembershipOfferSchema = z.object({ tiers: z.array(MembershipTierSchema) });
export type MembershipOffer = z.infer<typeof MembershipOfferSchema>;

export const MEMBERSHIP_STATUSES = ['TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'LAPSED', 'EXPIRED'] as const;
export const MEMBERSHIP_INTERVALS = ['MONTHLY', 'YEARLY'] as const;
export type MembershipInterval = (typeof MEMBERSHIP_INTERVALS)[number];

export const MembershipSummarySchema = z.object({
  status: z.enum(MEMBERSHIP_STATUSES),
  source: z.enum(['PAID', 'COMPLIMENTARY']),
  tierCode: z.string(),
  tierName: z.string(),
  interval: z.enum(MEMBERSHIP_INTERVALS),
  /** What the shopper pays each period, locked at sign-up or the last plan change. */
  priceMinor: MinorSchema,
  currency: z.string(),
  startedAt: z.string(),
  /** The renewal date, or when the benefits end for a canceled or complimentary membership. */
  periodEnd: z.string(),
  trialEnd: nullableString,
  cancelAtPeriodEnd: z.boolean(),
  endedAt: nullableString,
  pendingTierCode: nullableString,
  pendingTierName: nullableString,
  pendingInterval: z.enum(MEMBERSHIP_INTERVALS).nullable(),
  nextRetryAt: nullableString,
  freeDaysGranted: z.number().int()
});
export type MembershipSummary = z.infer<typeof MembershipSummarySchema>;

export const MembershipChargeSchema = z.object({
  kind: z.enum(['JOIN', 'RENEWAL', 'UPGRADE']),
  amountMinor: MinorSchema,
  currency: z.string(),
  status: z.enum(['SUCCEEDED', 'FAILED', 'REFUNDED']),
  refundedMinor: MinorSchema,
  periodEnd: nullableString,
  createdAt: z.string()
});
export type MembershipCharge = z.infer<typeof MembershipChargeSchema>;

export const MembershipUsageSchema = z.object({ benefit: z.string(), uses: z.number().int(), amountMinor: MinorSchema, currency: z.string() });
export type MembershipUsage = z.infer<typeof MembershipUsageSchema>;

/** Cancel within the window after a charge and before any benefit was used: the fee goes back to the card in full. */
export const MembershipRefundRuleSchema = z.object({
  eligible: z.boolean(),
  reason: z.enum(['OK', 'NOT_PAID', 'WINDOW_PASSED', 'BENEFIT_USED', 'DISABLED']),
  amountMinor: MinorSchema,
  currency: z.string(),
  until: nullableString,
  windowDays: z.number().int()
});
export type MembershipRefundRule = z.infer<typeof MembershipRefundRuleSchema>;

export const MembershipCardSchema = z.object({
  brand: nullableString,
  last4: nullableString,
  expMonth: z.number().int().nullable(),
  expYear: z.number().int().nullable()
});
export type MembershipCard = z.infer<typeof MembershipCardSchema>;

export const MembershipAccountSchema = z.object({
  /** The shopper has the benefits now (trialing, active or past due). */
  member: z.boolean(),
  membership: MembershipSummarySchema.nullable(),
  tier: MembershipTierSchema.nullable(),
  /** The tier a scheduled change moves to at the end of the period. */
  pendingTier: MembershipTierSchema.nullable(),
  charges: z.array(MembershipChargeSchema),
  usage: z.array(MembershipUsageSchema),
  refund: MembershipRefundRuleSchema,
  trialAvailable: z.boolean(),
  card: MembershipCardSchema.nullable(),
  tiers: z.array(MembershipTierSchema)
});
export type MembershipAccount = z.infer<typeof MembershipAccountSchema>;

export const MembershipPlanRequestSchema = z.object({
  tierCode: z.string().min(1).max(40),
  interval: z.enum(MEMBERSHIP_INTERVALS),
  paymentMethodId: z.string().max(120).optional()
});
export type MembershipPlanRequest = z.infer<typeof MembershipPlanRequestSchema>;

export const MembershipCancelRequestSchema = z.object({ refund: z.boolean().optional() });
export type MembershipCancelRequest = z.infer<typeof MembershipCancelRequestSchema>;

export const MembershipBenefitUseSchema = z.object({
  benefit: z.string(),
  tierName: z.string(),
  amountMinor: MinorSchema,
  currency: z.string(),
  createdAt: z.string()
});
export type MembershipBenefitUse = z.infer<typeof MembershipBenefitUseSchema>;

export const MembershipBenefitUsePageSchema = z.object({ items: z.array(MembershipBenefitUseSchema), nextCursor: nullableString });
export type MembershipBenefitUsePage = z.infer<typeof MembershipBenefitUsePageSchema>;

/** A delivery fee the membership waived on one package ("fee waived: membership"). */
export const MembershipWaiverSchema = z.object({
  packageId: z.string(),
  seller: z.string(),
  benefit: z.enum(['FREE_DELIVERY', 'FREE_SHIPPING']),
  waivedMinor: MinorSchema
});
export type MembershipWaiver = z.infer<typeof MembershipWaiverSchema>;

/** "Join Plus to save $X on this order". */
export const MembershipUpsellSchema = z.object({
  tierCode: z.string(),
  tierName: z.string(),
  monthlyPriceMinor: MinorSchema,
  yearlyPriceMinor: MinorSchema,
  trialDays: z.number().int(),
  savingMinor: MinorSchema,
  currency: z.string()
});
export type MembershipUpsell = z.infer<typeof MembershipUpsellSchema>;

export const MembershipSavingsSchema = z.object({
  member: z.boolean(),
  tierName: nullableString,
  waivers: z.array(MembershipWaiverSchema),
  totalWaivedMinor: MinorSchema,
  /** The credit earned on this order once it is delivered (cashback); 0 without cashback or during a trial. */
  cashbackMinor: MinorSchema,
  upsell: MembershipUpsellSchema.nullable()
});
export type MembershipSavings = z.infer<typeof MembershipSavingsSchema>;

/** The shipping fee the membership waived on an order shipment, with the tier and benefit. */
export const OrderMembershipWaiverSchema = z.object({ tierName: z.string(), benefit: z.string(), waivedMinor: MinorSchema });
export type OrderMembershipWaiver = z.infer<typeof OrderMembershipWaiverSchema>;
