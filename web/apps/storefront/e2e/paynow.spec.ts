import { expect, type Page, test } from '@playwright/test';

/* The late pre-order pay-now page (/orders/<number>/pay?token=…) against the mock BFF, without a Stripe key (the
   labelled test card form stands in for the Payment Element). The customer never leaves AfroTransact: no Stripe-hosted
   page, no sign-in. Mock fixtures: AT-2026-PAY001 with token mock-pay-token (valid) and mock-expired-token. */

const PAGE = '/orders/AT-2026-PAY001/pay';

async function fillTestCard(page: Page) {
  await page.getByLabel('Card number').fill('4242 4242 4242 4242');
  await page.getByLabel('Expiration').fill('12 / 34');
  await page.getByLabel('Security code').fill('123');
  await page.getByLabel('ZIP').fill('77002');
}

test('a pay-now link is paid on our own page and is refused afterwards', async ({ page }) => {
  const offsite: string[] = [];
  page.on('request', (r) => {
    if (/stripe\.com\/(c\/pay|pay)|checkout\.stripe\.com|buy\.stripe\.com/.test(r.url())) offsite.push(r.url());
  });
  await page.goto(`${PAGE}?token=mock-pay-token`);
  await expect(page.getByRole('heading', { name: 'Complete your pre-order payment' })).toBeVisible();
  await expect(page.getByText('Order AT-2026-PAY001').first()).toBeVisible();
  await expect(page.getByTestId('paynow-total')).toHaveText('$42.97');
  await fillTestCard(page);
  await page.getByTestId('paynow-pay').click();
  await expect(page.getByRole('heading', { name: 'This pre-order is paid' })).toBeVisible();
  expect(new URL(page.url()).pathname).toBe(PAGE);
  expect(offsite).toEqual([]);

  // Single-use: opening the link again offers nothing to pay.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'This pre-order is paid' })).toBeVisible();
  await expect(page.getByTestId('paynow-pay')).toHaveCount(0);
});

test('an expired, forged or token-less pay-now link is refused', async ({ page }) => {
  await page.goto(`${PAGE}?token=mock-expired-token`);
  await expect(page.getByRole('heading', { name: 'This payment link has expired' })).toBeVisible();
  await expect(page.getByTestId('paynow-pay')).toHaveCount(0);
  await page.goto(`${PAGE}?token=forged`);
  await expect(page.getByRole('heading', { name: 'This payment link is not valid' })).toBeVisible();
  await page.goto(PAGE);
  await expect(page.getByRole('heading', { name: 'This payment link is not valid' })).toBeVisible();
});
