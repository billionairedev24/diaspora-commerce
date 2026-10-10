/* MSW request handlers implementing contracts/openapi/gateway-bff.yaml against the prototype fixtures.
   Paths use a wildcard origin/prefix (any base URL ending in /v1/...), so the same handlers serve the
   browser (/api/bff/v1/…), server components (in-process) and Vitest (setupServer). */
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { z } from 'zod';

import { SignInRequestSchema, SignUpRequestSchema, VerifyCodeRequestSchema } from '../schemas/auth';
import { JobApplicationFormSchema } from '../schemas/careers';
import { AddToCartRequestSchema, SelectAllRequestSchema, UpdateCartLineRequestSchema } from '../schemas/cart';
import { StockAlertRequestSchema } from '../schemas/catalog';
import { ZipSchema } from '../schemas/location';
import { NewsletterRequestSchema } from '../schemas/site';
import { fromApiSearchParams } from '../search-params';
import { isPasswordAcceptable } from '../validation/password';
import { suggestAddresses } from './addresses';
import { getDb, getSession, type MockChallenge, type MockSession, nextId, userById } from './db';
import { locationForZip, nearestZip } from './delivery';
import { DEMO_CODE, JOBS, SITE_CONFIG } from './fixtures/content';
import {
  addToCart,
  buildCart,
  home,
  MockProblem,
  moveToCart,
  productDetail,
  removeLine,
  removeSaved,
  saveForLater,
  search,
  sessionLocation,
  suggest,
  updateLine
} from './service';

export const SESSION_COOKIE = 'at_session';
const PROBLEM_BASE = 'https://errors.afrotransact.com/';

interface Ctx { session: MockSession; isNew: boolean; request: Request; now: Date }

function problem(status: number, code: string, title: string, detail: string, extra: Record<string, unknown> = {}): HttpResponse<JsonBodyType> {
  return HttpResponse.json(
    { type: PROBLEM_BASE + code.toLowerCase().replace(/_/g, '-'), title, status, detail, code, ...extra } as JsonBodyType,
    { status, headers: { 'Content-Type': 'application/problem+json' } }
  );
}

function readCookie(request: Request, name: string): string | null {
  const raw = request.headers.get('cookie') ?? '';
  for (const part of raw.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return null;
}

/** Wraps a resolver with session lookup (cookie at_session), problem mapping and Set-Cookie for new sessions. */
function route(fn: (ctx: Ctx, params: Record<string, string>) => Promise<Response> | Response) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const existing = readCookie(request, SESSION_COOKIE);
    const sid = existing ?? crypto.randomUUID();
    const ctx: Ctx = { session: getSession(sid), isNew: !existing, request, now: new Date() };
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => { if (typeof v === 'string') flat[k] = v; });
    let res: Response;
    try {
      res = await fn(ctx, flat);
    } catch (e) {
      if (e instanceof MockProblem) res = problem(e.status, e.code, e.title, e.message, e.extra);
      else throw e;
    }
    if (ctx.isNew) res.headers.append('Set-Cookie', `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000`);
    return res;
  };
}

async function body<S extends z.ZodType>(request: Request, schema: S): Promise<z.infer<S>> {
  let json: unknown = {};
  try {
    const text = await request.text();
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new MockProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    const errors: Record<string, string> = {};
    parsed.error.issues.forEach((i) => { errors[i.path.join('.') || '_'] = i.message; });
    throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
  }
  return parsed.data;
}

const json = (data: unknown, status = 200) => HttpResponse.json(data as JsonBodyType, { status });
const cartOf = (ctx: Ctx) => json(buildCart(ctx.session, ctx.now));

function me(ctx: Ctx) {
  const u = userById(ctx.session.userId);
  if (!u) return { authenticated: false, user: null, counts: null };
  return {
    authenticated: true,
    user: { id: u.id, firstName: u.firstName, lastName: u.lastName, email: u.email, initials: (u.firstName[0] ?? '') + (u.lastName[0] ?? ''), roles: u.roles },
    counts: u.id === 'u_adaeze' ? { orders: 2, returns: 0, addresses: 2, paymentMethods: 3 } : { orders: 0, returns: 0, addresses: 0, paymentMethods: 0 }
  };
}

const safeReturn = (v: string | null | undefined): string => (v && v.startsWith('/') && !v.startsWith('//') ? v : '/');
const maskEmail = (e: string) => e.replace(/^(.)(.*)(@.*)$/, (_, a: string, b: string, c: string) => a + '•'.repeat(Math.max(1, Math.min(6, b.length))) + c);

function challenge(kind: MockChallenge['kind'], returnTo: string, extra: Partial<Pick<MockChallenge, 'userId' | 'pending'>> = {}) {
  const id = nextId('ch');
  getDb().challenges.set(id, { id, kind, returnTo, expiresAt: Date.now() + 10 * 60_000, ...extra });
  return id;
}

