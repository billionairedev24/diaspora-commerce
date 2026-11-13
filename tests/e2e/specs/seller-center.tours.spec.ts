import { expect, type Page, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { actAs, PROVIDER_ORG, SELLER_ORG, setActiveOrg } from '../support/seller';
import { expectNoDrift, helpMenu, openTourFromHelp, openTourHub, popover, recordTourEvents, resetTourProgress, runTour, serverTourProgress } from '../support/tours';

/*
 * Seller Center / Provider Hub guided tours against the mock BFF. This is the drift guard: every chapter is run for real from the
 * Tour hub (Help → Tour hub), and a step whose `data-tour` target (or page) is gone makes its chapter report drift and the test
 * fail. The counts are the steps each chapter shows for that organisation today: a change here means a screen or a chapter changed
 * on purpose.
 *
 * Progress is kept on the (mock) server per person, so each test starts by forgetting it. Automated browsers never get first-time
 * tours by themselves (navigator.webdriver), so the auto-start tests switch that on with localStorage `at.tour.autostart=force`.
 */

test.describe.configure({ mode: 'default' });

const SELLER_CHAPTERS: [id: string, shown: number][] = [
  ['welcome', 5],
  ['account-health', 4],
  ['team', 5],
  ['account-settings', 6],
  ['help-support', 4],
  ['listings-overview', 4],
  // Add a product opens on the barcode step (S382): the form steps of these two chapters appear once the person carries on (see below).
  ['first-product', 4],
  ['product-sections', 1],
  ['images', 4],
  ['bulk-upload', 5],
  ['approvals', 4],
  ['inventory', 4],
  ['orders', 5],
  ['shipping-setup', 4],
  ['run-pickups', 3],
  ['returns-disputes', 5],
  ['dashboard', 4],
  ['payouts', 5],
  ['fees-billing', 5],
  ['coupons', 4],
  ['reviews', 3]
];

const PROVIDER_CHAPTERS: [id: string, shown: number][] = [
  ['services', 3],
  ['availability', 3],
  ['bookings', 3],
  ['provider-earnings', 3]
];

test.describe('seller center · every seller chapter finds its targets', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
    await resetTourProgress(request, baseURL ?? '');
  });

  for (const [id, shown] of SELLER_CHAPTERS) {
    test(`chapter "${id}" runs to the end with no missing target`, async ({ page }, testInfo) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const result = await runTour(page, id, { axe: { testInfo, page: `seller tour ${id}` } });
      expectNoDrift(result, shown);
    });
  }

  test('the Tour hub groups the chapters into journeys with their steps, and the Help menu links to it', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourHub(page);
    await expect(page.getByRole('heading', { level: 1, name: 'Tour hub' })).toBeVisible();
    for (const journey of ['Get set up', 'Products and stock', 'Orders and delivery', 'Money and growth']) await expect(page.getByRole('heading', { level: 2, name: journey })).toBeVisible();
    // a seller is not offered the provider journey, nor the application chapter
    await expect(page.getByRole('heading', { level: 2, name: 'Your services' })).toHaveCount(0);
    for (const [id] of SELLER_CHAPTERS) await expect(page.locator(`[data-tour-entry="${id}"]`)).toBeVisible();
    await expect(page.locator('[data-tour-entry="services"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="application"]')).toHaveCount(0);
    await expect(page.locator('[data-journey="setup"]')).toContainText('0 of 5 chapters done');
  });

  test('the Tour hub has no serious accessibility violations', async ({ page }, testInfo) => {
    await page.goto('/help/tours');
    await expect(page.locator('[data-tour="tour-hub"]')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller tour hub', include: '[data-tour="tour-hub"]' });
  });

  test('a fulfilment role is only offered the chapters its permissions reach', async ({ page, context, baseURL }) => {
    await actAs(context, baseURL ?? '', 'seller.fulfilment');
    await page.goto('/orders');
    await openTourHub(page);
    await expect(page.locator('[data-tour-entry="orders"]')).toBeVisible();
    await expect(page.locator('[data-tour-entry="shipping-setup"]')).toBeVisible();
    await expect(page.locator('[data-tour-entry="payouts"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="first-product"]')).toHaveCount(0);
    await expect(page.locator('[data-tour-entry="team"]')).toHaveCount(0);
  });
});

test.describe('seller center · provider chapters', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', PROVIDER_ORG);
    await resetTourProgress(request, baseURL ?? '');
  });

  for (const [id, shown] of PROVIDER_CHAPTERS) {
    test(`chapter "${id}" runs to the end with no missing target`, async ({ page }, testInfo) => {
      await page.goto('/');
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      const result = await runTour(page, id, { axe: { testInfo, page: `provider tour ${id}` } });
      expectNoDrift(result, shown);
    });
  }

  test('a provider is offered the service journey and not the seller-only chapters', async ({ page }) => {
    await page.goto('/');
    await openTourHub(page);
    await expect(page.getByRole('heading', { level: 2, name: 'Your services' })).toBeVisible();
    for (const [id] of PROVIDER_CHAPTERS) await expect(page.locator(`[data-tour-entry="${id}"]`)).toBeVisible();
    for (const seller of ['orders', 'shipping-setup', 'run-pickups', 'returns-disputes', 'coupons', 'reviews']) await expect(page.locator(`[data-tour-entry="${seller}"]`)).toHaveCount(0);
  });
});

