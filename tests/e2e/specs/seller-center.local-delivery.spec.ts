import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* AfroTransact pickup at a ship-from address (S255). Mock org state is process-wide, so the journey starts from
   whatever an earlier run left: it turns pickup off first when needed. */
test.describe.configure({ mode: 'default' });

test.describe('seller center · AfroTransact pickup (S255)', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('turns AfroTransact pickup on at an address with pickup hours', async ({ page }, testInfo) => {
    await page.goto('/orders/ship-from');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Ship-from addresses/);
    const katy = page.getByTestId('ship-from-address').filter({ hasText: 'Cinco Ranch' });
    await expect(katy.getByTestId('ship-from-position')).toContainText('29.7355, -95.7739');
    const toggle = katy.getByLabel(/AfroTransact picks up here/);
    if (await toggle.isChecked()) {
      await toggle.uncheck();
      await katy.getByRole('button', { name: 'Save pickup settings' }).click();
      await expect(page.getByText('AfroTransact pickup updated').first()).toBeVisible();
    }
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller ship-from' });

    await katy.getByLabel(/AfroTransact picks up here/).check();
    await expect(katy.getByTestId('pickup-window').first()).toBeVisible();
    await katy.getByLabel('Opens, window 1').fill('10:00');
    await katy.getByLabel('Closes, window 1').fill('16:00');
    await katy.getByRole('button', { name: 'Save pickup settings' }).click();
    await expect(page.getByText('AfroTransact pickup updated').first()).toBeVisible();

    await page.reload();
    const after = page.getByTestId('ship-from-address').filter({ hasText: 'Cinco Ranch' });
    await expect(after.getByLabel(/AfroTransact picks up here/)).toBeChecked();
    await expect(after.getByLabel('Opens, window 1')).toHaveValue('10:00');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller ship-from' });
  });
});
