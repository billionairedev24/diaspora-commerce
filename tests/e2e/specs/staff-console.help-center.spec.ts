import { expect, test } from '@playwright/test';

/* Site content → Help center (S280): the storefront's help pages are edited here, without a deployment. */
test.describe('staff console · help center pages', () => {
  test('lists the help pages and opens one with its sections', async ({ page }) => {
    await page.goto('/site-content');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.getByRole('tab', { name: 'Help center' }).click();
    const row = page.getByRole('row', { name: /Shipping & delivery/ });
    await expect(row).toBeVisible();
    await expect(page.getByRole('row', { name: /Press/ })).toBeVisible();
    await row.getByText('Shipping & delivery').click();
    await expect(page.getByText('Orders & delivery · /help/shipping')).toBeVisible();
  });
});
