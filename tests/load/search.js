// k6: search — free-text query, faceted filtering and typeahead.
//
//   k6 run tests/load/search.js                                    # smoke against the BFF on :8200
//   k6 run -e SCENARIO=load -e RATE=200 tests/load/search.js       # 200 req/s for 5 min
//   k6 run -e TARGET=search -e SEARCH_URL=http://localhost:8201 tests/load/search.js   # the search service directly
//
// Budget (S200): search p95 < 150 ms at target load; typeahead p95 < 100 ms; < 1% errors.
import { check, group, sleep } from 'k6';
import http from 'k6/http';
import { Rate, Trend } from 'k6/metrics';

import { authHeaders, BASE_URL, pick, scenarios, SCENARIO, SEARCH_URL } from './lib/config.js';

const TARGET = __ENV.TARGET || 'bff'; // bff | search

// Query corpus drawn from the catalogue seed (tools/seed) and the storefront fixtures.
const QUERIES = ['jollof', 'jollof rice', 'rice', 'teff', 'injera', 'spice', 'suya', 'egusi', 'palm oil', 'plantain chips', 'gari',
  'shea butter', 'black soap', 'kente', 'gele', 'ankara', 'berbere', 'scotch bonnet', 'fufu', 'curry', 'jerk seasoning', 'hibiscus'];
const PREFIXES = ['j', 'jo', 'jol', 'ri', 'te', 'sp', 'su', 'eg', 'pa', 'pl', 'sh', 'ke', 'an', 'be', 'fu', 'cu', 'hi'];
const COMMUNITIES = ['Nigerian', 'Ghanaian', 'Ethiopian', 'Jamaican', 'Somali', 'Indian'];
const DEPARTMENTS = ['Groceries', 'Beauty', 'Fashion', 'Home & Art'];
const SORTS = ['relevance', 'price_asc', 'price_desc', 'rating'];

const errors = new Rate('search_errors');
const tookMs = new Trend('search_engine_took_ms', true);

export const options = {
  scenarios: scenarios('mix', { rate: 50 }),
  thresholds: {
    // Hard budgets: abort a load/stress run early once clearly broken.
    'http_req_duration{endpoint:search}': [{ threshold: 'p(95)<150', abortOnFail: SCENARIO !== 'stress', delayAbortEval: '1m' }],
    'http_req_duration{endpoint:facets}': ['p(95)<200'],
    'http_req_duration{endpoint:suggest}': ['p(95)<100'],
    http_req_failed: ['rate<0.01'],
    search_errors: ['rate<0.01'],
    checks: ['rate>0.99']
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

const enc = encodeURIComponent;
const searchPath = TARGET === 'search' ? `${SEARCH_URL}/v1/search` : `${BASE_URL}/v1/search`;
const suggestPath = TARGET === 'search' ? `${SEARCH_URL}/v1/suggest` : `${BASE_URL}/v1/search/suggestions`;

function okPage(res) {
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has hits and facets': (r) => {
      const b = r.json();
      return Array.isArray(b.hits) && typeof b.facets === 'object' && typeof b.total === 'number';
    }
  });
  errors.add(!ok);
  if (ok) {
    const t = res.json('tookMs');
    if (typeof t === 'number') tookMs.add(t);
  }
  return ok;
}

export function query() {
  const res = http.get(`${searchPath}?q=${enc(pick(QUERIES))}&page=0&size=24`, { headers: authHeaders(), tags: { endpoint: 'search', name: 'GET /v1/search?q' } });
  okPage(res);
}

export function facets() {
  // Disjunctive faceting: a query plus 1–3 active filters and a sort, like a shopper refining results.
  const params = [`q=${enc(pick(QUERIES))}`, `community=${enc(pick(COMMUNITIES))}`, `sort=${pick(SORTS)}`];
  if (Math.random() < 0.5) params.push(`fulfilment=${pick(['AFROTRANSACT', 'SELLER'])}`);
  if (Math.random() < 0.3) params.push(`department=${enc(pick(DEPARTMENTS))}`);
  if (Math.random() < 0.3) params.push('minRating=4');
  const res = http.get(`${searchPath}?${params.join('&')}`, { headers: authHeaders(), tags: { endpoint: 'facets', name: 'GET /v1/search?filters' } });
  okPage(res);
}

export function suggest() {
  const res = http.get(`${suggestPath}?q=${enc(pick(PREFIXES))}`, { headers: authHeaders(), tags: { endpoint: 'suggest', name: 'GET suggestions' } });
  const ok = check(res, { 'suggest 200': (r) => r.status === 200 });
  errors.add(!ok);
}

/** Traffic mix observed on the storefront: typing produces several suggest calls per search. */
export function mix() {
  group('typeahead', () => {
    suggest();
    if (Math.random() < 0.6) suggest();
  });
  group('search', () => (Math.random() < 0.6 ? query() : facets()));
  // Smoke is a closed-model single VU: add think time so it stays a gentle health check. Arrival-rate scenarios pace themselves.
  if (SCENARIO === 'smoke') sleep(0.5);
}
