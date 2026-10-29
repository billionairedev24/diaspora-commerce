import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';

import { type Browser, expect, type Page, test } from '@playwright/test';

/*
 * S300 on identity's hosted pages, against a running identity (the local stack, or identity alone): pages without an
 * app are neutral, the staff console's branding only appears for staff console requests, and "Forgot password?" from
 * Seller Center resets the password with an emailed code and comes back to Seller Center's sign-in page. Codes come
 * from Mailpit, else identity's log (at.identity.verification.sender=log|mailpit). LIVE_SCREENSHOTS saves the pages.
 */

const IDENTITY = process.env.LIVE_IDENTITY_URL ?? 'http://localhost:8100';
const SELLER = process.env.LIVE_SELLER_URL ?? 'http://localhost:3001';
const STAFF = process.env.LIVE_STAFF_URL ?? 'http://localhost:3002';
const MAILPIT = process.env.LIVE_MAILPIT_URL ?? 'http://localhost:8025';
const IDENTITY_LOG = process.env.LIVE_IDENTITY_LOG ?? path.resolve(import.meta.dirname, '../../../.local/logs/identity.log');
const SHOTS = process.env.LIVE_SCREENSHOTS;
const PASSWORD = 'Plantain-Jollof-2026';
const NEW_PASSWORD = 'Suya-Spice-Market-2027';

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function logSize(): number {
  try {
    return statSync(IDENTITY_LOG).size;
  } catch {
    return 0;
  }
}

/** Latest code sent to `email` since `after` (ms) / since the log was `offset` bytes long. */
async function latestCode(email: string, after: number, offset: number): Promise<string> {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}&limit=1`);
      const m = ((await res.json()) as { messages?: Array<{ ID: string; Created: string }> }).messages?.[0];
      if (m && Date.parse(m.Created) >= after) {
        const msg = (await (await fetch(`${MAILPIT}/api/v1/message/${m.ID}`)).json()) as { Text?: string };
        const code = /\b(\d{6})\b/.exec(msg.Text ?? '')?.[1];
        if (code) return code;
      }
    } catch {
      /* no Mailpit: the log */
    }
    try {
      const fresh = readFileSync(IDENTITY_LOG).subarray(offset).toString('utf8');
      const codes = [...fresh.matchAll(new RegExp(`code for .* to ${escape(email)}: (\\d{6})`, 'g'))];
      const code = codes.at(-1)?.[1];
      if (code) return code;
    } catch {
      /* no log */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no code for ${email}`);
}

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: path.join(SHOTS, name), fullPage: true });
}

async function newPage(browser: Browser) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'en-US' });
  return { context, page: await context.newPage() };
}

/** An authorization request from one of the web apps (gateway-bff, that app's callback), as the BFF starts it. */
function authorizeUrl(appOrigin: string, extra = ''): string {
  const q = new URLSearchParams({
    response_type: 'code',
    client_id: 'gateway-bff',
    redirect_uri: `${appOrigin}/api/bff/v1/auth/oidc/callback`,
    scope: 'openid profile email offline_access',
    state: `st-${Date.now()}`,
    nonce: 'n-1',
    code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    code_challenge_method: 'S256'
  });
  return `${IDENTITY}/oauth2/authorize?${q.toString()}${extra}`;
}

async function createAccount(page: Page): Promise<string> {
  const email = `reset.${Date.now()}@example.com`;
  await page.goto(`${IDENTITY}/signup`);
  await page.getByLabel('First name').fill('Ama');
  await page.getByLabel('Last name').fill('Mensah');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByRole('checkbox', { name: /I agree to AfroTransact/ }).check();
  const before = Date.now();
  const offset = logSize();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Confirm your email' })).toBeVisible();
  await page.getByLabel('Verification code').fill(await latestCode(email, before, offset));
  // No pending request: the new account lands on AfroTransact's home page, which need not be running.
  const home = page.waitForRequest((r) => !r.url().startsWith(IDENTITY));
  await page.getByRole('button', { name: 'Verify and continue' }).click();
  await home;
  return email;
}

