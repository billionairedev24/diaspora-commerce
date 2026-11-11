import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { enrolAuthenticator } from '../support/totp';

/* My account in the staff console (mock BFF, Super Admin): two-step verification is required, the authenticator is
   enrolled with a real code, staff can switch methods but never turn the last one off. */
test.describe('staff console · my account', () => {
  test('staff switch two-step methods but cannot turn it off @a11y', async ({ page }, testInfo) => {
    await page.goto('/account/security');
    const card = page.getByTestId('two-step');
    await expect(card).toContainText('Required');
    await expect(card).toContainText('required for your account');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff sign-in & security' });

    const app = page.getByTestId('mfa-totp');
    const email = page.getByTestId('mfa-email');
    await expect(page.getByTestId('mfa-sms')).toHaveCount(0); // SMS is not offered while its flag is off
    if (await app.getByRole('button', { name: 'Set up' }).isVisible()) await enrolAuthenticator(page, 'ifeoma@afrotransact.com');
    if (await email.getByRole('button', { name: 'Set up' }).isVisible()) {
      await email.getByRole('button', { name: 'Set up' }).click();
      await page.getByRole('button', { name: 'Email me a code' }).click();
      await page.getByLabel('6-digit code').fill('246810');
      await page.getByRole('button', { name: 'Verify and turn on' }).click();
      await expect(email.getByText('On', { exact: true })).toBeVisible();
    }
    // Both on: either may go (the password is asked again). Turn email codes off → the authenticator is the last method and stays.
    await email.getByRole('button', { name: 'Turn off' }).click();
    const confirm = page.getByRole('dialog');
    await confirm.getByLabel('Password').fill('Plantain-Jollof-2026');
    await confirm.getByRole('button', { name: 'Turn off' }).click();
    await expect(email.getByRole('button', { name: 'Set up' })).toBeVisible();
    await expect(app.getByRole('button', { name: 'Turn off' })).toBeDisabled();
  });

  test('profile shows the managed work email and platform roles; notifications show staff alerts', async ({ page }) => {
    await page.goto('/account');
    await expect(page.getByTestId('profile-email')).toHaveText(/ifeoma@afrotransact\.com/);
    await expect(page.getByText('Managed by AfroTransact IT. Ask IT to change it.')).toBeVisible();
    await expect(page.getByTestId('staff-roles')).toContainText('Super Admin');
    await page.getByRole('navigation', { name: 'Account settings' }).getByRole('link', { name: 'Notifications' }).click();
    await expect(page.getByTestId('alert-staff.assigned')).toContainText('Queues assigned to me');
    await expect(page.getByTestId('alert-staff.escalations')).toContainText('Always on');
    await expect(page.getByTestId('alert-seller.orders')).toHaveCount(0);
  });
});
