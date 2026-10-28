import { execSync } from 'node:child_process';

import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * A person who is both a customer and an approved seller (customer.houston: APP-30200, approved) opens Seller Center
 * from the storefront, against the running local stack, with Seller Center on whatever port the stack gave it
 * (tools/dev/local.sh moves it to :3010 when :3001 is taken, e.g. by your own Grafana; LIVE_SELLER_URL /
 * SELLER_CENTER_URL). Every main-frame navigation is recorded: the redirect chain must end on the dashboard, signed
 * in, without going round, and a shell that is sent round in circles stops with a panel instead of loading forever.
 * The last journey approves an application while identity is stopped (its approval event waits in Kafka) and starts
 * identity again: the applicant still reaches the dashboard (the event, the reconcile job or Seller Center's on-demand
 * sync repair identity's copy), never "Start selling" or onboarding.
 */

const STOREFRONT = process.env.LIVE_STOREFRONT_URL ?? 'http://localhost:3000';
const SELLER = (process.env.LIVE_SELLER_URL ?? process.env.SELLER_CENTER_URL ?? 'http://localhost:3001').replace(/\/+$/, '');
const IDENTITY = process.env.LIVE_IDENTITY_URL ?? 'http://localhost:8100';
const EMAIL = 'customer.houston@afrotransact.test';
const PASSWORD = 'Test1234!Local';

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Main-frame navigations of the tab, in order (the redirect chain), with the HTTP redirects in between. */
function chainOf(page: Page): string[] {
  const chain: string[] = [];
  page.on('response', (r) => {
    if (r.request().isNavigationRequest() && r.request().frame() === page.mainFrame()) {
      const to = r.headers()['location'];
      chain.push(to ? `${r.status()} ${r.url()} -> ${to}` : `${r.status()} ${r.url()}`);
    }
  });
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame()) chain.push(`NAV ${f.url()}`);
  });
  return chain;
}

async function newPage(browser: Browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US' });
  return { context, page: await context.newPage() };
}

async function signInOnIdentity(page: Page) {
  await page.getByLabel('Email').fill(EMAIL);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
}

async function storefrontSignIn(page: Page) {
  await page.goto(STOREFRONT);
  await page.getByRole('button', { name: /Account menu/ }).first().click();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(new RegExp(`^${escape(IDENTITY)}/login`));
  await signInOnIdentity(page);
  await page.waitForURL(`${STOREFRONT}/**`);
}

