import { mkdirSync } from 'node:fs';
import path from 'node:path';

import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * "Start selling" from the storefront into Seller Center onboarding (S286), against the running local stack:
 *   1. a signed-in customer lands in onboarding signed in, application started and prefilled, no credential page;
 *   2. a brand-new person creates an account on identity's seller-branded sign-up page, confirms the emailed code
 *      (Mailpit) and lands in onboarding signed in;
 *   3. an approved seller opens Seller Center home instead of a new application.
 * Seller Center's origin is the stack's SELLER_CENTER_URL (LIVE_SELLER_URL), never assumed.
 */

const STOREFRONT = process.env.LIVE_STOREFRONT_URL ?? 'http://localhost:3000';
const SELLER = (process.env.LIVE_SELLER_URL ?? process.env.SELLER_CENTER_URL ?? 'http://localhost:3001').replace(/\/+$/, '');
const IDENTITY = process.env.LIVE_IDENTITY_URL ?? 'http://localhost:8100';
const MAILPIT = process.env.LIVE_MAILPIT_URL ?? 'http://localhost:8025';
const SHOTS = process.env.LIVE_SCREENSHOTS;
const PASSWORD = 'Test1234!Local';

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
}

/** Latest emailed 6-digit code for the address (Mailpit). */
async function emailedCode(to: string, after: number): Promise<string> {
  for (let i = 0; i < 60; i++) {
    const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}&limit=1`);
    const list = (await res.json()) as { messages?: Array<{ ID: string; Created: string }> };
    const m = list.messages?.[0];
    if (m && Date.parse(m.Created) >= after - 1000) {
      const msg = (await (await fetch(`${MAILPIT}/api/v1/message/${m.ID}`)).json()) as { Text?: string };
      const code = /\b(\d{6})\b/.exec(msg.Text ?? '')?.[1];
      if (code) return code;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no emailed code for ${to}`);
}

async function newPage(browser: Browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US' });
  return { context, page: await context.newPage() };
}

/** Records identity's credential pages (sign-in / sign-up) the tab visits. */
function credentialPages(page: Page): string[] {
  const seen: string[] = [];
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame() && f.url().startsWith(IDENTITY) && /^\/(login|signup)/.test(new URL(f.url()).pathname)) seen.push(f.url());
  });
  return seen;
}

/** Signs in on the storefront through identity's hosted page. */
async function storefrontSignIn(page: Page, email: string) {
  await page.goto(STOREFRONT);
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(new RegExp(`^${escape(IDENTITY)}/login`));
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.waitForURL(`${STOREFRONT}/**`);
}

async function startSelling(page: Page) {
  await page.getByRole('link', { name: 'Sell on AfroTransact' }).first().click();
}

test('a signed-in customer goes straight into onboarding, signed in, with the application prefilled', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await storefrontSignIn(page, 'customer.houston@afrotransact.test');
  await shot(page, 'customer-1-storefront-signed-in.png');

  const seen = credentialPages(page);
  await startSelling(page);
  await page.waitForURL(`${SELLER}/onboarding`, { timeout: 60_000 });
  expect(seen, 'no credential page on the way').toEqual([]);

  await expect(page.getByText('Using your existing AfroTransact account.')).toBeVisible();
  await expect(page.getByText(/We filled in your name, email and address from your AfroTransact account \(10200 Richmond Ave, Houston\)/)).toBeVisible();
  await expect(page.getByLabel(/Legal first name/)).toHaveValue('Adaeze');
  await expect(page.getByLabel(/Legal last name/)).toHaveValue('Okafor');
  await expect(page.getByLabel(/Contact email/)).toHaveValue('customer.houston@afrotransact.test');
  await expect(page.getByLabel(/Street address/)).toHaveValue('10200 Richmond Ave');
  await expect(page.getByLabel(/^City/)).toHaveValue('Houston');
  await expect(page.getByLabel(/^ZIP code/)).toHaveValue('77042');
  await expect(page.getByText('Sign in to Seller Central')).toHaveCount(0);
  await shot(page, 'customer-2-onboarding-prefilled.png');

  // A second click resumes the same application (same reference), still without a credential page.
  const reference = await page.getByRole('heading', { level: 2, name: /Your seller application/ }).textContent();
  await page.goto(STOREFRONT);
  await startSelling(page);
  await page.waitForURL(`${SELLER}/onboarding`, { timeout: 60_000 });
  await expect(page.getByRole('heading', { level: 2, name: /Your seller application/ })).toHaveText(reference ?? '');
  expect(seen).toEqual([]);
  await shot(page, 'customer-3-onboarding-resumed.png');
  await context.close();
});

test('a new person creates an account on the seller sign-up page, confirms the email and lands in onboarding', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  const email = `new.seller.${Date.now()}@example.com`;
  await page.goto(STOREFRONT);
  await shot(page, 'new-1-storefront-guest.png');
  await startSelling(page);

  await expect(page).toHaveURL(new RegExp(`^${escape(IDENTITY)}/signup`));
  await expect(page.getByRole('heading', { level: 1, name: 'Create your account to start selling' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Already have an account? Sign in' })).toBeVisible();
  await shot(page, 'new-2-identity-seller-signup.png');

  await page.getByLabel('First name').fill('Kemi');
  await page.getByLabel('Last name').fill('Balogun');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Plantain-Jollof-2026');
  await page.getByLabel('Confirm password').fill('Plantain-Jollof-2026');
  await page.getByRole('checkbox', { name: /I agree to AfroTransact/ }).check();
  const before = Date.now();
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Confirm your email' })).toBeVisible();
  await page.getByLabel('Verification code').fill(await emailedCode(email, before));
  await shot(page, 'new-3-identity-email-code.png');
  await page.getByRole('button', { name: 'Verify and continue' }).click();

  await page.waitForURL(`${SELLER}/onboarding`, { timeout: 60_000 });
  await expect(page.getByLabel(/Legal first name/)).toHaveValue('Kemi');
  await expect(page.getByLabel(/Legal last name/)).toHaveValue('Balogun');
  await expect(page.getByLabel(/Contact email/)).toHaveValue(email);
  await expect(page.getByRole('heading', { level: 2, name: /Your seller application/ })).toBeVisible();
  await shot(page, 'new-4-onboarding-signed-in.png');
  await context.close();
});

test('an approved seller opens Seller Center home instead of a new application', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await storefrontSignIn(page, 'seller.owner@afrotransact.test');
  const seen = credentialPages(page);
  await startSelling(page);
  await page.waitForURL((u) => u.origin === SELLER && u.pathname === '/', { timeout: 60_000 });
  expect(seen).toEqual([]);
  await expect(page.getByRole('navigation', { name: 'Back office' }).first()).toBeVisible();
  await expect(page.getByText('Your seller application')).toHaveCount(0);
  await shot(page, 'seller-owner-seller-center-home.png');
  await context.close();
});
