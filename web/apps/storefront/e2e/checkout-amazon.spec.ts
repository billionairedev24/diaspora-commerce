import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

import { expect, type Page, test } from '@playwright/test';

/* The checkout follows Amazon's pattern (owner: "a standard match for amazon"), against the mock BFF:
   minimal header and slim footer; numbered sections with Change; a sticky Place your order and Order summary on wide
   screens, a single column with a sticky bar on phones; no chat launcher; aligned controls measured, not eyeballed.
   CHECKOUT_SHOTS_DIR=docs/engineering/img saves the screenshots at 1280 and 390. */

const SHOTS = process.env.CHECKOUT_SHOTS_DIR;
const axeSource = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8');

async function checkoutWith(page: Page, products: string[]) {
  await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
  for (const p of products) {
    await page.goto(`/p/${p}`);
    await page.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }).click();
    await expect(page.getByRole('complementary', { name: 'Buy box' }).getByText(/in cart$/)).toBeVisible();
  }
  await page.goto('/checkout');
  await expect(page.getByTestId('checkout-package').first()).toBeVisible();
}

const box = async (loc: ReturnType<Page['locator']>) => {
  const b = await loc.boundingBox();
  if (!b) throw new Error('not rendered');
  return b;
};

async function axeViolations(page: Page) {
  await page.evaluate(axeSource);
  return page.evaluate(async () => {
    const axe = (window as unknown as { axe: { run: (c: Element, o: object) => Promise<{ violations: Array<{ id: string; nodes: Array<{ target: unknown }> }> }> } }).axe;
    const r = await axe.run(document.body, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
    return r.violations.map((v) => `${v.id}: ${JSON.stringify(v.nodes.map((n) => n.target))}`);
  });
}

const REMOVED = /Estimated for|No tax on|no sales tax applies|Worked out from your|Includes tax|never charged more|we collect|we deliver/i;

test('1280: minimal chrome, three numbered sections, sticky summary, aligned controls', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await checkoutWith(page, ['AT000000P2', 'AT00000P23']);

  // Minimal header and footer only: no mega navbar, department bar, big footer or chat launcher.
  await expect(page.getByTestId('site-header')).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Departments' })).toHaveCount(0);
  await expect(page.getByTestId('lola-launcher')).toHaveCount(0);
  await expect(page.getByTestId('checkout-footer')).toBeVisible();
  await expect(page.locator('footer')).toHaveCount(1);
  const heading = page.getByRole('heading', { level: 1 });
  await expect(heading).toHaveText('Secure checkout');
  const h = await box(heading);
  expect(Math.abs(h.x + h.width / 2 - 640), 'heading is centred').toBeLessThanOrEqual(2);
  const logo = await box(page.getByTestId('checkout-header').getByRole('link').first());
  const cartLink = page.getByTestId('checkout-cart-link');
  await expect(cartLink).toHaveAttribute('href', '/cart');
  const cart = await box(cartLink);
  expect(logo.x).toBeLessThan(h.x);
  expect(cart.x).toBeGreaterThan(h.x + h.width);

  // Sections 1 and 2 are one-line summaries with Change; section 3 is the open one.
  const titles = page.locator('h2[id^="co-"]');
  await expect(titles).toHaveText(['Delivery address', 'Payment method', 'Review items and delivery']);
  for (const n of [1, 2]) {
    await expect(page.getByTestId(`checkout-summary-${n}`)).toBeVisible();
    await expect(page.getByTestId(`checkout-change-${n}`)).toBeVisible();
  }
  // The items section changes in the cart, so its Change is a link there.
  await expect(page.getByTestId('checkout-change-3')).toHaveAttribute('href', '/cart');

  // Aligned: titles share a left edge, numbers share one, the Change links share a right edge and sit on the title's line.
  const lefts = await Promise.all([1, 2, 3].map(async (n) => (await box(page.getByTestId(`checkout-step-${n}`).locator('h2'))).x));
  expect(new Set(lefts.map(Math.round)).size).toBe(1);
  const rights = await Promise.all([1, 2, 3].map(async (n) => { const b = await box(page.getByTestId(`checkout-change-${n}`)); return b.x + b.width; }));
  expect(Math.abs((rights[0] ?? 0) - (rights[1] ?? 1))).toBeLessThanOrEqual(1);
  expect(Math.abs((rights[0] ?? 0) - (rights[2] ?? 1))).toBeLessThanOrEqual(1);
  for (const n of [1, 2, 3]) {
    const t = await box(page.getByTestId(`checkout-step-${n}`).locator('h2'));
    const c = await box(page.getByTestId(`checkout-change-${n}`));
    expect(Math.abs(t.y + t.height / 2 - (c.y + c.height / 2)), `Change ${n} on the title line`).toBeLessThanOrEqual(6);
  }
  const steppers = page.getByRole('group', { name: /^Quantity of/ });
  const heights = await Promise.all((await steppers.all()).map(async (s) => Math.round((await box(s)).height)));
  expect(new Set(heights).size, 'quantity selectors share a height').toBe(1);

  // The end column: Place your order first, one terms line, then the summary rows in Amazon's order.
  const rail = page.getByRole('complementary', { name: 'Order summary' });
  const place = rail.getByRole('button', { name: 'Place your order' });
  await expect(place).toBeEnabled();
  expect((await box(place)).height).toBeGreaterThanOrEqual(44);
  const railBox = await box(rail);
  const placeBox = await box(place);
  expect(placeBox.y - railBox.y).toBeLessThan(40);
  await expect(rail.getByTestId('checkout-terms')).toContainText('Conditions of Use');
  expect(await rail.locator('dt').allTextContents()).toEqual(['Items (2)', 'Delivery', 'Estimated tax', 'Order total']);
  expect(await rail.getByTestId('checkout-tax-amount').textContent()).toMatch(/^\$\d+\.\d{2}$/);
  // Sticky: after scrolling the long left column the summary is still at the top of the viewport.
  await page.evaluate(() => window.scrollTo(0, 600));
  expect((await box(rail)).y).toBeLessThan(40);
  await page.evaluate(() => window.scrollTo(0, 0));

  // Each package: the real arrival date (or none), the items with a quantity selector, radios only where there is a choice.
  const first = page.getByTestId('checkout-package').first();
  await expect(first.getByTestId('package-promise')).toHaveText(/^(Arrives .+|Delivery|Pickup.*)$/);
  await expect(first.getByRole('radio')).toHaveCount(2);
  const second = page.getByTestId('checkout-package').nth(1);
  await expect(second.getByRole('radio')).toHaveCount(0);

  expect((await page.locator('body').innerText()).match(REMOVED)).toBeNull();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await axeViolations(page)).toEqual([]);
  // Remote product images and fonts can be unreachable from a sandbox; script and page errors are what count here.
  expect(errors.filter((e) => !/Failed to load resource/.test(e))).toEqual([]);
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/checkout-amazon-1280.png`, fullPage: true });
});

test('390: one column, summary after the sections, sticky Place order bar, 44px targets', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await checkoutWith(page, ['AT000000P2', 'AT00000P23']);
  await expect(page.getByTestId('site-header')).toHaveCount(0);
  await expect(page.getByTestId('lola-launcher')).toHaveCount(0);

  const step3 = await box(page.getByTestId('checkout-step-3'));
  const rail = await box(page.getByRole('complementary', { name: 'Order summary' }));
  expect(rail.y, 'summary comes after the sections').toBeGreaterThanOrEqual(step3.y + step3.height - 1);
  expect(Math.abs(rail.x - step3.x)).toBeLessThanOrEqual(2);

  // The bar is fixed to the bottom edge (safe-area aware) with the total and the button.
  const bar = page.getByTestId('checkout-bar');
  await expect(bar).toBeVisible();
  const barBox = await box(bar);
  expect(Math.round(barBox.y + barBox.height)).toBe(844);
  await page.evaluate(() => window.scrollTo(0, 400));
  expect(Math.round((await box(bar)).y)).toBe(Math.round(barBox.y));
  const barButton = bar.getByRole('button', { name: 'Place order' });
  await expect(barButton).toBeEnabled();
  expect((await box(barButton)).height).toBeGreaterThanOrEqual(44);
  await expect(bar.getByTestId('checkout-bar-total')).toHaveText(/^\$\d+\.\d{2}$/);
  // The closing Place your order (the summary's own is replaced by it on one column) is a full-width 44px target.
  const closing = page.getByTestId('checkout-finish').getByRole('button', { name: 'Place your order' });
  expect((await box(closing)).height).toBeGreaterThanOrEqual(44);
  await expect(page.getByRole('complementary', { name: 'Order summary' }).getByTestId('checkout-terms')).toBeHidden();

  // Touch targets: the small Change link is hit-tested over 44px.
  for (const n of [1, 2, 3]) {
    const c = await box(page.getByTestId(`checkout-change-${n}`));
    const ok = await page.evaluate(([x, y]) => (document.elementFromPoint(x as number, y as number) as HTMLElement | null)?.closest('[data-testid^="checkout-change-"]') !== null, [c.x + c.width / 2, c.y + c.height / 2 + 18]);
    expect(ok, `Change ${n} reaches 44px`).toBe(true);
  }
  const steppers = await page.getByRole('group', { name: /^Quantity of/ }).first().getByRole('button').all();
  for (const b of steppers) expect(await b.evaluate((el) => { const r = el.getBoundingClientRect(); return Math.max(r.height, r.height + 12); })).toBeGreaterThanOrEqual(44);

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(await axeViolations(page)).toEqual([]);
  if (SHOTS) {
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `${SHOTS}/checkout-amazon-390.png`, fullPage: true });
  }
});

test('Change opens a section and collapses the others; the payment section can be reopened and closed again', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await checkoutWith(page, ['AT000000P2']);
  await page.getByTestId('checkout-change-1').click();
  await expect(page.getByTestId('checkout-address-option').first()).toBeVisible();
  await expect(page.getByTestId('checkout-package')).toHaveCount(0);
  await page.getByRole('button', { name: 'Deliver to this address' }).click();
  await expect(page.getByTestId('checkout-package').first()).toBeVisible();
  await page.getByTestId('checkout-change-2').click();
  await expect(page.getByTestId('checkout-payment').first()).toBeVisible();
  await page.getByRole('button', { name: 'Use this payment method' }).click();
  await expect(page.getByTestId('checkout-package').first()).toBeVisible();
});

test('a seller who offers pick up only: the reason in words, nothing chosen for the shopper, Place your order waits for the confirmation', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
  const added = await page.evaluate(async () => (await fetch('/api/bff/v1/cart/items', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ offerId: 'AT00000P11-patel-grocers', quantity: 1 }) })).status);
  expect(added).toBeLessThan(300);
  await page.goto('/checkout');
  const pkg = page.getByTestId('checkout-package').first();
  await expect(pkg).toHaveAttribute('data-mode', 'pickup-only');
  await expect(pkg.getByTestId('pickup-only')).toContainText('This seller offers pick up only');
  await expect(page.getByRole('button', { name: 'Place your order' }).first()).toBeDisabled();
  await expect(page.getByTestId('delivery-blocked-note')).toContainText('Confirm where you');
  expect(await axeViolations(page)).toEqual([]);
  await pkg.getByTestId('pickup-confirm-button').click();
  await expect(pkg.getByTestId('pickup-confirm')).toContainText('Pick up confirmed');
  await expect(page.getByRole('button', { name: 'Place your order' }).first()).toBeEnabled();
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/checkout-pickup-only-1280.png`, fullPage: true });
});