test('the storefront menu opens the Seller Center dashboard of a dual-role approved seller, signed in, in one pass', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await storefrontSignIn(page);
  await page.getByRole('button', { name: /Account menu/ }).first().click();
  const entry = page.getByRole('menuitem', { name: /^(Seller Center|Continue your seller application)$/ });
  test.skip((await entry.count()) === 0, `${EMAIL} has no seller organisation on this stack (make seed)`);
  // Approved: the menu opens the back office itself, not the application.
  await expect(entry).toHaveText('Seller Center');
  await expect(entry).toHaveAttribute('href', '/back-office/seller-center');

  const chain = chainOf(page);
  await entry.click();
  await page.waitForURL((u) => u.origin === SELLER && u.pathname === '/', { timeout: 60_000 });
  // Loaded within the shell's 10 s budget: the sidebar of the organisation, never the loader or a loop panel.
  await expect(page.getByRole('navigation', { name: 'Back office' }).first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(3_000);
  await test.info().attach('redirect-chain.txt', { body: chain.join('\n'), contentType: 'text/plain' });
  expect(page.url()).toBe(`${SELLER}/`);
  await expect(page.getByText('Loading your back office…')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Seller Center keeps redirecting' })).toHaveCount(0);
  // One hosted sign-in hop (single sign-on, no credential page), never to the default port.
  const loginHops = (c: string[]) => c.filter((line) => !line.startsWith('NAV') && line.split(' ')[1]?.startsWith(`${SELLER}/api/bff/v1/auth/login`));
  expect(loginHops(chain).length, chain.join('\n')).toBe(1);
  expect(chain.filter((c) => c.startsWith('NAV') && new URL(c.slice(4)).pathname.startsWith('/login')), chain.join('\n')).toEqual([]);
  expect(chain.filter((c) => c.startsWith('NAV') && !c.startsWith(`NAV ${STOREFRONT}`) && !c.startsWith(`NAV ${SELLER}`)), chain.join('\n')).toEqual([]);

  // "Start selling" (/onboarding?role=seller) for the same approved seller lands on the dashboard once.
  const again = chainOf(page);
  await page.goto(`${SELLER}/onboarding?role=seller`);
  await page.waitForURL(`${SELLER}/`, { timeout: 30_000 });
  await expect(page.getByRole('navigation', { name: 'Back office' }).first()).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(3_000);
  await test.info().attach('onboarding-chain.txt', { body: again.join('\n'), contentType: 'text/plain' });
  expect(page.url()).toBe(`${SELLER}/`);
  expect(again.filter((c) => c === `NAV ${SELLER}/onboarding?role=seller`).length, again.join('\n')).toBeLessThanOrEqual(2);
  await context.close();
});

test('a Seller Center session that never sticks stops with a panel naming the loop, not a loader that spins forever', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  // The session never sticks (as with a cookie the browser drops) and the 2-minute sign-in guard is lost on every
  // page load: the only thing left to stop the full-page loop is the shell's redirect guard.
  await context.addInitScript(() => {
    try {
      window.sessionStorage.removeItem('at.sc.signInStartedAt');
    } catch {
      /* ignore */
    }
  });
  await page.route('**/api/bff/v1/seller/session', (route) =>
    route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Sign in required', status: 401, code: 'UNAUTHENTICATED' }) })
  );
  const chain = chainOf(page);
  await page.goto(`${SELLER}/`);
  await page.waitForURL(new RegExp(`^${escape(IDENTITY)}/login`), { timeout: 60_000 });
  await signInOnIdentity(page);
  const panel = page.getByRole('heading', { name: 'Seller Center keeps redirecting' });
  await expect(panel).toBeVisible({ timeout: 60_000 });
  await test.info().attach('loop-chain.txt', { body: chain.join('\n'), contentType: 'text/plain' });
  await expect(page.getByText(/\/api\/bff\/v1\/auth\/login\?returnTo=/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Go to storefront' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  // It stopped: no further round trips.
  const settledLength = chain.length;
  await page.waitForTimeout(4_000);
  expect(chain.length).toBe(settledLength);
  const logins = chain.filter((line) => !line.startsWith('NAV') && line.split(' ')[1]?.startsWith(`${SELLER}/api/bff/v1/auth/login`));
  expect(logins.length, chain.join('\n')).toBeLessThanOrEqual(4);
  await context.close();
});

/* ── An approval identity did not see when it happened ── */

const SELLER_API = (process.env.LIVE_SELLER_API_URL ?? 'http://localhost:8106').replace(/\/+$/, '');
const STOP_IDENTITY = process.env.LIVE_IDENTITY_STOP;
const START_IDENTITY = process.env.LIVE_IDENTITY_START;
const APPLICANT_PASSWORD = process.env.LIVE_APPLICANT_PASSWORD ?? 'Plantain-Jollof-2026';

/** An unsigned staff token: the seller service runs with security off locally and acts as this staff member. */
function localStaff(subject: string): string {
  const part = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${part({ alg: 'none' })}.${part({ sub: subject, pt: 'STAFF', roles: ['platform.super_admin'] })}.`;
}

async function sellerApi(path: string, init: { method?: string; body?: object; staff?: string; key?: string } = {}) {
  const res = await fetch(`${SELLER_API}${path}`, {
    method: init.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${localStaff(init.staff ?? 'live-reviewer')}`,
      'Content-Type': 'application/json',
      ...(init.key ? { 'Idempotency-Key': init.key } : {})
    },
    body: init.body ? JSON.stringify(init.body) : undefined
  });
  return { status: res.status, body: (await res.json().catch(() => ({}))) as Record<string, unknown> };
}

