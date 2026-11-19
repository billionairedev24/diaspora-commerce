/* Settings > Picture rules in the mock staff BFF (gateway-bff-staff.yaml tag Picture rules). The environment starts on the profile the mock
   is given (startup by default, like uat and prod); staff with catalog:picture-rules.manage change the profile and single knobs with a
   reason and the version they loaded, staff with catalog:moderate only look. Every change is kept in the history with the person's name. */
import { http, HttpResponse } from 'msw';

import {
  PICTURE_BOOL_KNOBS,
  PICTURE_FORMATS,
  PICTURE_INT_BOUNDS,
  PICTURE_PROFILE_KNOBS,
  PICTURE_INT_KNOBS,
  PICTURE_PROFILES,
  type PictureKnobName,
  type PictureKnobs,
  type PictureOverrides,
  type PictureProfile,
  type PictureRules,
  type PictureRulesChange,
  type PictureRulesView,
  summaryOfKnobs
} from '../../picture-rules';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

const KNOBS = PICTURE_PROFILE_KNOBS;

interface State {
  environmentDefault: PictureProfile;
  profile: PictureProfile;
  overrides: PictureOverrides;
  version: number;
  updatedBy?: string;
  updatedAt?: string;
  history: PictureRulesChange[];
}
const states = new WeakMap<object, State>();
const stateOf = (db: object): State => {
  let s = states.get(db);
  if (!s) {
    s = { environmentDefault: 'startup', profile: 'startup', overrides: {}, version: 0, history: [] };
    states.set(db, s);
  }
  return s;
};

/** Test hook: the setting as if staff had saved it (or the environment default again with `null`). */
export function setMockPictureRules(db: object, saved: { profile: PictureProfile; overrides?: PictureOverrides; updatedBy: string; reason: string } | null, environmentDefault: PictureProfile = 'startup'): void {
  if (!saved) {
    states.set(db, { environmentDefault, profile: environmentDefault, overrides: {}, version: 0, history: [] });
    return;
  }
  const at = '2026-11-18T09:00:00.000Z';
  const overrides = saved.overrides ?? {};
  states.set(db, {
    environmentDefault,
    profile: saved.profile,
    overrides,
    version: 1,
    updatedBy: saved.updatedBy,
    updatedAt: at,
    history: [{ version: 1, previousProfile: null, profile: saved.profile, overridden: Object.keys(overrides), changedBy: saved.updatedBy, changedAt: at, reason: saved.reason }]
  });
}

function resolve(profile: PictureProfile, overrides: PictureOverrides, version: number): PictureRules {
  const knobs = { ...KNOBS[profile], ...overrides };
  const overridden = ([...PICTURE_INT_KNOBS, ...PICTURE_BOOL_KNOBS] as PictureKnobName[]).filter((k) => overrides[k] !== undefined);
  return { profile, knobs, overridden, formats: [...PICTURE_FORMATS], version, summary: summaryOfKnobs(knobs), relaxed: profile !== 'strict' || overridden.length > 0 };
}

function view(ctx: PreorderCtx): PictureRulesView {
  const s = stateOf(ctx.db);
  const rules = resolve(s.profile, s.overrides, s.version);
  return {
    rules,
    overrides: s.overrides,
    ...(s.updatedBy ? { updatedBy: s.updatedBy, updatedAt: s.updatedAt } : {}),
    canChange: ctx.permissions.includes('catalog:picture-rules.manage'),
    environmentDefault: s.environmentDefault,
    relaxed: rules.relaxed === true,
    profiles: PICTURE_PROFILES.map((p) => resolve(p, {}, 0)),
    history: s.history
  };
}

export function pictureRulesHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, problem } = deps;
  return [
    http.get(
      `${W}/picture-rules`,
      route((ctx: PreorderCtx) => {
        if (!ctx.permissions.includes('catalog:moderate') && !ctx.permissions.includes('catalog:picture-rules.manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the catalog:moderate permission.');
        const body = view(ctx);
        return HttpResponse.json(body as never, { headers: { ETag: `"${body.rules.version}"` } });
      })
    ),
    http.put(
      `${W}/picture-rules`,
      route(async (ctx: PreorderCtx) => {
        if (!ctx.permissions.includes('catalog:picture-rules.manage')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the catalog:picture-rules.manage permission.');
        const b = (await ctx.request.json().catch(() => ({}))) as { profile?: unknown; overrides?: Record<string, unknown>; reason?: unknown };
        const errors: Record<string, string> = {};
        const reason = typeof b.reason === 'string' ? b.reason.trim() : '';
        if (reason.length < 3 || reason.length > 300) errors.reason = 'Say why, 3 to 300 characters: it is logged with your name';
        if (!(PICTURE_PROFILES as readonly unknown[]).includes(b.profile)) errors.profile = 'Choose strict, startup or testing';
        const overrides: Record<string, number | boolean> = {};
        for (const [k, v] of Object.entries(b.overrides ?? {})) {
          if ((PICTURE_INT_KNOBS as readonly string[]).includes(k)) {
            const bound = PICTURE_INT_BOUNDS[k as keyof typeof PICTURE_INT_BOUNDS];
            if (typeof v !== 'number' || !Number.isInteger(v) || v < bound.min || v > bound.max) errors[k] = `Use ${bound.min} to ${bound.max}`;
            else overrides[k] = v;
          } else if ((PICTURE_BOOL_KNOBS as readonly string[]).includes(k)) {
            if (typeof v !== 'boolean') errors[k] = 'Choose on or off';
            else overrides[k] = v;
          } else if (v != null) errors[k] = 'Not a picture rule';
        }
        if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        const state = stateOf(ctx.db);
        const raw = ctx.request.headers.get('if-match');
        if (!raw) throw problem(428, 'VERSION_REQUIRED', 'Reload first', 'Reload the page so the change is made against the rules you are looking at.');
        if (raw.replace(/^W\//, '').replace(/"/g, '') !== String(state.version)) throw problem(409, 'CONFLICT', 'Changed by someone else', 'Someone else changed this since you loaded it. Refresh to see the latest.');
        const at = ctx.now.toISOString();
        const next = state.version + 1;
        const merged = { ...KNOBS[b.profile as PictureProfile], ...overrides } as PictureKnobs;
        if (merged.minShortSidePx > merged.minLongSidePx) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { minShortSidePx: 'Use 1 up to the longest-side value' });
        state.history = [{ version: next, previousProfile: state.version === 0 ? null : state.profile, profile: b.profile as PictureProfile, overridden: Object.keys(overrides), changedBy: ctx.action.actor, changedAt: at, reason }, ...state.history];
        Object.assign(state, { profile: b.profile as PictureProfile, overrides, version: next, updatedBy: ctx.action.actor, updatedAt: at });
        const body = view(ctx);
        return HttpResponse.json(body as never, { headers: { ETag: `"${body.rules.version}"` } });
      })
    )
  ];
}
