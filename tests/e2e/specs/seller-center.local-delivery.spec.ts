import { expect, type Locator, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* AfroTransact pickup at a ship-from address (S255). Mock org state is process-wide, so the journey starts from
   whatever an earlier run left: it turns pickup off first when needed. */
test.describe.configure({ mode: 'default' });

/** The AfroTransact (driver) pickup form of an address card; customer pickup has its own form beside it. */
const atp = (card: Locator) => card.getByRole('form', { name: 'AfroTransact pickup' });

test.describe('seller center · AfroTransact pickup (S255)', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
  });

  test('turns AfroTransact pickup on at an address with pickup hours', async ({ page }, testInfo) => {
    await page.goto('/orders/ship-from');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Ship-from addresses/);
    const katy = page.getByTestId('ship-from-address').filter({ hasText: 'Cinco Ranch' });
    await expect(katy.getByTestId('ship-from-position')).toContainText('29.7355, -95.7739');
    const toggle = atp(katy).getByLabel(/AfroTransact picks up here/);
    if (await toggle.isChecked()) {
      await toggle.uncheck();
      await atp(katy).getByRole('button', { name: 'Save pickup settings' }).click();
      await expect(page.getByText('AfroTransact pickup updated').first()).toBeVisible();
    }
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller ship-from' });

    await atp(katy).getByLabel(/AfroTransact picks up here/).check();
    await expect(atp(katy).getByTestId('pickup-window').first()).toBeVisible();
    await atp(katy).getByLabel('Opens, window 1').fill('10:00');
    await atp(katy).getByLabel('Closes, window 1').fill('16:00');
    await atp(katy).getByRole('button', { name: 'Save pickup settings' }).click();
    await expect(page.getByText('AfroTransact pickup updated').first()).toBeVisible();

    await page.reload();
    const after = page.getByTestId('ship-from-address').filter({ hasText: 'Cinco Ranch' });
    await expect(atp(after).getByLabel(/AfroTransact picks up here/)).toBeChecked();
    await expect(atp(after).getByLabel('Opens, window 1')).toHaveValue('10:00');
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'seller ship-from' });
  });
});