test('pages without an app are neutral; the staff console’s branding needs a staff console request', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  await page.goto(`${IDENTITY}/login?logout`);
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in to AfroTransact' })).toBeVisible();
  await expect(page.getByText('You’ve been signed out.')).toBeVisible();
  await expect(page.getByText('One account for all of AfroTransact.')).toBeVisible();
  await expect(page.getByText(/Staff console|@afrotransact\.com account/)).toHaveCount(0);
  await shot(page, '01-neutral-signed-out-signin.png');

  await page.goto(`${IDENTITY}/login/forgot`);
  await expect(page.getByRole('heading', { level: 1, name: 'Forgot your password?' })).toBeVisible();
  await expect(page.getByText(/Staff console/)).toHaveCount(0);
  await shot(page, '02-neutral-forgot-password.png');

  const staff = await newPage(browser);
  await staff.page.goto(authorizeUrl(STAFF));
  await expect(staff.page.getByRole('heading', { level: 1, name: 'Sign in to the staff console' })).toBeVisible();
  await staff.page.getByRole('link', { name: 'Forgot password?' }).click();
  await expect(staff.page.getByText('Two-step verification is still required', { exact: false })).toBeVisible();
  await shot(staff.page, '03-staff-forgot-password.png');
  await staff.context.close();
  await context.close();
});

test('signing out of Seller Center lands on Seller Center’s own sign-in, saying so', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  // What the BFF's signed-out landing starts (prompt=login, notice=signed_out).
  await page.goto(authorizeUrl(SELLER, '&prompt=login&notice=signed_out'));
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in to Seller Center' })).toBeVisible();
  await expect(page.getByText('You’ve been signed out.')).toBeVisible();
  await expect(page.getByText(/Staff console/)).toHaveCount(0);
  await shot(page, '04-seller-center-signin-after-sign-out.png');
  await context.close();
});

test('forgot password from Seller Center: emailed code, new password, back on Seller Center’s sign-in', async ({ browser }) => {
  const setup = await newPage(browser);
  const email = await createAccount(setup.page);
  await setup.context.close();

  const { context, page } = await newPage(browser);
  await page.goto(authorizeUrl(SELLER));
  await page.getByRole('link', { name: 'Forgot password?' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Forgot your password?' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'AfroTransact home' })).toContainText('Seller Center');
  await page.getByLabel('Email').fill(email);
  await shot(page, '05-seller-center-forgot-password.png');
  const before = Date.now();
  const offset = logSize();
  await page.getByRole('button', { name: 'Send code' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Set a new password' })).toBeVisible();
  await expect(page.getByText(/If r•+@example\.com has an AfroTransact account/)).toBeVisible();
  const code = await latestCode(email, before, offset);
  await page.getByLabel('Code').fill(code === '000000' ? '111111' : '000000');
  await page.getByLabel('New password').fill(NEW_PASSWORD);
  await page.getByLabel('Confirm password').fill(NEW_PASSWORD);
  await expect(page.locator('[data-rule="match"]')).toHaveAttribute('data-ok', 'true');
  await page.getByRole('button', { name: 'Set new password' }).click();
  await expect(page.getByText('That code isn’t right or has expired.')).toBeVisible();
  await shot(page, '06-seller-center-reset-code-wrong.png');

  await page.getByLabel('Code').fill(code);
  await page.getByLabel('New password').fill(NEW_PASSWORD);
  await page.getByLabel('Confirm password').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Set new password' }).click();

  await expect(page.getByRole('heading', { level: 1, name: 'Sign in to Seller Center' })).toBeVisible();
  await expect(page.getByText('Your password was changed and you’ve been signed out everywhere.')).toBeVisible();
  await expect(page.getByLabel('Email')).toHaveValue(email);
  await shot(page, '07-seller-center-signin-after-reset.png');

  // Signing in with the new password continues Seller Center's request (its callback; the app need not run here).
  const callback = page.waitForRequest((r) => r.url().startsWith(`${SELLER}/api/bff/v1/auth/oidc/callback?code=`));
  await page.getByLabel('Password', { exact: true }).fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
  await callback;
  await context.close();
});
