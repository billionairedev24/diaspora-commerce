import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { controlsOf, expectOneLine, expectSameColumn, noSidewaysScroll, SIZES, shotter } from '../support/layout';

/* Settings > Roles and permissions > Module access against the in-process mock BFF (Super Admin): modules by name down, roles by name
   across, a level in every cell. A change needs a confirmation and a reason; Super Admin cannot be lowered below Edit on Users & roles.
   Measured in the browser at 1280 and 390: the filter row shares one height and centre line, every cell of a row sits on one centre
   line, a role's column has one left and right edge, the page never scrolls sideways (the grid scrolls inside its own region). */
test.describe.configure({ mode: 'default' });

for (const size of SIZES) {
  test.describe(`staff console · module access at ${size.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
    });

    test(`the matrix lines up and is axe clean @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/access?view=modules');
      await expect(page.getByRole('tab', { name: 'Module access', selected: true })).toBeVisible();
      const table = page.getByTestId('matrix-table');
      await expect(table.getByRole('rowheader', { name: 'Payouts & ledger' })).toBeVisible();
      await expect(table).not.toContainText(/platform\.|\brbac\b/);
      const wide = size.name === '1280';
      if (wide) await expect(table.getByRole('columnheader', { name: 'Finance' })).toBeVisible();
      else await expect(table.getByRole('columnheader')).toHaveCount(2); // a phone shows one role at a time (the module column and that role)

      // the filter row: one height; one centre line when wide
      await expectOneLine(page.getByTestId('matrix-filters'), 'filters', wide);
      // every cell of a row shares one height and one centre line (the row is wider than the screen: the grid scrolls inside its region)
      const row = table.getByTestId('matrix-row-payouts');
      const boxes = await controlsOf(row);
      expect(boxes.length).toBeGreaterThan(wide ? 10 : 0);
      expect(Math.max(...boxes.map((b) => b.cy)) - Math.min(...boxes.map((b) => b.cy)), `row centres ${JSON.stringify(boxes)}`).toBeLessThanOrEqual(1);
      expect(Math.max(...boxes.map((b) => b.h)) - Math.min(...boxes.map((b) => b.h)), `row heights`).toBeLessThanOrEqual(1);
      // a role's column has one left and one right edge from the first module to the last
      await expectSameColumn(table, 'Super Admin column', 'select[data-testid$="-platform.super_admin"]');
      if (wide) await expectSameColumn(table, 'Finance column', 'select[data-testid$="-platform.finance"]');
      // module names share one left edge
      const edges = await table.locator('tbody th[scope="row"]').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().left)));
      expect(new Set(edges).size, `module name edges ${edges.join(',')}`).toBe(1);
      await noSidewaysScroll(page, 'module access');
      await shot(`staff-modules-${size.name}`, 1000);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff module access ${size.name}` });
    });

    test(`a change needs a reason, and Super Admin keeps Edit on Users & roles @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/access?view=modules');
      const table = page.getByTestId('matrix-table');
      const cell = table.getByLabel('Users & roles for Super Admin');
      await expect(cell.locator('option', { hasText: 'No access' })).toBeDisabled();
      await expect(cell.locator('option', { hasText: 'View' })).toBeDisabled();

      const orders = table.getByLabel('Orders for Super Admin');
      await orders.selectOption('view');
      const dialog = page.getByRole('dialog', { name: /Change Super Admin's access to Orders/ });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Change access' })).toBeDisabled();
      await expectOneLine(dialog.locator('form > div:last-child'), 'dialog actions', true);
      await noSidewaysScroll(page, 'confirm dialog');
      await shot(`staff-modules-confirm-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff module access confirm ${size.name}` });
      await dialog.getByLabel(/^Why/).fill('Orders are read-only for this test');
      await dialog.getByRole('button', { name: 'Change access' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(table.getByLabel('Orders for Super Admin')).toHaveValue('view');
      // put it back
      await table.getByLabel('Orders for Super Admin').selectOption('full');
      await page.getByRole('dialog').getByLabel(/^Why/).fill('Restoring full access');
      await page.getByRole('dialog').getByRole('button', { name: 'Change access' }).click();
      await expect(table.getByLabel('Orders for Super Admin')).toHaveValue('full');
    });
  });
}
