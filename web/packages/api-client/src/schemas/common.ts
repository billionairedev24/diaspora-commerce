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
