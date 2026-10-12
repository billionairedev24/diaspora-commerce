import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * A person who is both a customer and an approved seller (customer.houston: APP-30200, approved) opens Seller Center
 * from the storefront, against the running local stack, with Seller Center on whatever port the stack gave it
 * (tools/dev/local.sh moves it to :3010 when :3001 is taken, e.g. by your own Grafana; LIVE_SELLER_URL /
 * SELLER_CENTER_URL). Every main-frame navigation is recorded: the redirect chain must end on the dashboard, signed
 * in, without going round, and a shell that is sent round in circles stops with a panel instead of loading forever.
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
