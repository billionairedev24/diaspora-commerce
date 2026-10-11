import { type APIRequestContext, expect, test } from '@playwright/test';

/** Signs the demo shopper in through the BFF (mock: password then the fixed MFA code); the cookie lands in the page's context. */
/** Signs the demo shopper in through the mock BFF's login redirect (the identity sign-in page in a real stack). */
async function signIn(request: APIRequestContext) {
  const r = await request.get('/api/bff/v1/auth/login?returnTo=%2F');
  expect(r.ok()).toBe(true);
}

test.describe('storefront · signed-in shopper', () => {
  test('checkout: address, delivery or pickup per package, payment on one page → place order (S047, S048)', async ({ page }) => {
    await signIn(page.request);

    await page.goto('/p/AT000000P2');
    const buyBox = page.getByRole('complementary', { name: 'Buy box' });
    await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
    await expect(buyBox.getByText(/in cart/)).toBeVisible();

    await page.goto('/checkout');
    // One page: the default address, every package with delivery or pickup and its items, payment, the summary.
    await expect(page.getByTestId('checkout-address').first()).toBeVisible();
    await expect(page.getByTestId('checkout-payment').first()).toBeVisible();
    await expect(page.getByTestId('checkout-new-method')).toBeVisible();
    await expect(page.getByRole('group', { name: 'Express checkout' })).toHaveCount(0);
    const pkg = page.getByTestId('checkout-package').first();
    await expect(pkg.getByTestId('checkout-item').first()).toBeVisible();
    await pkg.getByTestId('fulfil-pickup').click();
    await expect(pkg.getByTestId('pickup-point').first()).toBeVisible();
    await pkg.getByTestId('fulfil-delivery').click();
    await pkg.getByRole('radio', { name: /AfroTransact Delivery/ }).check();
    await expect(pkg.getByText(/Delivery preferences/)).toBeVisible();

    await page.getByRole('button', { name: 'Place your order' }).click();
    await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  });

  test('services: pick a time and book with a deposit, then see it under appointments (S150)', async ({ page }) => {
    await signIn(page.request);

    await page.goto('/services/svc-braids-cornrows');
    await expect(page.getByRole('heading', { level: 1, name: /Cornrows/ })).toBeVisible();
    const times = page.getByRole('group', { name: 'Times' });
    await times.getByRole('button').first().click();
    await page.getByRole('button', { name: /Book · \$15\.00 deposit/ }).click();
    await expect(page.getByTestId('booking-done')).toContainText('You’re booked!');

    await page.getByRole('link', { name: 'Your appointments' }).click();
    await expect(page).toHaveURL(/\/account\/bookings/);
    await expect(page.getByTestId('booking').first()).toBeVisible();
  });

  test('search: sponsored products are labelled with the reason, and clicks are reported (S240)', async ({ page }) => {
    const clicks: unknown[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith('/v1/search/clicks')) clicks.push(r.postDataJSON());
    });
    await page.goto('/s?q=shea');
    const first = page.getByTestId('results-grid').getByRole('article').first();
    await expect(first.getByText('Sponsored · Tamale Shea Co-op')).toBeVisible();
    await first.getByText('Why this ad?').click();
    await expect(first.getByText(/Shown because you searched for/)).toBeVisible();
    await first.getByRole('link').nth(1).click();
    await expect(page).toHaveURL(/\/p\/[A-Z0-9]+/);
    await expect.poll(() => clicks.length).toBeGreaterThan(0);
    expect(clicks[0]).toMatchObject({ q: 'shea', position: 1 });
    expect((clicks[0] as { adToken?: string }).adToken).toBeTruthy();
  });

  test('orders: the order page shows the return window and links to its returns (S148)', async ({ page }) => {
    await signIn(page.request);
    await page.goto('/orders/AT-2026-104221');
    await expect(page.getByRole('link', { name: 'Return or replace items' })).toBeVisible();
    await expect(page.getByText(/Return eligible through/)).toBeVisible();
    await page.getByRole('link', { name: 'View returns' }).click();
    await expect(page).toHaveURL(/\/account\/returns\?order=AT-2026-104221/);
    await expect(page.getByText('Showing returns for order AT-2026-104221.')).toBeVisible();
  });
});
