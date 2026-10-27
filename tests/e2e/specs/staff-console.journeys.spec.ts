import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Staff console against the in-process mock BFF (default role: platform super admin). */

test.describe('staff console · a11y smoke @a11y', () => {
  for (const [name, path] of [
    ['dashboard', '/'],
    ['orders', '/orders'],
    ['moderation', '/moderation'],
    ['support', '/support'],
    ['people', '/users'],
    ['payouts', '/payouts'],
    ['fees', '/fees'],
    ['audit log', '/audit'],
    ['exports', '/exports'],
    ['site content', '/site-content']
  ] as const) {
    test(`staff ${name} has no serious axe violations`, async ({ page }, testInfo) => {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ${name}` });
    });
  }

  test('skip link moves focus to the main content', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to content' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeInViewport();
    await page.keyboard.press('Enter');
    await expect(page.locator('#main')).toBeFocused();
  });
});

/* Fee schedules: a Finance-style change through the editor (default role: super admin with payments:configure). The
   mock state lives in the app server process, so the journey tolerates versions left by an earlier run. */
test.describe('staff console · fee schedules', () => {
  test('creates a new fee schedule version with a category rate change and a seller override', async ({ page }) => {
    const run = Date.now().toString(36);
    const note = `E2E ${run}: fashion rate and Lagos Threads deal`;
    await page.goto('/fees');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Fees/);
    await expect(page.getByText('No commission, no fees (merchant of record)', { exact: false }).first()).toBeVisible();
    await page.getByRole('button', { name: 'New version' }).click();

    // Category rate change (percent input), different from whatever the base version has.
    const fashion = page.getByLabel('Commission for fashion (%)');
    const rate = (await fashion.inputValue()) === '12.50' ? '12.25' : '12.50';
    await fashion.fill(rate);

    // Seller override: AfroTransact Retail can't be chosen; Lagos Threads gets a new fashion rate.
    const existing = page.getByLabel('Commission for Lagos Threads (%)');
    const lagosRate = (await existing.count()) > 0 && (await existing.first().inputValue()) === '9.75' ? '9.50' : '9.75';
    const lagosOverride = page.getByRole('button', { name: 'Remove the override for Lagos Threads' });
    while ((await lagosOverride.count()) > 0) await lagosOverride.first().click();
    const search = page.getByPlaceholder('Search by name');
    await search.fill('retail');
    await expect(page.locator('[role="listbox"] [role="option"]', { hasText: 'AfroTransact Retail' })).toBeDisabled();
    await search.fill('Lagos');
    await page.locator('[role="listbox"] [role="option"]', { hasText: 'Lagos Threads' }).click();
    await page.getByLabel('Category for Lagos Threads').selectOption('fashion');
    await page.getByLabel('Commission for Lagos Threads (%)').fill(lagosRate);
    await page.getByLabel(/Note explaining the change/).fill(note);
    await page.getByRole('button', { name: 'Review changes' }).click();

    // Review: diff, $100 impact and who is affected.
    const changes = page.getByRole('table', { name: 'Changes' });
    await expect(changes.getByRole('row', { name: /Fashion commission/ })).toContainText(`${rate}%`);
    await expect(changes.getByRole('row', { name: /Lagos Threads · Fashion/ })).toContainText(`${lagosRate}%`);
    await expect(page.getByRole('table', { name: 'Commission impact' }).getByRole('row', { name: /^Fashion/ })).toContainText(`$${rate}`);
    await expect(page.getByRole('list', { name: 'Sellers affected' })).toContainText('Lagos Threads');
    await page.getByRole('button', { name: 'Publish version' }).click();

    const row = page.getByRole('table', { name: 'Fee schedule versions' }).getByRole('row', { name: new RegExp(`E2E ${run}`) });
    await expect(row).toContainText('Scheduled');
    await expect(row.getByRole('button', { name: /^Cancel v/ })).toBeVisible();
  });
});
