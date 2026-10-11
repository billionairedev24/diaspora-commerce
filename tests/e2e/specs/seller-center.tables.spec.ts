import { expect, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* The shared data table in Seller Center (Orders) against the mock BFF: the one search over every column (buyer, total
   as text, status label), sort, selection with a bulk action and the export menu (org-scoped seller exports). */
test.describe('seller center · data table', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('orders: search, sort, select and export', async ({ page }) => {
    await page.goto('/orders');
    const table = page.getByRole('table', { name: 'Orders' });
    const rows = page.getByTestId('order-row');
    await expect(rows.first()).toBeVisible();
    const first = (await rows.first().locator('td').nth(2).innerText()).trim().split(/\s+/)[0] ?? '';

    // Search by the buyer's name, a column the orders service doesn't search.
    const search = page.getByRole('searchbox', { name: 'Search orders' });
    await search.fill(first);
    await expect(page).toHaveURL(new RegExp(`[?&]q=${encodeURIComponent(first)}`));
    for (const text of await rows.allInnerTexts()) expect(text.toLowerCase()).toContain(first.toLowerCase());
    await search.fill('zzz-no-such-order');
    await expect(table).toContainText('No orders match.');
    await search.fill('');
    await expect(page).not.toHaveURL(/[?&]q=/);

    // Sort by total, descending on the second click.
    const total = table.getByRole('columnheader', { name: /^Total/ });
    await total.getByRole('button').click();
    await total.getByRole('button').click();
    await expect(total).toHaveAttribute('aria-sort', 'descending');
    await expect(page).toHaveURL(/sort=totalMinor%2Cdesc/);

    // Select the page; the bar offers the next step and the export covers the selection.
    await table.getByRole('checkbox', { name: 'Select all rows on this page' }).check();
    const bar = page.getByRole('region', { name: 'Selection' });
    await expect(bar).toContainText(/\d+ selected/);
    await page.getByRole('button', { name: /^Export \d+ selected/ }).click();
    const download = page.waitForEvent('download');
    await page.getByRole('menuitem', { name: /Excel \(\.xlsx\) with charts/ }).click();
    expect((await download).suggestedFilename()).toMatch(/^orders-.*\.xlsx$/);
    await expect(page.getByText(/Exported \d+ rows · orders-/)).toBeVisible();
  });
});
