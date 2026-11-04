// Shared configuration for the k6 scripts: targets, auth, scenarios and thresholds — all overridable via env (-e).
//
//   BASE_URL     gateway BFF origin                         (default http://localhost:8200)
//   SEARCH_URL   search service origin (TARGET=search only) (default http://localhost:8201)
//   TOKEN        bearer access token (customer)             optional for search, required for checkout
//   TOKENS       comma-separated bearer tokens, one per VU (checkout at concurrency: one cart per customer)
//   SESSION      BFF session id, sent as the at_session cookie (alternative to TOKEN for web-style sessions)
//   SCENARIO     smoke | load | stress | soak | flash (checkout only) (default smoke)
//   SOAK_RATE    soak arrival rate (iterations/s)                     (default RATE / 2)
//   SOAK_DURATION soak length                                         (default 2h; use 4h to 8h before a release)
//   ITERATIONS   checkout smoke iterations                           (default 3)
//   RATE         target arrival rate (iterations/s) for load         (default 50 search, 5 checkout)
//   DURATION     steady-state duration for load                      (default 5m)
//   RAMP         ramp-up time to RATE for load                       (default 1m)
//   MAX_VUS      VU ceiling for arrival-rate executors                (default 200)

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8200').replace(/\/$/, '');
export const SEARCH_URL = (__ENV.SEARCH_URL || 'http://localhost:8201').replace(/\/$/, '');
export const SCENARIO = __ENV.SCENARIO || 'smoke';
const MAX_VUS = Number(__ENV.MAX_VUS || 200);

const tokens = (__ENV.TOKENS || __ENV.TOKEN || '').split(',').map((t) => t.trim()).filter(Boolean);

/** Request headers for the current VU: bearer token (one per VU when TOKENS is set) or the BFF session cookie. */
export function authHeaders(extra = {}) {
  const h = { Accept: 'application/json', 'User-Agent': 'afrotransact-k6', ...extra };
  if (tokens.length) h.Authorization = `Bearer ${tokens[(__VU - 1) % tokens.length]}`;
  else if (__ENV.SESSION) h.Cookie = `at_session=${__ENV.SESSION}`;
  return h;
}

export const hasAuth = () => tokens.length > 0 || Boolean(__ENV.SESSION);

/**
 * Scenario shapes. `rate` is the steady-state arrival rate for `load`; `stress` ramps to 3× and holds to find the
 * knee; `smoke` is one VU proving the script and the environment work (run it on every deploy).
 */
export function scenarios(exec, { rate, duration = __ENV.DURATION || '5m', smokeIterations = 0 }) {
  const r = Number(__ENV.RATE || rate);
  const all = {
    smoke: smokeIterations
      ? { executor: 'shared-iterations', vus: 1, iterations: Number(__ENV.ITERATIONS || smokeIterations), maxDuration: '2m', exec }
      : { executor: 'constant-vus', vus: 1, duration: __ENV.DURATION || '30s', exec },
    load: {
      executor: 'ramping-arrival-rate', exec, startRate: 1, timeUnit: '1s', preAllocatedVUs: Math.min(50, MAX_VUS), maxVUs: MAX_VUS,
      stages: [{ target: r, duration: __ENV.RAMP || '1m' }, { target: r, duration }, { target: 0, duration: '30s' }]
    },
    // Soak: a steady, moderate rate (about half the load rate by default) held for hours to expose leaks, pool and
    // connection exhaustion, queue and lag build-up, disk growth and slow degradation that a 5-minute run never sees.
    // Watch the dashboards while it runs (see README 'Soak test'); the thresholds only catch gross failures.
    soak: {
      executor: 'constant-arrival-rate', exec, rate: Math.max(1, Math.round(Number(__ENV.SOAK_RATE || r / 2))), timeUnit: '1s',
      duration: __ENV.SOAK_DURATION || '2h', preAllocatedVUs: Math.min(50, MAX_VUS), maxVUs: MAX_VUS
    },
    stress: {
      executor: 'ramping-arrival-rate', exec, startRate: 1, timeUnit: '1s', preAllocatedVUs: Math.min(100, MAX_VUS), maxVUs: MAX_VUS * 2,
      stages: [
        { target: r, duration: '2m' }, { target: r * 2, duration: '3m' }, { target: r * 3, duration: '3m' },
        { target: r * 3, duration: '5m' }, { target: 0, duration: '2m' }
      ]
    }
  };
  if (!all[SCENARIO]) throw new Error(`unknown SCENARIO=${SCENARIO} (smoke | load | stress | soak)`);
  return { [SCENARIO]: all[SCENARIO] };
}

/** RFC 4122 v4 id for Idempotency-Key (k6 ≥ 0.49 exposes WebCrypto as a global). */
export function uuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const v = (Math.random() * 16) | 0;
    return (c === 'x' ? v : (v & 0x3) | 0x8).toString(16);
  });
}

export const pick = (list) => list[Math.floor(Math.random() * list.length)];
