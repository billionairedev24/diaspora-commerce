import { expect, test } from '@playwright/test';

/* The seller storefront on the mock stack: its own header and footer, products a shopper can buy straight from the page,
   and a proper 404 for a store that is not published. */

test.describe('seller storefront /b/<slug>', () => {
  test('is a branded mini-store: its own header and footer, never the marketplace chrome', async ({ page }) => {
    await page.goto('/b/laserfeet');
    await expect(page.getByTestId('storefront-header')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Laserfeet' })).toBeVisible();
    await expect(page.getByTestId('storefront-footer').getByRole('link', { name: 'Sold on AfroTransact' })).toHaveAttribute('href', '/');
    // the marketplace header has the deliver-to picker, the all-departments menu and the big footer
    await expect(page.getByText(/Deliver to/i)).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: /departments/i })).toHaveCount(0);
    await expect(page.locator('footer')).toHaveCount(1);
    await expect(page.getByTestId('store-product').first()).toBeVisible();
  });

  test('search, filter, sort, quick view, add to cart and buy now go to the marketplace cart and checkout', async ({ page }) => {
    await page.goto('/b/laserfeet');
    const grid = page.locator('#sf-products');
    await page.getByRole('searchbox').fill('bamboo');
    await expect(grid.getByTestId('store-product')).toHaveCount(1);
    await page.getByRole('searchbox').fill('');
    await page.getByRole('navigation', { name: 'Store categories' }).getByRole('button', { name: 'Spa and tools' }).click();
    await expect(grid.getByRole('heading', { name: 'Spa and tools' })).toBeVisible();
    await grid.getByLabel('Sort by').selectOption('price_asc');
    const first = grid.getByTestId('store-product').first();
    await expect(first).toContainText('Toe Separators');
    // sold-out and photo-less products never appear
    await expect(page.getByText('Limited Foot Scrub')).toHaveCount(0);
    await expect(page.getByText('Pumice Stone')).toHaveCount(0);
    await first.getByRole('button', { name: /^Quick view/ }).click({ force: true });
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Toe Separators' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Add to cart' }).click();
    await expect(dialog.getByRole('button', { name: /1 in cart/ })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('link', { name: /Cart, 1 items/ })).toBeVisible();
    // buy now: add and go straight to checkout
    await grid.getByTestId('store-product').nth(1).getByRole('button', { name: /^Buy now/ }).click();
    await expect(page).toHaveURL(/\/(checkout|signin|api\/bff\/v1\/auth)/);
  });

  test('a store that is not published or does not exist is a 404 page, not a broken storefront', async ({ page }) => {
    const res = await page.goto('/b/not-a-store');
    expect(res?.status()).toBe(404);
    await expect(page.getByTestId('store-not-found')).toBeVisible();
    await expect(page.getByTestId('storefront-header')).toHaveCount(0);
  });

  test('old and mixed-case addresses 301 to the canonical page', async ({ request }) => {
    const r = await request.get('/stores/LaserFeet', { maxRedirects: 0 });
    expect(r.status()).toBe(301);
    expect(new URL(r.headers()['location']!, 'http://x.test').pathname).toBe('/b/laserfeet');
  });

  test('non-prod hosts are noindex and the page has no horizontal scroll on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/b/laserfeet');
    await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /noindex/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});
