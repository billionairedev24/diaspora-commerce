import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Help center (S278) against the mock BFF: every footer link opens a real page (no 404, no "next phase" placeholder),
   the hub lists the help pages and each page renders the content-service copy as in the Shop design. */

test.describe('storefront · help center', () => {
  test('every footer link opens a real page', async ({ page }) => {
    await page.goto('/');
    const footer = page.getByRole('contentinfo');
    await expect(footer).toBeVisible();
    await expect(footer.getByRole('link', { name: 'Help center' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    let hrefs: string[] = [];
    await expect(async () => {
      hrefs = await footer.locator('a[href^="/"]').evaluateAll((as) => [...new Set(as.map((a) => a.getAttribute('href') ?? ''))]);
    }).toPass({ timeout: 30_000 });
    expect(hrefs).toEqual(expect.arrayContaining(['/help', '/help/shipping', '/help/press', '/help/advertise', '/help/fulfilled-by-afrotransact']));
    for (const href of hrefs.filter((h) => !h.startsWith('/sell') && !h.startsWith('/provide'))) {
      const res = await page.goto(href);
      expect(res?.status(), href).toBeLessThan(400);
      await expect(page.getByRole('heading', { level: 1 }).first(), href).toBeVisible();
      await expect(page.getByText(/Coming in the next phase|Page not found/)).toHaveCount(0);
    }
  });

  test('the hub shows the design copy and the help pages, and a page opens from it', async ({ page }, testInfo) => {
    await page.goto('/help');
    await expect(page.getByRole('heading', { level: 1, name: 'Help center' })).toBeVisible();
    for (const h of ['Track a package', 'Returns', 'Contact us']) await expect(page.getByRole('heading', { level: 2, name: h, exact: true })).toBeVisible();
    const list = page.getByTestId('help-articles');
    await expect(list.getByRole('link')).toHaveCount(5);
    await expect(page.getByRole('navigation', { name: /pagination/i })).toHaveCount(0);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'help center' });

    await page.getByRole('button', { name: 'Selling & advertising' }).click();
    await expect(page).toHaveURL(/\/help\?topic=SELLING/);
    await expect(list.getByRole('link')).toHaveCount(2);

    await list.getByRole('link', { name: /Advertise on AfroTransact/ }).click();
    await expect(page).toHaveURL(/\/help\/advertise$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Advertise on AfroTransact' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'ads@afrotransact.com' })).toHaveAttribute('href', 'mailto:ads@afrotransact.com');
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Help center' }).click();
    await expect(page).toHaveURL(/\/help$/);
  });

  test('shipping & delivery renders the design sections; unknown pages are 404', async ({ page }, testInfo) => {
    await page.goto('/help/shipping');
    await expect(page.getByRole('heading', { level: 1, name: 'Shipping & delivery' })).toBeVisible();
    for (const h of ['Carrier shipping', 'AfroTransact Delivery', 'Pickup']) await expect(page.getByRole('heading', { level: 2, name: h })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'help shipping' });
    const res = await page.goto('/help/no-such-page');
    expect(res?.status()).toBe(404);
  });
});
