/* MSW request handlers implementing contracts/openapi/gateway-bff.yaml against the prototype fixtures.
   Paths use a wildcard origin/prefix (any base URL ending in /v1/...), so the same handlers serve the
   browser (/api/bff/v1/…), server components (in-process) and Vitest (setupServer). */
import { http, HttpResponse } from 'msw';
import { z } from 'zod';

import { CodeSchema, SignInRequestSchema, SignUpRequestSchema, VerifyCodeRequestSchema } from '../schemas/auth';
import { JobApplicationFormSchema } from '../schemas/careers';
import { AddToCartRequestSchema, SelectAllRequestSchema, UpdateCartLineRequestSchema } from '../schemas/cart';
import { StockAlertRequestSchema } from '../schemas/catalog';
import { ZipSchema } from '../schemas/location';
import { NewsletterRequestSchema } from '../schemas/site';
import { fromApiSearchParams } from '../search-params';
import { isPasswordAcceptable } from '../validation/password';
import { suggestAddresses } from './addresses';
import { getDb, type MockChallenge, nextId, userById } from './db';
import { locationForZip, nearestZip } from './delivery';
import { DEMO_CODE, JOBS, SITE_CONFIG } from './fixtures/content';
import { body, type Ctx, json, requireUser, route } from './http-kit';
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
import { shopperHandlers } from './shopper';
import { accountCounts } from './shopper-state';

const cartOf = (ctx: Ctx) => json(buildCart(ctx.session, ctx.now));

function me(ctx: Ctx) {
  const u = userById(ctx.session.userId);
  if (!u) return { authenticated: false, user: null, counts: null };
  return {
    authenticated: true,
    user: { id: u.id, firstName: u.firstName, lastName: u.lastName, email: u.email, initials: (u.firstName[0] ?? '') + (u.lastName[0] ?? ''), roles: u.roles,
      ...(u.phone ? { phone: '•••• ' + u.phone.slice(-4) } : {}), phoneVerified: phoneVerified.has(u.id) },
    counts: accountCounts(u.id)
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

/** Accounts whose phone passed the mock SMS check (code DEMO_CODE). */
const phoneVerified = new Set<string>();


const prefixOf = (url: string) => {
  const u = new URL(url);
  return u.origin + u.pathname.slice(0, u.pathname.indexOf('/v1/'));
};

export { SESSION_COOKIE } from './http-kit';

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
    const page = search(query, sessionLocation(ctx.session), ctx.now);
    // ads=false (machine-made queries) gets organic cards only; a searchId identifies the page for click reporting.
    const ads = new URL(ctx.request.url).searchParams.get('ads') !== 'false';
    return json({ ...page, hits: ads ? page.hits : page.hits.map((h) => ({ ...h, sponsor: null })), searchId: `srch-${crypto.randomUUID()}` });
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
    if (req.invitationToken) {
      /* An organisation invite link: the account starts verified and joined, no email code. */
      getDb().users.set(email, pending);
      return json({ status: 'authenticated', redirectTo: `/signin?returnTo=${encodeURIComponent(safeReturn(req.returnTo))}` }, 201);
    }
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
  http.post('*/v1/careers/uploads', route(async (ctx) => {
    const b = (await ctx.request.json()) as { fileName?: string; contentType?: string; size?: number };
    if (b.contentType !== 'application/pdf' || !(b.size && b.size > 0 && b.size <= 5 * 1024 * 1024)) {
      throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'Upload your résumé as a PDF of 5 MB or less.');
    }
    const id = `resume-${Date.now()}`;
    return json({ assetId: id, maxBytes: 5 * 1024 * 1024, upload: { method: 'PUT', url: `${new URL(ctx.request.url).origin}/api/bff/mock-resume-uploads/${id}`,
      headers: { 'Content-Type': 'application/pdf' }, expiresAt: new Date(Date.now() + 15 * 60_000).toISOString() } }, 201);
  })),
  http.put('*/mock-resume-uploads/:id', () => new HttpResponse(null, { status: 200 })),
  http.post('*/v1/careers/uploads/:assetId/complete', route((_ctx, p) => json({ assetId: p.assetId, contentType: 'application/pdf', bytes: 120000, status: 'READY' }))),
  http.post('*/v1/careers/jobs/:jobId/applications', route(async (ctx, p) => {
    if (!JOBS.some((j) => j.id === p.jobId)) throw new MockProblem(404, 'JOB_NOT_FOUND', 'Role not found', 'This role is no longer open.');
    await body(ctx.request, JobApplicationFormSchema);
    return json({ reference: 'CAR-' + String(Math.floor(100000 + Math.random() * 899999)), jobId: p.jobId }, 201);
  })),

  /* Account (next phase pages; authenticated) */
  http.post('*/v1/account/phone/verification', route((ctx) => {
    const u = requireUser(ctx);
    if (!u.phone) throw new MockProblem(422, 'PHONE_MISSING', 'No phone number', 'Add a mobile number to your profile first.', { errors: { phone: 'Add a mobile number to your profile first.' } });
    if (phoneVerified.has(u.id)) throw new MockProblem(409, 'CONFLICT', 'Already verified', 'Your phone number is already verified.');
    return json({ sentTo: '•••• ' + u.phone.slice(-4), expiresAt: new Date(Date.now() + 10 * 60_000).toISOString() }, 202);
  })),
  http.post('*/v1/account/phone/verification/confirm', route(async (ctx) => {
    const u = requireUser(ctx);
    const { code } = await body(ctx.request, z.object({ code: CodeSchema }));
    if (code !== DEMO_CODE) throw new MockProblem(422, 'INVALID_CODE', 'Incorrect code', 'That code is incorrect or expired.', { errors: { code: 'That code is incorrect or expired.' } });
    phoneVerified.add(u.id);
    return json({ phoneVerified: true, verifiedAt: new Date().toISOString() });
  })),

  /* Addresses, payments, orders, returns, security, checkout, services & bookings, Lola */
  ...shopperHandlers
];