test.describe('seller center · tour behaviour', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
    await resetTourProgress(request, baseURL ?? '');
  });

  test('keyboard: arrows move, Esc closes and gives focus back, Tab stays inside the popover', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await recordTourEvents(page);
    await openTourFromHelp(page, 'welcome');
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

  test('a "do it" chapter waits for the person\'s own click on the real control and never clicks for them', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'first-product');
    await expect(page).toHaveURL(/\/listings$/);
    await expect(popover(page)).toContainText('Start with Add a product');
    await expect(popover(page).getByRole('button', { name: 'Skip this step' })).toBeVisible();
    await expect(popover(page)).toContainText('Your turn');
    // nothing moves by itself
    await page.waitForTimeout(1_500);
    await expect(page).toHaveURL(/\/listings$/);
    await expect(popover(page)).toContainText('Start with Add a product');
    // the highlighted control is the real one: a click goes through and opens the form, and the tour moves on by itself
    await page.locator('[data-tour="listings-add"]').click();
    await expect(page).toHaveURL(/\/listings\/new$/);
    await expect(popover(page)).toContainText('Start with the barcode', { timeout: 15_000 });
    await page.keyboard.press('Escape');
  });

  test('"Skip this step" moves on without doing the thing', async ({ page }) => {
    await page.goto('/');
    await openTourFromHelp(page, 'first-product');
    await expect(popover(page)).toContainText('Start with Add a product');
    await popover(page).getByRole('button', { name: 'Skip this step' }).click();
    await expect(popover(page)).toContainText('Start with the barcode');
    await page.keyboard.press('Escape');
  });

  // Add a product opens on the barcode step. The form's own steps (category, photos ...) are optional in the chapters and are found once
  // the person has carried on to the full form, which only they can do: here the test does it with their clicks, the tour never does.
  const carryOnToTheForm = async (page: Page) => {
    await page.getByRole('button', { name: 'I don’t have a barcode' }).click();
    await page.getByRole('button', { name: 'Continue without a barcode' }).click();
    await expect(page.locator('[data-tour="product-form-basics"]')).toBeVisible({ timeout: 15_000 });
  };

  test('first-product: the barcode step, the offer-only branch in plain words, then the form steps once the person carries on', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    const result = await runTour(page, 'first-product', {
      acts: {
        add: { act: async (p) => { await p.locator('[data-tour="listings-add"]').click(); } },
        barcode: { act: async (p) => { await expect(popover(p)).toContainText('Start with the barcode'); }, then: 'next' },
        'offer-only': { act: async (p) => { await expect(popover(p)).toContainText('If the product already has a page'); await expect(popover(p)).toContainText('price, quantity and condition'); }, then: 'next' },
        'carry-on': { act: async (p) => { await carryOnToTheForm(p); }, then: 'next' }
      }
    });
    // add, barcode, offer-only, carry-on, then category, photos, what is missing and submit in the form: nothing missing.
    expectNoDrift(result, 8);
    expect(result.steps.map((s) => s.stepId)).toEqual(['add', 'barcode', 'offer-only', 'carry-on', 'category', 'photos', 'missing', 'submit']);
  });

  test('product-sections: opens on the barcode step and shows the form sections once the person carries on', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    const result = await runTour(page, 'product-sections', { acts: { 'carry-on': { act: async (p) => { await carryOnToTheForm(p); }, then: 'next' } } });
    expectNoDrift(result, 3);
    expect(result.steps.map((s) => s.stepId)).toEqual(['carry-on', 'basics', 'nav']);
  });

  test('progress is kept on the server: closing part way offers Resume, which starts at the last step', async ({ page, request, baseURL }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await openTourFromHelp(page, 'account-settings');
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 1 of 6');
    await popover(page).locator('.at-tour-next').click();
    await popover(page).locator('.at-tour-next').click();
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 3 of 6');
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'sc.account-settings')?.status, { timeout: 10_000 }).toBe('IN_PROGRESS');
    expect((await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'sc.account-settings')?.lastStep).toBe('profile');

    // the Help menu offers it, and so does the hub
    await helpMenu(page).click();
    await expect(page.locator('[data-tour-resume-entry="account-settings"]')).toBeVisible();
    await page.keyboard.press('Escape');
    await openTourHub(page);
    await expect(page.locator('[data-chapter="account-settings"]')).toContainText('In progress');
    await page.locator('[data-tour-resume="account-settings"]').click();
    await expect(popover(page)).toContainText('Your profile');
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 3 of 6');
    await page.keyboard.press('Escape');
  });

  test('a finished chapter shows as done in the hub, counts in its journey, and can always be replayed', async ({ page, request, baseURL }) => {
    await page.goto('/');
    const result = await runTour(page, 'help-support');
    expectNoDrift(result, 4);
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'sc.help-support')?.status, { timeout: 10_000 }).toBe('COMPLETED');
    await openTourHub(page);
    await expect(page.locator('[data-chapter="help-support"]')).toContainText('Done');
    await expect(page.locator('[data-journey="setup"]')).toContainText('1 of 5 chapters done');
    await expect(page.locator('[data-tour-entry="help-support"]')).toHaveText('Replay');
    await page.locator('[data-tour-entry="help-support"]').click();
    await expect(popover(page)).toBeVisible();
    await page.keyboard.press('Escape');
    // closing a replay does not turn "done" into anything else
    expect((await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'sc.help-support')?.status).toBe('COMPLETED');
  });

  test('skipping a journey and resetting my progress both reach the server', async ({ page, request, baseURL }) => {
    await page.goto('/help/tours');
    await expect(page.locator('[data-tour="tour-hub"]')).toBeVisible();
    await page.locator('[data-journey="fulfilment"]').getByRole('button', { name: 'Skip this journey' }).click();
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.filter((i) => i.status === 'DISMISSED').length, { timeout: 10_000 }).toBe(4);
    await expect(page.locator('[data-chapter="orders"]')).toContainText('Skipped');
    await page.getByRole('button', { name: 'Reset my progress' }).click();
    await page.getByRole('button', { name: 'Yes, reset' }).click();
    await expect(page.getByText('Your tour progress was reset.')).toBeVisible();
    expect((await serverTourProgress(request, baseURL ?? '')).items).toEqual([]);
    await expect(page.locator('[data-chapter="orders"]')).toContainText('Not started');
  });

  test('a first-time visit starts the welcome tour once, after the server was asked; Don\'t show again is kept on the server and follows the person to a new browser', async ({ page, browser, request, baseURL }) => {
    await page.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await page.goto('/');
    await expect(popover(page)).toBeVisible({ timeout: 15_000 });
    await expect(popover(page).locator('.at-tour-count')).toHaveText('Step 1 of 5');
    await page.getByLabel("Don't show again").check();
    await page.keyboard.press('Escape');
    await expect(popover(page)).toHaveCount(0);
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.find((i) => i.tourId === 'sc.welcome')?.status, { timeout: 10_000 }).toBe('DISMISSED');
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
    // the cache holds ids only: nothing that looks like an email or a name in a key
    const keys = await page.evaluate(() => Object.keys(window.localStorage).filter((k) => k.startsWith('at.tour.')));
    expect(keys.length).toBeGreaterThan(0);
    expect(keys.join(' ')).not.toMatch(/@/);

    // a different browser (nothing in its storage): the tour does not start again
    const other = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } });
    const fresh = await other.newPage();
    await fresh.addInitScript(() => window.localStorage.setItem('at.tour.autostart', 'force'));
    await fresh.goto('/');
    await expect(fresh.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await fresh.waitForTimeout(4_500);
    await expect(popover(fresh)).toHaveCount(0);
    await other.close();
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
    // the load is tried again by itself
    await expect(popover(page)).toBeVisible({ timeout: 30_000 });
    await page.keyboard.press('Escape');
  });

  test('what this browser kept before is copied to the server once, and does not start the tour again', async ({ page, request, baseURL }) => {
    const session = await (await request.get(`${baseURL}/api/bff/v1/seller/session`)).json();
    const userId = (session as { user: { id: string } }).user.id;
    await page.addInitScript(
      ([id]) => {
        window.localStorage.setItem('at.tour.autostart', 'force');
        window.localStorage.setItem(`at.tour.v1:${id}:welcome:1`, JSON.stringify({ outcome: 'never', at: '2026-01-01T00:00:00.000Z' }));
        window.localStorage.setItem(`at.tour.v1:${id}:orders:1`, JSON.stringify({ outcome: 'completed', at: '2026-01-01T00:00:00.000Z' }));
      },
      [userId]
    );
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await expect.poll(async () => (await serverTourProgress(request, baseURL ?? '')).items.map((i) => `${i.tourId}:${i.status}`).sort(), { timeout: 15_000 }).toEqual(['sc.orders:COMPLETED', 'sc.welcome:DISMISSED']);
    await page.waitForTimeout(4_500);
    await expect(popover(page)).toHaveCount(0);
    const left = await page.evaluate(() => Object.keys(window.localStorage).filter((k) => k.startsWith('at.tour.v1:')));
    expect(left).toEqual([]);
  });

  test('it does not start by itself on top of a form with unsaved text', async ({ page }) => {
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
