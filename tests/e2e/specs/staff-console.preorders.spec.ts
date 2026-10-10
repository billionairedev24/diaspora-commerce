import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Back office › Pre-orders (S274) against the in-process mock BFF (default role: platform super admin, Full access).
   Seed dates are shifted to the mock's "today", so every status is present. Mock state lives in the app server
   process, so the journey tolerates drafts and late adds created by an earlier run. */
test.describe('staff console · pre-orders', () => {
  test('lists windows by status, opens one, exports and checks the wizard gates publishing @a11y', async ({ page }, testInfo) => {
    // List: status tabs with counts, design pills, sidebar entry.
    await page.goto('/preorders');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Pre-orders');
    await expect(page.getByRole('navigation', { name: 'Staff console' }).getByRole('link', { name: 'Pre-orders' })).toHaveAttribute('aria-current', 'page');
    const table = page.getByRole('table', { name: 'Pre-orders' });
    await expect(table.getByRole('link', { name: 'Lubbock Pre-order #7' })).toBeVisible();
    await expect(table.getByRole('row', { name: /Laredo Pre-order #1/ })).toContainText('Area off');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff pre-orders list' });
    await page.getByRole('tab', { name: /Closed/ }).click();
    await expect(page).toHaveURL(/status=closed/);
    await expect(table.getByRole('link', { name: 'Amarillo Pre-order #4' })).toBeVisible();
    await expect(table.getByRole('link', { name: 'Lubbock Pre-order #7' })).toHaveCount(0);

    // Detail: header, KPIs, orders tab (12 per page) and the filtered CSV export.
    await page.getByRole('tab', { name: /All/ }).click();
    await table.getByRole('link', { name: 'Lubbock Pre-order #7' }).click();
    await expect(page.getByRole('heading', { name: 'Lubbock Pre-order #7' })).toBeVisible();
    await expect(page.getByTestId('detail-status')).toHaveText('Open');
    await expect(page.getByTestId('kpi-Pre-orders')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff pre-order overview' });
    await page.getByRole('tab', { name: /^Orders \(\d+\)$/ }).click();
    await expect(page).toHaveURL(/tab=orders/);
    await expect(page.getByTestId('order-row')).toHaveCount(12);
    await page.getByRole('combobox', { name: 'Channel' }).selectOption('INSTAGRAM');
    await expect(page.getByTestId('order-row').first()).toContainText('Instagram');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /^Export \d+ rows$/ }).click();
    expect((await download).suggestedFilename()).toBe('lbk-7-orders-filtered.csv');
    await expect(page.getByText(/Exported \d+ rows · lbk-7-orders-filtered\.csv/)).toBeVisible();

    // Creatives: five slots, the seeded 4:5 feed post is flagged.
    await page.getByRole('tab', { name: 'Creatives' }).click();
    await expect(page.getByTestId('creative-FEED')).toContainText('Ratio off · 1080×1350');
    await expect(page.getByTestId('creative-EMAIL')).toContainText('Missing');

    // Wizard: a new pre-order can't be published without an area and a hero.
    await page.getByRole('button', { name: 'Open a pre-order' }).click();
    await expect(page.getByRole('heading', { name: 'Open a pre-order' })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff pre-order wizard' });
    await page.getByRole('combobox', { name: 'State' }).selectOption('TX');
    await page.getByRole('combobox', { name: 'City' }).selectOption('Houston');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByRole('list', { name: 'Pre-order areas' })).toContainText('Houston, TX');
    await page.getByRole('button', { name: /Review/ }).click();
    await expect(page.getByRole('button', { name: '1 check to fix' })).toBeDisabled();
    await expect(page.getByRole('list', { name: 'Checks' })).toContainText('Storefront hero uploaded (required)');
  });

  test('customer service adds a late order to a closed pre-order', async ({ context, page, baseURL }) => {
    await context.addCookies([{ name: 'at_mock_staff_role', value: 'platform.customer_support', url: baseURL ?? 'http://localhost:3412' }]);
    await page.goto('/preorders/ama-4');
    await expect(page.getByRole('heading', { name: 'Amarillo Pre-order #4' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Open a pre-order' })).toBeDisabled();
    await page.getByRole('button', { name: 'Late add' }).click();
    const dialog = page.getByRole('dialog', { name: 'Late add · Amarillo Pre-order #4' });
    await dialog.getByRole('combobox', { name: 'Matching customers' }).selectOption({ index: 1 });
    await dialog.getByRole('combobox', { name: /^Deliver to/ }).selectOption('a1');
    await dialog.getByRole('button', { name: /Ijebu Garri, 4 lb/ }).click();
    await dialog.getByRole('checkbox', { name: /Phone read back and confirmed/ }).check();
    await dialog.getByRole('button', { name: 'Add $20.98 order' }).click();
    await expect(page.getByText(/Late order AMA-L\d+ added and logged/)).toBeVisible();
    await expect(page).toHaveURL(/tab=orders/);
  });
});
