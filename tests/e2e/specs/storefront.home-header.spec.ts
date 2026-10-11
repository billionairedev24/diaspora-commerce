import { expect, type Page, test } from '@playwright/test';

/* Storefront home page and header against the mock BFF (design/AfroTransact Shop.dc.html, home view). Houston 77042
   (the default delivery place) is a next-day metro: no pre-order ribbon, hero or fallback on the home page. */

const headerBox = (page: Page) =>
  page.getByTestId('site-header').evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { top: Math.round(r.top), height: Math.round(r.height), position: getComputedStyle(el).position };
  });

test.describe('storefront · home and fixed header', () => {
  test('home follows the Shop design order with no pre-order fallback and no developer captions', async ({ page }) => {
    await page.goto('/');
    const main = page.locator('main');
    await expect(main.locator('[aria-roledescription="carousel"]')).toBeVisible();
    await expect(page.getByTestId('preorder-ribbon')).toHaveCount(0);
    await expect(page.getByTestId('preorder-hero')).toHaveCount(0);
    await expect(page.getByTestId('preorder-off')).toHaveCount(0);
    // Prototype annotations (image size hints, "no pre-order" previews) never reach the real UI.
    await expect(page.getByText(/UPS Ground|1600×900|970×250|Campaign image|Advertiser creative|Preview pre-order city/)).toHaveCount(0);
    // Carousel first, then tiles, sponsored banners and rails.
    const order = await main.evaluate((m) => {
      const pick = (sel: string) => {
        const el = m.querySelector(sel);
        return el ? Array.prototype.indexOf.call(m.querySelectorAll('*'), el) : -1;
      };
      return { hero: pick('[aria-roledescription="carousel"]'), tiles: pick('section[class*="tiles"]'), banner: pick('section[class*="banner"]'), rail: pick('section[class*="rail"]') };
    });
    expect(order.hero).toBeGreaterThanOrEqual(0);
    expect(order.tiles).toBeGreaterThan(order.hero);
    if (order.banner >= 0) expect(order.banner).toBeGreaterThan(order.tiles);
    if (order.rail >= 0 && order.banner >= 0) expect(order.rail).toBeGreaterThan(order.banner);
  });

  for (const [name, viewport] of [
    ['desktop', { width: 1440, height: 900 }],
    ['mobile', { width: 390, height: 844 }]
  ] as const) {
    test(`header (top bar + department bar) stays fixed while the page scrolls · ${name}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto('/');
      const before = await headerBox(page);
      expect(before).toMatchObject({ top: 0, position: 'sticky' });
      await page.evaluate(() => window.scrollTo(0, 1500));
      await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(200);
      expect(await headerBox(page)).toEqual(before);
      // The search, cart and department bar are still on screen.
      await expect(page.getByRole('combobox', { name: /search/i }).first()).toBeInViewport();
      await expect(page.getByRole('link', { name: /^Cart, \d+ items$/ })).toBeInViewport();
      await expect(page.getByRole('navigation', { name: 'Departments' })).toBeInViewport();
      // The announcement bar is not part of the fixed header: it scrolls away (Shop design).
      await expect(page.getByRole('region', { name: 'Announcements' })).not.toBeInViewport();
      // Keyboard focus is never hidden under it (WCAG 2.4.11): scroll-padding-top covers the header height.
      const padding = await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop));
      expect(padding).toBeGreaterThanOrEqual(before.height);
    });
  }
});
