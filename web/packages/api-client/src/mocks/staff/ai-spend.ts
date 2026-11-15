/* Settings > AI and MCP spend cap in the mock staff BFF (gateway-bff-staff.yaml tag AI spend). Staff with ai:manage see today's spend by
   feature from a small fixed ledger; a test hook empties it to see the "nothing recorded" state. The cap is USD 2.00, as deployed. */
import { http } from 'msw';

import { type AiSpendToday } from '../../staff-ai-spend';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const ledgers = new WeakMap<object, AiSpendToday['byFeature']>();
const seed = (): AiSpendToday['byFeature'] => [
  { feature: 'lola', label: 'Lola', calls: 12, tokens: 9_000, costMicros: 1_250_000 },
  { feature: 'listing-writer', label: 'Listing writer', calls: 3, tokens: 2_000, costMicros: 150_000 }
];
const ledger = (db: object) => {
  let l = ledgers.get(db);
  if (!l) {
    l = seed();
    ledgers.set(db, l);
  }
  return l;
};

/** Test hook: the day's ledger, or nothing recorded (an empty day). */
export function setMockAiSpend(db: object, byFeature: AiSpendToday['byFeature']): void {
  ledgers.set(db, byFeature);
}

export function aiSpendHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, problem } = deps;
  return [
    http.get(
      `${W}/ai/spend-today`,
      route((ctx: PreorderCtx): AiSpendToday => {
        if (!ctx.permissions.includes('ai:manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the ai:manage permission.');
        const byFeature = ledger(ctx.db);
        const day = ctx.now.toISOString().slice(0, 10);
        const midnight = new Date(`${day}T00:00:00Z`);
        return {
          day,
          capMicros: 2_000_000,
          callerCapMicros: 500_000,
          spentMicros: byFeature.reduce((n, f) => n + f.costMicros, 0),
          calls: byFeature.reduce((n, f) => n + f.calls, 0),
          tokens: byFeature.reduce((n, f) => n + f.tokens, 0),
          hasData: byFeature.some((f) => f.calls > 0),
          resetsAt: new Date(midnight.getTime() + 86_400_000).toISOString(),
          capSetBy: 'AI_GLOBAL_DAILY_COST_USD',
          byFeature
        };
      })
    )
  ];
}
