import { type APIRequestContext, expect, type Page, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Storefront pre-orders (S273) against the mock BFF. The mock windows are seeded around "today": Lubbock #7 open
   (door $7.99 / pickup free), Amarillo #4 closed, El Paso #3 upcoming, Greater Toronto #1 upcoming (CAD). Houston is a
   next-day metro. The dev-only preview endpoint forces a stage, the master switch or the fallback. */

const JOLLOF = 'AT000000P2'; // groceries: pre-order eligible
const SHEA = 'AT00000P15'; // beauty: ships now

async function signIn(request: APIRequestContext) {
  const r = await request.post('/api/bff/v1/auth/sign-in', { data: { email: 'adaeze@example.com', password: 'shop2026' } });
  expect(r.ok()).toBe(true);
  const body = (await r.json()) as { status: string; challengeId?: string };
  if (body.status === 'mfa_required') {
    const v = await request.post('/api/bff/v1/auth/mfa/verify', { data: { challengeId: body.challengeId, code: '246810' } });
    expect(v.ok()).toBe(true);
  }
}

const deliverTo = async (page: Page, zip: string) => expect((await page.request.put('/api/bff/v1/delivery-location', { data: { zip } })).ok()).toBe(true);
const preview = async (page: Page, data: Record<string, unknown>) => expect((await page.request.put('/api/bff/v1/mock/preorder-preview', { data })).ok()).toBe(true);

async function addToCart(page: Page, atin: string, quantity: number) {
  const p = (await (await page.request.get(`/api/bff/v1/products/${atin}`)).json()) as { buyBox: { offerId: string } };
  expect((await page.request.post('/api/bff/v1/cart/items', { data: { offerId: p.buyBox.offerId, quantity } })).ok()).toBe(true);
}

test.describe('storefront · pre-orders (S273)', () => {
  test('metro shopper: send-to-family banner → pre-order list → shop for Lubbock switches the city and shows the window', async ({ page }, testInfo) => {
    await page.goto('/');
    const family = page.getByTestId('preorder-family');
    await expect(family).toContainText('Pre-order open in Lubbock, El Paso, Toronto.');
    await expect(family).toContainText('Their delivery fee is $7.99 flat.');
    await family.click();
    await expect(page).toHaveURL(/\/pre-orders$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Pre-orders' })).toBeVisible();
    await expect(page.getByTestId('preorder-card')).toHaveCount(3);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront pre-order list' });

    await page.getByRole('button', { name: 'Shop for Lubbock' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Lubbock Pre-order #7' })).toBeVisible();
    await expect(page.getByText('Delivering to Lubbock, TX')).toBeVisible();
    const ribbon = page.getByTestId('preorder-ribbon');
    await expect(ribbon).toContainText('Lubbock pre-order is open');
    await expect(ribbon).toContainText(/· \$7\.99 flat/);
    await expect(ribbon.getByRole('timer')).toHaveAttribute('aria-live', 'off');
    await expect(ribbon.getByRole('timer')).toHaveAttribute('aria-label', /^Closes in \d+ days, \d+ hours, \d+ minutes$/);
    await expect(page.getByRole('list', { name: 'How this pre-order works' }).getByRole('listitem')).toHaveCount(4);
    await expect(page.getByTestId('preorder-timeline')).toContainText('Opens');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront pre-order window' });
  });

  test('home hero, badges and the PDP delivery box while the window is open', async ({ page }, testInfo) => {
    await deliverTo(page, '79401');
    await page.goto('/');
    const hero = page.getByTestId('preorder-hero');
    await expect(hero).toContainText('Lubbock pre-order · open');
    await expect(hero.getByRole('timer')).toBeVisible();
    await expect(hero).toContainText(/pre-orders from Lubbock so far/);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront home pre-order hero' });

    await page.goto('/s?dept=Groceries');
    await expect(page.getByTestId('preorder-badge').first()).toHaveText('Pre-order');
    await expect(page.getByTestId('card-availability').first()).toHaveText(/^Pre-order · delivered \w{3} \d+$/);

    await page.goto(`/p/${JOLLOF}`);
    const box = page.getByTestId('pdp-preorder');
    await expect(box).toContainText('Pre-order · Open now');
    await expect(box).toContainText('Charged at checkout. Cancel for free until the pre-order closes.');
    await expect(page.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' })).toBeEnabled();
  });

  test('cart split with door / pickup, then checkout confirms “You’re in”', async ({ page }) => {
    await signIn(page.request);
    await deliverTo(page, '79401');
    await addToCart(page, JOLLOF, 2);
    await addToCart(page, SHEA, 1);
    expect((await page.request.put('/api/bff/v1/cart/selection', { data: { selected: true } })).ok()).toBe(true);

    await page.goto('/?src=ig&cr=story&po=LBK-7');
    await page.goto('/cart');
    await expect(page.getByTestId('cart-preorder-head')).toContainText(/Pre-order · delivered \w{3}, \w{3} \d+/);
    await expect(page.getByTestId('cart-ships-now-head')).toHaveText('Ships now · UPS Ground, 3–5 days');
    const summary = page.getByTestId('cart-preorder-summary');
    await expect(summary).toContainText('Pre-order · door delivery$7.99');
    await page.getByTestId('fulfil-pickup').click();
    await expect(page.getByTestId('fulfil-pickup')).toHaveAttribute('aria-pressed', 'true');
    await expect(summary).toContainText('Pre-order · pickup$0.00');
    await expect(page.getByTestId('preorder-pay-notes')).toContainText('by card, Apple Pay or ACH.');

    await page.getByRole('link', { name: 'Proceed to checkout' }).click();
    await page.getByRole('radio', { name: /Adaeze Nwosu 9800 Westheimer/ }).check();
    await page.getByRole('button', { name: 'Deliver to this address' }).click();
    await page.getByRole('button', { name: 'Use this payment method' }).click();
    await page.getByRole('button', { name: 'Place your order' }).click();
    await expect(page).toHaveURL(/\/orders\?placed=AT-2026-/);
    const conf = page.getByTestId('preorder-confirmation');
    await expect(conf.getByRole('heading', { name: "You're in: Lubbock Pre-order #7" })).toBeVisible();
    await expect(conf).toContainText(/Pre-order #LBK-20\d\d/);
    await expect(conf).toContainText('1 other item(s) ship now with tracking.');
    await expect(conf.getByRole('button', { name: 'Add to calendar' })).toBeVisible();
  });

  test('locked: an upcoming window blocks checkout with an explanation', async ({ page }, testInfo) => {
    await deliverTo(page, '79401');
    await addToCart(page, JOLLOF, 1);
    await preview(page, { stage: 'UPCOMING' });
    await page.goto('/cart');
    await expect(page.getByTestId('cart-preorder-locked')).toContainText('They stay in your cart until then.');
    await expect(page.getByRole('button', { name: 'Pre-order not open' })).toBeDisabled();
    await expect(page.getByTestId('preorder-ribbon')).toContainText(/Lubbock pre-order opens \w{3} \d+/);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront cart pre-order locked' });
    await page.goto(`/p/${JOLLOF}`);
    // Already in the cart: the box explains when it opens, the quantity can only go down and there is no Buy Now.
    await expect(page.getByTestId('pdp-preorder')).toContainText(/You can pre-order from \w{3}, \w{3} \d+/);
    await expect(page.getByRole('button', { name: 'Buy Now' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Increase quantity/ })).toBeDisabled();
  });

  test('off: waitlist fallback hides checkout and collects an email; ship fallback explains UPS Ground', async ({ page }, testInfo) => {
    await deliverTo(page, '79401');
    await preview(page, { enabled: false, fallback: 'WAITLIST' });
    await page.goto('/');
    await expect(page.getByTestId('preorder-off-ribbon')).toHaveText("We don't deliver to Lubbock yet. Join the waitlist to hear when the next pre-order opens.");
    await expect(page.getByRole('heading', { name: 'Pre-orders are coming to Lubbock.' })).toBeVisible();
    await page.getByLabel('Email').fill('kwame@example.com');
    await page.getByRole('button', { name: 'Join the waitlist' }).click();
    await expect(page.getByText("You're on the list. We'll email kwame@example.com when a pre-order opens in Lubbock.")).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'storefront pre-order waitlist' });

    await preview(page, { enabled: false, fallback: 'SHIP' });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Shipping by UPS Ground for now.' })).toBeVisible();
    await expect(page.getByTestId('preorder-off-ribbon')).toContainText('Orders ship by UPS Ground in 3–5 days.');
  });

  test('the subheader nav stays on one row and scrolls sideways', async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto('/');
    const nav = page.getByRole('navigation', { name: 'Departments' }).getByRole('list');
    const style = await nav.evaluate((el) => ({ wrap: getComputedStyle(el).flexWrap, overflow: getComputedStyle(el).overflowX, scroll: el.scrollWidth > el.clientWidth }));
    expect(style).toEqual({ wrap: 'nowrap', overflow: 'auto', scroll: true });
  });
});
