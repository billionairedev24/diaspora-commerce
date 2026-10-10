import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Staff console against the in-process mock BFF (default role: platform super admin). */

test.describe('staff console · a11y smoke @a11y', () => {
  for (const [name, path] of [
    ['dashboard', '/'],
    ['orders', '/orders'],
    ['moderation', '/moderation'],
    ['support', '/support'],
    ['people', '/users'],
    ['payouts', '/payouts'],
    ['audit log', '/audit'],
    ['exports', '/exports'],
    ['site content', '/site-content']
  ] as const) {
    test(`staff ${name} has no serious axe violations`, async ({ page }, testInfo) => {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ${name}` });
    });
  }

  test('skip link moves focus to the main content', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to content' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeInViewport();
    await page.keyboard.press('Enter');
    await expect(page.locator('#main')).toBeFocused();
  });
});
