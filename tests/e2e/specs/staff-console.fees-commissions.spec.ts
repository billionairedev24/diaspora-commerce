import { expect, test } from '@playwright/test';

/* Back office › Fees & commissions: product commission and service connection-fee matrices (category × plan), the
   negotiated rates table (create / end with a reason) and the rate calculator. Runs against the in-process mock BFF;
   the journey creates its own negotiated rate and ends it again, so it can run repeatedly on the same server.
   SHOTS (optional) saves screenshots. */
const shot = (name: string) => (process.env.SHOTS ? { path: `${process.env.SHOTS}/${name}.png`, fullPage: true } : undefined);

test.describe('staff console · fees & commissions', () => {
  test('commission and connection-fee matrices show the plan rates', async ({ page }) => {
    await page.goto('/fees?tab=commission');
    const commission = page.getByRole('table', { name: 'Commission by category and plan' });
    await expect(commission.getByRole('columnheader', { name: /Seller Essentials/ })).toContainText('15%');
    await expect(commission.getByRole('columnheader', { name: /Seller Growth/ })).toContainText('8–12%');
    await expect(commission.getByRole('columnheader', { name: /Seller Pro/ })).toContainText('6–10%');
    await expect(page.getByText(/First match wins/)).toBeVisible();
    const s = shot('fees-commission-matrix');
    if (s) await page.screenshot(s);

    await page.getByRole('tab', { name: 'Service connection fees' }).click();
    await expect(page).toHaveURL(/tab=connection-fees/);
    const connection = page.getByRole('table', { name: 'Connection fees by service category and plan' });
    await expect(connection.getByRole('columnheader', { name: /Provider Solo/ })).toContainText('10%');
    await expect(connection.getByRole('columnheader', { name: /Provider Studio/ })).toContainText('8%');
    await expect(connection.getByRole('columnheader', { name: /Provider Business/ })).toContainText('6%');
  });

  test('negotiate a connection fee, see it in the calculator, then end it with a reason', async ({ page }) => {
    await page.goto('/fees?tab=negotiated');
    const table = page.getByRole('table', { name: 'Negotiated rates' });
    await expect(table).toContainText('Wholesale partner');

    await page.getByRole('button', { name: 'Negotiate a rate' }).click();
    const dialog = page.getByRole('dialog', { name: 'Negotiate a rate' });
    await dialog.getByRole('searchbox').fill('lagos');
    await dialog.getByRole('button', { name: /Lagos Threads/ }).click();
    await dialog.getByLabel('Type').selectOption('COMMISSION');
    await dialog.getByLabel('Category (empty = all categories)').fill('fashion');
    await dialog.getByLabel('Rate (%)').fill('7.25');
    await dialog.getByRole('button', { name: 'Negotiate' }).click();
    await expect(dialog.getByText(/Say why/)).toBeVisible();
    await dialog.getByLabel('Reason (kept in the audit trail)').fill('E2E: matching a competitor offer');
    await dialog.getByRole('button', { name: 'Negotiate' }).click();
    await expect(page.getByText('7.25% negotiated with Lagos Threads')).toBeVisible();
    const row = table.getByRole('row', { name: /E2E: matching a competitor offer/ });
    await expect(row).toContainText('Active');

    await page.getByRole('tab', { name: 'Rate calculator' }).click();
    await page.getByRole('searchbox').fill('lagos');
    await page.getByRole('button', { name: /Lagos Threads/ }).click();
    await page.getByLabel('Category', { exact: true }).fill('fashion');
    await page.getByRole('button', { name: 'Calculate' }).click();
    await expect(page.getByTestId('rate-quote')).toContainText('7.25%');
    await expect(page.getByTestId('rate-quote')).toContainText(/Negotiated/i);

    await page.getByRole('tab', { name: 'Negotiated rates' }).click();
    await table.getByRole('row', { name: /E2E: matching a competitor offer/ }).getByRole('button', { name: 'End' }).click();
    const end = page.getByRole('dialog', { name: 'End the negotiated rate' });
    await end.getByLabel(/Why it ends/).fill('E2E clean-up');
    await end.getByRole('button', { name: 'End now' }).click();
    await expect(page.getByText('Negotiated rate of Lagos Threads ended')).toBeVisible();
    await expect(table.getByRole('row', { name: /E2E: matching a competitor offer/ })).toContainText('Ended');
  });
});
