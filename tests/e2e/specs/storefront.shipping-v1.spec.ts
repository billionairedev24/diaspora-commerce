import { type APIRequestContext, expect, test } from '@playwright/test';

/* Launch shipping (listing rules D16 to D22): AfroTransact sets all shipping. One flat fee for Delivery by AfroTransact,
   free pickup, no express and no carrier named anywhere a shopper reads; an address outside every delivery zone gets
   "Too far for standard delivery. Ask us for a quote", which cannot be paid for. Runs on the mock BFF. */

const CARRIER = /(^|[^\w-])(USPS|UPS|FedEx|DHL)([^\w-]|$)|Ground Advantage|Next Day Air|Express Air/i;

async function signIn(request: APIRequestContext) {
  const r = await request.get('/api/bff/v1/auth/login?returnTo=%2F');
  expect(r.ok()).toBe(true);
}

test.describe('storefront · launch shipping', () => {
  test('product page, cart and checkout offer Delivery by AfroTransact at the flat fee, with pickup, and name no carrier', async ({ page }) => {
    await signIn(page.request);

    await page.goto('/p/AT000000P2');
    await expect(page.getByText(/Delivery by AfroTransact/).first()).toBeVisible();
    const buyBox = page.getByRole('complementary', { name: 'Buy box' });
    await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
    await expect(buyBox.getByText(/in cart/)).toBeVisible();
    await expect(page.locator('body')).not.toContainText(CARRIER);

    await page.goto('/cart');
    await expect(page.getByTestId('cart-package').first()).toBeVisible();
    await expect(page.locator('body')).not.toContainText(CARRIER);

    await page.goto('/checkout');
    const pkg = page.getByTestId('checkout-package').first();
    await expect(pkg.getByTestId('delivery-option-afrotransact_delivery')).toContainText('Delivery by AfroTransact');
    await expect(pkg.getByTestId('option-fee-afrotransact_delivery')).toHaveText('$5.99');
    await expect(pkg.getByTestId('delivery-option-express')).toHaveCount(0);
    await pkg.getByTestId('fulfil-pickup').click();
    await expect(pkg.getByTestId('pickup-point').first()).toBeVisible();
    await expect(page.locator('body')).not.toContainText(CARRIER);
    await expect(page.getByRole('button', { name: 'Place your order' })).toBeEnabled();
  });

  test('an address outside every delivery zone offers a quote, not a price, and cannot be paid for', async ({ page }) => {
    await signIn(page.request);
    await page.goto('/p/AT000000P2');
    const buyBox = page.getByRole('complementary', { name: 'Buy box' });
    await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
    await expect(buyBox.getByText(/in cart/)).toBeVisible();
    const made = await page.request.post('/api/bff/v1/account/addresses', {
      data: { name: 'Ada Obi', phone: '+13015550142', line1: '8605 Colesville Rd', city: 'Silver Spring', state: 'MD', zip: '20910', isDefault: true }
    });
    expect(made.ok()).toBe(true);

    await page.goto('/checkout');
    const pkg = page.getByTestId('checkout-package').first();
    await expect(pkg.getByTestId('quote-required')).toContainText('Too far for standard delivery. Ask us for a quote');
    await expect(pkg.getByRole('radio', { name: /Too far/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Place your order' })).toBeDisabled();
    await expect(page.getByTestId('delivery-blocked-note')).toContainText(/too far for standard delivery/i);
    // The button calls the stub the quote flow replaces: it is there and does not break the page.
    await pkg.getByTestId('request-quote').click();
    await expect(pkg.getByTestId('quote-required')).toBeVisible();
    await expect(page.locator('body')).not.toContainText(CARRIER);
  });
});
