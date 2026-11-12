import { expect, test } from '@playwright/test';

import { actAs, PROVIDER_ORG, SELLER_ORG, setActiveOrg } from '../support/seller';
import { expectNoDrift, helpMenu, openTourFromHelp, popover, recordTourEvents, runTour } from '../support/tours';

/*
 * Seller Center / Provider Hub guided tours against the mock BFF. This is the drift guard: every tour is run for real from the
 * Help menu, and a step whose `data-tour` target (or page) is gone makes its tour report drift and the test fail. The counts are
 * the steps each tour shows for that organisation today: a change here means a screen or a tour changed on purpose.
 *
 * Automated browsers never get first-time tours by themselves (navigator.webdriver), so the other specs are not covered by a
 * popover; the auto-start tests below switch that on with localStorage `at.tour.autostart=force`.
 */

test.describe.configure({ mode: 'default' });

const SELLER_TOURS: [id: string, shown: number][] = [
  ['welcome', 5],
  ['first-product', 6],
  ['approvals', 4],
  ['inventory', 4],
  ['orders', 5],
  ['payouts', 5]
];

test.describe('seller center · every seller tour finds its targets', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  for (const [id, shown] of SELLER_TOURS) {
    test(`tour "${id}" runs to the end with no missing target`, async ({ page }, testInfo) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const result = await runTour(page, id, { axe: { testInfo, page: `seller tour ${id}` } });
      expectNoDrift(result, shown);
    });
  }

  test('the Help menu lists the tours the role allows, with a one-line summary', async ({ page }) => {
    await page.goto('/');
    await helpMenu(page).click();
    for (const [id] of SELLER_TOURS) await expect(page.locator(`[data-tour-entry="${id}"]`)).toBeVisible();
    await expect(page.locator('[data-tour-entry="services"]')).toHaveCount(0);
    await expect(page.getByText('Replay a tour')).toBeVisible();
  });

  test('a fulfilment role is only offered the tours its permissions reach', async ({ page, context, baseURL }) => {
    await actAs(context, baseURL ?? '', 'seller.fulfilment');
    await page.goto('/orders');
    await helpMenu(page).click();
    await expect(page.locator('[data-tour-entry="orders"]')).toBeVisible();
    await expect(page.locator('[data-tour-entry="payouts"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="first-product"]')).toHaveCount(0);
  });
});

test.describe('seller center · provider tour', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', PROVIDER_ORG);
  });

  test('the services tour runs to the end with no missing target', async ({ page }, testInfo) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    const result = await runTour(page, 'services', { axe: { testInfo, page: 'provider tour services' } });
    expectNoDrift(result, 5);
  });

  test('a provider is not offered the seller-only orders tour', async ({ page }) => {
    await page.goto('/');
    await helpMenu(page).click();
    await expect(page.locator('[data-tour-entry="services"]')).toBeVisible();
    await expect(page.locator('[data-tour-entry="orders"]')).toHaveCount(0);
  });
});

test.describe('seller center · tour behaviour', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('keyboard: arrows move, Esc closes and gives focus back, Tab stays inside the popover', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await recordTourEvents(page);
    await helpMenu(page).focus();
    await page.keyboard.press('Enter');
    // the menu opens with its first entry (the welcome tour) focused
    await page.keyboard.press('Enter');
    await expect(popover(page)).toBeVisible();
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 1 of 5');
    // The popover is a named dialog and the primary button has focus.
    await expect(popover(page)).toHaveAttribute('role', 'dialog');
    await expect(popover(page)).toHaveAttribute('aria-label', 'Guided tour');
    await expect(popover(page).locator('.at-tour-next')).toBeFocused();

    await page.keyboard.press('ArrowRight');
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 2 of 5');
    await page.keyboard.press('ArrowLeft');
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 1 of 5');

    // Focus trap: tabbing round never leaves the popover (the first step points at nothing else).
    for (let i = 0; i < 9; i++) {
      await page.keyboard.press('Tab');
      expect(await page.evaluate(() => !!document.activeElement?.closest('[data-tour-popover]'))).toBe(true);
    }

    // aria-live announcement of the step.
    await expect(page.locator('[data-tour-live]')).toContainText('Step 1 of 5');

    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await expect(helpMenu(page)).toBeFocused();
    const events = await page.evaluate(() => window.__atTour ?? []);
    expect(events.at(-1)).toMatchObject({ type: 'ended', reason: 'close' });
  });

  test('Skip is visible on every step and ends the tour', async ({ page }) => {
    await page.goto('/');
    await openTourFromHelp(page, 'welcome');
    await expect(popover(page).getByRole('button', { name: 'Skip tour' })).toBeVisible();
    await expect(popover(page).getByRole('button', { name: 'Next' })).toBeVisible();
    await popover(page).getByRole('button', { name: 'Next' }).click();
    await expect(popover(page).getByRole('button', { name: 'Back' })).toBeVisible();
    await expect(popover(page).getByRole('button', { name: 'Skip tour' })).toBeVisible();
    await popover(page).getByRole('button', { name: 'Skip tour' }).click();
    await expect(popover(page)).toHaveCount(0);
  });

  test('a first-time visit starts the welcome tour once; Don\'t show again keeps it away', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/');
    await expect(popover(page)).toBeVisible({ timeout: 15_000 });
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 1 of 5');
    // never blocking: Esc closes it at once
    await page.getByLabel("Don't show again").check();
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
    const keys = await page.evaluate(() => Object.keys(window.localStorage).filter((k) => k.startsWith('at.tour.v1:')));
    expect(keys.length).toBeGreaterThan(0);
    // opaque ids only: nothing that looks like an email or a name in the key
    expect(keys.join(' ')).not.toMatch(/@/);
  });

  test('it does not start by itself on top of an open dialog or a form with unsaved text', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/listings/new');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.getByRole('textbox', { name: /title/i }).first().fill('Half-written title');
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
  });

  test('on a phone it opens as a bottom sheet and never starts by itself', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
    await openTourFromHelp(page, 'welcome');
    await expect(popover(page)).toBeVisible();
    await expect(popover(page)).toHaveClass(/at-tour-sheet/);
    const box = await popover(page).boundingBox();
    expect(box?.width).toBeGreaterThan(380);
    expect((box?.y ?? 0) + (box?.height ?? 0)).toBeGreaterThan(780);
    await page.keyboard.press('Escape');
  });

  test('right to left: Arabic text, mirrored popover, ArrowLeft goes forward', async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'at_locale', value: 'ar', url: baseURL ?? '' }]);
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'welcome');
    await expect(popover(page)).toBeVisible();
    await expect(popover(page)).toHaveAttribute('dir', 'rtl');
    await expect(popover(page).locator('.at-tour-count')).toContainText('الخطوة 1');
    await page.keyboard.press('ArrowLeft');
    await expect(popover(page).locator('.at-tour-count')).toContainText('الخطوة 2');
    const box = await popover(page).boundingBox();
    const view = page.viewportSize();
    expect(box && view && box.x >= 0 && box.x + box.width <= view.width).toBe(true);
    await page.keyboard.press('Escape');
  });

  test('reduced motion: the popover appears without animation', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/');
    await openTourFromHelp(page, 'welcome');
    await expect(popover(page)).toBeVisible();
    const animation = await popover(page).evaluate((el) => getComputedStyle(el).animationName);
    expect(animation).toBe('none');
    await expect(page.locator('.driver-fade')).toHaveCount(0);
    await page.keyboard.press('Escape');
  });
});
