import { expect, type Page, test } from '@playwright/test';

/* Back office › Search analytics: top queries, queries with no results, click-through by day, and the synonym that
   fixes a zero-result query. Runs against the in-process mock BFF (daily numbers are generated from the dates, so a
   fixed custom range always shows the same rows); the journey deletes the synonym it adds so it can run again on the
   same server. SHOTS (optional) saves screenshots. */
const shot = async (page: Page, name: string) => {
  if (process.env.SHOTS) await page.screenshot({ path: `${process.env.SHOTS}/${name}.png`, fullPage: true });
};

const RANGE = 'period=custom&from=2026-10-28&to=2026-11-03';

test.describe('staff console · search analytics', () => {
  test('reads the three reports and turns a no-result query into a synonym', async ({ page }) => {
    await page.goto(`/search/analytics?${RANGE}`);
    await expect(page.getByRole('heading', { name: 'Search analytics', level: 1 })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Market' })).toHaveValue('us');

    const top = page.getByRole('table', { name: 'Top queries' });
    await expect(top.getByText('shea butter')).toBeVisible();
    await shot(page, 'mock-search-top-queries');

    await page.getByRole('tab', { name: 'Click-through' }).click();
    await expect(page.getByRole('table', { name: 'Click-through' }).getByRole('row')).toHaveCount(8); // header + 7 days
    await expect(page.getByTestId('search-trend').getByRole('img')).toHaveCount(2);

    await page.getByRole('tab', { name: 'No results' }).click();
    const zero = page.getByRole('table', { name: 'No results' });
    await expect(zero.getByText('agbada kids')).toBeVisible();
    await expect(zero.getByText('shea butter')).toHaveCount(0);

    await page.getByRole('button', { name: 'Add a synonym for agbada kids' }).click();
    const sheet = page.getByRole('dialog', { name: 'Add a synonym' });
    await expect(sheet.getByLabel('What shoppers typed')).toHaveValue('agbada kids');
    await expect(sheet.getByRole('button', { name: 'Save synonym' })).toBeDisabled();
    await sheet.getByLabel('Words it should match').fill('grand boubou kids');
    await shot(page, 'mock-search-add-synonym');
    await sheet.getByRole('button', { name: 'Save synonym' }).click();
    await expect(page.getByText(/Saved\. Search now treats agbada kids = grand boubou kids/)).toBeVisible();

    await page.getByRole('tab', { name: 'Synonym rules' }).click();
    const rule = page.getByTestId('synonym-rule').filter({ hasText: 'grand boubou kids' });
    await expect(rule).toBeVisible();
    await shot(page, 'mock-search-rules');

    // Put it back.
    await rule.getByRole('button', { name: /Delete the synonym/ }).click();
    await page.getByRole('dialog', { name: 'Delete this synonym?' }).getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByTestId('synonym-rule').filter({ hasText: 'grand boubou kids' })).toHaveCount(0);
  });

  test('the page fits a phone without sideways scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`/search/analytics?${RANGE}`);
    await expect(page.getByRole('table', { name: 'Top queries' })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await shot(page, 'mock-search-phone');
  });
});
