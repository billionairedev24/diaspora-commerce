import { expect, type Locator, type Page, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Staff Settings on the seller pattern, against the in-process mock BFF (default role: Super Admin). One content column, a flat list
   of sections with a live status, each section its own page with a Settings link back. Roles and permissions: roles by portal,
   permissions grouped by module with a switch each, a confirmation with a required reason, a person's effective permissions.
   Controls are measured in the browser at 1280 and 390: the filter row shares one height and centre line, every permission row
   centres its switch, and nothing scrolls sideways. SHOTS (optional) saves screenshots for docs/engineering/img. */
test.describe.configure({ mode: 'default' });

const SHOTS = process.env.SHOTS;
/** Full page, or the top `maxHeight` px of it (a role lists ~180 permissions: the first screens show the design). */
const shot = async (page: Page, name: string, maxHeight = 0) => {
  if (!SHOTS) return;
  if (!maxHeight) return void (await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true }));
  const width = page.viewportSize()?.width ?? 1280;
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true, clip: { x: 0, y: 0, width, height: maxHeight } });
};

const SIZES = [
  { name: '1280', width: 1280, height: 900 },
  { name: '390', width: 390, height: 844 }
];

interface Box {
  cy: number;
  h: number;
  label: string;
}

/** Centre line and height of every visible control (input, select, button, switch) inside a row. */
async function controlsOf(row: Locator): Promise<Box[]> {
  return row.evaluate((el) => {
    const out: { cy: number; h: number; label: string }[] = [];
    for (const c of el.querySelectorAll<HTMLElement>('button, select, input, [role="switch"]')) {
      const r = c.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      out.push({ cy: r.top + r.height / 2, h: r.height, label: c.getAttribute('aria-label') ?? c.textContent?.trim() ?? c.tagName });
    }
    return out;
  });
}

/**
 * A row of controls: one height always; on a wide screen one centre line too. On a phone the row wraps, and the controls that end up
 * on the same line (centres within 12px of each other) must share it to the pixel.
 */
async function expectOneLine(row: Locator, what: string, wide: boolean) {
  const boxes = await controlsOf(row);
  expect(boxes.length, `${what}: has controls`).toBeGreaterThan(1);
  const hs = boxes.map((b) => b.h);
  expect(Math.max(...hs) - Math.min(...hs), `${what}: heights ${JSON.stringify(boxes)}`).toBeLessThanOrEqual(1);
  const lines: Box[][] = [];
  for (const b of [...boxes].sort((a, c) => a.cy - c.cy)) {
    const line = lines.find((l) => Math.abs((l[0]?.cy ?? 0) - b.cy) <= 12);
    if (line) line.push(b);
    else lines.push([b]);
  }
  if (wide) expect(lines.length, `${what}: one line ${JSON.stringify(boxes)}`).toBe(1);
  for (const l of lines) expect(Math.max(...l.map((b) => b.cy)) - Math.min(...l.map((b) => b.cy)), `${what}: centres ${JSON.stringify(l)}`).toBeLessThanOrEqual(1);
}

/** A row's one control sits on the row's own centre line. */
async function expectCentred(row: Locator, what: string) {
  const m = await row.evaluate((el) => {
    const sw = el.querySelector<HTMLElement>('[role="switch"]');
    const r = el.getBoundingClientRect();
    const s = sw?.getBoundingClientRect();
    return { row: r.top + r.height / 2, sw: s ? s.top + s.height / 2 : null };
  });
  expect(m.sw, `${what}: has a switch`).not.toBeNull();
  expect(Math.abs((m.sw ?? 0) - m.row), `${what}: ${JSON.stringify(m)}`).toBeLessThanOrEqual(1);
}

const noSidewaysScroll = async (page: Page, what: string) => {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(over, `${what}: horizontal overflow`).toBeLessThanOrEqual(1);
};

