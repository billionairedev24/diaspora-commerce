import { expect, type Page, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { actAs, PROVIDER_ORG, SELLER_ORG, setActiveOrg } from '../support/seller';

const sidebar = (page: Page) => page.getByRole('navigation', { name: 'Back office' });

/** A sidebar entry by its label; entries may carry a count badge ("Orders 16"). */
const navLink = (page: Page, label: string) =>
  sidebar(page).getByRole('link', { name: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}( \\d+)?$`) });

/** Expands every collapsed sidebar group (not the organisation switcher) so all role-visible entries can be asserted. */
async function expandAllGroups(page: Page) {
  const collapsed = sidebar(page).locator('button[aria-controls^="nav-"][aria-expanded="false"]');
  while ((await collapsed.count()) > 0) await collapsed.first().click();
}

// Mock org state is process-wide: tests in this file run in order (the project is not fullyParallel), but one
// failure does not skip the rest.
test.describe.configure({ mode: 'default' });

test.describe('seller center · shell navigation by role', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('owner sees every Seller Center module and can navigate between them', async ({ page }) => {
    await page.goto('/');
    const nav = sidebar(page);
    await expect(nav).toBeVisible();
    await expect(nav).toContainText('Owner');
    await expandAllGroups(page);
    for (const label of ['Home', 'Orders', 'Listings', 'Inventory & FBA', 'Storefront & theme', 'Payouts', 'Plan & billing', 'Account health', 'Team & roles']) {
      await expect(navLink(page, label)).toBeVisible();
    }
    await navLink(page, 'Orders').click();
    await expect(page).toHaveURL(/\/orders$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Orders' })).toBeVisible();
    await expect(navLink(page, 'Orders')).toHaveAttribute('aria-current', 'page');

    await navLink(page, 'Team & roles').click();
    await expect(page).toHaveURL(/\/team$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Team & roles' })).toBeVisible();
  });

  test('fulfilment role only sees orders/inventory work and is refused money pages', async ({ page, context, baseURL }) => {
    await actAs(context, baseURL ?? '', 'seller.fulfilment');
    await page.goto('/orders');
    const nav = sidebar(page);
    await expect(nav).toContainText('Fulfilment');
    await expandAllGroups(page);
    for (const label of ['Orders', 'Inventory & FBA']) await expect(navLink(page, label)).toBeVisible();
    for (const label of ['Payouts', 'Plan & billing', 'Team & roles', 'Storefront & theme', 'Account health']) {
      await expect(navLink(page, label)).toHaveCount(0);
    }
    // Deep link into a module the role cannot open → access notice, not the page.
    await page.goto('/payouts');
    await expect(page.getByRole('main')).toContainText(/access|permission|can’t|can't/i);
    await expect(page.getByRole('heading', { level: 1, name: 'Payouts' })).toHaveCount(0);
  });

  test('finance role sees money modules but not listings or orders editing', async ({ page, context, baseURL }) => {
    await actAs(context, baseURL ?? '', 'seller.finance');
    await page.goto('/payouts');
    await expect(page.getByRole('heading', { level: 1, name: 'Payouts' })).toBeVisible();
    await expandAllGroups(page);
    for (const label of ['Payouts', 'Plan & billing', 'Account health']) await expect(navLink(page, label)).toBeVisible();
    for (const label of ['Listings', 'Inventory & FBA', 'Team & roles']) await expect(navLink(page, label)).toHaveCount(0);
  });

  test('seller home has no serious axe violations @a11y', async ({ page }, testInfo) => {
    await page.goto('/');
    await expect(sidebar(page)).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller home' });
  });

  for (const path of ['/orders', '/listings', '/listings/new', '/inventory', '/storefront', '/payouts', '/billing', '/account-health', '/team', '/onboarding']) {
    test(`seller ${path} has no serious axe violations @a11y`, async ({ page }, testInfo) => {
      await page.goto(path);
      await expect(sidebar(page)).toBeVisible();
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await expectNoSeriousA11yViolations(page, testInfo, { page: `seller ${path}` });
    });
  }
});

test.describe('provider hub · calendar', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', PROVIDER_ORG);
  });
  test.afterAll(async ({ request }, testInfo) => {
    // Leave the shared mock in its seeded state (the Seller Center org active).
    await setActiveOrg(request, String(testInfo.project.use.baseURL ?? ''), SELLER_ORG);
  });

  test('calendar renders the week grid and switches to month', async ({ page }) => {
    await page.goto('/calendar');
    await expect(page.getByRole('heading', { level: 1, name: 'Calendar' })).toBeVisible();
    await expect(navLink(page, 'Calendar')).toHaveAttribute('aria-current', 'page');
    const week = page.getByRole('grid', { name: 'Week calendar' });
    await expect(week).toBeVisible();
    // 7 day headers plus the time column (S194: complete grid > row > cell structure).
    await expect(week.getByRole('columnheader')).toHaveCount(8);
    await expect(week.getByRole('row')).toHaveCount(2);
    await page.getByRole('radio', { name: 'Month' }).or(page.getByRole('button', { name: 'Month' })).first().click();
    const month = page.getByRole('grid', { name: 'Month calendar' });
    await expect(month).toBeVisible();
    await expect(month.getByRole('gridcell')).toHaveCount(42);
    await expect(month.getByRole('row')).toHaveCount(7);
  });

  test('provider calendar has no serious axe violations @a11y', async ({ page }, testInfo) => {
    await page.goto('/calendar');
    await expect(page.getByRole('grid', { name: 'Week calendar' })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'provider calendar' });
    await page.getByRole('radio', { name: 'Month' }).or(page.getByRole('button', { name: 'Month' })).first().click();
    await expect(page.getByRole('grid', { name: 'Month calendar' })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'provider calendar' });
  });

  for (const path of ['/requests', '/hours', '/services', '/policy', '/public-page', '/earnings']) {
    test(`provider ${path} has no serious axe violations @a11y`, async ({ page }, testInfo) => {
      await page.goto(path);
      await expect(sidebar(page)).toBeVisible();
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await expectNoSeriousA11yViolations(page, testInfo, { page: `provider ${path}` });
    });
  }
});
