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
  // The application resumes at its first incomplete step (/onboarding/<step>).
  await page.waitForURL(`${SELLER}/onboarding/**`, { timeout: 60_000 });
  expect(seen, 'no credential page on the way').toEqual([]);

  await page.goto(`${SELLER}/onboarding/business`);
  await expect(page.getByRole('heading', { level: 2, name: 'Your business' })).toBeVisible();
  await expect(page.getByText('Using your existing AfroTransact account.')).toBeVisible();
  await expect(page.getByText(/from your AfroTransact account \(10200 Richmond Ave, Houston\)/)).toBeVisible();
  await expect(page.getByLabel(/Business email/)).toHaveValue('customer.houston@afrotransact.test');
  await expect(page.getByText('Sign in to Seller Central')).toHaveCount(0);
  await shot(page, 'customer-2-onboarding-prefilled.png');

  // A second click resumes the same application (same reference), still without a credential page.
  const reference = await page.getByRole('heading', { level: 1, name: /Your seller application/ }).textContent();
  await page.goto(STOREFRONT);
  await startSelling(page);
  await page.waitForURL(`${SELLER}/onboarding/**`, { timeout: 60_000 });
  await expect(page.getByRole('heading', { level: 1, name: /Your seller application/ })).toHaveText(reference ?? '');
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

  await page.waitForURL(`${SELLER}/onboarding/business`, { timeout: 60_000 });
  await expect(page.getByLabel(/Business email/)).toHaveValue(email);
  await expect(page.getByText(/We filled in your name, email/)).toBeVisible();
  await expect(page.getByRole('heading', { level: 1, name: /Your seller application/ })).toBeVisible();
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

/* ── S289: the whole wizard, staff review in the staff console, then Seller Center unlocks ── */

const STAFF = (process.env.LIVE_STAFF_URL ?? 'http://localhost:3002').replace(/\/+$/, '');
const PDF = { name: 'articles-of-organization.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% test document\n%%EOF\n') };

