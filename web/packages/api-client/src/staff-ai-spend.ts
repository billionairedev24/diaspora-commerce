/* Settings > AI and MCP spend cap (contracts/openapi/gateway-bff-staff.yaml, tag AI spend): today's AI spend against the daily cap,
   by feature, read from ai-gateway's usage ledger. The cap itself is deployment configuration and is only shown. */
import { queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const AiSpendFeatureSchema = z.object({ feature: z.string(), label: z.string(), calls: z.number().int(), tokens: z.number().int(), costMicros: z.number().int() });
export type AiSpendFeature = z.infer<typeof AiSpendFeatureSchema>;

export const AiSpendTodaySchema = z.object({
  day: z.string(),
  /** The cap for the whole gateway in micro-dollars (1,000,000 = USD 1); 0 is unlimited. */
  capMicros: z.number().int(),
  callerCapMicros: z.number().int(),
  spentMicros: z.number().int(),
  calls: z.number().int(),
  tokens: z.number().int(),
  /** False when no model call was recorded today: the zero is "nothing recorded", not an estimate. */
  hasData: z.boolean(),
  resetsAt: z.string(),
  /** The ai-gateway environment variable that sets the cap. */
  capSetBy: z.string(),
  byFeature: z.array(AiSpendFeatureSchema)
});
export type AiSpendToday = z.infer<typeof AiSpendTodaySchema>;

export function createStaffAiSpendApi(request: Requester) {
  return {
    today: (signal?: AbortSignal): Promise<AiSpendToday> => request('/v1/staff/ai/spend-today', AiSpendTodaySchema, { signal })
  };
}
export type StaffAiSpendApi = ReturnType<typeof createStaffAiSpendApi>;

export const staffAiSpendKeys = { today: () => ['staff', 'ai', 'spend-today'] as const };
export const staffAiSpendQueries = {
  today: (api: StaffAiSpendApi) => queryOptions({ queryKey: staffAiSpendKeys.today(), queryFn: ({ signal }) => api.today(signal), staleTime: 15_000 })
};

/** Micro-dollars as "USD 0.42" (two decimals, three below a cent so a small spend is not shown as zero). */
export function usdFromMicros(micros: number): string {
  const usd = micros / 1_000_000;
  return `USD ${usd.toFixed(usd > 0 && usd < 0.01 ? 4 : 2)}`;
}
