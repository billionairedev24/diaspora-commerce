import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { enrolAuthenticator } from '../support/totp';

/* My account in Seller Center against the in-process mock BFF: the account menu stays in the back office, the
   authenticator is enrolled with a real TOTP from the setup key, alerts and preferences save. Mock state lives in the
   app server, so the journey resets what an earlier run turned on, and puts the shared display preferences back to
   their seeded values afterwards: later specs (billing, payouts…) read dates in the seeded "match my language" format. */
const SEEDED_PREFERENCES = { locale: null, timeZone: null, dateFormat: 'AUTO', numberFormat: 'AUTO', tableDensity: 'COMFORTABLE' };

test.describe('seller center · my account', () => {
  test.afterAll(async ({ request }, testInfo) => {
    const res = await request.put(`${String(testInfo.project.use.baseURL ?? '')}/api/bff/v1/account/preferences`, { data: SEEDED_PREFERENCES });
    expect(res.status(), await res.text()).toBe(200);
  });

  test('account menu opens My account inside Seller Center, not the storefront', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: /Account menu for/ }).click();
    await expect(page.getByRole('menuitem', { name: 'Open AfroTransact' })).toHaveAttribute('target', '_blank');
    await page.getByRole('menuitem', { name: 'My account' }).click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByRole('heading', { level: 1, name: 'My account' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Account settings' }).getByRole('link', { name: 'Profile' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByTestId('profile-email')).toContainText('@');
    await expect(page.getByTestId('organisations').getByTestId('membership')).toHaveCount(3);
    // No customer features in the back office.
    await expect(page.getByText(/addresses|payment methods|wishlist/i)).toHaveCount(0);
  });

  test('enrols an authenticator app from the QR / setup key with a real code @a11y', async ({ page }, testInfo) => {
    await page.goto('/account/security');
    await expect(page.getByTestId('two-step')).toBeVisible();
    const app = page.getByTestId('mfa-totp');
    if (await app.getByRole('button', { name: 'Turn off' }).isVisible()) {
      await app.getByRole('button', { name: 'Turn off' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Turn off' }).click();
      await expect(app.getByRole('button', { name: 'Set up' })).toBeVisible();
    }
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller sign-in & security' });
    await enrolAuthenticator(page, 'titilayo@adeskitchen.com');
    await expect(page.getByTestId('passkey').first()).toBeVisible();
    await expect(page.getByTestId('session').first()).toContainText('This device');
  });

  test('saves alert topics with quiet hours, and compact tables as the default', async ({ page }) => {
    await page.goto('/account/notifications');
    await expect(page.getByTestId('alert-seller.compliance')).toContainText('Always on');
    const sms = page.getByRole('switch', { name: 'Low stock by Text' });
    const was = await sms.isChecked();
    await sms.click();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByText('Notification settings saved')).toBeVisible();
    await page.reload();
    await expect(page.getByRole('switch', { name: 'Low stock by Text' })).toBeChecked({ checked: !was });

    await page.goto('/account/preferences');
    await page.getByRole('radio', { name: /Compact/ }).check();
    await page.getByRole('radio', { name: /Day, month, year/ }).check();
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByText('Preferences saved')).toBeVisible();
    expect(await page.evaluate(() => window.localStorage.getItem('at.table.defaultDensity'))).toBe('compact');

    // Back to the defaults (the mock is shared with every later spec in this run).
    await page.getByRole('group', { name: 'Date format' }).getByRole('radio', { name: /Match my language/ }).check();
    await page.getByRole('radio', { name: /Comfortable/ }).check();
    const saved = page.waitForResponse((r) => new URL(r.url()).pathname.endsWith('/v1/account/preferences') && r.request().method() === 'PUT');
    await page.getByRole('button', { name: 'Save changes' }).click();
    expect((await saved).status()).toBe(200);
    await page.reload();
    await expect(page.getByRole('group', { name: 'Date format' }).getByRole('radio', { name: /Match my language/ })).toBeChecked();
  });
});
