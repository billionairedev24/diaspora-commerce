import { expect, test } from '@playwright/test';

// Adire Wrap Dress (mock catalogue): 4 sizes x 2 colours, the seller's dress size chart.
const DRESS = '/p/AT000000P24';

test('size and colour chips switch the variant and the size guide opens with the chart', async ({ page }) => {
  await page.goto(DRESS);
  const size = page.getByRole('group', { name: 'Size' });
  const colour = page.getByRole('group', { name: 'Color' });
  await expect(size.getByRole('listitem')).toHaveCount(4);
  await expect(colour.getByRole('listitem')).toHaveCount(2);
  await expect(size.locator('[aria-current="true"]')).toHaveText('S');

  // Another size is its own product page.
  await size.getByRole('link', { name: 'M' }).click();
  await expect(page).toHaveURL(/\/p\/AT/);
  await expect(page.getByRole('group', { name: 'Size' }).locator('[aria-current="true"]')).toHaveText('M');
  await expect(page.getByRole('group', { name: 'Size' }).getByRole('listitem')).toHaveCount(4);
});

test('the size guide shows the chart in cm or inches, marks the shopper size and selects another size', async ({ page }) => {
  await page.goto(DRESS);
  await page.getByRole('button', { name: 'Size guide' }).click();
  const sheet = page.getByRole('dialog', { name: 'Size guide' });
  await expect(sheet.getByRole('table')).toBeVisible();
  await expect(sheet.getByRole('row', { name: /your size/ })).toHaveCount(1);

  const firstCell = sheet.getByRole('cell').first();
  const cm = await firstCell.textContent();
  await sheet.getByRole('button', { name: 'Inches' }).click();
  await expect(sheet.getByRole('button', { name: 'Inches' })).toHaveAttribute('aria-pressed', 'true');
  expect(await firstCell.textContent()).not.toBe(cm);

  await sheet.getByRole('button', { name: 'Select size M' }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByRole('group', { name: 'Size' }).locator('[aria-current="true"]')).toHaveText('M');
});

test('the size guide fits a phone without horizontal page scroll', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 740 });
  await page.goto(DRESS);
  await page.getByRole('button', { name: 'Size guide' }).click();
  await expect(page.getByRole('dialog', { name: 'Size guide' }).getByRole('table')).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test('products without sizes have no size guide', async ({ page }) => {
  await page.goto('/p/AT000000P2');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Size guide' })).toHaveCount(0);
});
