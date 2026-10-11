import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* The shared data table on a staff resource (Inventory › Lots) against the mock BFF: one search over every column kept
   in ?q=, sorting with aria-sort, row selection (select all, shift-range), the selection bar with a bulk action, the
   Columns menu (persisted) and the export menu (Excel and PDF with charts, CSV; selected rows only). */
test.describe('staff console · data table', () => {
  test('lots: search, sort, select, bulk action, columns and export @a11y', async ({ page }, testInfo) => {
    await page.goto('/inventory');
    const table = page.getByRole('table', { name: 'Lots' });
    const bodyRows = table.locator('tbody tr[data-row-id]');
    await expect(bodyRows.first()).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff inventory table' });

    // One search, every column: the status label is not one of the upstream's search fields.
    const search = page.getByRole('searchbox', { name: 'Search lots' });
    await search.fill('quarantined');
    await expect(page).toHaveURL(/[?&]q=quarantined/);
    await expect(bodyRows.first()).toContainText('Quarantined');
    for (const text of await bodyRows.allInnerTexts()) expect(text).toContain('Quarantined');
    await search.fill('');
    await expect(page).not.toHaveURL(/[?&]q=/);

    // Sort by a column header: aria-sort and the URL follow.
    const onHand = table.getByRole('columnheader', { name: /^On hand/ });
    await onHand.getByRole('button').click();
    await expect(onHand).toHaveAttribute('aria-sort', 'ascending');
    await expect(page).toHaveURL(/sort=qtyOnHand%2Casc/);
    await onHand.getByRole('button').click();
    await expect(onHand).toHaveAttribute('aria-sort', 'descending');

    // Selection: one row, then a shift-click range; the checkbox never opens the sheet.
    const boxes = table.locator('tbody input[type="checkbox"]');
    await boxes.nth(0).check();
    await boxes.nth(2).click({ modifiers: ['Shift'] });
    const bar = page.getByRole('region', { name: 'Selection' });
    await expect(bar).toContainText('3 selected');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(table.getByRole('checkbox', { name: 'Select all rows on this page' })).toHaveJSProperty('indeterminate', true);
    await expect(bar.getByRole('button', { name: 'Quarantine' })).toBeVisible();

    // Export menu: the selection only, three formats.
    await page.getByRole('button', { name: 'Export 3 selected' }).click();
    await expect(page.getByRole('menuitem', { name: /Excel \(\.xlsx\) with charts/ })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /PDF with charts/ })).toBeVisible();
    const download = page.waitForEvent('download');
    await page.getByRole('menuitem', { name: /CSV/ }).click();
    expect((await download).suggestedFilename()).toMatch(/^inventory-.*\.csv$/);
    await expect(page.getByText(/Exported 3 rows/)).toBeVisible();

    await bar.getByRole('button', { name: /Clear selection/ }).click();
    await expect(bar).toHaveCount(0);

    // Columns menu: hiding a column is remembered for this table.
    await page.getByRole('button', { name: /Columns/ }).click();
    await page.getByRole('checkbox', { name: 'Lot' }).uncheck();
    await expect(table.getByRole('columnheader', { name: /^Lot/ })).toHaveCount(0);
    await page.reload();
    await expect(bodyRows.first()).toBeVisible();
    await expect(table.getByRole('columnheader', { name: /^Lot/ })).toHaveCount(0);

    // A row click opens the detail sheet.
    await bodyRows.first().locator('td').nth(2).click();
    await expect(page).toHaveURL(/[?&]open=/);
  });
});
