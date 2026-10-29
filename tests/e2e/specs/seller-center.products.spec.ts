import { readFileSync } from 'node:fs';

import { expect, type Page, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* Seller Center products (S307, S308) against the mock BFF: adding a product on the one-step form (wide cards of sections, a section navigator, the
   searchable category and brand comboboxes, type-driven food fields, image upload with alt text, readiness panel, S309), and a bulk upload whose file has errors, is fixed and
   imported. The mock is process-wide state, so each journey uses its own SKUs. */

/** A white square PNG drawn in the browser (the main image must be at least 1000 × 1000 px). */
async function productImage(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const c = document.createElement('canvas');
    c.width = 1200;
    c.height = 1200;
    const g = c.getContext('2d');
    if (g) {
      g.fillStyle = '#ffffff';
      g.fillRect(0, 0, 1200, 1200);
      g.fillStyle = '#b5651d';
      g.beginPath();
      g.arc(600, 600, 380, 0, Math.PI * 2);
      g.fill();
    }
    return c.toDataURL('image/png');
  });
  return Buffer.from(dataUrl.split(',')[1] ?? '', 'base64');
}

const csv = (rows: string[][]) => rows.map((r) => r.map((c) => (/[",\n]/.test(c) ? `"${c.replace(/"/g, '""')}"` : c)).join(',')).join('\n');
const HEADER = ['SKU *', 'Category *', 'Title *', 'Description', 'Image URLs *', 'Price (USD) *', 'Quantity *', 'Barcode (GTIN/UPC/EAN)', 'No barcode'];
const SPICES = 'Groceries › Pantry › Spices & seasonings';
/** SKUs unique to this run: the mock keeps its listings between runs against the same server. */
const RUN = Date.now().toString(36).toUpperCase();

test.describe('seller center · products', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('add a product in one step', async ({ page }) => {
    await page.goto('/listings/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Add a product' })).toBeVisible();
    const panel = page.getByRole('complementary', { name: 'Listing readiness' });
    await expect(panel.getByTestId('readiness')).toContainText('things missing');

    // Wide cards of sections; the category is a searchable combobox over the full path, chosen from the keyboard.
    await expect(page.getByRole('form', { name: 'Product' }).getByRole('heading', { level: 2 })).toHaveText(['Basics', 'Photos']);
    const category = page.getByRole('combobox', { name: 'Category' });
    await category.fill('pantry spices');
    // The full path is searched: "pantry" only appears in the path of the spices type. The last row requests a new category.
    await expect(page.getByRole('listbox').getByRole('option')).toHaveText([/Spices & seasonings/, /Request “pantry spices” as a new category/]);
    await page.keyboard.press('Enter');
    await expect(category).toHaveValue(SPICES);
    await expect(page.getByRole('heading', { name: 'Food information' })).toBeVisible();
    await expect(page.getByRole('form', { name: 'Product' }).getByRole('heading', { level: 2 })).toHaveText(
      ['Basics', 'Photos', 'Price & stock', 'Details', 'Variants', 'Shipping & fulfilment', 'Compliance']
    );

    await page.getByLabel('Product title', { exact: true }).fill('Yaji Suya Spice, Hand-Ground, 4 oz');
    await page.getByRole('combobox', { name: 'Brand' }).fill('Mama Kano');
    await page.getByRole('option', { name: 'Add “Mama Kano” as a new brand' }).click();
    await expect(page.getByRole('combobox', { name: 'Brand' })).toHaveValue('Mama Kano');
    await page.getByLabel('Description', { exact: true }).fill('Hand-ground in Kano from groundnut, chilli and ginger.');
    await page.getByTestId('image-input').setInputFiles({ name: 'yaji.png', mimeType: 'image/png', buffer: await productImage(page) });
    await expect(page.getByText('1200×1200')).toBeVisible();
    await page.getByLabel('Description of image 1').fill('Jar of yaji suya spice on a white background');
    await page.getByLabel('Ingredients', { exact: true }).fill('Groundnut, chilli, ginger, salt');
    await page.getByLabel('Allergens (FALCPA)', { exact: true }).selectOption('Contains peanuts');
    await page.getByLabel('Shelf life', { exact: true }).fill('12');
    await page.getByLabel('Unit of Shelf life').selectOption('months');
    await page.getByLabel('Storage', { exact: true }).selectOption('Room temperature');
    await page.getByLabel('Country of origin', { exact: true }).fill('Nigeria');
    await page.getByLabel('Price (USD)', { exact: true }).fill('7.99');
    await page.getByLabel('Quantity', { exact: true }).fill('48');
    await page.getByLabel('SKU', { exact: true }).fill(`E2E-${RUN}-YAJI-4`);
    await page.getByLabel(/I don’t have a barcode/).check();
    // Fulfilment is a segmented control, handling time a dropdown.
    await expect(page.getByRole('group', { name: 'Who ships it?' }).getByRole('button', { name: 'I ship it' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByLabel('Handling time (business days)').selectOption('3');

    await expect(panel.getByTestId('readiness')).toHaveText('Ready to publish');
    await expect(page.getByTestId('save-state')).toContainText('Draft saved');
    // The toast lasts a few seconds and the listings page compiles on first visit in dev: start watching before the click.
    const toast = expect(page.getByText('Submitted for review')).toBeVisible();
    await page.getByTestId('pf-primary').click();
    await toast;
    await expect(page).toHaveURL(/\/listings$/);
    await expect(page.getByRole('table', { name: 'Listings' })).toContainText('Yaji Suya Spice, Hand-Ground, 4 oz');
  });

  test('a book gets book fields and no Brand, and the quality panel jumps to a missing field', async ({ page }) => {
    await page.goto('/listings/new');
    const category = page.getByRole('combobox', { name: 'Category' });
    await category.fill('cookbooks');
    await page.getByRole('option', { name: /African & Caribbean cooking/ }).click();
    await expect(page.getByLabel('Author', { exact: true })).toBeVisible();
    const form = page.getByRole('form', { name: 'Product' });
    for (const label of ['Publisher', 'Language', 'Pages', 'ISBN-13']) await expect(form.getByLabel(label, { exact: true })).toBeVisible();
    await expect(page.getByRole('combobox', { name: /Brand/ })).toHaveCount(0);
    await expect(page.getByText('Brand', { exact: false })).toHaveCount(0);

    // The navigator lists the sections with what is left in each; the panel jumps to a missing field and focuses it.
    const nav = page.getByRole('navigation', { name: 'Sections' });
    await expect(nav.getByRole('link', { name: /Details/ })).toContainText('4 to do');
    const panel = page.getByRole('complementary', { name: 'Listing readiness' });
    await panel.getByTestId('missing-list').getByRole('link', { name: /Publisher/ }).click();
    await expect(page.getByLabel('Publisher', { exact: true })).toBeFocused();
    await expect(page.getByTestId('listing-preview')).toBeVisible();
  });

  test('bulk upload with errors, fix, import', async ({ page }) => {
    await page.goto('/listings/bulk');
    await expect(page.getByRole('heading', { level: 1, name: 'Bulk upload' })).toBeVisible();
    await page.getByRole('button', { name: 'CSV (.csv)' }).click();
    const [template] = await Promise.all([page.waitForEvent('download'), page.getByTestId('download-template').click()]);
    expect(template.suggestedFilename()).toMatch(/\.csv$/);
    expect(readFileSync((await template.path()) ?? '', 'utf8')).toContain('SKU *');

    const broken = csv([
      HEADER,
      [`E2E-${RUN}-JOLLOF-4`, SPICES, 'Jollof Seasoning, Smoky, 4 oz', 'House blend', 'https://example.com/jollof.jpg', '6.99', '40', '', 'Yes'],
      [`E2E-${RUN}-EGUSI-1`, SPICES, 'Egusi, Ground Melon Seed, 1 lb', 'Ground egusi', 'https://example.com/egusi.jpg', '12.5O', '25', '', 'Yes'],
      [`E2E-${RUN}-JOLLOF-4`, SPICES, 'Jollof Seasoning, Mild, 4 oz', 'Mild blend', 'http://example.com/mild.jpg', '5.99', '10', '', 'Yes']
    ]);
    await page.getByTestId('bulk-file').setInputFiles({ name: 'pantry.csv', mimeType: 'text/csv', buffer: Buffer.from(broken) });
    await page.getByRole('button', { name: 'Upload and check' }).click();
    await expect(page).toHaveURL(/\/listings\/bulk\/imp-\d+$/);
    await expect(page.getByText('3 rows have errors.')).toBeVisible();
    await expect(page.locator('[data-cell-error="true"]').filter({ hasText: '12.5O' })).toContainText('not a price');
    await page.getByLabel('Only rows with errors').check();
    await expect(page.getByTestId('import-row')).toHaveCount(3);

    const [report] = await Promise.all([page.waitForEvent('download'), (async () => {
      await page.getByRole('button', { name: /Error report/ }).click();
      await page.getByRole('menuitem', { name: 'CSV (.csv)' }).click();
    })()]);
    const reportText = readFileSync((await report.path()) ?? '', 'utf8');
    expect(reportText).toContain('Errors');
    expect(reportText).toContain('appears more than once');

    // Fix the file and upload it again: every row is ready, then import.
    await page.getByRole('link', { name: 'Upload a fixed file' }).click();
    const fixed = csv([
      HEADER,
      [`E2E-${RUN}-JOLLOF-4`, SPICES, 'Jollof Seasoning, Smoky, 4 oz', 'House blend', 'https://example.com/jollof.jpg', '6.99', '40', '', 'Yes'],
      [`E2E-${RUN}-EGUSI-1`, SPICES, 'Egusi, Ground Melon Seed, 1 lb', 'Ground egusi', 'https://example.com/egusi.jpg', '12.50', '25', '', 'Yes'],
      [`E2E-${RUN}-JOLLOF-MILD-4`, SPICES, 'Jollof Seasoning, Mild, 4 oz', 'Mild blend', 'https://example.com/mild.jpg', '5.99', '10', '', 'Yes']
    ]);
    await page.getByTestId('bulk-file').setInputFiles({ name: 'pantry-fixed.csv', mimeType: 'text/csv', buffer: Buffer.from(fixed) });
    await page.getByRole('button', { name: 'Upload and check' }).click();
    await expect(page.getByText('Every row is ready: 3 products.')).toBeVisible();
    await page.getByTestId('import-all').click();
    await expect(page.getByText(/Import finished: 3 created/).first()).toBeVisible({ timeout: 30_000 });

    await page.getByRole('link', { name: 'Upload history' }).click();
    await expect(page.getByRole('table', { name: 'Upload history' })).toContainText('pantry-fixed.csv');
    await page.goto('/listings');
    await expect(page.getByRole('table', { name: 'Listings' })).toContainText('Egusi, Ground Melon Seed, 1 lb');
  });
});
