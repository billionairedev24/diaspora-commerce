import { expect, test } from '@playwright/test';

import { expectNoDrift, helpMenu, openTourFromHelp, popover, runTour } from '../support/tours';

/*
 * Staff console guided tours against the mock BFF (default role: platform super admin, so every tour is offered). The drift
 * guard: each tour is run for real from the Help menu; a step whose `data-tour` target (or page) is gone makes the tour report
 * drift and the test fail. The counts are the steps each tour shows today: a change means a screen or a tour changed on purpose.
 */

test.describe.configure({ mode: 'default' });

const STAFF_TOURS: [id: string, shown: number][] = [
  ['admin-home', 4],
  ['applications-review', 4],
  ['moderation-queue', 3],
  ['tax-review', 3],
  ['orders-shipments', 4],
  ['settings', 5],
  ['roles-permissions', 4]
];

test.describe('staff console · every tour finds its targets', () => {
  for (const [id, shown] of STAFF_TOURS) {
    test(`tour "${id}" runs to the end with no missing target`, async ({ page }, testInfo) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const result = await runTour(page, id, { axe: { testInfo, page: `staff tour ${id}` } });
      expectNoDrift(result, shown);
    });
  }

  test('the Help menu lists every tour for a super admin', async ({ page }) => {
    await page.goto('/');
    await helpMenu(page).click();
    for (const [id] of STAFF_TOURS) await expect(page.locator(`[data-tour-entry="${id}"]`)).toBeVisible();
    await expect(page.getByText('Replay a tour')).toBeVisible();
  });
});

test.describe('staff console · tour behaviour', () => {
  test('the orders tour warns that Picked up charges the customer, and the keyboard drives the tour', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'orders-shipments');
    await expect(popover(page)).toBeVisible();
    // page, table, steps, warning
    for (let i = 0; i < 3; i++) {
      await expect(popover(page).locator('.at-tour-count')).toContainText(`Step ${i + 1} of 4`);
      await page.keyboard.press('ArrowRight');
    }
    await expect(popover(page)).toContainText('Careful with Picked up');
    await expect(popover(page)).toContainText('charges the customer');
    await expect(popover(page).getByRole('button', { name: 'Done' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
  });

  test('the two-step review tour says the same person cannot approve', async ({ page }) => {
    await page.goto('/applications');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'applications-review');
    await popover(page).locator('.at-tour-next').click();
    await popover(page).locator('.at-tour-next').click();
    await popover(page).locator('.at-tour-next').click();
    await expect(popover(page)).toContainText('You cannot approve your own review');
    await page.keyboard.press('Escape');
  });

  test('a first-time visit starts the admin tour once, and replaying from Help works after "never"', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/');
    await expect(popover(page)).toBeVisible({ timeout: 15_000 });
    await page.getByLabel("Don't show again").check();
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
    await openTourFromHelp(page, 'admin-home');
    await expect(popover(page)).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('on a phone it opens as a bottom sheet', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'admin-home');
    await expect(popover(page)).toHaveClass(/at-tour-sheet/);
    await page.keyboard.press('Escape');
  });
});
