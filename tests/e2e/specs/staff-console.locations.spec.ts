import { expect, type Page, test } from '@playwright/test';

/* Back office › Settings → Markets & locations and Pre-orders → Pre-order areas share one location tree: only live and
   planned places show (country → states → cities), and every edit happens in place. Runs against the in-process mock
   BFF; each journey puts its changes back so it can run again on the same server. SHOTS (optional) saves screenshots. */
const shot = async (page: Page, name: string) => {
  if (process.env.SHOTS) await page.screenshot({ path: `${process.env.SHOTS}/${name}.png`, fullPage: true });
};

test.describe('staff console · location tree', () => {
  test('markets: expand Texas, add two cities, switch one live, remove one with undo, add a state', async ({ page }) => {
    await page.goto('/markets');
    const tree = page.getByRole('tree', { name: 'Markets & locations' });
    await expect(tree.getByTestId('tree-country')).toHaveCount(2); // United States (live) and Canada (coming soon)
    await expect(page.getByText(/countries hidden/)).toBeVisible();

    await tree.getByRole('button', { name: 'Open United States' }).click();
    await tree.getByRole('button', { name: 'Open Texas' }).click();
    const texas = tree.getByRole('treeitem', { name: /^Texas, Live/ });
    await expect(texas).toHaveAttribute('aria-expanded', 'true');
    await expect(texas.getByTestId('tree-city')).toHaveCount(6);

    await texas.getByRole('button', { name: 'Add cities' }).click();
    const adder = texas.getByTestId('city-adder');
    await adder.getByRole('textbox').fill('fort');
    await adder.getByRole('checkbox', { name: 'Fort Worth' }).check();
    await adder.getByRole('textbox').fill('mid');
    await adder.getByRole('checkbox', { name: 'Midland' }).check();
    await adder.getByRole('button', { name: 'Add 2 cities' }).click();
    await expect(texas.getByTestId('tree-city')).toHaveCount(8);
    await expect(texas.getByRole('treeitem', { name: 'Houston, Live' })).toBeVisible(); // appended, not replaced
    await expect(texas.getByRole('treeitem', { name: 'Fort Worth, Planned' })).toBeVisible();

    await texas.getByRole('radiogroup', { name: 'Fort Worth status' }).getByRole('radio', { name: 'Live' }).click();
    await expect(texas.getByRole('treeitem', { name: 'Fort Worth, Live' })).toBeVisible();

    await texas.getByRole('button', { name: 'Remove Midland' }).click();
    await expect(texas.getByRole('treeitem', { name: /^Midland/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Undo' }).click();
    await expect(texas.getByRole('treeitem', { name: 'Midland, Planned' })).toBeVisible();

    // An open pre-order sells in Lubbock: removing it is refused and switching it off is offered.
    await texas.getByRole('button', { name: 'Remove Lubbock' }).click();
    await expect(page.getByText(/Lubbock: LBK-7 sells here/)).toBeVisible();
    await expect(texas.getByRole('treeitem', { name: 'Lubbock, Live' })).toBeVisible();

    await tree.getByRole('button', { name: 'Add a state' }).click();
    await page.getByRole('combobox', { name: 'Add a state to United States' }).fill('michi');
    await page.getByTestId('place-picker').getByRole('button', { name: /Michigan/ }).click();
    const michigan = tree.getByRole('treeitem', { name: /^Michigan, Planned/ });
    await expect(michigan).toBeVisible();
    await expect(michigan).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('markets-publish')).toContainText('Unpublished changes');
    await shot(page, 'mock-markets-tree');

    // Put it back.
    await michigan.getByRole('radiogroup', { name: 'Michigan status' }).getByRole('radio', { name: 'Off' }).click();
    await expect(tree.getByRole('treeitem', { name: /^Michigan/ })).toHaveCount(0);
    await texas.getByRole('button', { name: 'Remove Midland' }).click();
    await texas.getByRole('button', { name: 'Remove Fort Worth' }).click();
    await expect(texas.getByTestId('tree-city')).toHaveCount(6);
  });

  test('keyboard: arrow keys move through the tree and open and close nodes', async ({ page }) => {
    await page.goto('/markets');
    const us = page.getByRole('treeitem', { name: /^United States/ });
    await us.focus();
    await page.keyboard.press('ArrowRight');
    await expect(us).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('treeitem', { name: /^Georgia/ })).toBeVisible();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('treeitem', { name: /^Georgia/ })).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(us).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(us).toHaveAttribute('aria-expanded', 'false');
  });

  test('pre-order areas use the same tree', async ({ page }) => {
    await page.goto('/preorders/settings');
    const tree = page.getByRole('tree', { name: 'Pre-order areas' });
    await tree.getByRole('button', { name: 'Open United States' }).click();
    await tree.getByRole('button', { name: 'Open Texas' }).click();
    const texas = tree.getByRole('treeitem', { name: /^Texas/ });
    await expect(texas.getByRole('treeitem', { name: 'Lubbock, On' })).toBeVisible();
    await expect(texas.getByRole('treeitem', { name: /^Laredo/ })).toHaveCount(0); // off: hidden

    await texas.getByRole('button', { name: 'Add cities' }).click();
    await texas.getByTestId('city-adder').getByRole('textbox').fill('hou');
    await texas.getByTestId('city-adder').getByRole('checkbox', { name: 'Houston' }).check();
    await texas.getByTestId('city-adder').getByRole('button', { name: 'Add 1 city' }).click();
    await expect(texas.getByRole('treeitem', { name: 'Houston, On' })).toBeVisible();
    await expect(texas.getByRole('treeitem', { name: 'Lubbock, On' })).toBeVisible();
    await shot(page, 'mock-preorder-areas-tree');

    await texas.getByRole('button', { name: 'Remove Houston' }).click();
    await expect(texas.getByRole('treeitem', { name: /^Houston/ })).toHaveCount(0);
  });

  test('at 390px the tree stacks with large touch targets', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/markets');
    const tree = page.getByRole('tree', { name: 'Markets & locations' });
    await tree.getByRole('button', { name: 'Open United States' }).click();
    const live = tree.getByRole('radiogroup', { name: 'Texas status' }).getByRole('radio', { name: 'Live' });
    const box = await live.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    const width = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(width).toBeLessThanOrEqual(390);
  });
});
