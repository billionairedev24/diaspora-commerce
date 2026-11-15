import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { expectOneLine, expectSameColumn, noSidewaysScroll, SIZES, shotter } from '../support/layout';

/* Staff invitations against the in-process mock BFF (Super Admin). Settings > Staff accounts lists who was invited and has not chosen a
   password yet (names and emails, never ids or tokens), invites a person, sends a link again and revokes with a reason. The invitation
   page /invite is standalone: no staff navigation, the token is read from the address fragment and removed from the bar, and the
   invitee chooses their own password. Controls are measured at 1280 and 390. */
test.describe.configure({ mode: 'default' });

for (const size of SIZES) {
  test.describe(`staff console · invitations at ${size.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
    });

    test(`waiting invitations by name and email, with actions that line up @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/staff');
      await expect(page.getByRole('heading', { level: 1, name: 'Staff accounts' })).toBeVisible();
      const panel = page.getByTestId('invitations');
      await expect(panel.getByRole('heading', { name: 'Invitations waiting' })).toBeVisible();
      const table = page.getByRole('table', { name: 'Invitations waiting' });
      await expect(table.getByText('Tunde Bakare').first()).toBeVisible();
      await expect(table.getByText('tunde@afrotransact.com').first()).toBeVisible();
      await expect(panel).not.toContainText(/mock-token|0b0b0b0b/);
      // the title row: heading block and the Invite button
      await expect(panel.getByTestId('invite-open')).toBeVisible();
      // every visible row's two actions share one height and one centre line
      const row = table.getByRole('row').filter({ hasText: 'Tunde Bakare' }).first();
      await expectOneLine(row, 'Tunde Bakare actions', true);
      await noSidewaysScroll(page, 'staff accounts');
      await shot(`staff-invitations-list-${size.name}`, 1100);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff accounts invitations ${size.name}` });
    });

    test(`invite a person: aligned fields, a work-domain error on the field, then the new row @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/staff');
      await page.getByTestId('invite-open').click();
      const dialog = page.getByRole('dialog', { name: 'Invite a person to be staff' });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Send invitation' })).toBeDisabled();
      await dialog.getByLabel(/^First name/).fill('Ada');
      await dialog.getByLabel(/^Last name/).fill(`Obi ${size.name}`);
      await dialog.getByLabel(/^Work email/).fill(`ada${size.name}@gmail.com`);
      await dialog.getByLabel(/^Role/).selectOption('platform.customer_support');
      await dialog.getByLabel(/^Why/).fill('Joins support in the Lagos office');
      // fields share their left and right edges (the two name fields sit side by side wide, stacked on a phone)
      await expectSameColumn(dialog.locator('form'), 'invite fields', 'input[type="email"], select, textarea');
      const buttons = dialog.getByRole('button');
      await expectOneLine(dialog.locator('form > div:last-child'), 'dialog actions', true);
      await noSidewaysScroll(page, 'invite dialog');
      await shot(`staff-invitations-invite-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff invite dialog ${size.name}` });
      await buttons.getByText('Send invitation').click();
      await expect(dialog.getByText(/afrotransact.com address/)).toBeVisible();
      await dialog.getByLabel(/^Work email/).fill(`ada${size.name}@afrotransact.com`);
      await dialog.getByRole('button', { name: 'Send invitation' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(page.getByRole('table', { name: 'Invitations waiting' }).getByText(`Ada Obi ${size.name}`).first()).toBeVisible();
    });

    test(`the invitation page is standalone: who it is for, own password, and the token leaves the address bar @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      const token = size.name === '1280' ? 'mock-token-3' : 'mock-token-4';
      const first = size.name === '1280' ? 'Ngozi' : 'Kwame';
      await page.goto(`/invite#token=${token}`);
      await expect(page.getByRole('heading', { level: 1, name: `Welcome, ${first}` })).toBeVisible();
      await expect(page.getByTestId('invite-email')).toContainText('@afrotransact.com');
      // no staff navigation, header or Settings link on this page
      await expect(page.getByRole('navigation')).toHaveCount(0);
      await expect(page.getByRole('banner')).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Settings' })).toHaveCount(0);
      expect(new URL(page.url()).hash).toBe('');
      await expectSameColumn(page.locator('form'), 'password fields', 'input');
      await noSidewaysScroll(page, 'invitation page');
      await shot(`staff-invitations-accept-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff invitation page ${size.name}` });

      await page.getByLabel(/^Choose a password/).fill('short');
      await page.getByLabel(/^Type it again/).fill('short');
      await page.getByRole('button', { name: 'Create my account' }).click();
      await expect(page.getByText(/Use at least 12 characters/)).toBeVisible();
      await page.getByLabel(/^Choose a password/).fill('Plantain-Jollof-2026');
      await page.getByLabel(/^Type it again/).fill('Plantain-Jollof-2026');
      await page.getByRole('button', { name: 'Create my account' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Your account is ready' })).toBeVisible();
      await expect(page.getByTestId('invite-signin')).toHaveAttribute('href', /\/v1\/auth\/login/);
      await shot(`staff-invitations-done-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff invitation done ${size.name}` });
    });

    test(`an expired link and a link that is not valid say what to do`, async ({ page }) => {
      await page.goto('/invite#token=mock-token-2');
      await expect(page.getByRole('heading', { level: 1, name: 'This invitation has expired' })).toBeVisible();
      await expect(page.getByLabel(/^Choose a password/)).toHaveCount(0);
      await page.goto('/invite#token=nope');
      await expect(page.getByRole('heading', { level: 1, name: "This link isn't valid" })).toBeVisible();
      await noSidewaysScroll(page, 'invalid link');
    });
  });
}
