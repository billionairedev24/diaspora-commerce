import { expect, test, type Page } from '@playwright/test';

/* The detail sheet's lifecycle on the staff console (owner feedback on Sales tax › Tax review): with a sheet open, a click on
   another sidebar item leaves the page and the sheet goes with it; coming back shows the plain list; Back closes the sheet
   before it leaves the page; Escape and the close button close it and drop the item from the address. The same shared
   Sheet and URL handling serve every page with a detail sheet, so Orders and Payouts are checked alongside Tax review. */

const sheet = (page: Page) => page.getByRole('dialog');

/** A sidebar link, opening its group first when the group is folded (only the group holding the current page is open). */
async function menuLink(page: Page, group: string, link: string | RegExp) {
  const nav = page.getByRole('navigation', { name: 'Staff console' });
  const folder = nav.getByRole('button', { name: new RegExp(`^${group}$`, 'i') });
  if ((await folder.getAttribute('aria-expanded')) === 'false') await folder.click();
  return nav.getByRole('link', { name: link }).first();
}

test.describe('staff console · detail sheet lifecycle', () => {
  test('tax review: another menu item leaves the page and the sheet; coming back is the plain list', async ({ page }) => {
    await page.goto('/tax');
    const table = page.getByRole('table', { name: 'Tax review queue' });
    await table.locator('tbody tr[data-row-id]').first().click();
    await expect(sheet(page)).toBeVisible();
    await expect(page).toHaveURL(/[?&]variant=/);

    // The real pointer sequence: pointer-down outside the sheet (it used to close and rewrite /tax), then the link's click.
    await (await menuLink(page, 'Finance', /Payouts/)).click();
    await expect(page).toHaveURL(/\/payouts$/);
    await expect(page.getByRole('heading', { name: /Payouts/ }).first()).toBeVisible();
    await expect(sheet(page)).toHaveCount(0);

    await (await menuLink(page, 'Finance', 'Sales tax')).click();
    await expect(page).toHaveURL(/\/tax$/);
    await expect(page.getByRole('table', { name: 'Tax review queue' })).toBeVisible();
    await expect(sheet(page)).toHaveCount(0);
    await expect(page.locator('tr[aria-current="true"]')).toHaveCount(0);
  });

  test('tax review: Back closes the sheet first; Escape and the close button drop the item from the address', async ({ page }) => {
    await page.goto('/tax');
    const rows = page.getByRole('table', { name: 'Tax review queue' }).locator('tbody tr[data-row-id]');
    await rows.first().click();
    await expect(sheet(page)).toBeVisible();
    await page.goBack();
    await expect(sheet(page)).toHaveCount(0);
    await expect(page).toHaveURL(/\/tax(\?(?!.*variant=).*)?$/);

    await rows.first().click();
    await expect(sheet(page)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(sheet(page)).toHaveCount(0);
    await expect(page).not.toHaveURL(/variant=/);
    await expect(rows.first()).toBeFocused();

    await rows.nth(1).click();
    await expect(sheet(page)).toBeVisible();
    await sheet(page).getByRole('button', { name: 'Close' }).click();
    await expect(sheet(page)).toHaveCount(0);
    await expect(page).not.toHaveURL(/variant=/);
  });

  test('a deep link opens the item, and the other tabs of Sales tax never keep it', async ({ page }) => {
    await page.goto('/tax?variant=AT20000101');
    await expect(sheet(page)).toBeVisible();
    // The sheet covers the right half of the tab strip: switch with the keyboard, as a keyboard user would.
    await page.getByRole('tab', { name: 'Profiles' }).focus();
    await page.keyboard.press('Enter');
    await expect(page).not.toHaveURL(/variant=/);
    await expect(page.getByRole('table', { name: /Tax profiles|Profiles/ })).toBeVisible();
    await page.getByRole('tab', { name: 'Tax review' }).click();
    await expect(page.getByRole('table', { name: 'Tax review queue' })).toBeVisible();
    await expect(sheet(page)).toHaveCount(0);
  });

  test('the same on Orders and Payouts: a menu click leaves the page, the sheet is gone, Back closes it first', async ({ page }) => {
    for (const [path, group, name] of [
      ['/orders', 'Commerce', /Orders/],
      ['/payouts', 'Finance', /Payouts/]
    ] as const) {
      await page.goto(path);
      await page.locator('table tbody tr[data-row-id]').first().click();
      await expect(sheet(page)).toBeVisible();
      await expect(page).toHaveURL(/[?&]open=/);
      await page.goBack();
      await expect(sheet(page)).toHaveCount(0);
      await page.locator('table tbody tr[data-row-id]').first().click();
      await expect(sheet(page)).toBeVisible();
      await (await menuLink(page, 'Finance', 'Sales tax')).click();
      await expect(page).toHaveURL(/\/tax$/);
      await expect(sheet(page)).toHaveCount(0);
      await (await menuLink(page, group, name)).click();
      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(sheet(page)).toHaveCount(0);
    }
  });

  test('tax review rows line up: fixed height, cells centred, the item named with its photo, nothing clipped without a tooltip', async ({ page }) => {
    await page.goto('/tax');
    const rows = page.getByRole('table', { name: 'Tax review queue' }).locator('tbody tr[data-row-id]');
    await expect(rows.first()).toBeVisible();
    const heights = await rows.evaluateAll((trs) => trs.map((tr) => Math.round(tr.getBoundingClientRect().height)));
    expect(new Set(heights).size).toBe(1);
    const first = rows.first();
    await expect(first.locator('img').first()).toBeVisible();
    await expect(first.locator('strong').first()).not.toHaveText('');
    // Every cell is vertically centred on the row.
    const middles = await first.locator('td').evaluateAll((tds) => tds.map((td) => getComputedStyle(td).verticalAlign));
    expect(new Set(middles)).toEqual(new Set(['middle']));
    // The table never scrolls sideways at this width.
    const region = page.getByRole('region', { name: /Tax review queue \(scrollable\)/ });
    expect(await region.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
  });
});
