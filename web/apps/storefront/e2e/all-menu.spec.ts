import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

import { expect, type Page, test } from '@playwright/test';

const axeSource = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8');

/** WCAG A/AA violations of the open drawer (the page behind it is inert), colour contrast included (the vitest run has no colour engine). */
async function axeViolations(page: Page) {
  await page.evaluate(axeSource);
  return page.evaluate(async () => {
    const axe = (window as unknown as { axe: { run: (c: Element, o: object) => Promise<{ violations: Array<{ id: string; nodes: Array<{ target: unknown }> }> }> } }).axe;
    const r = await axe.run(document.querySelector('[role="dialog"]') ?? document.body, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
    return r.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }));
  });
}

const openAll = async (page: Page) => {
  await page.getByRole('navigation', { name: 'Departments' }).getByRole('button', { name: 'All' }).click();
  return page.getByRole('dialog', { name: 'All menu' });
};

test.describe('All menu drawer', () => {
  test('opens from the left with a dim backdrop, drills into a department, closes with Escape and gives focus back', async ({ page }) => {
    await page.goto('/');
    const all = page.getByRole('navigation', { name: 'Departments' }).getByRole('button', { name: 'All' });
    const dialog = await openAll(page);
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('link', { name: 'Hello, sign in' })).toBeVisible();
    const box = (await dialog.boundingBox())!;
    expect(box.x).toBe(0);
    expect(box.height).toBe(page.viewportSize()!.height);
    // The page behind is locked and dimmed; no scrollbar inside the panel.
    expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).toBe('hidden');
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('scrollbar-width').trim())).toBe('none');
    await expect(dialog.getByRole('group', { name: 'Shop by department' })).toBeVisible();
    await expect(dialog.getByRole('group', { name: 'Programs & features' })).toBeVisible();
    await expect(dialog.getByRole('group', { name: 'Help & settings' })).toBeVisible();
    await dialog.getByRole('menuitem', { name: 'Groceries' }).click();
    await expect(dialog.getByRole('menuitem', { name: 'Main menu' })).toBeFocused();
    await expect(dialog.getByRole('menuitem', { name: 'See all in Groceries' })).toHaveAttribute('href', /\/s\?dept=Groceries/);
    await dialog.getByRole('menuitem', { name: 'Main menu' }).click();
    await expect(dialog.getByRole('menuitem', { name: 'Groceries' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(all).toBeFocused();
    expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe('hidden');
  });

  test('keeps focus inside while open, and a department link lands on its results', async ({ page }) => {
    await page.goto('/');
    const dialog = await openAll(page);
    for (let i = 0; i < 6; i++) await page.keyboard.press('Tab');
    expect(await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'))).toBe(true);
    await dialog.getByRole('menuitem', { name: 'Groceries' }).click();
    await dialog.getByRole('menuitem', { name: 'See all in Groceries' }).click();
    await expect(page).toHaveURL(/\/s\?dept=Groceries/);
    await expect(dialog).toBeHidden();
  });

  test('is axe-clean, colour contrast included: main menu, a department, and signed in', async ({ page }) => {
    await page.goto('/');
    const dialog = await openAll(page);
    expect(await axeViolations(page)).toEqual([]);
    await dialog.getByRole('menuitem', { name: 'Groceries' }).click();
    await expect(dialog.getByRole('menuitem', { name: 'Main menu' })).toBeFocused();
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await page.goto('/api/bff/v1/auth/login?returnTo=%2F');
    const signedIn = await openAll(page);
    await expect(signedIn.getByRole('link', { name: 'Hello, Adaeze' })).toBeVisible();
    await expect(signedIn.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
  });

  test('Arabic: the drawer slides in from the right', async ({ page, context }) => {
    await context.addCookies([{ name: 'at_locale', value: 'ar', url: 'http://localhost:3005' }]);
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await page.getByRole('navigation').first().getByRole('button').first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect.poll(async () => (await dialog.boundingBox())!.x + (await dialog.boundingBox())!.width).toBe(page.viewportSize()!.width);
  });
});
