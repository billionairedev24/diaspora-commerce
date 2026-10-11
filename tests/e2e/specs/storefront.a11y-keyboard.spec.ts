import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/*
 * Keyboard and preference checks that axe cannot make (S194, WCAG 2.2 AA): skip link, visible focus, keyboard
 * scrolling without visible scrollbars, dialog focus trap and return, reduced motion, RTL and the error summary.
 */
test.describe('storefront · keyboard and preferences @a11y', () => {
  test('skip link is the first stop and moves focus to the main content', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Skip to main content' });
    await expect(skip).toBeFocused();
    await expect(skip).toBeInViewport();
    await page.keyboard.press('Enter');
    await expect(page.locator('#main')).toBeFocused();
  });

  test('keyboard focus is always visible (2px outline)', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Tab');
      // The indicator is an outline (≥ 2 px) or a ring (box-shadow) on the element or on a :focus-within wrapper.
      const visible = await page.evaluate(() => {
        let el = document.activeElement as HTMLElement | null;
        if (!el || el === document.body) return null;
        for (let depth = 0; el && depth < 3; depth++, el = el.parentElement) {
          const s = getComputedStyle(el);
          if ((s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) >= 2) || s.boxShadow !== 'none') return true;
        }
        return false;
      });
      expect(visible, `focus stop ${i + 1} shows no focus indicator`).toBe(true);
    }
  });

  test('pages scroll with the keyboard although scrollbars are hidden', async ({ page }) => {
    await page.goto('/s?q=jollof');
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible();
    const hidden = await page.evaluate(() => getComputedStyle(document.documentElement).scrollbarWidth);
    expect(hidden).toBe('none');
    await page.locator('#main').focus();
    const before = await page.evaluate(() => window.scrollY);
    await page.keyboard.press('PageDown');
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before);
    await page.keyboard.press('End');
    await page.keyboard.press('Home');
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  });

  test('a dialog traps focus, closes with Escape and returns focus to its trigger', async ({ page }) => {
    await page.goto('/');
    const trigger = page.getByRole('button', { name: /^Deliver to/ });
    await trigger.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      expect(await dialog.evaluate((d) => d.contains(document.activeElement)), `Tab ${i + 1} left the dialog`).toBe(true);
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('reduced motion: the hero carousel does not auto-advance and transitions are near-instant', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/');
    const slide = page.locator('[aria-roledescription="slide"]');
    await expect(slide).toBeVisible();
    const label = await slide.getAttribute('aria-label');
    await page.waitForTimeout(7500);
    await expect(slide).toHaveAttribute('aria-label', label ?? '');
    const duration = await page.evaluate(() => getComputedStyle(document.querySelector('a, button') as Element).transitionDuration);
    expect(duration.split(',').every((d) => parseFloat(d) <= 0.001)).toBe(true);
  });

  test('the carousel can be paused with a 24 px or larger control', async ({ page }) => {
    await page.goto('/');
    const pause = page.getByRole('button', { name: 'Pause slideshow' });
    const box = await pause.boundingBox();
    expect(box && box.width >= 24 && box.height >= 24).toBe(true);
    await pause.click();
    await expect(page.getByRole('button', { name: 'Play slideshow' })).toBeVisible();
  });

  test('Arabic switches the page to right-to-left and stays axe-clean', async ({ page, context, baseURL }, testInfo) => {
    await context.addCookies([{ name: 'at_locale', value: 'ar', url: baseURL ?? '' }]);
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ar');
    await expect(page.getByRole('link', { name: 'تخطَّ إلى المحتوى الرئيسي' })).toBeAttached();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'home (ar)' });
  });

  test('the footer language picker switches the language', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('combobox', { name: /Language/ }).selectOption('fr');
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expect(page.getByRole('link', { name: 'Aller au contenu principal' })).toBeAttached();
  });
});
