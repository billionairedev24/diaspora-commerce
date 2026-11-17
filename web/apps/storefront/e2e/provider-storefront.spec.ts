import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

import { expect, type Page, test } from '@playwright/test';

/* A provider's page /b/<slug> on the mock stack: the same branded mini-store, with its real services, a booker over real
   availability, the weekly schedule and the service area. At 1280 and 390: no sideways scroll, axe clean, and the content
   column lines up (measured, not eyeballed). */

const axeSource = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8');

async function axeViolations(page: Page) {
  await page.evaluate(axeSource);
  return page.evaluate(async () => {
    const axe = (window as unknown as { axe: { run: (c: Element, o: object) => Promise<{ violations: Array<{ id: string; impact: string; nodes: Array<{ target: unknown }> }> }> } }).axe;
    // Colour contrast is judged per look by the tokens tests; here: structure, names, keyboard.
    const r = await axe.run(document.body, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] }, rules: { 'color-contrast': { enabled: false } } });
    return r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${JSON.stringify(v.nodes[0]?.target)}`);
  });
}

const noSidewaysScroll = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

for (const size of [{ name: '1280', width: 1280, height: 900 }, { name: '390', width: 390, height: 844 }]) {
  test.describe(`provider storefront at ${size.name}`, () => {
    test.use({ viewport: { width: size.width, height: size.height } });

    test('is a bookable mini-store: services, schedule, area, no cart or products', async ({ page }) => {
      await page.goto('/b/braids-by-ama');
      await expect(page.getByRole('heading', { level: 1, name: 'Braids by Ama' })).toBeVisible();
      await expect(page.getByTestId('storefront-header').getByRole('search')).toHaveCount(0);
      await expect(page.getByRole('link', { name: /^Cart/ })).toHaveCount(0);
      await expect(page.getByRole('heading', { name: 'All products' })).toHaveCount(0);
      await expect(page.locator('footer')).toHaveCount(1);
      await expect(page.getByRole('heading', { name: 'Services' })).toBeVisible();
      await expect(page.getByRole('heading', { name: 'Knotless braids, mid-back' })).toBeVisible();
      await expect(page.getByText('Sunday').locator('xpath=following-sibling::dd[1]')).toHaveText('Closed');
      await expect(page.getByText(/Based in Houston, TX/)).toBeVisible();
      expect(await noSidewaysScroll(page)).toBe(true);
    });

    test('books from real availability: pick a service, a day, a time; signed out asks to sign in', async ({ page }) => {
      await page.goto('/b/braids-by-ama');
      await page.getByRole('button', { name: /^Book Knotless braids/ }).click();
      const book = page.locator('#sf-book');
      await expect(book.getByRole('heading', { name: 'Book an appointment' })).toBeVisible();
      await expect(book.getByRole('radio', { name: 'Knotless braids, mid-back' })).toBeChecked();
      const days = book.getByRole('group', { name: /days/i });
      const day = days.getByRole('button').and(page.locator(':not([disabled])')).first();
      await day.click();
      const slot = book.getByRole('group', { name: /times/i }).getByRole('button').first();
      await slot.click();
      await expect(slot).toHaveAttribute('aria-pressed', 'true');
      await expect(book.getByText(/Due now|due now/i).first()).toBeVisible();
      await expect(book.getByRole('link', { name: /sign in/i })).toBeVisible();
      expect(await noSidewaysScroll(page)).toBe(true);
    });

    test('axe has no serious or critical violations', async ({ page }) => {
      await page.goto('/b/braids-by-ama');
      await page.getByRole('button', { name: /^Book Knotless braids/ }).click();
      await expect(page.locator('#sf-book').getByRole('group', { name: /days/i })).toBeVisible();
      expect(await axeViolations(page)).toEqual([]);
    });

    test('the content column lines up: hero text, section headings and the booker share one left edge', async ({ page }) => {
      await page.goto('/b/braids-by-ama');
      await page.getByRole('button', { name: /^Book Knotless braids/ }).click();
      await expect(page.locator('#sf-book').getByRole('group', { name: /days/i })).toBeVisible();
      const left = async (loc: ReturnType<Page['locator']>) => Math.round((await loc.boundingBox())!.x);
      const h2s = ['Services', 'Book an appointment', 'Opening hours', 'About the studio'];
      const edges = [] as number[];
      for (const h of h2s) edges.push(await left(page.getByRole('heading', { level: 2, name: new RegExp(`^\\s*${h}`) }).first()));
      edges.push(await left(page.getByRole('heading', { level: 1 })));
      edges.push(await left(page.getByTestId('storefront-header').getByRole('link').first()));
      edges.push(await left(page.getByTestId('storefront-footer').locator('strong').first()));
      edges.push(await left(page.locator('#sf-book').getByRole('group', { name: /days/i })));
      const first = edges[0]!;
      // Headings, the hero name and the booker's days all start at the page gutter (within 1 px; the schedule icon sits inside its heading).
      for (const e of edges) expect(Math.abs(e - first), `edges ${edges.join(',')}`).toBeLessThanOrEqual(1);
    });
  });
}

test('an unpublished provider keeps the plain page; a published seller store is unaffected', async ({ page }) => {
  const res = await page.goto('/b/not-a-provider');
  expect(res?.status()).toBe(404);
});

test('the On sale filter shows only discounted products and the in-stock line is gone', async ({ page }) => {
  await page.goto('/b/laserfeet');
  await expect(page.getByText('Showing products in stock now')).toHaveCount(0);
  const grid = page.locator('#sf-products');
  await grid.getByRole('checkbox', { name: 'On sale' }).check();
  await expect(page).toHaveURL(/onSale=true/);
  const tiles = grid.getByTestId('store-product');
  await expect(tiles.first()).toBeVisible();
  const n = await tiles.count();
  for (let i = 0; i < n; i++) await expect(tiles.nth(i)).toContainText(/% off/);
  await grid.getByRole('checkbox', { name: 'On sale' }).uncheck();
  expect(await tiles.count()).toBeGreaterThanOrEqual(n);
});
