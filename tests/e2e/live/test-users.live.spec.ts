import { readFileSync } from 'node:fs';
import path from 'node:path';

import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * S276 local test users, signed in through the real SSO flow (app → identity's hosted page → back) against the running
 * local stack after `make seed`: a customer lands on the storefront with her Houston address and no second step; the
 * Mama Put Pantry owner lands in Seller Center acting for her organisation and sees its stock; a support agent keeps
 * the staff two-step policy, gets the texted code from Mailpit and sees the staff console as Customer Support.
 */

const ROOT = path.resolve(import.meta.dirname, '../../..');

/** An app's local address: LIVE_* override, else the port `make up` chose (.local/web-ports), else the default. */
function appUrl(envVar: string, portVar: string, fallback: number): string {
  const explicit = process.env[envVar];
  if (explicit) return explicit;
  try {
    const line = readFileSync(path.join(ROOT, '.local/web-ports'), 'utf8').split('\n').find((l) => l.startsWith(`${portVar}=`));
    const port = line?.split('=')[1]?.trim();
    if (port) return `http://localhost:${port}`;
  } catch {
    /* no .local/web-ports: default ports */
  }
  return `http://localhost:${fallback}`;
}

const STOREFRONT = appUrl('LIVE_STOREFRONT_URL', 'STOREFRONT_PORT', 3000);
const SELLER = appUrl('LIVE_SELLER_URL', 'SELLER_CENTER_PORT', 3001);
const STAFF = appUrl('LIVE_STAFF_URL', 'STAFF_CONSOLE_PORT', 3002);
const IDENTITY = process.env.LIVE_IDENTITY_URL ?? 'http://localhost:8100';
const MAILPIT = process.env.LIVE_MAILPIT_URL ?? 'http://localhost:8025';
const SHOTS = process.env.LIVE_SCREENSHOTS;
const PASSWORD = 'Test1234!Local';

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, name) });
}

async function newPage(browser: Browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US' });
  return { context, page: await context.newPage() };
}

/** Identity's hosted sign-in: email + password; returns whether a two-step page followed. */
async function signInOnIdentity(page: Page, email: string): Promise<boolean> {
  await page.waitForURL(`${IDENTITY}/**`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Sign in');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login') || u.pathname === '/login/mfa');
  return page.url().startsWith(`${IDENTITY}/login/mfa`);
}

/** The texted sign-in code identity dropped in Mailpit for this phone after `after` (local profile only). */
async function smsCodeFromMailpit(phone: string, after: number): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${phone}"`)}&limit=1`);
    const list = (await res.json()) as { messages?: Array<{ Subject: string; Created: string; Tags: string[] }> };
    const m = list.messages?.[0];
    if (m && Date.parse(m.Created) >= after - 1000) {
      expect(m.Tags).toContain('sms');
      const code = /\b(\d{6})\b/.exec(m.Subject)?.[1];
      if (code) return code;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no texted code for ${phone} in Mailpit`);
}

test('customer.houston signs in on the storefront with email + password only and shops with her Houston address', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await page.goto(STOREFRONT);
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();
  expect(await signInOnIdentity(page, 'customer.houston@afrotransact.test'), 'no second step for a customer').toBe(false);

  await page.waitForURL(`${STOREFRONT}/**`);
  await expect(page.getByRole('button', { name: /Account menu: Adaeze Okafor/ })).toBeVisible();
  await expect(page.getByText('Houston 77042').first()).toBeVisible();
  await shot(page, 'live-customer-houston.png');
  await page.goto(`${STOREFRONT}/account/addresses`);
  await expect(page.getByText('10200 Richmond Ave')).toBeVisible();
  await expect(page.getByText('Default address')).toBeVisible();
  await context.close();
});

test('seller.owner signs in to Seller Center acting for Mama Put Pantry and sees its stock', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await page.goto(SELLER);
  await page.getByRole('link', { name: 'Sign in' }).click();
  expect(await signInOnIdentity(page, 'seller.owner@afrotransact.test'), 'no second step for a seller').toBe(false);

  await page.waitForURL(`${SELLER}/**`);
  await expect(page.getByText('Today at Mama Put Pantry.')).toBeVisible();
  await expect(page.getByText('Seller · Owner')).toBeVisible();
  await shot(page, 'live-seller-owner.png');
  await page.goto(`${SELLER}/inventory`);
  await expect(page.getByRole('heading', { name: 'Inventory & FBA' })).toBeVisible();
  // Mama Put Pantry's FBA stock received by `make seed` at the Houston fulfilment centre.
  await expect(page.getByRole('cell', { name: 'HOU-FC' }).first()).toBeVisible();
  await context.close();
});

test('staff.support keeps the staff two-step policy: the texted code comes from Mailpit, then the console as Customer Support', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await page.goto(STAFF);
  await page.getByRole('link', { name: 'Sign in' }).click();
  expect(await signInOnIdentity(page, 'staff.support@afrotransact.test'), 'staff are asked for a second step').toBe(true);

  await expect(page.getByRole('heading', { level: 1, name: 'Two-step verification' })).toBeVisible();
  const before = Date.now();
  await page.getByRole('radio', { name: 'Text message' }).check();
  await page.getByRole('button', { name: 'Text me a code' }).click();
  await page.getByRole('radio', { name: 'Text message' }).check();
  await page.getByLabel('Code').fill(await smsCodeFromMailpit('+17135550105', before));
  await page.getByLabel('Trust this browser for 30 days').check();
  await page.getByRole('button', { name: 'Verify' }).click();

  await page.waitForURL(`${STAFF}/**`);
  await expect(page.getByText('Access: Customer Support')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await shot(page, 'live-staff-support.png');
  await context.close();
});