/** The texted sign-in code identity dropped in Mailpit for this phone after `after` (local profile only). */
async function smsCode(phone: string, after: number): Promise<string> {
  for (let i = 0; i < 60; i++) {
    const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${phone}"`)}&limit=1`);
    const list = (await res.json()) as { messages?: Array<{ Subject: string; Created: string }> };
    const m = list.messages?.[0];
    const code = m && Date.parse(m.Created) >= after - 1000 ? /\b(\d{6})\b/.exec(m.Subject)?.[1] : undefined;
    if (code) return code;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no texted code for ${phone}`);
}

/** Staff sign in to the console through identity, with the texted second step. */
async function staffSignIn(browser: Browser, email: string, phone: string) {
  const { context, page } = await newPage(browser);
  await page.goto(STAFF);
  await page.getByRole('link', { name: 'Sign in' }).click();
  await page.waitForURL(`${IDENTITY}/**`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login') || u.pathname === '/login/mfa');
  if (page.url().startsWith(`${IDENTITY}/login/mfa`)) {
    const before = Date.now();
    await page.getByRole('radio', { name: 'Text message' }).check();
    await page.getByRole('button', { name: 'Text me a code' }).click();
    await page.getByRole('radio', { name: 'Text message' }).check();
    await page.getByLabel('Code').fill(await smsCode(phone, before));
    await page.getByRole('button', { name: 'Verify' }).click();
  }
  await page.waitForURL(`${STAFF}/**`);
  return { context, page };
}

/** Opens the application in the staff console's Applications module and runs one decision on it. */
async function decide(page: Page, reference: string, action: string, shotName: string) {
  await page.goto(`${STAFF}/apps`);
  await page.getByRole('searchbox').first().fill(reference);
  await page.getByRole('row', { name: new RegExp(escape(reference)) }).first().click();
  const sheet = page.getByRole('dialog');
  await expect(sheet.getByText(reference).first()).toBeVisible();
  await sheet.getByRole('group', { name: 'Actions' }).getByRole('button', { name: action }).click();
  const form = page.getByRole('dialog').last();
  await form.getByLabel('Reason').selectOption({ index: 1 });
  await shot(page, shotName);
  await form.getByRole('button', { name: 'Approve' }).click();
  await expect(form).toBeHidden();
}

const next = (page: Page) => page.getByRole('button', { name: 'Save and continue' }).click();
async function onStep(page: Page, slug: string, title: string | RegExp) {
  await page.waitForURL(`${SELLER}/onboarding/${slug}`);
  await expect(page.getByRole('heading', { level: 2, name: title })).toBeVisible();
}

test('customer.houston completes the whole application; staff approve it in the console; Seller Center unlocks', async ({ browser }) => {
  test.setTimeout(300_000);
  const { context, page } = await newPage(browser);
  await storefrontSignIn(page, 'customer.houston@afrotransact.test');
  await startSelling(page);
  await page.waitForURL((u) => u.origin === SELLER, { timeout: 60_000 });
  test.skip(!page.url().includes('/onboarding'), 'customer.houston is already approved (reseed the stack to run this again)');
  await page.goto(`${SELLER}/onboarding`);
  if (await page.getByRole('heading', { level: 2, name: 'Submitted — what happens next' }).isVisible().catch(() => false)) {
    test.skip(true, 'customer.houston already submitted (reseed the stack to run this again)');
  }

  // The header has no account menu during onboarding, and My account redirects back.
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Account menu/ })).toHaveCount(0);
  await page.goto(`${SELLER}/account`);
  await page.waitForURL(`${SELLER}/onboarding/**`);

  // 1 · Your business
  await page.goto(`${SELLER}/onboarding/business`);
  await onStep(page, 'business', 'Your business');
  await page.getByRole('radio', { name: /^Products\b/ }).check();
  await page.getByRole('radio', { name: /Single-member LLC/ }).check();
  await page.getByLabel(/Store or business display name/).fill('Adaeze’s Pantry');
  const phone = page.getByLabel(/Mobile phone/);
  if (!(await phone.inputValue())) await phone.fill('7135550142');
  await shot(page, 'wizard-1-business.png');
  await next(page);

  // 2 · Business details & TIN (address autocomplete from the geo service when it answers)
  await onStep(page, 'business-details', 'Business details & TIN');
  await page.getByLabel('Legal business name (as on IRS records)').fill('Adaeze’s Pantry LLC');
  await page.getByLabel('Employer Identification Number (EIN)').fill('123456789');
  await page.getByLabel('State of formation').selectOption('TX');
  await page.getByLabel('Formation date').fill('2021-03-01');
  const street = page.getByRole('combobox', { name: /Registered business address/ });
  await street.fill('10200 Richmond');
  const option = page.getByRole('option').first();
  if (await option.isVisible({ timeout: 8000 }).catch(() => false)) await option.click();
  else {
    await street.fill('10200 Richmond Ave');
    await page.getByLabel('City').fill('Houston');
    await page.getByLabel('State', { exact: true }).selectOption('TX');
    await page.getByLabel('ZIP code').fill('77042');
  }
  await page.locator('input[type=file]').setInputFiles(PDF);
  await expect(page.getByText(PDF.name)).toBeVisible({ timeout: 30_000 });
  await shot(page, 'wizard-2-business-details.png');
  await next(page);

  // 3 · Owners
  await onStep(page, 'owners', 'Owners');
  await page.getByLabel('Legal first name').fill('Adaeze');
  await page.getByLabel('Legal last name').fill('Okafor');
  await page.getByLabel('Title').fill('Managing member');
  await page.getByLabel('Email').fill('customer.houston@afrotransact.test');
  await page.getByLabel('Ownership %').fill('100');
  await page.getByLabel('Date of birth').fill('1986-05-14');
  const control = page.getByRole('checkbox', { name: /significant control/ });
  if (!(await control.isChecked())) await control.check();
  await page.getByRole('checkbox', { name: 'Home address is the same as the business address' }).check();
  await shot(page, 'wizard-3-owners.png');
  await next(page);

  // 4 · Stripe verification: the seller service's local stand-in when the stack has no Stripe key.
  await onStep(page, 'stripe-verification', 'Stripe verification');
  if (!(await page.getByText(/Verified · charges enabled/).isVisible())) {
    await page.getByRole('button', { name: /(Start|Continue) verification/ }).click();
    const local = page.getByText(/Local test mode — Stripe isn’t configured on this machine/);
    test.skip(!(await local.isVisible({ timeout: 15_000 }).catch(() => false)), 'the stack uses real Stripe: embedded onboarding needs a person');
    await shot(page, 'wizard-4-stripe.png');
    await page.getByRole('button', { name: 'Complete test verification' }).click();
    await expect(page.getByText(/Verified · charges enabled/)).toBeVisible();
  }
  await next(page);

  // 5 · Payouts (linked to the Stripe account)
  await onStep(page, 'payouts', 'Payouts');
  if (await page.getByLabel('Routing number').isVisible().catch(() => false)) {
    await page.getByLabel('Account holder name').fill('Adaeze’s Pantry LLC');
    await page.getByLabel('Routing number').fill('021000021');
    await page.getByLabel('Account type').selectOption('checking');
    await page.getByLabel('Account number', { exact: true }).fill('000123456789');
    await page.getByLabel('Confirm account number').fill('000123456789');
  }
  await shot(page, 'wizard-5-payouts.png');
  await next(page);

  // 6 · Plan (the free plan: no card)
  await onStep(page, 'plan', 'Plan');
  await page.getByRole('radio', { name: /Seller Essentials/ }).click();
  await expect(page.getByRole('region', { name: 'Fee summary' })).toContainText('Seller Essentials');
  await shot(page, 'wizard-6-plan.png');
  await next(page);

  // 7 · Catalog, permits & licences
  await onStep(page, 'catalog-compliance', 'Catalog, permits & licences');
  const fashion = page.getByRole('button', { name: 'Fashion' });
  if ((await fashion.getAttribute('aria-pressed')) !== 'true') await fashion.click();
  for (const name of [/Seller & Provider Agreement/, /Prohibited Items/, /W-9 certification/]) {
    const box = page.getByRole('checkbox', { name });
    if (!(await box.isChecked())) await box.check();
  }
  await shot(page, 'wizard-7-catalog.png');
  await next(page);

  // 8 · Review & submit
  await onStep(page, 'review', 'Review & submit');
  await expect(page.getByRole('region', { name: 'Business details & TIN' })).toContainText('6789');
  await shot(page, 'wizard-8-review.png');
  const reference = ((await page.getByRole('heading', { level: 1 }).textContent()) ?? '').replace(/^.*·\s*/, '').trim();
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await page.waitForURL(`${SELLER}/onboarding`);
  await expect(page.getByRole('heading', { level: 2, name: 'Submitted — what happens next' })).toBeVisible();
  await shot(page, 'wizard-9-submitted.png');

  // Staff review: step 1 by Trust & Safety, final approval by the super admin (a different person, four-eyes rule).
  const trust = await staffSignIn(browser, 'staff.trust@afrotransact.test', '+17135550103');
  await decide(trust.page, reference, 'Approve (step 1)', 'staff-1-review.png');
  await trust.context.close();
  const admin = await staffSignIn(browser, 'staff.admin@afrotransact.test', '+17135550101');
  await decide(admin.page, reference, 'Final approval', 'staff-2-final-approval.png');
  await admin.context.close();

  // Seller Center unlocks: the back office instead of onboarding (identity learns the organisation from the event).
  await expect(async () => {
    await page.goto(SELLER);
    await expect(page.getByRole('navigation', { name: 'Back office' }).first()).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('link', { name: /Orders/ }).first()).toBeVisible({ timeout: 5000 });
  }).toPass({ timeout: 90_000 });
  await expect(page.getByRole('button', { name: /Account menu/ })).toBeVisible();
  await shot(page, 'wizard-10-seller-center-unlocked.png');
  await context.close();
});
