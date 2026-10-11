import { type APIRequestContext, expect, type Page, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* AfroTransact Delivery from the seller's store (S255): Ama's Market (Houston) packs Red Palm Oil and an AfroTransact
   driver brings it. The demo shopper's home address (Houston) is 7.4 road miles away; the work address (Bronx) is out
   of range. */

/** Signs the demo shopper in through the mock BFF's login redirect (the identity sign-in page in a real stack). */
async function signIn(request: APIRequestContext) {
  const r = await request.get('/api/bff/v1/auth/login?returnTo=%2F');
  expect(r.ok()).toBe(true);
}

/** Adds Red Palm Oil from the product page (it shows where it is delivered from) and selects the whole cart. */
async function addPalmOil(page: Page) {
  await page.goto('/p/AT00000P23');
  await expect(page.getByText('AfroTransact Delivery from Houston, TX').first()).toBeVisible();
  const buyBox = page.getByRole('complementary', { name: 'Buy box' });
  await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
  await expect(buyBox.getByText(/in cart/)).toBeVisible();
  const sel = await page.request.put('/api/bff/v1/cart/selection', { data: { selected: true } });
  expect(sel.ok()).toBe(true);
}

async function toDelivery(page: Page, address: RegExp) {
  await page.goto('/checkout');
  await page.getByRole('button', { name: 'Change Delivery address' }).click();
  await page.getByRole('radio', { name: address }).check();
  await page.getByRole('button', { name: 'Deliver to this address' }).click();
}

test.describe('storefront · AfroTransact Delivery from the seller’s store (S255)', () => {
  test('checkout quotes the delivery per package, places the order and the order shows it', async ({ page }, testInfo) => {
    await signIn(page.request);
    await addPalmOil(page);
    await page.goto('/cart');
    await expect(page.getByText(/AfroTransact Delivery from Houston, TX/).first()).toBeVisible();

    await toDelivery(page, /Adaeze Nwosu 9800 Westheimer/);
    const quote = page.getByTestId('local-delivery-quote');
    await expect(quote).toContainText('7.4 mi from Houston, TX · about 18 min drive');
    await expect(quote).toContainText('Delivery charge $11.28');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront checkout local delivery' });

    await page.getByRole('button', { name: 'Place your order' }).click();
    await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
    const placed = new URL(page.url()).searchParams.get('placed') ?? '';
    await page.goto(`/orders/${encodeURIComponent(placed)}`);
    await expect(page.getByTestId('order-local-delivery').first()).toContainText('AfroTransact Delivery from the seller’s store');
    await expect(page.getByTestId('order-local-delivery').first()).toContainText('$11.28');
  });

  test('out of range: explains why, blocks the order, and offers to remove the package or change address', async ({ page }, testInfo) => {
    await signIn(page.request);
    await addPalmOil(page);

    await toDelivery(page, /Adaeze Nwosu \(work\)/);
    const problem = page.getByTestId('local-delivery-problem');
    await expect(problem).toContainText('Not deliverable to this address');
    await expect(problem).toContainText(/too far for AfroTransact Delivery \(up to \d+ miles\)/);
    await expect(page.getByRole('button', { name: 'Place your order' })).toBeDisabled();
    await expect(page.getByTestId('local-delivery-blocked')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront checkout local delivery out of range' });

    // Change address opens the address list.
    await problem.getByRole('button', { name: 'Change address' }).click();
    await expect(page.getByRole('button', { name: 'Deliver to this address' })).toBeVisible();
    await page.getByRole('button', { name: 'Deliver to this address' }).click();

    // Remove from this order: the package leaves checkout, the items stay in the cart.
    await problem.getByRole('button', { name: /Remove Ama's Market’s items from this order/ }).click();
    await expect(page.getByTestId('local-delivery-problem')).toHaveCount(0);
    await page.goto('/cart');
    await expect(page.getByText('Red Palm Oil').first()).toBeVisible();
  });
});