/** A submitted application of an account whose password is known (the wizard journey's wizard.*@example.com). */
async function reviewableApplication(): Promise<{ id: string; email: string } | null> {
  const queue = await sellerApi('/v1/applications?status=PENDING_REVIEW&size=50');
  for (const item of (queue.body.items as Array<{ id: string }> | undefined) ?? []) {
    const dossier = await sellerApi(`/v1/applications/${item.id}`);
    const email = (dossier.body.business as { contactEmail?: string } | undefined)?.contactEmail ?? '';
    if (process.env.LIVE_APPLICANT_EMAIL ? email === process.env.LIVE_APPLICANT_EMAIL : /^(wizard|new\.seller)\.\d+@example\.com$/.test(email)) {
      return { id: item.id, email };
    }
  }
  return null;
}

async function identityUp(): Promise<boolean> {
  try {
    return (await fetch(`${IDENTITY}/actuator/health`)).ok;
  } catch {
    return false;
  }
}

test('approved while identity was stopped: once identity is back, the applicant reaches the Seller Center dashboard', async ({ browser }) => {
  test.skip(!STOP_IDENTITY || !START_IDENTITY, 'set LIVE_IDENTITY_STOP and LIVE_IDENTITY_START (commands that stop and start identity)');
  if (!STOP_IDENTITY || !START_IDENTITY) return;
  test.setTimeout(300_000);
  const app = await reviewableApplication();
  test.skip(!app, 'no submitted application of a wizard.*@example.com account (wizard.* or new.seller.*@example.com; run the S289 wizard journey, or set LIVE_APPLICANT_EMAIL / LIVE_APPLICANT_PASSWORD)');
  if (!app) return;

  const { context, page } = await newPage(browser);
  // The applicant is signed in to Seller Center while the application is in review.
  await page.goto(`${SELLER}/`);
  await page.waitForURL(new RegExp(`^${escape(IDENTITY)}/login`), { timeout: 60_000 });
  await page.getByLabel('Email').fill(app.email);
  await page.getByLabel('Password', { exact: true }).fill(APPLICANT_PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.waitForURL((u) => u.origin === SELLER, { timeout: 60_000 });
  await expect(page.getByText(/Your seller application|Your provider application/).first()).toBeVisible({ timeout: 30_000 });

  // Identity is down while AfroTransact approves the application (both review steps, two staff members).
  execSync(STOP_IDENTITY, { stdio: 'inherit' });
  try {
    const reviewed = await sellerApi(`/v1/applications/${app.id}/review`, { method: 'POST', staff: 'live-reviewer',
      body: { action: 'APPROVE', reasonCode: 'ALL_CHECKS_PASSED', actorName: 'Live Reviewer' } });
    expect(reviewed.status, JSON.stringify(reviewed.body)).toBe(200);
    const decided = await sellerApi(`/v1/applications/${app.id}/decision`, { method: 'POST', staff: 'live-approver',
      key: `live-${app.id}`, body: { action: 'FINAL_APPROVE', reasonCode: 'ALL_CHECKS_PASSED', actorName: 'Live Approver' } });
    expect(decided.status, JSON.stringify(decided.body)).toBe(200);
    expect(decided.body.status).toBe('APPROVED');
  } finally {
    execSync(START_IDENTITY, { stdio: 'inherit' });
  }
  await expect.poll(identityUp, { timeout: 180_000, intervals: [2_000] }).toBe(true);

  // Back in Seller Center: signed in again through single sign-on if needed, and on the dashboard.
  const chain = chainOf(page);
  await page.goto(`${SELLER}/`);
  await expect(async () => {
    if (new URL(page.url()).origin === IDENTITY && /\/login/.test(page.url())) {
      await page.getByLabel('Email').fill(app.email);
      await page.getByLabel('Password', { exact: true }).fill(APPLICANT_PASSWORD);
      await page.getByRole('button', { name: 'Continue' }).click();
    }
    await expect(page.getByRole('navigation', { name: 'Back office' }).first()).toBeVisible({ timeout: 10_000 });
  }).toPass({ timeout: 120_000 });
  await test.info().attach('after-approval-chain.txt', { body: chain.join('\n'), contentType: 'text/plain' });
  expect(new URL(page.url()).pathname).toBe('/');
  await expect(page.getByText('Start selling on AfroTransact')).toHaveCount(0);
  await expect(page.getByText(/Your seller application/)).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Seller Center keeps redirecting' })).toHaveCount(0);
  await context.close();
});
