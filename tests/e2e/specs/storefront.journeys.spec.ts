import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

test.describe('storefront · guest shopper', () => {
  test('browse → search → product → add to cart', async ({ page }) => {
    // Browse: the home page with header, departments and footer.
    await page.goto('/');
    const header = page.getByRole('banner');
    await expect(header.getByRole('link', { name: 'AfroTransact home' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Departments' })).toBeVisible();
    await expect(page.getByRole('contentinfo')).toBeVisible();

    // Search from the header.
    // In dev mode hydration can land after the first keystrokes and reset the input, so retry until the query sticks.
    const box = header.getByRole('combobox', { name: 'Search' });
    await expect(async () => {
      await box.fill('jollof');
      await expect(box).toHaveValue('jollof', { timeout: 1_000 });
      await header.getByRole('button', { name: 'Search', exact: true }).click();
      await expect(page).toHaveURL(/\/s\?(.*&)?q=jollof/, { timeout: 5_000 });
    }).toPass({ timeout: 45_000 });
    const grid = page.getByTestId('results-grid');
    await expect(grid).toBeVisible();
    const first = grid.getByRole('article').first();
    await expect(first).toContainText(/jollof/i);

    // Product page from the first result.
    await first.getByRole('link').first().click();
    await expect(page).toHaveURL(/\/p\/[A-Z0-9]+/);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/jollof/i);

    // Add to cart from the buy box; the header count and the cart page reflect it.
    const buyBox = page.getByRole('complementary', { name: 'Buy box' });
    await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
    await expect(buyBox.getByText('1 in cart')).toBeVisible();
    await expect(page.getByTestId('cart-count')).toHaveText('1');

    await page.goto('/cart');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('main')).toContainText(/jollof/i);
  });

  test('sign-in renders without the storefront header, department bar or footer', async ({ page }) => {
    await page.goto('/signin');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('main').getByLabel('Email')).toBeVisible();
    await expect(page.getByRole('main').getByRole('link', { name: 'AfroTransact home' })).toBeVisible();
    await expect(page.getByRole('banner')).toHaveCount(0);
    await expect(page.getByRole('contentinfo')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Departments' })).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: 'Search' })).toHaveCount(0);
  });
});

test.describe('storefront · a11y smoke @a11y', () => {
  for (const [name, path] of [
    ['home', '/'],
    ['search results', '/s?q=jollof'],
    ['product page', '/p/AT00000P18'],
    ['cart', '/cart'],
    ['sign in', '/signin']
  ] as const) {
    test(`${name} has no serious axe violations`, async ({ page }, testInfo) => {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await expectNoSeriousA11yViolations(page, testInfo, { page: name });
    });
  }
});
