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

  test('hub is flat grouped link lists with live facts, not tiles', async ({ page }) => {
    const main = page.getByRole('main');
    for (const group of ['Orders & appointments', 'Login & security', 'Payments & credit', 'Addresses & delivery', 'Membership & rewards', 'Notifications & privacy', 'Help']) {
      await expect(main.getByRole('heading', { level: 2, name: group })).toBeVisible();
    }
    await expect(main.getByRole('link', { name: 'Gift cards & credit' })).toHaveAttribute('href', '/account/credit');
    await expect(main.getByRole('link', { name: 'Refer a friend' })).toHaveAttribute('href', '/account/refer');
    await expect(main.getByTestId('hub-credit')).toContainText(/Balance \$/);
    await expect(main.getByTestId('hub-addresses')).toContainText(/Default: /);
    await expect(main.getByTestId('hub-payments')).toContainText(/Default: /);
    // No bordered panels: the hub's link entries have no border.
    const border = await main.getByTestId('hub-credit').evaluate((el) => getComputedStyle(el).borderTopWidth);
    expect(border).toBe('0px');
  });

  test('the account menu, the hub and the footer all reach Gift cards & credit', async ({ page }) => {
    await page.getByRole('button', { name: /Account menu/ }).first().click();
    await page.getByRole('menuitem', { name: 'Gift cards & credit' }).click();
    await expect(page).toHaveURL(/\/account\/credit$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Gift cards & credit' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Your account');
    await expect(page.getByRole('contentinfo').getByRole('link', { name: 'Gift cards & credit' })).toHaveAttribute('href', '/account/credit');
  });

  test('gift cards & credit: balance, inline redeem with code grouping, buy link and the activity table @a11y', async ({ page }, testInfo) => {
    await page.goto('/account/credit');
    await expect(page.getByRole('heading', { level: 1, name: 'Gift cards & credit' })).toBeVisible();
    await expect(page.getByTestId('credit-balance')).toContainText('$');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'gift cards & credit' });
    await expect(page.getByText(/never expires\. It can’t be withdrawn, refunded to a card or sent to anyone else\./)).toBeVisible();

    const code = page.getByLabel('Gift card code');
    const redeem = page.getByRole('button', { name: 'Redeem' });
    await expect(code).toHaveAttribute('placeholder', 'XXXX-XXXX-XXXX-XXXX');
    await expect(redeem).toBeDisabled();
    await code.fill('abcd efgh');
    await expect(code).toHaveValue('ABCD-EFGH');
    await expect(redeem).toBeDisabled();
    await code.fill('abcd efgh jklm 2345');
    await expect(code).toHaveValue('ABCD-EFGH-JKLM-2345');
    await expect(redeem).toBeEnabled();
    await redeem.click();
    // The demo card redeems once per server run; either answer is shown inline, next to the field.
    await expect(page.getByText(/added to your credit|already redeemed/).first()).toBeVisible();

    await expect(page.getByRole('link', { name: 'Buy a gift card' })).toHaveAttribute('href', '/gift-cards');
    const table = page.getByRole('table', { name: 'Credit activity' });
    await expect(table.getByRole('columnheader')).toHaveText(['Date', 'Description', 'Amount', 'Balance']);
    await expect(table.getByTestId('credit-entry').first()).toBeVisible();
    await page.getByTestId('credit-filter-type').selectOption('used');
    for (const amount of await table.getByTestId('credit-entry').locator('td:nth-child(3)').allTextContents()) expect(amount.startsWith('−')).toBe(true);
  });

  test('buy a gift card: presets, recipient, delivery choice, live email preview, terms link', async ({ page }, testInfo) => {
    await page.goto('/gift-cards');
    await expect(page.getByRole('heading', { level: 1, name: 'Buy a gift card' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Gift cards & credit');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'buy a gift card' });
    const preview = page.getByTestId('gift-preview');
    await page.getByRole('button', { name: '$25.00' }).click();
    await expect(preview).toContainText('$25.00');
    await page.getByLabel('Recipient’s name (optional)').fill('Ama');
    await page.getByLabel('Recipient’s email').fill('ama@example.com');
    await page.getByLabel('From (optional)').fill('Adaeze');
    await page.getByLabel('Message (optional)').fill('Happy birthday');
    await expect(preview).toContainText('Hi Ama,');
    await expect(preview).toContainText('ama@example.com');
    await expect(preview).toContainText('Adaeze sent you an AfroTransact gift card');
    await expect(preview).toContainText('Happy birthday');
    await expect(preview).toContainText('Sent as soon as the payment goes through.');
    await page.getByLabel('Send on a date').check();
    await expect(page.getByLabel('Delivery date')).toBeVisible();
    await expect(page.getByTestId('gift-terms-link')).toHaveAttribute('href', '/legal/gift-card-terms');
    await expect(page.getByText('Credit never converts to cash.').first()).toBeVisible();
  });

  test('membership: plan radio rows, renewal and cancellation disclosure before the button, terms link', async ({ page }) => {
    await page.goto('/membership');
    await expect(page.getByRole('heading', { level: 1, name: 'AfroTransact Membership' })).toBeVisible();
    const monthly = page.getByTestId('membership-interval-MONTHLY');
    const yearly = page.getByTestId('membership-interval-YEARLY');
    await expect(monthly).toBeChecked();
    await yearly.check();
    const plus = page.getByTestId('membership-tier-plus');
    await expect(plus).toContainText(/Save \$\d+\.\d\d a year/);
    await expect(plus).toContainText(/renews automatically until you cancel/);
    await expect(plus).toContainText(/Cancel any time in Your account/);
    await expect(plus.getByRole('link', { name: 'Membership terms and refund rule' })).toHaveAttribute('href', '/legal/membership-terms');
    await expect(plus.getByRole('listitem').first()).toBeVisible();
  });

  test('membership: manage view shows the plan, the refund rule and one cancel path, or the way in', async ({ page }) => {
    await page.goto('/account/membership');
    await expect(page.getByRole('heading', { level: 1, name: 'Your membership' })).toBeVisible();
    if (await page.getByTestId('membership-none').isVisible()) {
      await expect(page.getByRole('link', { name: 'AfroTransact Membership' })).toHaveAttribute('href', '/membership');
      return;
    }
    await expect(page.getByTestId('membership-plan')).toContainText('Status');
    await expect(page.getByTestId('membership-plan')).toContainText('Payment method');
    await expect(page.getByRole('heading', { level: 2, name: 'Cancel membership' })).toBeVisible();
  });

  test('refer a friend, payment methods, addresses and help list share one flat page language', async ({ page }) => {
    await page.goto('/account/refer');
    await expect(page.getByRole('heading', { level: 1, name: 'Refer a friend' })).toBeVisible();
    await expect(page.getByTestId('refer-code')).toBeVisible();
    await expect(page.getByRole('table', { name: 'Friends you invited' })).toBeVisible();

    await page.goto('/account/payments');
    await expect(page.getByRole('heading', { level: 1, name: 'Your Payments' })).toBeVisible();
    await expect(page.getByTestId('payment-method').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add a payment method' })).toBeVisible();

    await page.goto('/account/addresses');
    await expect(page.getByRole('heading', { level: 1, name: 'Your Addresses' })).toBeVisible();
    await expect(page.getByTestId('address-card').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add address' })).toBeVisible();

    await page.goto('/account/help');
    await expect(page.getByRole('heading', { level: 1, name: 'Get help with an order' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Your account');
    // Every page of the area is flat: no element with a bordered card box and a radius.
    for (const path of ['/account/refer', '/account/payments', '/account/addresses', '/account/help', '/account/credit', '/gift-cards']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const boxed = await page.getByRole('main').evaluate((main) =>
        [...main.querySelectorAll('section, div, li')].filter((el) => {
          const c = getComputedStyle(el);
          return c.borderTopWidth !== '0px' && c.borderBottomWidth !== '0px' && c.borderLeftWidth !== '0px' && c.borderRightWidth !== '0px' && c.borderTopStyle === 'solid' && parseFloat(c.borderTopLeftRadius) > 0 && el.children.length > 1;
        }).length
      );
      expect(boxed, `${path} has boxed panels`).toBe(0);
    }
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
