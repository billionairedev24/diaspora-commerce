// Shared URLs and helpers for k6 scripts (override via environment when invoking k6).

export const gateway = __ENV.GATEWAY_BFF_URL || 'http://127.0.0.1:8200';
export const search = __ENV.SEARCH_URL || 'http://127.0.0.1:8201';
export const market = __ENV.PERF_MARKET || 'US';
export const zip = __ENV.PERF_ZIP || '77042';
export const searchQ = __ENV.PERF_SEARCH_Q || 'jollof rice';
export const bearer = __ENV.PERF_BEARER_TOKEN || '';

export function authHeaders() {
  if (!bearer) {
    return {};
  }
  return { Authorization: `Bearer ${bearer}` };
}

export function readLoadOptions(defaultVus, defaultDuration) {
  return {
    vus: Number(__ENV.K6_VUS || __ENV.PERF_VUS || defaultVus),
    duration: __ENV.K6_DURATION || __ENV.PERF_DURATION || defaultDuration,
  };
}

export function standardThresholds(p95Ms) {
  return {
    http_req_failed: ['rate<0.05'],
    http_req_duration: [`p(95)<${p95Ms}`],
  };
}
