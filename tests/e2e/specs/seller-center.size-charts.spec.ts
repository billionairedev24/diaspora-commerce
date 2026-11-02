import { expect, type Locator, type Page, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* Seller Center size charts against the mock BFF: writing a chart (the category suggests, the seller writes the numbers), seeing the table the shopper reads,
   duplicating and retiring it, uploading charts from a file, choosing a chart on the product form with a size of the seller's own, the quality panel grouped by
   section, and the floating buttons (Ask Lola, WhatsApp) staying clear of the form's actions and panel. The mock is process-wide state, so each journey uses
   names unique to the run. */

const RUN = Date.now().toString(36);

/** Whether two boxes share any area. */
async function overlaps(a: Locator, b: Locator): Promise<boolean> {
  const [x, y] = await Promise.all([a.boundingBox(), b.boundingBox()]);
  if (!x || !y) return false;
  return x.x < y.x + y.width && y.x < x.x + x.width && x.y < y.y + y.height && y.y < x.y + x.height;
}

async function chooseKaftanCategory(page: Page) {
  await page.getByRole('combobox', { name: 'Category' }).fill('kaftans');
  await page.getByRole('option', { name: /Kaftans & shirts/ }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Size guide' })).toBeVisible();
}

test.describe('seller center · size charts', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('write a chart from the category’s suggestions, see the shopper’s table, duplicate it and retire it', async ({ page }) => {
    const name = `E2E kaftans ${RUN}`;
    await page.goto('/listings/size-charts');
    await expect(page.getByRole('heading', { level: 1, name: 'My size charts' })).toBeVisible();
    await page.getByRole('button', { name: 'New size chart' }).click();
    const editor = page.getByTestId('size-chart-editor');
    await editor.getByLabel('Made for').selectOption({ label: 'Kaftans & shirts' });
    await expect(editor.getByLabel('Measurement 1')).toHaveValue('Chest');
    for (const size of ['M', 'L', 'XL', '2XL', '3XL']) await editor.getByRole('button', { name: `Remove size ${size}` }).click();

    // An unfinished chart says which cells are open and saves nothing.
    await editor.getByLabel('Chart name').fill(name);
    await editor.getByRole('button', { name: 'Create chart' }).click();
    await expect(editor.getByText(/^Enter the chest of size S/)).toBeVisible();

    await editor.getByLabel('Chest of size S').fill('36-38');
    await editor.getByLabel('Waist of size S').fill('30-32');
    await editor.getByLabel('Sleeve length of size S').fill('25');
    const preview = editor.getByTestId('chart-preview');
    await expect(preview.getByRole('row', { name: /^S 36–38 30–32 25/ })).toBeVisible();
    await editor.getByRole('button', { name: 'Create chart' }).click();
    await expect(page.getByText(`Size chart “${name}” created`)).toBeVisible();
    await expect(page.getByTestId('size-chart-editor')).toBeHidden();

    await page.getByRole('searchbox', { name: 'Search my size charts' }).fill(name);
    const row = page.getByRole('row', { name: new RegExp(name) });
    await expect(row).toBeVisible();
    await page.getByRole('button', { name: `Duplicate ${name}` }).click();
    await expect(page.getByText(`Created “${name} (copy)”`)).toBeVisible();

    await page.getByRole('button', { name: `Retire ${name}`, exact: true }).click();
    await expect(page.getByRole('heading', { name: `Retire “${name}”?` })).toBeVisible();
    await page.getByRole('button', { name: 'Retire chart' }).click();
    await expect(page.getByText(`“${name}” retired`)).toBeVisible();
  });

  test('closing the editor with unsaved changes asks first', async ({ page }) => {
    await page.goto('/listings/size-charts');
    await page.getByRole('button', { name: 'Edit Leather sandals' }).click();
    const editor = page.getByTestId('size-chart-editor');
    await editor.getByLabel('Chart name').fill('Sandals, renamed');
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Discard your changes?' })).toBeVisible();
    await page.getByRole('button', { name: 'Keep editing' }).click();
    await expect(editor.getByLabel('Chart name')).toHaveValue('Sandals, renamed');
    await page.getByRole('button', { name: 'Close', exact: true }).click();
    await page.getByRole('button', { name: 'Discard changes' }).click();
    await expect(editor).toBeHidden();
  });

  test('upload charts from a file: check it, read the rows, import what can be saved', async ({ page }) => {
    const good = `Import dresses ${RUN}`;
    const bad = `Import broken ${RUN}`;
    const file = [
      'Size chart name *,Unit,Size *,Bust *,Waist *,Size fit,How to measure',
      `${good},cm,S,84-87,66-69,Runs small,`,
      `${good},,M,88-92,70-74,,`,
      `${bad},cm,S,abc,66-69,,`
    ].join('\n');
    await page.goto('/listings/size-charts/import');
    await expect(page.getByRole('heading', { level: 1, name: 'Import size charts' })).toBeVisible();
    await page.getByTestId('chart-file').setInputFiles({ name: 'charts.csv', mimeType: 'text/csv', buffer: Buffer.from(file) });
    await page.getByRole('button', { name: 'Check the file' }).click();
    const report = page.getByTestId('chart-report');
    await expect(report.getByRole('heading', { name: 'What charts.csv would do' })).toBeVisible();
    await expect(report.getByTestId('sum-created')).toContainText('1');
    await expect(report.getByTestId('sum-failed')).toContainText('1');
    await expect(report.getByTestId('chart-row')).toHaveCount(1);
    await expect(report.getByTestId('chart-row')).toContainText('Bust: "abc" is not a number or a range like 86-90');

    await report.getByRole('button', { name: 'Import 1 chart' }).click();
    await expect(page.getByText('Imported 1 size chart')).toBeVisible();
    await report.getByRole('link', { name: 'View my size charts' }).click();
    await page.getByRole('searchbox', { name: 'Search my size charts' }).fill(good);
    await expect(page.getByRole('row', { name: new RegExp(good) })).toBeVisible();
  });
});

test.describe('seller center · sized product form', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('a chart is asked for, its table shows in cm or in, and a size of the seller’s own needs a row', async ({ page }) => {
    await page.goto('/listings/new');
    await chooseKaftanCategory(page);
    await expect(page.getByRole('form', { name: 'Product' }).getByRole('heading', { level: 2 })).toHaveText(
      ['Basics', 'Photos', 'Price & stock', 'Details', 'Variants', 'Size guide', 'Shipping & fulfilment']
    );
    const panel = page.getByRole('complementary', { name: 'Listing readiness' });
    // The quality panel groups what is left by section, in page order.
    const guide = panel.getByTestId('quality-sizeGuide');
    await expect(guide).toContainText('Choose a size chart');
    await guide.getByRole('link', { name: 'Go to Size guide' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Size guide' })).toBeFocused();

    await page.getByLabel('Size chart').selectOption({ label: 'Kaftans and shirts' });
    const table = page.getByRole('table', { name: 'Size chart Kaftans and shirts' });
    await expect(table.getByRole('row', { name: /^S 35–37/ })).toBeVisible();
    await page.getByRole('button', { name: 'cm', exact: true }).click();
    await expect(table.getByRole('row', { name: /^S 88.9–94/ })).toBeVisible();

    // A size the category does not list is welcome, but the chart must have a row for it.
    await page.getByRole('button', { name: 'Yes, several' }).click();
    await page.getByRole('button', { name: 'Size × Color' }).click();
    const own = page.getByTestId('own-Size');
    await own.getByLabel('Add your own size').fill('4XL, bad$name');
    await own.getByRole('button', { name: 'Add' }).click();
    await expect(own.getByRole('button', { name: 'Remove 4XL' })).toBeVisible();
    await expect(own.getByRole('alert')).toContainText('“bad$name” has characters that cannot be used');
    await page.getByRole('group', { name: 'Color' }).getByRole('button', { name: 'Gold' }).click();
    await expect(panel.getByTestId('quality-sizeGuide')).toContainText('has no row for 4XL');
  });

  test('Ask Lola, the quality panel and the form’s actions do not cover one another', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 720 });
    await page.goto('/listings/new');
    await chooseKaftanCategory(page);
    const lola = page.getByRole('button', { name: /Ask Lola/ });
    const submit = page.getByTestId('pf-primary');
    const panel = page.getByRole('complementary', { name: 'Listing readiness' });
    await expect(lola).toBeVisible();
    expect(await overlaps(lola, submit)).toBe(false);
    expect(await overlaps(lola, panel)).toBe(false);

    // At the end of the page the actions bar sits at the bottom edge, with Ask Lola above it.
    await page.keyboard.press('End');
    expect(await overlaps(lola, submit)).toBe(false);
    const bar = page.getByRole('region', { name: 'Listing actions' });
    const [barBox, viewport] = await Promise.all([bar.boundingBox(), page.viewportSize()]);
    expect(Math.round((barBox?.y ?? 0) + (barBox?.height ?? 0))).toBeGreaterThanOrEqual((viewport?.height ?? 0) - 1);

    await page.setViewportSize({ width: 390, height: 800 });
    await page.keyboard.press('End');
    expect(await overlaps(lola, submit)).toBe(false);
    // The WhatsApp button is there once the support phone is set; wherever it is, it covers neither the actions nor Ask Lola.
    for (const chat of await page.locator('a[href^="https://wa.me/"]').all()) {
      expect(await overlaps(chat, submit)).toBe(false);
      expect(await overlaps(chat, lola)).toBe(false);
    }
  });
});
