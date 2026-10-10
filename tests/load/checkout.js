// k6: cart → checkout through the gateway BFF.
//
//   k6 run -e TOKEN=$AT_TOKEN tests/load/checkout.js                              # smoke: one customer, one order
//   k6 run -e SCENARIO=load -e RATE=5 -e TOKENS=$(cat tokens.txt) tests/load/checkout.js
//   k6 run -e SCENARIO=flash -e FLASH_OFFER_ID=off_123 -e FLASH_UNITS=10 -e TOKENS=… tests/load/checkout.js
//
// Each iteration: search an in-stock product → add its buy-box offer to the cart → checkout summary → place the order
// with an Idempotency-Key (and replay it once to prove idempotency). PLACE_ORDER=0 stops at the summary
// (safe against shared environments without test payment methods).
//
// Flash sale (S200): N units of one offer, N+25 customers order it at the same instant. Exactly N orders must succeed
// and the rest must be refused with 409 (out of stock) — never oversold, never 5xx.
import { check, fail, group } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

import { authHeaders, BASE_URL, hasAuth, pick, scenarios, SCENARIO, uuid } from './lib/config.js';

const PLACE_ORDER = (__ENV.PLACE_ORDER || '1') !== '0';
const FLASH_UNITS = Number(__ENV.FLASH_UNITS || 10);
const FLASH_EXTRA = Number(__ENV.FLASH_EXTRA || 25);
const QUERIES = ['jollof', 'rice', 'teff', 'spice', 'shea butter', 'plantain chips', 'gari', 'berbere'];

const placed = new Counter('orders_placed');
const stockRefused = new Counter('orders_refused_stock');
const declined = new Counter('orders_payment_declined');
const flowErrors = new Rate('checkout_errors');
const flowDuration = new Trend('checkout_flow_duration', true);

const flash = {
  flash: {
    executor: 'per-vu-iterations', exec: 'flashSale', vus: FLASH_UNITS + FLASH_EXTRA, iterations: 1, maxDuration: '2m'
  }
};

