import { mkdirSync } from 'node:fs';
import path from 'node:path';

import { expect, type Page, test } from '@playwright/test';

/*
 * The application wizard end to end against the mock BFF (S289): Start selling → every step → Review & submit →
 * "Submitted — what happens next". The mock BFF has no Stripe, like a local stack without a key: Stripe verification
 * and the plan's card use the labelled local stand-ins. E2E_SHOTS saves each step at 1440 and 390 px.
 */

const SHOTS = process.env.E2E_SHOTS;
const PDF = { name: 'permit.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% test document\n') };

async function shot(page: Page, name: string) {
  if (!SHOTS) return;
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: path.join(SHOTS, `${name}-1440.png`), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(150);
  await page.screenshot({ path: path.join(SHOTS, `${name}-390.png`), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });
}

async function resetMock(page: Page, baseURL: string, applicant: boolean) {
  const res = await page.request.post(`${baseURL}/api/bff/v1/seller/__mock/reset`, { data: { applicant } });
  expect(res.status(), await res.text()).toBe(200);
}

const saveAndContinue = (page: Page) => page.getByRole('button', { name: 'Save and continue' }).click();

async function onStep(page: Page, slug: string, title: string | RegExp) {
  await expect(page).toHaveURL(new RegExp(`/onboarding/${slug}$`));
  await expect(page.getByRole('heading', { level: 2, name: title })).toBeVisible();
}

