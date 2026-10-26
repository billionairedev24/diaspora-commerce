import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Back office › Pre-orders › Delivery runs (S260) against the in-process mock BFF (default role: platform super admin).
   Mock state lives in the app server process, so the journeys tolerate what an earlier run left behind: the run that was
   short of its minimum is decided once, and every run created here is cancelled again. */
test.describe('staff console · delivery runs', () => {
  test('schedules a run for a location, announces and opens it, then cancels it with a reason @a11y', async ({ page }, testInfo) => {
    await page.goto('/preorders/runs');
    const view = page.getByTestId('runs-view');
    await expect(view.getByRole('heading', { name: 'Delivery runs', level: 2 })).toBeVisible();
    await expect(view.getByRole('tab', { name: /^All/ })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff delivery runs' });

    // New run: the location brings its area; the break-even sits next to the minimum.
    await view.getByRole('button', { name: 'New run' }).click();
    const form = page.getByTestId('run-form');
    await expect(form).toBeVisible();
    await form.getByRole('combobox', { name: 'Location' }).selectOption({ label: 'Midland' });
    await expect(form.getByTestId('run-form-area')).toContainText('12 miles around Midland');
    await expect(form.getByTestId('run-break-even')).toContainText(/Break-even at \d+ orders/);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff new delivery run' });
    await form.getByLabel('Notes').fill('Bring the cold bags');
    await form.getByRole('button', { name: 'Create draft' }).click();

    // The run's sheet: a draft, with its details, then announced and open.
    const run = page.getByTestId('run-sheet');
    await expect(run.getByTestId('run-sheet-status')).toHaveText('Draft');
    await expect(run.getByTestId('run-details')).toContainText('Bring the cold bags');
    await expect(run.getByTestId('run-details')).toContainText('12 miles around Midland');
    await run.getByRole('button', { name: 'Announce' }).click();
    await expect(run.getByTestId('run-sheet-status')).toHaveText('Announced');
    await run.getByRole('button', { name: 'Open for orders' }).click();
    await expect(run.getByTestId('run-sheet-status')).toHaveText('Open');
    await expect(run.getByTestId('run-outlook')).toContainText('more orders needed');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff delivery run sheet' });

    // Cancelling needs a reason and refunds every order.
    await run.getByRole('button', { name: 'Cancel run' }).click();
    const dialog = page.getByRole('dialog', { name: 'Cancel this run?' });
    await dialog.getByRole('button', { name: 'Cancel run and refund' }).click();
    await expect(dialog.getByText('Enter a reason.')).toBeVisible();
    await dialog.getByRole('textbox', { name: 'Reason (customers are shown it)' }).fill('Tropical storm warning');
    await dialog.getByRole('button', { name: 'Cancel run and refund' }).click();
    await expect(run.getByTestId('run-sheet-status')).toHaveText('Cancelled');
    await expect(run.getByTestId('run-outlook')).toContainText('This run was cancelled');
    await expect(run.getByTestId('run-log-CANCEL')).toContainText('Reason: Tropical storm warning');
  });

  test('shows the run that is short of its minimum and confirms it below the minimum with a reason', async ({ page }) => {
    await page.goto('/preorders/runs');
    const panel = page.getByTestId('runs-need-decision');
    if (!(await panel.isVisible())) {
      test.info().annotations.push({ type: 'note', description: 'The short run was decided by an earlier journey on this server.' });
      return;
    }
    await expect(panel).toContainText('needs a decision');
    await panel.getByRole('button', { name: 'Review' }).first().click();
    const run = page.getByTestId('run-sheet');
    await expect(run.getByTestId('run-outlook')).toHaveAttribute('data-kind', 'AT_RISK');
    await expect(run.getByTestId('run-counts')).toContainText('5 of 12 orders');
    await run.getByRole('button', { name: 'Confirm below the minimum' }).click();
    const dialog = page.getByRole('dialog', { name: 'Confirm this run now?' });
    await dialog.getByRole('button', { name: 'Confirm run' }).click();
    await expect(dialog.getByText('Enter a reason.')).toBeVisible();
    await dialog.getByRole('textbox', { name: 'Why confirm below the minimum?' }).fill('The sellers already packed');
    await dialog.getByRole('button', { name: 'Confirm run' }).click();
    await expect(run.getByTestId('run-sheet-status')).toHaveText('Confirmed');
    await expect(run.getByTestId('run-log-CONFIRM_OVERRIDE')).toContainText('Reason: The sellers already packed');
    await expect(panel).toBeHidden();
  });

  test('is read-only for staff who cannot manage pre-orders', async ({ context, page, baseURL }) => {
    await context.addCookies([{ name: 'at_mock_staff_role', value: 'platform.finance', url: baseURL ?? 'http://localhost:3412' }]);
    await page.goto('/preorders/runs');
    await expect(page.getByRole('button', { name: 'New run' })).toBeDisabled();
    await page
      .getByRole('button', { name: /Open the Lubbock run/ })
      .first()
      .click();
    const run = page.getByTestId('run-sheet');
    await expect(run.getByTestId('run-read-only')).toContainText('Changing runs needs pre-order management access.');
    await expect(run.getByRole('button', { name: /Cancel run|Extend cutoff|Confirm/ })).toHaveCount(0);
    await expect(run.getByTestId('run-log')).toBeVisible();
  });
});
