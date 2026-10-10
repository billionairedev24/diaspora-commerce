import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* AfroTransact Delivery from the seller's store (S255): delivery pricing in the fee editor and seller pickups in
   dispatch (default role: platform super admin). Mock state lives in the app server process, so the journey works
   from whatever version an earlier run left. */
test.describe('staff console · AfroTransact Delivery pricing (S255)', () => {
  test('edits the radius and customer per-mile rate, previews quotes, reviews the diff and publishes', async ({ page }, testInfo) => {
    const run = Date.now().toString(36);
    await page.goto('/fees');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Fees/);
    await expect(page.getByTestId('current-delivery-pricing')).toContainText('/mi');
    await page.getByRole('button', { name: 'New version' }).click();

    // Omitted sections carry over: the section is off until the editor opts in.
    await expect(page.getByText(/delivery pricing carries over unchanged/)).toBeVisible();
    await page.getByLabel(/Change AfroTransact Delivery pricing in this version/).check();
    const radius = page.getByLabel('Maximum radius (miles)');
    const nextRadius = (await radius.inputValue()) === '30' ? '28' : '30';
    await radius.fill(nextRadius);
    const customer = page.getByRole('group', { name: 'Customer delivery charge' });
    const perMile = customer.getByLabel('Per-mile rate ($)');
    const nextPerMile = (await perMile.inputValue()) === '0.95' ? '0.90' : '0.95';
    await perMile.fill(nextPerMile);

    // Quote preview: miles and packages → customer charge and seller fee.
    const preview = page.getByTestId('delivery-quote-preview');
    await expect(preview).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff fees delivery pricing' });

    await page.getByLabel(/Note explaining the change/).fill(`E2E ${run}: delivery radius ${nextRadius} mi`);
    await page.getByRole('button', { name: 'Review changes' }).click();
    const changes = page.getByRole('table', { name: 'Changes' });
    await expect(changes.getByRole('row', { name: /AfroTransact Delivery radius/ })).toContainText(`${nextRadius} mi`);
    await expect(changes.getByRole('row', { name: /Customer delivery charge/ })).toContainText(`$${nextPerMile}/mi`);
    await expect(page.getByRole('table', { name: 'AfroTransact Delivery examples' })).toBeVisible();
    await page.getByRole('button', { name: 'Publish version' }).click();

    const row = page.getByRole('table', { name: 'Fee schedule versions' }).getByRole('row', { name: new RegExp(`E2E ${run}`) });
    await expect(row).toContainText('Scheduled');
  });

  test('dispatch lists seller pickups with the nearest drivers', async ({ page }, testInfo) => {
    await page.goto('/dispatch?tab=pickups');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    const rows = page.getByTestId('pickup-row');
    await expect(rows.first()).toBeVisible();
    await rows.first().getByRole('button', { name: /^Nearest drivers for/ }).click();
    await expect(page.getByTestId('driver-suggestion').first()).toContainText(/mi/);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff dispatch pickups' });
  });
});
