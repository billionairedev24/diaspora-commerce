import { readFileSync } from 'node:fs';
import path from 'node:path';

import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * Single sign-on through identity's hosted pages (Spring Authorization Server), against the running local stack:
 * storefront "Sign in" → identity's branded sign-in → back on the storefront signed in; the staff console then signs
 * in without a credential prompt; sign-out ends the identity session; "Create account" → identity's sign-up → email
 * code → back on the storefront signed in.
 */

const STOREFRONT = process.env.LIVE_STOREFRONT_URL ?? 'http://localhost:3000';
const STAFF = process.env.LIVE_STAFF_URL ?? 'http://localhost:3002';
const IDENTITY = process.env.LIVE_IDENTITY_URL ?? 'http://localhost:8100';
const MAILPIT = process.env.LIVE_MAILPIT_URL ?? 'http://localhost:8025';
const IDENTITY_LOG = process.env.LIVE_IDENTITY_LOG ?? path.resolve(import.meta.dirname, '../../../.local/logs/identity.log');
const SHOTS = process.env.LIVE_SCREENSHOTS;
const ADMIN = { email: 'admin@afrotransact.com', password: 'LocalAdmin2026!' };

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Latest one-time code sent to an email or phone: Mailpit first (outbox delivery), else identity's local log sender. */
async function latestCode(destination: string, after: number): Promise<string> {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${destination}"`)}&limit=1`);
      const list = (await res.json()) as { messages?: Array<{ ID: string; Created: string }> };
      const m = list.messages?.[0];
      if (m && Date.parse(m.Created) >= after) {
        const msg = (await (await fetch(`${MAILPIT}/api/v1/message/${m.ID}`)).json()) as { Text?: string };
        const code = /\b(\d{6})\b/.exec(msg.Text ?? '')?.[1];
        if (code) return code;
      }
    } catch {
      /* Mailpit not reachable: fall back to the log */
    }
    try {
      const lines = readFileSync(IDENTITY_LOG, 'utf8').split('\n').reverse();
      const line = lines.find((l) => new RegExp(`code for .* to ${escape(destination)}: (\\d{6})`).test(l) && Date.parse(l.slice(0, 24)) >= after - 1000);
      const code = line ? new RegExp(`to ${escape(destination)}: (\\d{6})`).exec(line)?.[1] : undefined;
      if (code) return code;
    } catch {
      /* no local log */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no verification code for ${destination}`);
}

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
}

async function expectBrandedIdentityPage(page: Page, heading: string) {
  await expect(page).toHaveURL(new RegExp(`^${escape(IDENTITY)}/`));
  await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
  await expect(page.locator('link[href="/assets/tokens.css"]')).toHaveCount(1);
  await expect(page.getByRole('link', { name: 'AfroTransact home' })).toBeVisible();
  // Real flow only: no demo credentials, step switchers or persona pickers.
  await expect(page.getByText(/demo|mock API/i)).toHaveCount(0);
  const font = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
  expect(font).toContain('Figtree');
}

/** Second factor for staff accounts: a texted code (or none when this browser is trusted). */
async function completeMfaIfAsked(page: Page) {
  await page.waitForURL((u) => !u.pathname.startsWith('/login') || u.pathname === '/login/mfa');
  if (!page.url().startsWith(`${IDENTITY}/login/mfa`)) return;
  await expect(page.getByRole('heading', { level: 1, name: 'Two-step verification' })).toBeVisible();
  const sms = page.getByRole('radio', { name: 'Text message' });
  if (await sms.count()) {
    const before = Date.now();
    await sms.check();
    await page.getByRole('button', { name: 'Text me a code' }).click();
    await page.getByRole('radio', { name: 'Text message' }).check();
    await page.getByLabel('Code').fill(await latestCode('+17132490142', before));
  }
  await page.getByLabel('Trust this browser for 30 days').check();
  await page.getByRole('button', { name: 'Verify' }).click();
}

async function newPage(browser: Browser, width = 1440, height = 900) {
  const context = await browser.newContext({ viewport: { width, height }, locale: 'en-US' });
  return { context, page: await context.newPage() };
}

test('storefront sign-in on identity, then the staff console signs in without a credential prompt (SSO), then sign-out ends both', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await page.goto(STOREFRONT);
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();

  await expectBrandedIdentityPage(page, 'Sign in to AfroTransact');
  await expect(page.getByText('The flavours of home, a click away.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in with a passkey' })).toBeVisible();
  await shot(page, 'identity-signin-desktop-1440.png');

  await page.getByLabel('Email').fill(ADMIN.email);
  await page.getByLabel('Password', { exact: true }).fill(ADMIN.password);
  await page.getByRole('button', { name: 'Continue' }).click();
  await completeMfaIfAsked(page);

  await page.waitForURL(`${STOREFRONT}/**`);
  await page.getByRole('button', { name: /Account menu: Platform Administrator/ }).click();
  await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  await page.keyboard.press('Escape');

  // The staff console: drop the web session cookie so only identity's session remains, then sign in there.
  await context.clearCookies({ name: 'at_session' });
  const identityPages: string[] = [];
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame() && f.url().startsWith(IDENTITY) && /\/(login|signup)/.test(new URL(f.url()).pathname)) identityPages.push(f.url());
  });
  await page.goto(STAFF);
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.waitForURL(`${STAFF}/**`);
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  expect(identityPages, 'single sign-on: no identity sign-in page on the way').toEqual([]);

  // Signing out of the staff console ends the identity session too (RP-initiated logout) and returns there.
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL(`${STAFF}/**`);
  await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  await context.clearCookies({ name: 'at_session' });
  await page.goto(STOREFRONT);
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();
  await expectBrandedIdentityPage(page, 'Sign in to AfroTransact');
  await context.close();
});

test('Create account on the storefront opens identity’s sign-up, confirms the email and returns signed in', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  const email = `ama.${Date.now()}@example.com`;
  await page.goto(`${STOREFRONT}/cart`);
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await page.getByRole('menuitem', { name: 'Create account' }).click();

  await expectBrandedIdentityPage(page, 'Create your AfroTransact account');
  await expect(page.getByText('Home, delivered to your door.')).toBeVisible();
  await shot(page, 'identity-signup-desktop-1440.png');

  // Server-side validation comes back on the page with an error summary that takes focus.
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('alert').filter({ hasText: /problems? with this form/ })).toBeFocused();

  await page.getByLabel('First name').fill('Ama');
  await page.getByLabel('Last name').fill('Mensah');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('Plantain-Jollof-2026');
  await page.getByLabel('Confirm password').fill('Plantain-Jollof-2026');
  await expect(page.locator('[data-rule="length"]')).toHaveAttribute('data-ok', 'true');
  await page.getByRole('checkbox', { name: /I agree to AfroTransact/ }).check();
  const before = Date.now();
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Confirm your email' })).toBeVisible();
  await page.getByLabel('Verification code').fill(await latestCode(email, before));
  await page.getByRole('button', { name: 'Verify and continue' }).click();

  await page.waitForURL(`${STOREFRONT}/cart`);
  await page.getByRole('button', { name: /Account menu: Ama Mensah/ }).click();
  await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  await context.close();
});

test('hosted pages at mobile width (390): no horizontal scroll, no visible scrollbars', async ({ browser }) => {
  const { context, page } = await newPage(browser, 390, 844);
  for (const [label, heading, file] of [
    ['Sign in', 'Sign in to AfroTransact', 'identity-signin-mobile-390.png'],
    ['Create account', 'Create your AfroTransact account', 'identity-signup-mobile-390.png']
  ] as const) {
    await page.goto(STOREFRONT);
    await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
    await page.getByRole('menuitem', { name: label }).click();
    await expectBrandedIdentityPage(page, heading);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('scrollbar-width').trim())).toBe('none');
    await shot(page, file);
  }
  await context.close();
});
