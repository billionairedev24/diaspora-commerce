import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { expectNoDrift, openTourFromHelp, openTourHub, popover, resetTourProgress, runTour, serverTourProgress } from '../support/tours';

/*
 * Staff console guided tours against the mock BFF (default role: platform super admin, so every chapter is offered). The drift guard:
 * each chapter is run for real from the Tour hub (Help → Tour hub); a step whose `data-tour` target (or page) is gone makes the
 * chapter report drift and the test fail. The counts are the steps each chapter shows today: a change means a screen or a chapter
 * changed on purpose. Progress is kept on the (mock) server per person, so each test starts by forgetting it.
 */

test.describe.configure({ mode: 'default' });

const STAFF_CHAPTERS: [id: string, shown: number][] = [
  ['admin-home', 4],
  ['my-account', 6],
  ['applications-review', 5],
  ['selling-approvals', 3],
  ['organisations', 4],
  ['department-requests', 3],
  ['delivery-quotes', 3],
  ['moderation-queue', 5],
  ['catalogue-rules', 5],
  ['orders-shipments', 4],
  ['disputes-returns', 4],
  ['support-refunds', 4],
  ['payouts', 5],
  ['tax-review', 3],
  ['tax-profiles', 5],
  ['fees-plans', 5],
  ['inventory', 7],
  ['retail', 6],
  ['dispatch', 5],
  ['preorders', 4],
  ['exports', 3],
  ['roles-permissions', 4],
  ['settings', 5],
  ['site-content', 3],
  ['notifications', 3],
  ['identity-access', 4],
  ['marketing', 6],
  ['audit-log', 3]
];

test.describe('staff console · every chapter finds its targets', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await resetTourProgress(request, baseURL ?? '');
  });

  for (const [id, shown] of STAFF_CHAPTERS) {
    test(`chapter "${id}" runs to the end with no missing target`, async ({ page }, testInfo) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const result = await runTour(page, id, { axe: { testInfo, page: `staff tour ${id}` } });
      expectNoDrift(result, shown);
    });
  }

  test('the Tour hub lists every chapter for a super admin, grouped into journeys', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourHub(page);
    await expect(page.getByRole('heading', { level: 1, name: 'Tour hub' })).toBeVisible();
    for (const journey of ['Find your way', 'Sellers and providers', 'Catalogue and rules', 'Orders and money', 'Operations', 'Platform settings and access']) {
      await expect(page.getByRole('heading', { level: 2, name: journey })).toBeVisible();
    }
    for (const [id] of STAFF_CHAPTERS) await expect(page.locator(`[data-tour-entry="${id}"]`)).toBeVisible();
    await expect(page.locator('[data-journey="start"]')).toContainText('0 of 2 chapters done');
  });

  test('the Tour hub has no serious accessibility violations', async ({ page }, testInfo) => {
    await page.goto('/help/tours');
    await expect(page.locator('[data-tour="tour-hub"]')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff tour hub', include: '[data-tour="tour-hub"]' });
  });

  test('a role is only offered the chapters its permissions reach', async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'at_mock_staff_role', value: 'platform.finance', url: baseURL ?? 'http://localhost:3412' }]);
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourHub(page);
    await expect(page.locator('[data-tour-entry="admin-home"]')).toBeVisible();
    await expect(page.locator('[data-tour-entry="roles-permissions"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="moderation-queue"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="identity-access"]')).toHaveCount(0);
  });
});

test.describe('staff console · tour behaviour', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await resetTourProgress(request, baseURL ?? '');
  });

  test('the orders chapter warns that Picked up charges the customer, and the keyboard drives the tour', async ({ page }) => {
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

  test('the two-step review chapter says the same person cannot approve', async ({ page }) => {
    await page.goto('/applications');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'applications-review');
    await popover(page).locator('.at-tour-next').click();
    await popover(page).locator('.at-tour-next').click();
    await expect(popover(page)).toContainText('Two steps, two people');
    await popover(page).locator('.at-tour-next').click();
    await expect(popover(page)).toContainText('You cannot approve your own review');
    await page.keyboard.press('Escape');
  });

  test('the tax chapter says it is not tax advice', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'tax-profiles');
    for (let i = 0; i < 4; i++) await popover(page).locator('.at-tour-next').click();
    await expect(popover(page)).toContainText('not tax advice');
    await page.keyboard.press('Escape');
  });

  test('progress is kept on the server: closing part way offers Resume and the hub shows the chapter in progress', async ({ page, request, baseURL }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'audit-log');
    await popover(page).locator('.at-tour-next').click();
    await page.keyboard.press('Escape');
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'st.audit-log')?.status, { timeout: 10_000 }).toBe('IN_PROGRESS');
    await openTourHub(page);
    await expect(page.locator('[data-chapter="audit-log"]')).toContainText('In progress');
    await page.locator('[data-tour-resume="audit-log"]').click();
    await expect(popover(page)).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('a first-time visit starts the admin tour once; Don\'t show again is kept on the server, follows the person to a new browser, and Replay still works', async ({ page, browser, request, baseURL }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/');
    await expect(popover(page)).toBeVisible({ timeout: 15_000 });
    await page.getByLabel("Don't show again").check();
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'st.admin-home')?.status, { timeout: 10_000 }).toBe('DISMISSED');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);

    const other = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } });
    const fresh = await other.newPage();
    await fresh.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await fresh.goto('/');
    await expect(fresh.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await fresh.waitForTimeout(4_500);
    await expect(popover(fresh)).toHaveCount(0);
    await other.close();

    await openTourFromHelp(page, 'admin-home');
    await expect(popover(page)).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('fail quiet: when progress cannot be loaded nothing starts by itself, and it starts once the server answers', async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    let down = true;
    await page.route('**/api/bff/v1/account/tour-progress', async (route) => {
      if (down && route.request().method() === 'GET') await route.fulfill({ status: 503, contentType: 'application/problem+json', body: '{"title":"Unavailable","status":503,"code":"UPSTREAM_UNAVAILABLE"}' });
      else await route.continue();
    });
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(8_500);
    await expect(popover(page)).toHaveCount(0);
    down = false;
    await expect(popover(page)).toBeVisible({ timeout: 30_000 });
    await page.keyboard.press('Escape');
  });

  test('resetting my progress in the hub reaches the server', async ({ page, request, baseURL }) => {
    await page.goto('/help/tours');
    await expect(page.locator('[data-tour="tour-hub"]')).toBeVisible();
    await page.locator('[data-journey="start"]').getByRole('button', { name: 'Skip this journey' }).click();
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.length, { timeout: 10_000 }).toBe(2);
    await page.getByRole('button', { name: 'Reset my progress' }).click();
    await page.getByRole('button', { name: 'Yes, reset' }).click();
    await expect(page.getByText('Your tour progress was reset.')).toBeVisible();
    expect((await serverTourProgress(request, baseURL ?? '')).items).toEqual([]);
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
