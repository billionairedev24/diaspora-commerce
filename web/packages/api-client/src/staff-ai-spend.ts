/* Settings > AI and MCP spend cap (contracts/openapi/gateway-bff-staff.yaml, tag AI spend): today's AI spend against the daily cap,
   by feature, read from ai-gateway's usage ledger, and the cap itself: a runtime setting staff with ai:cap.manage change (reason and
   the version they loaded required); everyone with ai:manage sees it. AI_GLOBAL_DAILY_COST_USD is only its initial default and
   AI_GLOBAL_DAILY_COST_MAX_USD its hard ceiling. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const AiSpendFeatureSchema = z.object({ feature: z.string(), label: z.string(), calls: z.number().int(), tokens: z.number().int(), costMicros: z.number().int() });
export type AiSpendFeature = z.infer<typeof AiSpendFeatureSchema>;

export const AiSpendTodaySchema = z.object({
  day: z.string(),
  /** The cap in force for the whole gateway in micro-dollars (1,000,000 = USD 1); 0 is AI off. */
  capMicros: z.number().int(),
  callerCapMicros: z.number().int(),
  spentMicros: z.number().int(),
  calls: z.number().int(),
  tokens: z.number().int(),
  /** False when no model call was recorded today: the zero is "nothing recorded", not an estimate. */
  hasData: z.boolean(),
  resetsAt: z.string(),
  byFeature: z.array(AiSpendFeatureSchema)
});
export type AiSpendToday = z.infer<typeof AiSpendTodaySchema>;

export const AiSpendCapChangeSchema = z.object({
  version: z.number().int(),
  /** The cap before; null when the value before was the deployment default. */
  previousMicros: z.number().int().nullable(),
  previousWasDefault: z.boolean(),
  newMicros: z.number().int(),
  changedByName: z.string(),
  changedAt: z.string(),
  reason: z.string()
});
export type AiSpendCapChange = z.infer<typeof AiSpendCapChangeSchema>;

export const AiSpendCapSchema = z.object({
  /** The cap in force, micro-dollars per UTC day; 0 is AI off. */
  capMicros: z.number().int(),
  /** `default` until a value is saved. */
  source: z.enum(['default', 'saved']),
  defaultMicros: z.number().int(),
  /** The hard ceiling a saved value cannot exceed. */
  ceilingMicros: z.number().int(),
  /** The ETag: sent back as If-Match when changing the cap (0 while the default is in force). */
  version: z.number().int(),
  changedByName: z.string().optional(),
  changedAt: z.string().optional(),
  reason: z.string().optional(),
  day: z.string(),
  spentMicros: z.number().int(),
  remainingMicros: z.number().int(),
  resetsAt: z.string(),
  canChange: z.boolean(),
  history: z.array(AiSpendCapChangeSchema)
});
export type AiSpendCap = z.infer<typeof AiSpendCapSchema>;

export interface AiSpendCapInput {
  capMicros: number;
  reason: string;
}

export const AI_CAP_REASON_MIN = 3;
export const AI_CAP_REASON_MAX = 300;

export function createStaffAiSpendApi(request: Requester) {
  return {
    today: (signal?: AbortSignal): Promise<AiSpendToday> => request('/v1/staff/ai/spend-today', AiSpendTodaySchema, { signal }),
    cap: (signal?: AbortSignal): Promise<AiSpendCap> => request('/v1/staff/ai/spend-cap', AiSpendCapSchema, { signal }),
    /** Changes the cap against the version the person loaded (If-Match); the answer is the new state. */
    setCap: (input: AiSpendCapInput, version: number): Promise<AiSpendCap> =>
      request('/v1/staff/ai/spend-cap', AiSpendCapSchema, { method: 'PUT', body: input, headers: { 'If-Match': `"${version}"` } })
  };
}
export type StaffAiSpendApi = ReturnType<typeof createStaffAiSpendApi>;

export const staffAiSpendKeys = { all: ['staff', 'ai'] as const, today: () => ['staff', 'ai', 'spend-today'] as const, cap: () => ['staff', 'ai', 'spend-cap'] as const };
export const staffAiSpendQueries = {
  today: (api: StaffAiSpendApi) => queryOptions({ queryKey: staffAiSpendKeys.today(), queryFn: ({ signal }) => api.today(signal), staleTime: 15_000 }),
  cap: (api: StaffAiSpendApi) => queryOptions({ queryKey: staffAiSpendKeys.cap(), queryFn: ({ signal }) => api.cap(signal), staleTime: 15_000 })
};

/** What a person typed as dollars ("5", "5.5", "5.50", "$5") as micro-dollars, whole cents only; null when it is not an amount of 0 or more. */
export function microsFromUsdText(text: string): number | null {
  const m = /^\$?\s*(\d{1,6})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (!m) return null;
  return (Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0') || '0')) * 10_000;
}

/** Micro-dollars as the text a dollar field starts with: "2.00". */
export function usdTextFromMicros(micros: number): string {
  return (micros / 1_000_000).toFixed(2);
}

/** Micro-dollars as "USD 0.42" (two decimals, three below a cent so a small spend is not shown as zero). */
export function usdFromMicros(micros: number): string {
  const usd = micros / 1_000_000;
  return `USD ${usd.toFixed(usd > 0 && usd < 0.01 ? 4 : 2)}`;
}