for (const size of SIZES) {
  test.describe(`staff console · settings at ${size.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
    });

    test(`the Settings home is one flat list with statuses, and each row opens its own page @a11y`, async ({ page }, testInfo) => {
      await page.goto('/settings');
      await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
      await expect(page.getByRole('navigation', { name: 'Settings sections' })).toHaveCount(0);
      const list = page.getByTestId('settings-list');
      await expect(list.getByTestId('settings-row-access')).toContainText('Roles and permissions');
      await expect(page.getByTestId('settings-status-access')).toContainText('platform roles');
      await expect(page.getByTestId('settings-status-ai')).toContainText('USD 2.00 a day');
      await expect(list.getByTestId('settings-row-tax-review')).toHaveAttribute('href', '/tax?tab=tax-review-settings');
      await noSidewaysScroll(page, 'home');
      await shot(page, `staff-settings-home-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff settings home ${size.name}` });

      await list.getByTestId('settings-row-access').click();
      await expect(page).toHaveURL(/\/settings\/access(\?|$)/);
      await expect(page.getByTestId('settings-back')).toBeVisible();
      await expect(page.getByRole('heading', { level: 1, name: 'Roles and permissions' })).toBeVisible();
      await page.getByTestId('settings-back').click();
      await expect(page).toHaveURL(/\/settings$/);
    });

    test(`the old addresses open the new pages`, async ({ page }) => {
      await page.goto('/settings?section=fees');
      await expect(page).toHaveURL(/\/settings\/fees(\?|$)/);
      await page.goto('/users');
      await expect(page).toHaveURL(/\/settings\/staff(\?|$)/);
      await expect(page.getByRole('heading', { level: 1, name: 'Staff accounts' })).toBeVisible();
      await page.goto('/users?tab=roles');
      await expect(page).toHaveURL(/\/settings\/access/);
    });

    test(`roles by portal, a role's permissions by module, switch one with a reason, then a person @a11y`, async ({ page }, testInfo) => {
      await page.goto('/settings/access');
      await expect(page.getByText(/within about 5 minutes/).first()).toBeVisible();
      const roles = page.getByRole('table', { name: 'Roles' });
      await expect(roles.getByText('Finance')).toBeVisible();
      await expect(roles.getByText('Super Admin')).toBeVisible();
      await noSidewaysScroll(page, 'roles list');
      await shot(page, `staff-settings-roles-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff roles list ${size.name}` });

      await page.getByLabel('Roles for').selectOption('seller');
      await expect(page.getByRole('table', { name: 'Roles' }).getByText('Owner')).toBeVisible();
      await page.getByLabel('Roles for').selectOption('platform');

      await roles.getByText('Finance').click();
      const detail = page.getByTestId('role-detail');
      await expect(detail.getByRole('heading', { level: 2, name: 'Finance' })).toBeVisible();
      await expect(detail.getByTestId('role-members').getByText('Musa Bello')).toBeVisible();
      await expect(detail.getByTestId('perm-group-orders').getByText('Refunds: issue')).toBeVisible();

      // The filter row: search, show and area share one height and one centre line.
      await expectOneLine(page.getByTestId('permission-filters'), 'permission filters', size.width >= 1000);
      // Each permission row centres its switch.
      for (const key of ['refunds:issue', 'catalog:write', 'payouts:read']) await expectCentred(detail.getByTestId(`perm-${key}`), `row ${key}`);
      await noSidewaysScroll(page, 'role detail');
      await shot(page, `staff-settings-role-${size.name}`, 1500);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff role detail ${size.name}` });

      // Switch a permission on: a confirmation asks why; the reason is required.
      const toggle = detail.getByRole('switch', { name: 'Catalog: write for Finance' });
      await expect(toggle).not.toBeChecked();
      await toggle.click();
      const dialog = page.getByRole('dialog', { name: 'Add “Catalog: write” to Finance?' });
      await expect(dialog.getByRole('button', { name: 'Add permission' })).toBeDisabled();
      await shot(page, `staff-settings-confirm-${size.name}`, 1100);
      await dialog.getByLabel(/^Why/).fill('Finance prices the retail catalogue');
      await dialog.getByRole('button', { name: 'Add permission' }).click();
      await expect(dialog).toBeHidden();
      await expect(detail.getByRole('switch', { name: 'Catalog: write for Finance' })).toBeChecked();
      // ... and back off, so the journey leaves the role as it found it.
      await detail.getByRole('switch', { name: 'Catalog: write for Finance' }).click();
      const off = page.getByRole('dialog', { name: 'Remove “Catalog: write” from Finance?' });
      await off.getByLabel(/^Why/).fill('Put back after the journey');
      await off.getByRole('button', { name: 'Remove permission' }).click();
      await expect(detail.getByRole('switch', { name: 'Catalog: write for Finance' })).not.toBeChecked();

      // The change log names who and why.
      await page.getByRole('button', { name: 'All roles' }).click();
      await page.getByRole('tab', { name: 'Change log' }).click();
      await page.getByLabel('Role', { exact: true }).selectOption('platform.finance');
      await expect(page.getByRole('table', { name: 'Change log' }).getByText('Finance prices the retail catalogue').first()).toBeVisible();
      await noSidewaysScroll(page, 'change log');
      await shot(page, `staff-settings-log-${size.name}`);

      // People: what a person can do, and where each permission comes from.
      await page.getByRole('tab', { name: 'People' }).click();
      await page.getByRole('searchbox').fill('grace');
      await page.getByText('Grace Adjei').click();
      const person = page.getByTestId('person-detail');
      await expect(person.getByTestId('source-refunds:issue')).toContainText('From Customer Support');
      await noSidewaysScroll(page, 'person');
      await shot(page, `staff-settings-person-${size.name}`);
      await expectOneLine(person.getByTestId('grant-direct'), 'grant row', size.width >= 1000);
    });
  });
}
