import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, type Page, test } from '@playwright/test';

/*
 * Settings → Published pages (the owner's report: "All published pages from Settings in admin can't even be viewed. I want
 * that to be previewable and editable so dev does not need to do this"): every public page with an absolute live link,
 * an in-console preview of what shoppers read (language, width, the draft banner and counsel markers), a Markdown editor
 * with a live preview, a draft that changes nothing public, publication with a reason and a second approver for legal
 * documents, the history and rollback. Runs against the mock BFF.
 */

const STOREFRONT = (process.env.E2E_STOREFRONT_URL ?? `http://localhost:${process.env.E2E_STOREFRONT_PORT ?? 3410}`).replace(/\/+$/, '');

const openIndex = async (page: Page) => {
  await page.goto('/settings?section=published-pages');
  await expect(page.getByRole('table', { name: 'Published pages' })).toBeVisible();
};

test.describe('staff console · published pages', () => {
  test('every live link is an absolute storefront address that opens in a new tab', async ({ page }) => {
    await openIndex(page);
    const row = page.getByRole('row', { name: /Privacy Notice/ }).first();
    const live = row.getByRole('link', { name: /\/legal\/privacy/ });
    await expect(live).toHaveAttribute('href', `${STOREFRONT}/legal/privacy`);
    await expect(live).toHaveAttribute('target', '_blank');
    const hrefs = await page.locator('table[aria-label="Published pages"] a[target="_blank"]').evaluateAll((links) => links.map((a) => (a as HTMLAnchorElement).getAttribute('href') ?? ''));
    expect(hrefs.length).toBeGreaterThan(10);
    for (const href of hrefs) expect(href.startsWith(`${STOREFRONT}/`)).toBe(true);
    // Who changed it and when sits in the table; names, never ids.
    await expect(row.getByText('Imani Okafor')).toBeVisible();
    // A page only a developer changes says so.
    await expect(page.getByRole('row', { name: /About AfroTransact/ }).getByText('Developer only').first()).toBeVisible();
  });

  test('previews a legal document in a sheet: language, width, the fallback notice; an outside click closes the sheet', async ({ page }) => {
    await openIndex(page);
    await page.getByRole('button', { name: 'Preview Privacy Notice' }).click();
    const sheet = page.getByRole('dialog');
    await expect(sheet.getByTestId('preview-stage')).toBeVisible();
    await expect(sheet.getByText('Draft for legal counsel review — not yet in force.')).toBeVisible();
    await sheet.getByRole('button', { name: 'Français' }).click();
    await expect(sheet.getByRole('heading', { name: /Les données que nous collectons/ })).toBeVisible();
    await sheet.getByRole('button', { name: /Español/ }).click();
    await expect(sheet.getByText('No Español translation yet.')).toBeVisible();
    await sheet.getByRole('button', { name: 'Phone' }).click();
    await expect(sheet.getByTestId('preview-stage')).toHaveAttribute('data-device', 'phone');
    expect((await sheet.getByTestId('preview-stage').boundingBox())?.width).toBeLessThanOrEqual(391);
    await page.mouse.click(60, 400);
    await expect(sheet).toBeHidden();
  });

  test('a legal document: markers are counted, a draft saves without changing the page, the last editor cannot publish it', async ({ page }) => {
    await page.goto('/settings/pages/legal/accessibility');
    await expect(page.getByText('Unresolved counsel markers: 1')).toBeVisible();
    await expect(page.locator('[data-testid="preview-stage"] mark[data-legal-marker]')).toHaveCount(1);
    await page.getByRole('tab', { name: 'Edit' }).click();
    const save = page.getByRole('button', { name: 'Save draft' });
    await expect(save).toBeDisabled();
    const body = page.getByRole('textbox', { name: /Text in English/ });
    await body.click();
    await body.press('Control+End');
    await page.keyboard.type('\n\n## New point\n\nWe answer in two days.');
    // The preview beside the editor follows the typing; the form can now be saved.
    await expect(page.getByTestId('preview-stage').last().getByText('We answer in two days.')).toBeVisible();
    await expect(save).toBeEnabled();
    await page.getByRole('textbox', { name: /What changed/ }).fill('Adds the answer time');
    await save.click();
    await expect(page.getByText('Draft saved. Nothing public has changed.')).toBeVisible();
    // Published page unchanged until a second person publishes.
    await page.getByRole('tab', { name: 'Preview' }).click();
    await page.locator('#preview-source').selectOption('published');
    await expect(page.getByTestId('preview-stage').getByText('We answer in two days.')).toHaveCount(0);
    await page.getByRole('tab', { name: 'Edit' }).click();
    await page.getByRole('button', { name: 'Publish…' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/You saved the last change to this draft/)).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Publish' })).toBeDisabled();
  });

  test('a colleague’s draft is reviewed in the preview and published with a reason; the history shows who and why', async ({ page }) => {
    await page.goto('/settings/pages/legal/returns?mode=edit');
    await expect(page.getByText('Imani Okafor has a draft waiting')).toBeVisible();
    await page.getByRole('button', { name: 'Publish…' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/Someone else saved this draft/)).toBeVisible();
    await dialog.getByRole('textbox', { name: /Why are you publishing/ }).fill('Counsel approved 45 days');
    await dialog.getByRole('button', { name: 'Publish' }).click();
    await expect(dialog).toBeHidden();
    await page.getByRole('tab', { name: 'History' }).click();
    const rows = page.getByTestId('content-version');
    await expect(rows.first()).toContainText('Live');
    await expect(rows.first()).toContainText('Ifeoma Okafor');
    await expect(rows.first()).toContainText('Return window from 30 to 45 days');
    await expect(page.getByTestId('content-diff')).toBeVisible();
  });

  test('a help page: edit with the toolbar, preview, publish with a reason, then roll back', async ({ page }) => {
    await page.goto('/settings/pages/help/shipping?mode=edit');
    const body = page.getByRole('textbox', { name: /Page text/ });
    await body.click();
    await body.press('Control+End');
    await page.keyboard.type('\n\nlockers');
    await page.getByRole('button', { name: 'Section heading' }).click();
    await page.keyboard.type('\n\nCollect from a **locker** near you.');
    await expect(page.getByTestId('preview-stage').last().getByText('locker', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Publish…' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox', { name: /Why are you publishing/ }).fill('Lockers are live');
    await dialog.getByRole('button', { name: 'Publish' }).click();
    await expect(dialog).toBeHidden();
    await page.getByRole('tab', { name: 'History' }).click();
    await expect(page.getByTestId('content-version')).toHaveCount(2);
    await expect(page.getByTestId('content-version').first()).toContainText('Reason: Lockers are live');
    await page.getByTestId('content-version').nth(1).getByRole('button', { name: 'Roll back' }).click();
    const rollback = page.getByRole('dialog');
    await rollback.getByRole('textbox', { name: /Why are you going back/ }).fill('Lockers paused');
    await rollback.getByRole('button', { name: 'Publish it again' }).click();
    await expect(page.locator('#preview-source')).toContainText('Published (version 3)');
  });

  test('is reachable from the Settings hub and from Site content, and the careers and cookie tables are there', async ({ page }) => {
    await page.goto('/settings');
    await page.getByRole('link', { name: /Published pages/ }).click();
    await expect(page).toHaveURL(/section=published-pages/);
    await page.goto('/site-content');
    await expect(page.getByRole('tab', { name: 'Published pages' })).toBeVisible();
    await page.getByRole('tab', { name: 'Careers' }).click();
    await expect(page.getByRole('row', { name: /Customer support agent/ })).toBeVisible();
    await page.getByRole('tab', { name: 'Cookies' }).click();
    await expect(page.getByRole('row', { name: /at_session/ })).toBeVisible();
  });
});

/* Design notes: the preview and edit screens at desktop and phone width (docs/design-notes/admin-content/). */
test.describe('staff console · published pages screenshots', () => {
  test.skip(!process.env.E2E_DESIGN_SHOTS, 'set E2E_DESIGN_SHOTS=1 to refresh docs/design-notes/admin-content');
  const dir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'docs', 'design-notes', 'admin-content');

  for (const [name, width, height] of [['1440', 1440, 900], ['390', 390, 844]] as const) {
    test(`screens at ${name}px`, async ({ page }) => {
      mkdirSync(dir, { recursive: true });
      await page.setViewportSize({ width, height });
      await openIndex(page);
      await page.screenshot({ path: join(dir, `published-pages-${name}.png`), fullPage: true });
      await page.goto('/settings/pages/legal/accessibility');
      await expect(page.getByTestId('preview-stage')).toBeVisible();
      await page.screenshot({ path: join(dir, `legal-preview-${name}.png`), fullPage: true });
      await page.goto('/settings/pages/legal/returns?mode=edit');
      await expect(page.getByText('Imani Okafor has a draft waiting')).toBeVisible();
      await page.screenshot({ path: join(dir, `legal-edit-${name}.png`), fullPage: true });
      await page.goto('/settings/pages/help/shipping?mode=history');
      await expect(page.getByTestId('content-version').first()).toBeVisible();
      await page.screenshot({ path: join(dir, `help-history-${name}.png`), fullPage: true });
    });
  }
});
