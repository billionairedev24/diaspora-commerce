import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Inventory hub (S281): an inbound shipment announced with its batch is received at the dock and its lot appears in
   stock; a cycle count with a variance needs a reason and posts an audited adjustment. Default role: platform super
   admin. Mock state lives in the app server process, so every run uses its own shipment name and lot number. */
test.describe('staff console · inventory hub (S281)', () => {
  test('creates an inbound shipment with a batch, receives it and the lot appears in stock', async ({ page }, testInfo) => {
    test.slow();
    const run = Date.now().toString(36).toUpperCase();
    const lotNo = `E2E${run}`.slice(0, 20);
    const year = new Date().getFullYear();

    await page.goto('/inventory?tab=inbound');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.getByRole('button', { name: 'New inbound shipment' }).click();
    await page.getByLabel('Shipment name').fill(`E2E restock ${run}`);
    const line = page.getByRole('group', { name: 'Line 1' });
    await line.getByLabel('Product').selectOption({ index: 1 });
    await line.getByLabel('Units', { exact: true }).fill('48');
    await line.getByLabel('Units per case').fill('24');
    await line.getByLabel(/^Lot \/ batch/).fill(lotNo);
    await line.getByLabel('Made on').fill(`${year}-01-15`);
    await line.getByLabel(/^Expires on/).fill(`${year + 2}-01-15`);
    await page.getByRole('button', { name: 'Create inbound shipment' }).click();

    // The sheet: timeline, the announced batch and printable labels with real barcodes.
    const sheet = page.getByTestId('shipment-sheet');
    await expect(sheet).toBeVisible();
    await expect(sheet.getByRole('table', { name: 'Shipment lines' })).toContainText(lotNo);
    await expect(sheet.getByRole('list', { name: 'Shipment timeline' })).toBeVisible();
    await expect(sheet.getByRole('region', { name: 'Labels' }).locator('svg').first()).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff inventory inbound sheet' });

    await sheet.getByRole('button', { name: 'Mark shipped' }).click();
    await sheet.getByRole('button', { name: 'Receive at the dock' }).click();

    // Receiving: count against the ASN, the batch comes from the announcement.
    const dock = page.getByTestId('receiving-screen');
    await expect(dock).toBeVisible();
    const count = dock.getByTestId('receive-line').first();
    await expect(count.getByLabel('Lot / batch')).toHaveValue(lotNo);
    await count.getByLabel('Good units').fill('48');
    await expect(dock.getByTestId('variance').first()).toContainText(/match/i);
    await dock.getByRole('button', { name: 'Post receipt' }).click();
    await expect(page.getByTestId('created-lots')).toContainText(lotNo);

    // The lot is in stock, sellable, with its expiry.
    await page.goto('/inventory?tab=stock');
    await page.getByRole('searchbox', { name: 'Search lots' }).fill(lotNo);
    const row = page.getByRole('row', { name: new RegExp(lotNo) }).first();
    await expect(row).toBeVisible();
    await expect(row).toContainText(/Sellable/i);
    await row.click();
    await expect(page.getByTestId('lot-sheet')).toContainText(lotNo);
  });

  test('a cycle count variance needs a reason and posts an adjustment', async ({ page }, testInfo) => {
    await page.goto('/inventory?tab=counts');
    await page.getByLabel('SKU (optional)').fill('AT-SHI-200078');
    await page.getByRole('button', { name: 'Start count' }).click();
    const sheet = page.getByTestId('count-sheet');
    await expect(sheet).toBeVisible();
    const lines = sheet.getByTestId('count-line');
    await expect(lines.first()).toBeVisible();
    const first = lines.first();
    // One unit missing on the first lot; every other lot matches the system quantity.
    for (let i = 0; i < (await lines.count()); i += 1) {
      const line = lines.nth(i);
      const system = Number((await line.getByRole('cell').nth(3).textContent()) ?? '0');
      await line.getByLabel(/^Counted /).fill(String(i === 0 ? Math.max(0, system - 1) : system));
    }
    await expect(first.getByTestId('count-variance')).toContainText('-1');
    // Save, then give the reason once the saved count is reloaded (the sheet resets its entries from it).
    const reloaded = () => page.waitForResponse((r) => /\/cycle-counts\/[^/]+$/.test(new URL(r.url()).pathname) && r.request().method() === 'GET');
    await Promise.all([reloaded(), sheet.getByRole('button', { name: 'Save counts' }).click()]);
    await expect(sheet.getByRole('status')).toContainText('1 variances');
    await first.getByLabel(/^Reason for/).selectOption('DAMAGED');
    await expect(first.getByLabel(/^Reason for/)).toHaveValue('DAMAGED');
    await Promise.all([reloaded(), sheet.getByRole('button', { name: 'Save counts' }).click()]);
    const post = sheet.getByRole('button', { name: 'Post adjustments' });
    await expect(post).toBeEnabled();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff inventory cycle count' });
    await post.click();
    await expect(sheet).toContainText(/posted/);
  });
});
