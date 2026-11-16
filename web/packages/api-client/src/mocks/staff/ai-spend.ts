/* Settings > AI and MCP spend cap in the mock staff BFF (gateway-bff-staff.yaml tag AI spend). Staff with ai:manage see today's spend by
   feature from a small fixed ledger; a test hook empties it to see the "nothing recorded" state. The cap starts at the deployment
   default USD 2.00 (ceiling USD 20.00); staff with ai:cap.manage change it with a reason and the version they loaded, like ai-gateway. */
import { http, HttpResponse } from 'msw';

import { type AiSpendCap, type AiSpendToday } from '../../staff-ai-spend';
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

const DEFAULT_MICROS = 2_000_000;
const CEILING_MICROS = 20_000_000;
interface CapState {
  capMicros: number;
  source: 'default' | 'saved';
  version: number;
  changedByName?: string;
  changedAt?: string;
  reason?: string;
  history: AiSpendCap['history'];
}
const caps = new WeakMap<object, CapState>();
const capState = (db: object): CapState => {
  let c = caps.get(db);
  if (!c) {
    c = { capMicros: DEFAULT_MICROS, source: 'default', version: 0, history: [] };
    caps.set(db, c);
  }
  return c;
};

/** Test hook: the cap as if staff had saved it (or the deployment default again with `null`). */
export function setMockAiCap(db: object, saved: { capMicros: number; changedByName: string; reason: string } | null): void {
  caps.set(
    db,
    saved
      ? {
          capMicros: saved.capMicros,
          source: 'saved',
          version: 1,
          changedByName: saved.changedByName,
          changedAt: '2026-11-10T09:00:00.000Z',
          reason: saved.reason,
          history: [{ version: 1, previousMicros: null, previousWasDefault: true, newMicros: saved.capMicros, changedByName: saved.changedByName, changedAt: '2026-11-10T09:00:00.000Z', reason: saved.reason }]
        }
      : { capMicros: DEFAULT_MICROS, source: 'default', version: 0, history: [] }
  );
}

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
          capMicros: capState(ctx.db).capMicros,
          callerCapMicros: 500_000,
          spentMicros: byFeature.reduce((n, f) => n + f.costMicros, 0),
          calls: byFeature.reduce((n, f) => n + f.calls, 0),
          tokens: byFeature.reduce((n, f) => n + f.tokens, 0),
          hasData: byFeature.some((f) => f.calls > 0),
          resetsAt: new Date(midnight.getTime() + 86_400_000).toISOString(),
          byFeature
        };
      })
    ),
    http.get(
      `${W}/ai/spend-cap`,
      route((ctx: PreorderCtx) => {
        if (!ctx.permissions.includes('ai:manage') && !ctx.permissions.includes('ai:cap.manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the ai:manage permission.');
        return capView(ctx);
      })
    ),
    http.put(
      `${W}/ai/spend-cap`,
      route(async (ctx: PreorderCtx) => {
        if (!ctx.permissions.includes('ai:cap.manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the ai:cap.manage permission.');
        const b = (await ctx.request.json().catch(() => ({}))) as { capMicros?: unknown; reason?: unknown };
        const errors: Record<string, string> = {};
        const reason = typeof b.reason === 'string' ? b.reason.trim() : '';
        if (reason.length < 3 || reason.length > 300) errors.reason = 'Say why, 3 to 300 characters: it is logged with your name';
        const micros = b.capMicros;
        if (typeof micros !== 'number' || !Number.isInteger(micros) || micros < 0 || micros % 10_000 !== 0) errors.capMicros = 'Enter the cap in dollars, 0 or more, in whole cents';
        else if (micros > CEILING_MICROS) errors.capMicros = 'The cap cannot be more than the ceiling set on the deployment (USD 20.00)';
        if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        const state = capState(ctx.db);
        const raw = ctx.request.headers.get('if-match');
        if (!raw) throw problem(428, 'VERSION_REQUIRED', 'Reload first', 'Reload the page so the change is made against the cap you are looking at.');
        if (raw.replace(/^W\//, '').replace(/"/g, '') !== String(state.version)) {
          throw problem(412, 'VERSION_CONFLICT', 'Changed by someone else', 'Someone else changed this since you loaded it. Refresh to see the latest.');
        }
        const at = ctx.now.toISOString();
        const next = state.version + 1;
        state.history = [{ version: next, previousMicros: state.source === 'saved' ? state.capMicros : null, previousWasDefault: state.source === 'default', newMicros: micros as number, changedByName: ctx.action.actor, changedAt: at, reason }, ...state.history];
        Object.assign(state, { capMicros: micros as number, source: 'saved', version: next, changedByName: ctx.action.actor, changedAt: at, reason });
        const body = capView(ctx);
        return HttpResponse.json(body as never, { headers: { ETag: `"${body.version}"` } });
      })
    )
  ];
}

function capView(ctx: PreorderCtx): AiSpendCap {
  const state = capState(ctx.db);
  const spent = ledger(ctx.db).reduce((n, f) => n + f.costMicros, 0);
  const day = ctx.now.toISOString().slice(0, 10);
  return {
    capMicros: state.capMicros,
    source: state.source,
    defaultMicros: DEFAULT_MICROS,
    ceilingMicros: CEILING_MICROS,
    version: state.version,
    ...(state.changedByName ? { changedByName: state.changedByName, changedAt: state.changedAt, reason: state.reason } : {}),
    day,
    spentMicros: spent,
    remainingMicros: Math.max(0, state.capMicros - spent),
    resetsAt: new Date(new Date(`${day}T00:00:00Z`).getTime() + 86_400_000).toISOString(),
    canChange: ctx.permissions.includes('ai:cap.manage'),
    history: state.history
  };
}