test.describe('seller center · application wizard', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await resetMock(page, baseURL ?? '', true);
  });
  test.afterAll(async ({ browser }, testInfo) => {
    const page = await browser.newPage();
    await resetMock(page, String(testInfo.project.use.baseURL ?? ''), false);
    await page.close();
  });

  test('a customer goes through every step and submits for review', async ({ page }) => {
    await page.goto('/onboarding?role=seller');
    await onStep(page, 'business', 'Your business');

    // The header has no account menu while the application is in progress; My account redirects to onboarding.
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /Account menu/ })).toHaveCount(0);
    const steps = page.getByRole('navigation', { name: 'Application steps' });
    await expect(steps.getByRole('link')).toHaveCount(1);
    await expect(page.getByText('Using your existing AfroTransact account.')).toBeVisible();

    // 1 · Your business: inline errors and the error summary first, then the next step.
    await saveAndContinue(page);
    await expect(page.getByText(/There are \d problems with this step/)).toBeVisible();
    await expect(page).toHaveURL(/\/onboarding\/business$/);
    await page.getByRole('radio', { name: /Single-member LLC/ }).check();
    await page.getByLabel(/Store or business display name/).fill('Titi Foods');
    await expect(page.getByLabel(/Business email/)).toHaveValue('titilayo@adeskitchen.com');
    await expect(page.getByLabel(/Mobile phone/)).toHaveValue('(713) 249-0142');
    await shot(page, '1-business');
    await saveAndContinue(page);

    // 2 · Business details & TIN: address autocomplete fills city, state and ZIP; the EIN shows its last 4.
    await onStep(page, 'business-details', 'Business details & TIN');
    await page.getByLabel('Legal business name (as on IRS records)').fill('Titi Foods LLC');
    const ein = page.getByLabel('Employer Identification Number (EIN)');
    await ein.fill('123456789');
    await expect(ein).toHaveValue('12-3456789');
    await page.getByLabel('State of formation').selectOption('TX');
    await page.getByLabel('Formation date').fill('2021-03-01');
    const street = page.getByRole('combobox', { name: /Registered business address/ });
    await street.fill('9800 West');
    await page.getByRole('option', { name: /9800 Westheimer Rd/ }).click();
    await expect(page.getByLabel('City')).toHaveValue('Houston');
    await expect(page.getByLabel('State', { exact: true })).toHaveValue('TX');
    await expect(page.getByLabel('ZIP code')).toHaveValue('77042');
    await page.locator('input[type=file]').setInputFiles(PDF);
    await expect(page.getByText('permit.pdf')).toBeVisible();
    await expect(ein).toHaveValue('••-•••6789');
    await shot(page, '2-business-details');
    await saveAndContinue(page);

    // 3 · Owners: the applicant is the first person; ownership total and "same as business address".
    await onStep(page, 'owners', 'Owners');
    await expect(page.getByLabel('Legal first name')).toHaveValue('Titilayo');
    await page.getByLabel('Title').fill('Managing member');
    await page.getByLabel('Ownership %').fill('100');
    await page.getByLabel('Date of birth').fill('1985-01-02');
    await page.getByRole('checkbox', { name: 'Home address is the same as the business address' }).check();
    await expect(page.getByText('Ownership listed: 100%')).toBeVisible();
    await shot(page, '3-owners');
    await saveAndContinue(page);

    // Refresh keeps the step; the browser's back button goes to the previous one.
    await onStep(page, 'stripe-verification', 'Stripe verification');
    await page.reload();
    await onStep(page, 'stripe-verification', 'Stripe verification');
    await page.goBack();
    await onStep(page, 'owners', 'Owners');
    await page.goForward();

    // 4 · Stripe verification: Stripe's form opens with the step (here the labelled local stand-in: no Stripe in the
    // mock BFF), right under one line saying where to continue. Finishing it shows success and moves on by itself.
    await onStep(page, 'stripe-verification', 'Stripe verification');
    await expect(page.getByText('Continue in the secure Stripe form below — it saves as you go.')).toBeVisible();
    await expect(page.getByRole('list', { name: 'What Stripe will ask for' }).getByRole('listitem')).toHaveCount(3);
    const stripeForm = page.getByRole('region', { name: 'Local test verification' });
    await expect(stripeForm).toBeVisible();
    await expect(page.getByRole('button', { name: /Start verification/ })).toHaveCount(0);
    await shot(page, '4-stripe-verification');
    // The page's own Continue is only a quiet fallback; the Stripe form holds the one primary action.
    await expect(page.getByRole('button', { name: 'I’ve finished — continue' })).toBeVisible();
    await stripeForm.getByRole('button', { name: 'Complete test verification' }).click();
    await expect(page.getByText('Verified by Stripe')).toBeVisible();
    // Stripe's onboarding (here the stand-in) collects the payout bank account too.
    await expect(page.getByText(/Payouts go to STRIPE TEST BANK \(local\) ••6789/)).toBeVisible();
    await shot(page, '4-stripe-verified');

    // 5 · Payouts (reached by itself): read-only — the bank Stripe holds; "Change bank account" opens Stripe's
    // account management. Back sits at the top, beside the heading.
    await onStep(page, 'payouts', 'Payouts');
    await expect(page.getByRole('button', { name: 'Back' })).toBeVisible();
    await expect(page.getByText('STRIPE TEST BANK (local) ••6789')).toBeVisible();
    await expect(page.getByText('Held by Stripe')).toBeVisible();
    await expect(page.getByLabel('Routing number')).toHaveCount(0);
    await shot(page, '5-payouts');
    await page.getByRole('button', { name: 'Change bank account' }).click();
    await expect(page.getByRole('region', { name: 'Local test account management' })).toBeVisible();
    await shot(page, '5-payouts-change-bank');
    await page.getByRole('button', { name: 'Done' }).click();
    await saveAndContinue(page);

    // 6 · Plan: a paid plan with the labelled local test card; the fee summary.
    await onStep(page, 'plan', 'Plan');
    await page.getByRole('radio', { name: /Seller Growth/ }).click();
    await expect(page.getByRole('region', { name: 'Fee summary' })).toContainText('Seller Growth');
    await page.getByLabel('Card number').fill('4242 4242 4242 4242');
    await page.getByLabel('Expiry').fill('1230');
    await page.getByLabel('Security code').fill('123');
    await shot(page, '6-plan');
    await saveAndContinue(page);

    // 7 · Catalog, permits & licences.
    await onStep(page, 'catalog-compliance', 'Catalog, permits & licences');
    await page.getByRole('button', { name: 'Groceries' }).click();
    await page.getByLabel('Food permit type').selectOption('COTTAGE_FOOD');
    await page.getByLabel('Permit / registration number').fill('CF-204417');
    await page.getByLabel('Expires').fill('2028-06-30');
    await page.locator('input[type=file]').setInputFiles(PDF);
    await expect(page.getByText('permit.pdf')).toBeVisible();
    await page.getByRole('checkbox', { name: /Seller & Provider Agreement/ }).check();
    await page.getByRole('checkbox', { name: /Prohibited Items/ }).check();
    await page.getByRole('checkbox', { name: /W-9 certification/ }).check();
    await shot(page, '7-catalog-compliance');
    await saveAndContinue(page);

    // 8 · Review & submit.
    await onStep(page, 'review', 'Review & submit');
    await expect(page.getByRole('region', { name: 'Payouts' })).toContainText('STRIPE TEST BANK (local) ••6789');
    await expect(page.getByRole('region', { name: 'Payouts' })).toContainText('Held by Stripe');
    await expect(page.getByRole('region', { name: 'Business details & TIN' })).toContainText('••-•••6789');
    await expect(page.getByRole('link', { name: 'Edit Owners' })).toHaveAttribute('href', '/onboarding/owners');
    await shot(page, '8-review');
    await page.getByRole('button', { name: 'Submit for review' }).click();

    await expect(page).toHaveURL(/\/onboarding$/);
    await expect(page.getByRole('heading', { level: 2, name: 'Submitted — what happens next' })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Application status' })).toContainText('AfroTransact review');
    await shot(page, '9-submitted');
  });

  test('a returning applicant resumes at the first incomplete step', async ({ page, baseURL }) => {
    await page.goto('/onboarding?role=seller');
    await onStep(page, 'business', 'Your business');
    const res = await page.request.put(`${baseURL}/api/bff/v1/seller/onboarding/business`, {
      data: { offering: 'PRODUCTS', entityType: 'SOLE_PROPRIETOR', country: 'US', storeName: 'Titi Foods', contactEmail: 'titi@example.com', phone: '+17132490142' }
    });
    expect(res.status()).toBe(200);
    await page.goto('/onboarding');
    await onStep(page, 'business-details', 'About you');
    await expect(page.getByText('Step 2 of 7').first()).toBeAttached();
    // A later step can't be opened before the earlier ones are complete.
    await page.goto('/onboarding/plan');
    await onStep(page, 'business-details', 'About you');
    await page.goto('/account');
    await onStep(page, 'business-details', 'About you');
  });
});
