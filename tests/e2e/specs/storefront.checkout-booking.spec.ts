import { type APIRequestContext, expect, test } from '@playwright/test';

/** Signs the demo shopper in through the BFF (mock: password then the fixed MFA code); the cookie lands in the page's context. */
async function signIn(request: APIRequestContext) {
  const r = await request.post('/api/bff/v1/auth/sign-in', { data: { email: 'adaeze@example.com', password: 'shop2026' } });
  expect(r.ok()).toBe(true);
  const body = (await r.json()) as { status: string; challengeId?: string };
  if (body.status === 'mfa_required') {
    const v = await request.post('/api/bff/v1/auth/mfa/verify', { data: { challengeId: body.challengeId, code: '246810' } });
    expect(v.ok()).toBe(true);
  }
}

test.describe('storefront · signed-in shopper', () => {
  test('checkout: address → payment → delivery choice → place order (S047, S048)', async ({ page }) => {
    await signIn(page.request);

    await page.goto('/p/AT000000P2');
    const buyBox = page.getByRole('complementary', { name: 'Buy box' });
    await buyBox.getByRole('button', { name: 'Add to Cart' }).click();
    await expect(buyBox.getByText(/in cart/)).toBeVisible();

    await page.goto('/checkout');
    // Step 1: delivery address.
    await expect(page.getByTestId('checkout-address').first()).toBeVisible();
    await page.getByRole('button', { name: 'Deliver to this address' }).click();
    // Step 2: payment (mock Stripe: saved methods + express wallets).
    await expect(page.getByTestId('checkout-payment').first()).toBeVisible();
    await expect(page.getByRole('group', { name: 'Express checkout' })).toBeVisible();
    await page.getByRole('button', { name: 'Use this payment method' }).click();
    // Step 3: delivery choices per package with promise dates.
    const pkg = page.getByTestId('checkout-package').first();
    await expect(pkg).toBeVisible();
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
