import { expect, test } from '@playwright/test';

test('mobile: no horizontal scroll and hidden scrollbars on home, search and auth', async ({ page }) => {
  for (const path of ['/', '/s?q=rice', '/signin', '/cart']) {
    await page.goto(path);
    await expect(page.locator('body')).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, path).toBeLessThanOrEqual(0);
    const scrollbar = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('scrollbar-width'));
    expect(scrollbar.trim(), path).toBe('none');
  }
});
