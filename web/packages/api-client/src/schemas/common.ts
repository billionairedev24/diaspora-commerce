import { z } from 'zod';

/** Amount in minor units (cents for USD). */
export const MinorSchema = z.number().int();

/** RFC 9457 problem details, as returned by every AfroTransact API. Extension members are preserved. */
export const ProblemSchema = z.looseObject({
  type: z.string(),
  title: z.string(),
  status: z.number().int(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  errors: z.record(z.string(), z.string()).optional()
});
export type Problem = z.infer<typeof ProblemSchema>;

export const FacetBucketSchema = z.object({ value: z.string(), label: z.string(), count: z.number().int().min(0) });
export type FacetBucket = z.infer<typeof FacetBucketSchema>;

/**
 * The staff delivery fee rule behind a fee a shopper pays (ADR 0005 §3): the method, which rule and version, the amount and
 * where it came from. The cart and checkout show it beside the fee; the order keeps the rule it was charged by.
 */
export const DeliveryFeeRuleRefSchema = z.object({
  method: z.enum(['AFROTRANSACT_DELIVERY', 'CARRIER', 'PICKUP']),
  ruleId: z.string().optional(),
  ruleVersion: z.number().int(),
  currency: z.string().optional(),
  amountMinor: MinorSchema,
  source: z.enum(['RULE', 'WINDOW_OVERRIDE', 'CARRIER_QUOTE', 'CARRIER_FALLBACK']).optional(),
  /** "10–40 mi" for AfroTransact delivery. */
  band: z.string().optional(),
  distanceMiles: z.number().optional()
});
export type DeliveryFeeRuleRef = z.infer<typeof DeliveryFeeRuleRefSchema>;
