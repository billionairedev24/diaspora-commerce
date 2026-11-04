import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Sign-out after inactivity in the staff console (mock BFF). The cookie `at_mock_session` = "idle:warn:absolute:tag" gives
   this browser its own short session (12 s idle, warning 8 s before the end), so the real timer runs in real time without
   touching the other specs' sessions. docs/engineering/session-timeouts.md. */
test.describe('staff console · sign-out after inactivity', () => {
  test.beforeEach(async ({ context, baseURL }, testInfo) => {
    const tag = `${testInfo.workerIndex}-${testInfo.retry}-${Date.now()}`;
    await context.addCookies([{ name: 'at_mock_session', value: `12:8:600:${tag}`, url: baseURL ?? 'http://localhost:3412' }]);
  });

  test('warns, "Stay signed in" keeps the session, and the next silence signs out with the reason @a11y', async ({ page }, testInfo) => {
    await page.goto('/');
    const warning = page.getByRole('dialog', { name: 'You’re about to be signed out' });
    await expect(warning).toBeVisible({ timeout: 15_000 });
    await expect(warning.getByTestId('session-countdown')).toHaveText(/^0:\d\d$/);
    await expect(warning.getByRole('button', { name: 'Stay signed in' })).toBeFocused();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff sign-out warning' });

    await warning.getByRole('button', { name: 'Stay signed in' }).click();
    await expect(warning).toBeHidden();
    await expect(page.getByRole('navigation', { name: 'Staff console' }).first()).toBeVisible();

    // No input from here on: the warning comes back and then the session ends, with the reason and nothing signed-in left.
    await expect(warning).toBeVisible({ timeout: 20_000 });
    const ended = page.getByRole('alertdialog', { name: 'You’ve been signed out' });
    await expect(ended).toBeVisible({ timeout: 20_000 });
    await expect(ended).toContainText('You were inactive for a while');
    await expect(page.getByRole('navigation', { name: 'Staff console' })).toHaveCount(0);
    await expect(ended.getByRole('button', { name: 'Sign in again' })).toBeEnabled();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff signed-out screen' });
  });

  test('a second tab keeps the first one alive, and signing out in one signs out both', async ({ context, page }) => {
    await page.goto('/');
    const other = await context.newPage();
    await other.goto('/');
    await expect(page.getByRole('navigation', { name: 'Staff console' }).first()).toBeVisible();
    // Work in the other tab only: this tab must not warn although it saw no input of its own.
    for (let i = 0; i < 6; i += 1) {
      await other.mouse.click(300, 300);
      await other.waitForTimeout(2_500);
    }
    await expect(page.getByRole('dialog', { name: 'You’re about to be signed out' })).toBeHidden();
    // Let both go quiet: whichever times out first signs the other out.
    await expect(page.getByRole('alertdialog', { name: 'You’ve been signed out' })).toBeVisible({ timeout: 40_000 });
    await expect(other.getByRole('alertdialog', { name: 'You’ve been signed out' })).toBeVisible({ timeout: 10_000 });
  });
});
