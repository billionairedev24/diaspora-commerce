import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { enrolAuthenticator } from '../support/totp';

/* Your Account on the storefront (mock BFF, demo shopper): the design's hub, Login & security with the authenticator
   QR enrolment, and notifications with marketing consent linked to Your Privacy Choices. */
test.describe('storefront · your account', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/signin?returnTo=%2Faccount');
    await expect(page).toHaveURL(/\/account$/);
  });

  test('hub tiles lead to every customer area @a11y', async ({ page }, testInfo) => {
    await expect(page.getByRole('heading', { level: 1, name: 'Your Account' })).toBeVisible();
    for (const name of ['Login & security', 'Your orders', 'Get help with an order', 'Your addresses', 'Your payments', 'Your appointments', 'Your profile', 'Notifications & privacy']) {
      await expect(page.getByRole('main').getByRole('link', { name: new RegExp(`^${name}`) })).toBeVisible();
    }
    await expect(page.getByRole('main').getByRole('button', { name: /^Ask Lola/ })).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'account hub' });
  });

  test('login & security: authenticator re-enrolled from the QR code, codes shown once', async ({ page }) => {
    await page.getByRole('main').getByRole('link', { name: /^Login & security/ }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Login & security' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Your Account' })).toBeVisible();
    const app = page.getByTestId('mfa-totp');
    if (await app.getByRole('button', { name: 'Turn off' }).isVisible()) {
      await app.getByRole('button', { name: 'Turn off' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Turn off' }).click();
    }
    await enrolAuthenticator(page, 'adaeze@example.com');
    await expect(page.getByRole('heading', { name: 'Linked sign-in accounts' })).toBeVisible();
  });

  test('notifications & privacy: marketing opt-in next to Your Privacy Choices', async ({ page }) => {
    await page.getByRole('main').getByRole('link', { name: /^Notifications & privacy/ }).click();
    const marketing = page.getByTestId('marketing');
    await expect(marketing.getByRole('link', { name: 'Your Privacy Choices' })).toHaveAttribute('href', '/legal/privacy-choices');
    const optIn = marketing.getByRole('switch', { name: 'Send me offers and news' });
    const was = await optIn.isChecked();
    await optIn.click();
    await expect(page.getByText('Notification settings saved')).toBeVisible();
    await expect(optIn).toBeChecked({ checked: !was });
  });
});