export const options = {
  scenarios: SCENARIO === 'flash' ? flash : scenarios('checkout', { rate: 5, smokeIterations: 3 }),
  thresholds: {
    'http_req_duration{endpoint:add_to_cart}': ['p(95)<300'],
    'http_req_duration{endpoint:checkout_summary}': ['p(95)<500'],
    'http_req_duration{endpoint:place_order}': ['p(95)<1500'],
    checkout_errors: ['rate<0.01'],
    // 409 (stock) and 402 (decline) are expected business answers, not transport failures.
    http_req_failed: ['rate<0.02'],
    ...(SCENARIO === 'flash' ? { orders_placed: [`count==${FLASH_UNITS}`], orders_refused_stock: [`count==${FLASH_EXTRA}`] } : {})
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

const json = (extra = {}) => ({ headers: authHeaders({ 'Content-Type': 'application/json', ...extra }) });
// Business refusals are "expected" so they do not count in http_req_failed.
const expected = http.expectedStatuses({ min: 200, max: 299 }, 402, 409);

export function setup() {
  if (!hasAuth()) fail('checkout needs a customer: pass -e TOKEN=… (or TOKENS=a,b,c / SESSION=…) — see tests/load/README.md');
  const res = http.get(`${BASE_URL}/v1/cart`, { headers: authHeaders() });
  if (res.status !== 200) fail(`GET /v1/cart answered ${res.status}: is BASE_URL=${BASE_URL} the BFF and the token valid?`);
}

function findOffer() {
  const res = http.get(`${BASE_URL}/v1/search?q=${encodeURIComponent(pick(QUERIES))}&size=24`, { headers: authHeaders(), tags: { endpoint: 'search' } });
  if (res.status !== 200) return null;
  const hits = (res.json('hits') || []).filter((h) => h.inStock && h.offerId && h.availableQty > 5);
  return hits.length ? pick(hits).offerId : null;
}

function addToCart(offerId) {
  const res = http.post(`${BASE_URL}/v1/cart/items`, JSON.stringify({ offerId, quantity: 1 }), { ...json(), tags: { endpoint: 'add_to_cart' }, responseCallback: expected });
  check(res, { 'added to cart': (r) => r.status === 200 });
  return res;
}

function summary() {
  const res = http.get(`${BASE_URL}/v1/checkout/summary`, { headers: authHeaders(), tags: { endpoint: 'checkout_summary' }, responseCallback: expected });
  check(res, { 'summary 200': (r) => r.status === 200 });
  return res.status === 200 ? res.json() : null;
}

function orderBody(s) {
  const address = s.addresses.find((a) => a.isDefault) || s.addresses[0];
  const payment = s.paymentMethods[0];
  if (!address) return null;
  return {
    addressId: address.id,
    ...(payment ? { paymentMethodId: payment.id } : { wallet: 'link' }),
    deliverySelections: s.packages.map((p) => ({ packageId: p.packageId, option: (p.deliveryOptions.find((o) => o.key !== 'pickup') || p.deliveryOptions[0]).key }))
  };
}

function placeOrder(body) {
  const key = uuid();
  const res = http.post(`${BASE_URL}/v1/checkout/orders`, JSON.stringify(body), { ...json({ 'Idempotency-Key': key }), tags: { endpoint: 'place_order' }, responseCallback: expected });
  if (res.status === 201 || res.status === 202) placed.add(1);
  else if (res.status === 409) stockRefused.add(1);
  else if (res.status === 402) declined.add(1);
  return { res, key };
}

/** Load/stress/smoke: the full cart → checkout journey for one customer. */
export function checkout() {
  const started = Date.now();
  let ok = false;
  group('cart → checkout', () => {
    const offerId = findOffer();
    if (!check(offerId, { 'found an in-stock offer': (o) => Boolean(o) })) return;
    if (addToCart(offerId).status !== 200) return;
    const s = summary();
    if (!s) return;
    const body = orderBody(s);
    if (!check(body, { 'customer has an address': (b) => Boolean(b) })) return;
    if (!PLACE_ORDER) {
      ok = true;
      return;
    }
    const { res, key } = placeOrder(body);
    ok = check(res, { 'order placed (201/202) or refused (402/409)': (r) => [201, 202, 402, 409].includes(r.status) });
    if (res.status === 201) {
      // Same key + same body must return the original confirmation, not a second order.
      const replay = http.post(`${BASE_URL}/v1/checkout/orders`, JSON.stringify(body), { ...json({ 'Idempotency-Key': key }), tags: { endpoint: 'place_order_replay' } });
      ok = check(replay, { 'idempotent replay returns the same order': (r) => r.status === 201 && r.json('orderNumber') === res.json('orderNumber') }) && ok;
    }
  });
  flowErrors.add(!ok);
  flowDuration.add(Date.now() - started);
}

/** Flash sale: every VU (one customer each — pass TOKENS) buys one unit of FLASH_OFFER_ID at the same moment. */
export function flashSale() {
  const offerId = __ENV.FLASH_OFFER_ID;
  if (!offerId) fail('SCENARIO=flash needs FLASH_OFFER_ID (an offer with exactly FLASH_UNITS units in stock)');
  const added = addToCart(offerId);
  if (added.status === 409) {
    // Refused at add-to-cart because the units are already gone: a correct outcome.
    stockRefused.add(1);
    flowErrors.add(false);
    return;
  }
  const s = summary();
  const body = s && orderBody(s);
  if (!body) {
    flowErrors.add(true);
    return;
  }
  const { res } = placeOrder(body);
  const ok = check(res, { 'placed or refused for stock — never 5xx': (r) => [201, 202, 409].includes(r.status) });
  flowErrors.add(!ok);
}
