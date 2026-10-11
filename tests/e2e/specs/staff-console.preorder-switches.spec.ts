import { expect, test } from '@playwright/test';

/* Back office › Pre-orders: each pre-order has its own on/off switch (the master switch is a separate global kill
   switch that never rewrites them). Pre-order areas are edited in the location tree (staff-console.locations.spec.ts).
   Runs against the in-process mock BFF;
   the journey puts everything back so it can run again on the same server. SHOTS (optional) saves screenshots. */
const shot = (name: string) => (process.env.SHOTS ? { path: `${process.env.SHOTS}/${name}.png`, fullPage: true } : undefined);

test.describe('staff console · pre-order switches and coverage', () => {
  test('switching one pre-order off leaves the others on', async ({ page }) => {
    await page.goto('/preorders');
    const table = page.getByRole('table', { name: 'Pre-orders' });
    const row = (name: string) => table.getByRole('row', { name: new RegExp(name) });
    const lubbock = row('Lubbock Pre-order #7');
    const elPaso = row('El Paso Pre-order #3');
    const lubbockSwitch = lubbock.getByRole('switch', { name: 'Switch Lubbock Pre-order #7 on or off' });
    await expect(lubbockSwitch).toBeVisible();
    if ((await lubbockSwitch.getAttribute('aria-checked')) === 'false') {
      await lubbockSwitch.click();
      await expect(lubbockSwitch).toHaveAttribute('aria-checked', 'true');
    }
    await expect(elPaso.getByTestId('window-switch')).toHaveAttribute('data-state', 'ON');

    await lubbockSwitch.click();
    await expect(lubbockSwitch).toHaveAttribute('aria-checked', 'false');
    await expect(lubbock.getByTestId('window-switch')).toHaveAttribute('data-state', 'PAUSED');
    await expect(lubbock).toContainText('Paused');
    await expect(elPaso.getByTestId('window-switch')).toHaveAttribute('data-state', 'ON');
    await expect(elPaso.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
    await expect(row('Greater Toronto Pre-order #1').getByRole('switch')).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByRole('button', { name: /Pre-order on/ })).toHaveAttribute('aria-pressed', 'true');
    const s = shot('mock-list-lubbock-off');
    if (s) await page.screenshot(s);

    // The detail page agrees, and resumes it.
    await table.getByRole('link', { name: 'Lubbock Pre-order #7' }).click();
    await expect(page.getByTestId('detail-status')).toHaveText('Paused');
    await page.getByRole('switch', { name: 'Switch Lubbock Pre-order #7 on or off' }).click();
    await expect(page.getByTestId('detail-status')).toHaveText('Open');
  });
});