function takeChallenge(id: string, kind: string) {
  const c = getDb().challenges.get(id);
  if (!c || c.kind !== kind || c.expiresAt < Date.now()) throw new MockProblem(410, 'CHALLENGE_EXPIRED', 'Verification expired', 'That code has expired. Start again.');
  return c;
}

function requireUser(ctx: Ctx) {
  const u = userById(ctx.session.userId);
  if (!u) throw new MockProblem(401, 'UNAUTHENTICATED', 'Sign in required', 'Sign in to continue.');
  return u;
}

const prefixOf = (url: string) => {
  const u = new URL(url);
  return u.origin + u.pathname.slice(0, u.pathname.indexOf('/v1/'));
};

export const handlers = [
  /* Site & content */
  http.get('*/v1/site-config', route(() => json(SITE_CONFIG))),
  http.get('*/v1/home', route((ctx) => json(home(sessionLocation(ctx.session), ctx.now)))),
  http.post('*/v1/newsletter/subscriptions', route(async (ctx) => {
    await body(ctx.request, NewsletterRequestSchema);
    return json({ status: 'pending_confirmation' }, 202);
  })),

  /* Location */
  http.get('*/v1/delivery-location', route((ctx) => json(sessionLocation(ctx.session)))),
  http.put('*/v1/delivery-location', route(async (ctx) => {
    const input = await body(ctx.request, z.union([z.object({ zip: ZipSchema }), z.object({ lat: z.number().min(-90).max(90), lon: z.number().min(-180).max(180) })]));
    const zip = 'zip' in input ? input.zip : nearestZip(input.lat, input.lon);
    const loc = locationForZip(zip);
    if (!loc) throw new MockProblem(422, 'ZIP_NOT_SERVICEABLE', 'We don’t deliver there yet', 'We don’t deliver to that ZIP yet.', { errors: { zip: 'We don’t deliver to that ZIP yet.' } });
    ctx.session.zip = zip;
    return json(loc);
  })),
  http.get('*/v1/geo/address-suggestions', route((ctx) => {
    const q = new URL(ctx.request.url).searchParams.get('q') ?? '';
    return json({ suggestions: suggestAddresses(q), attribution: 'Sample addresses (geo service: Google Places / OpenStreetMap Nominatim in production)' });
  })),

  /* Search */
  http.get('*/v1/search', route((ctx) => {
    let query;
    try {
      query = fromApiSearchParams(new URL(ctx.request.url).searchParams);
    } catch {
      throw new MockProblem(400, 'INVALID_QUERY', 'Invalid search query', 'One or more search parameters are invalid.');
    }
    return json(search(query, sessionLocation(ctx.session), ctx.now));
  })),
  http.get('*/v1/search/suggestions', route((ctx) => {
    const q = (new URL(ctx.request.url).searchParams.get('q') ?? '').slice(0, 100);
    return json(suggest(q, sessionLocation(ctx.session), ctx.now));
  })),

  /* Catalogue */
  http.get('*/v1/products/:atin', route((ctx, p) => {
    const loc = sessionLocation(ctx.session);
    return json(productDetail(p.atin ?? '', `${loc.city}, ${loc.state}`, ctx.now));
  })),
  http.post('*/v1/products/:atin/stock-alerts', route(async (ctx, p) => {
    const atin = p.atin ?? '';
    if (!getDb().products.has(atin)) throw new MockProblem(404, 'PRODUCT_NOT_FOUND', 'Product not found', `No product with ATIN ${atin}.`);
    const req = await body(ctx.request, StockAlertRequestSchema);
    const email = req.email ?? userById(ctx.session.userId)?.email;
    if (!email) throw new MockProblem(422, 'VALIDATION_FAILED', 'Email required', 'Enter an email address so we can tell you when it’s back.', { errors: { email: 'Enter your email' } });
    getDb().stockAlerts.add(`${atin}:${email}`);
    return json({ atin, status: 'subscribed' }, 201);
  })),

  /* Cart */
  http.get('*/v1/cart', route((ctx) => cartOf(ctx))),
  http.post('*/v1/cart/items', route(async (ctx) => {
    const req = await body(ctx.request, AddToCartRequestSchema);
    addToCart(ctx.session, req.offerId, req.quantity);
    return cartOf(ctx);
  })),
  http.patch('*/v1/cart/items/:lineId', route(async (ctx, p) => {
    updateLine(ctx.session, p.lineId ?? '', await body(ctx.request, UpdateCartLineRequestSchema));
    return cartOf(ctx);
  })),
  http.delete('*/v1/cart/items/:lineId', route((ctx, p) => {
    removeLine(ctx.session, p.lineId ?? '');
    return cartOf(ctx);
  })),
  http.put('*/v1/cart/selection', route(async (ctx) => {
    const { selected } = await body(ctx.request, SelectAllRequestSchema);
    ctx.session.lines.forEach((l) => { l.selected = selected; });
    return cartOf(ctx);
  })),
  http.post('*/v1/cart/items/:lineId/save-for-later', route((ctx, p) => {
    saveForLater(ctx.session, p.lineId ?? '');
    return cartOf(ctx);
  })),
  http.post('*/v1/cart/saved/:savedId/move-to-cart', route((ctx, p) => {
    moveToCart(ctx.session, p.savedId ?? '');
    return cartOf(ctx);
  })),
  http.delete('*/v1/cart/saved/:savedId', route((ctx, p) => {
    removeSaved(ctx.session, p.savedId ?? '');
    return cartOf(ctx);
  })),

  /* Auth */
  http.get('*/v1/me', route((ctx) => json(me(ctx)))),
  http.post('*/v1/auth/sign-in', route(async (ctx) => {
    const req = await body(ctx.request, SignInRequestSchema);
    const u = getDb().users.get(req.email.trim().toLowerCase());
    if (!u || u.password !== req.password) throw new MockProblem(401, 'INVALID_CREDENTIALS', 'Sign-in failed', 'Email or password is incorrect.');
    const id = challenge('mfa', safeReturn(req.returnTo), { userId: u.id });
    return json({ status: 'mfa_required', challengeId: id, methods: ['totp', 'sms'], destination: '+1 (713) •••-' + u.phone.slice(-4) });
  })),
  http.post('*/v1/auth/mfa/verify', route(async (ctx) => {
    const req = await body(ctx.request, VerifyCodeRequestSchema);
    const c = takeChallenge(req.challengeId, 'mfa');
    if (req.code !== DEMO_CODE) throw new MockProblem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { errors: { code: 'That code is incorrect or expired.' } });
    getDb().challenges.delete(c.id);
    ctx.session.userId = c.userId ?? null;
    return json({ status: 'authenticated', redirectTo: c.returnTo });
  })),
  http.post('*/v1/auth/sign-up', route(async (ctx) => {
    const req = await body(ctx.request, SignUpRequestSchema);
    const email = req.email.trim().toLowerCase();
    if (getDb().users.has(email)) throw new MockProblem(409, 'EMAIL_TAKEN', 'Account exists', 'An account with this email already exists. Sign in instead.', { errors: { email: 'An account with this email already exists' } });
    if (!isPasswordAcceptable(req.password, { firstName: req.firstName, lastName: req.lastName, email })) {
      throw new MockProblem(422, 'WEAK_PASSWORD', 'Password too weak', 'Password must meet all the rules.', { errors: { password: 'Password must meet all the rules' } });
    }
    const pending = { id: nextId('u'), firstName: req.firstName, lastName: req.lastName, email, password: req.password, phone: req.phone, roles: ['customer'] };
    const id = challenge('email', safeReturn(req.returnTo), { pending });
    return json({ status: 'verification_required', challengeId: id, destination: maskEmail(email), resendAfterSeconds: 30 }, 201);
  })),
  http.post('*/v1/auth/email-verification/verify', route(async (ctx) => {
    const req = await body(ctx.request, VerifyCodeRequestSchema);
    const c = takeChallenge(req.challengeId, 'email');
    if (req.code !== DEMO_CODE) throw new MockProblem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { errors: { code: 'That code is incorrect or expired.' } });
    const u = c.pending;
    if (!u) throw new MockProblem(410, 'CHALLENGE_EXPIRED', 'Verification expired', 'Start again.');
    getDb().users.set(u.email, u);
    getDb().challenges.delete(c.id);
    ctx.session.userId = u.id;
    return json({ status: 'authenticated', redirectTo: c.returnTo });
  })),
  http.post('*/v1/auth/email-verification/resend', route(async (ctx) => {
    const { challengeId } = await body(ctx.request, VerifyCodeRequestSchema.pick({ challengeId: true }));
    const c = takeChallenge(challengeId, 'email');
    c.expiresAt = Date.now() + 10 * 60_000;
    return json({ resendAfterSeconds: 30 }, 202);
  })),
  http.post('*/v1/auth/passkey/options', route((ctx) => {
    const id = challenge('passkey', '/');
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    const challengeB64 = btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    return json({ challengeId: id, challenge: challengeB64, rpId: new URL(ctx.request.url).hostname, timeout: 60000, userVerification: 'required', allowCredentials: [] });
  })),
  http.post('*/v1/auth/passkey/verify', route(async (ctx) => {
    const raw = (await ctx.request.json()) as { challengeId?: string };
    const c = takeChallenge(raw.challengeId ?? '', 'passkey');
    getDb().challenges.delete(c.id);
    ctx.session.userId = 'u_adaeze';
    return json({ status: 'authenticated', redirectTo: c.returnTo });
  })),
  http.post('*/v1/auth/sign-out', route((ctx) => {
    ctx.session.userId = null;
    return new HttpResponse(null, { status: 204 });
  })),
  http.get('*/v1/auth/oidc/:provider/authorize', route((ctx, p) => {
    const provider = p.provider ?? '';
    const idp = SITE_CONFIG.identityProviders.find((x) => x.id === provider && x.enabled);
    if (!idp) throw new MockProblem(404, 'PROVIDER_DISABLED', 'Sign-in option unavailable', `${provider} sign-in is not enabled.`);
    const returnTo = safeReturn(new URL(ctx.request.url).searchParams.get('returnTo'));
    const state = challenge('oidc', returnTo);
    // Production: 302 to identity /oauth2/authorize with PKCE (S256), state, nonce and idp_hint.
    return new HttpResponse(null, { status: 302, headers: { Location: `${prefixOf(ctx.request.url)}/v1/auth/oidc/callback?code=mock-${provider}&state=${state}` } });
  })),
  http.get('*/v1/auth/oidc/callback', route((ctx) => {
    const url = new URL(ctx.request.url);
    const c = takeChallenge(url.searchParams.get('state') ?? '', 'oidc');
    getDb().challenges.delete(c.id);
    ctx.session.userId = 'u_adaeze';
    return new HttpResponse(null, { status: 302, headers: { Location: new URL(c.returnTo, url.origin).toString() } });
  })),

  /* Careers */
  http.get('*/v1/careers/jobs', route(() => json({ jobs: JOBS }))),
  http.post('*/v1/careers/jobs/:jobId/applications', route(async (ctx, p) => {
    if (!JOBS.some((j) => j.id === p.jobId)) throw new MockProblem(404, 'JOB_NOT_FOUND', 'Role not found', 'This role is no longer open.');
    await body(ctx.request, JobApplicationFormSchema);
    return json({ reference: 'APL-' + String(Math.floor(100000 + Math.random() * 899999)), jobId: p.jobId }, 201);
  })),

  /* Account (next phase pages; authenticated) */
  http.get('*/v1/account/addresses', route((ctx) => {
    requireUser(ctx);
    return json({ items: [
      { id: 'ad1', name: 'Adaeze Nwosu', phone: '+17135550142', line1: '9800 Westheimer Rd', line2: '', city: 'Houston', state: 'TX', zip: '77042', isDefault: true, verified: true },
      { id: 'ad2', name: 'Adaeze Nwosu (work)', phone: '+17185550199', line1: '1250 Grand Concourse', line2: 'Apt 4B', city: 'Bronx', state: 'NY', zip: '10456', isDefault: false, verified: true }
    ], nextCursor: null });
  })),
  http.get('*/v1/account/payment-methods', route((ctx) => {
    requireUser(ctx);
    return json({ items: [
      { id: 'pm1', type: 'card', brand: 'visa', last4: '4242', label: 'Visa ending in 4242', expires: '08/29', isDefault: true },
      { id: 'pm2', type: 'card', brand: 'mastercard', last4: '4444', label: 'Mastercard ending in 4444', expires: '11/27', isDefault: false },
      { id: 'pm3', type: 'cashapp', brand: null, last4: null, label: 'Cash App Pay', expires: null, isDefault: false }
    ], nextCursor: null });
  })),
  http.get('*/v1/account/orders', route((ctx) => {
    requireUser(ctx);
    return json({ items: [
      { orderNumber: '112-3349127-8841', placedAt: '2026-10-01', totalMinor: 6147, currency: 'USD', shipTo: '9800 Westheimer Rd, Houston', status: 'delivered', shipments: [{ shipmentId: 'sh1', label: 'Shipped by AfroTransact from Houston FC', status: 'Delivered Oct 3', items: 'Raw Unrefined Shea Butter × 1 · Ofada Rice × 1' }] },
      { orderNumber: '112-2210853-1176', placedAt: '2026-09-18', totalMinor: 2803, currency: 'USD', shipTo: '9800 Westheimer Rd, Houston', status: 'delivered', shipments: [{ shipmentId: 'sh2', label: 'Shipped by Diaspora Books from Atlanta, GA', status: 'Delivered Sep 22', items: 'Pepper & Palm × 1' }] }
    ], nextCursor: null });
  })),
  http.get('*/v1/account/returns', route((ctx) => {
    requireUser(ctx);
    return json({ items: [], nextCursor: null });
  }))
];

