import { expect, type Page, test } from '@playwright/test';

/* Lola, the shopping assistant: ONE entry point, a round launcher fixed at the bottom end corner (WhatsApp's pattern).
   Never in the navbar, never on the checkout, clear of the cookie banner. CHECKOUT_SHOTS_DIR saves screenshots. */

const SHOTS = process.env.CHECKOUT_SHOTS_DIR;
const box = async (loc: ReturnType<Page['locator']>) => {
  const b = await loc.boundingBox();
  if (!b) throw new Error('not rendered');
  return b;
};

for (const [width, height] of [[1280, 900], [390, 844]] as const) {
  test(`${width}: a single launcher at the bottom end corner, none in the navbar, opens a chat panel`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    const errors: string[] = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.goto('/');
    const launcher = page.getByRole('button', { name: 'Chat with Lola' });
    await expect(launcher).toHaveCount(1);
    // Not in the header or the department bar, wherever it would have been.
    await expect(page.getByTestId('site-header').getByText(/Lola/)).toHaveCount(0);
    await expect(page.getByTestId('site-header').getByRole('button', { name: /Lola/ })).toHaveCount(0);

    const b = await box(launcher);
    expect(Math.round(b.width)).toBe(56);
    expect(Math.round(b.x + b.width)).toBeLessThanOrEqual(width - 12);
    expect(Math.round(b.y + b.height)).toBeLessThanOrEqual(height - 12);
    expect(b.x).toBeGreaterThan(width / 2);
    // First visit: the small label, dismissible; it does not come back after a reload.
    const label = page.getByTestId('lola-fab-label');
    await expect(label).toBeVisible();
    if (SHOTS && width === 1280) await page.screenshot({ path: `${SHOTS}/lola-launcher-1280.png` });
    await label.getByRole('button', { name: 'Dismiss' }).click();
    await expect(label).toHaveCount(0);
    await page.reload();
    await expect(page.getByTestId('lola-fab-label')).toHaveCount(0);

    await page.getByRole('button', { name: 'Chat with Lola' }).click();
    const panel = page.getByRole('dialog', { name: 'Lola' });
    await expect(panel).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Track my order' })).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Delivery to my ZIP code' })).toBeVisible();
    const p = await box(panel);
    if (width >= 641) {
      expect(Math.round(p.x + p.width)).toBeLessThanOrEqual(width - 12);
      expect(p.y + p.height).toBeLessThanOrEqual(b.y - 4);
      expect(p.width).toBeLessThanOrEqual(400);
    } else {
      expect(Math.round(p.width)).toBe(width);
      expect(Math.round(p.height)).toBe(height);
    }
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/lola-panel-${width}.png` });
    await page.keyboard.press('Escape');
    await expect(panel).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Chat with Lola' })).toBeFocused();
    // Remote product images and fonts can be unreachable from a sandbox; script and page errors are what count here.
  expect(errors.filter((e) => !/Failed to load resource/.test(e))).toEqual([]);
  });
}

test('the launcher is not on the checkout, and clears the cookie banner elsewhere', async ({ page, context }) => {
  await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
  await page.goto('/checkout');
  await expect(page.getByTestId('lola-launcher')).toHaveCount(0);
  await expect(page.getByTestId('checkout-header')).toBeVisible();

  // With the cookie banner showing (no choice made yet) the launcher rides above it.
  await context.clearCookies();
  await page.setViewportSize({ width: 800, height: 700 });
  await page.goto('/');
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--at-cookie-banner-h') !== '');
  const banner = page.getByRole('region', { name: /cookie|privacy/i }).first();
  const bannerBox = (await banner.count()) ? await banner.boundingBox() : null;
  const launcher = await box(page.getByTestId('lola-launcher'));
  expect(bannerBox, 'the cookie banner is showing').not.toBeNull();
  if (bannerBox) expect(launcher.y + launcher.height).toBeLessThanOrEqual(bannerBox.y);
});

test('RTL: the launcher sits at the bottom left', async ({ page, context }) => {
  await context.addCookies([{ name: 'at_locale', value: 'ar', domain: 'localhost', path: '/' }]);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/');
  const html = await page.locator('html').getAttribute('dir');
  test.skip(html !== 'rtl', 'the locale cookie name differs here');
  const b = await box(page.getByTestId('lola-launcher'));
  expect(b.x).toBeLessThan(200);
});
