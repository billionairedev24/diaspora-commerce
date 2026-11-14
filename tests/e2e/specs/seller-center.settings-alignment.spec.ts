import { expect, type Locator, type Page, test } from '@playwright/test';

import { SELLER_ORG, setActiveOrg } from '../support/seller';

/* Settings is one column, and every control in a row shares height and vertical centre. Rows are measured in the browser:
   the centres of the switch, the time selects and the buttons of one row must be within 1px of each other (and the controls
   the same height), at desktop and phone widths. SHOTS (optional) saves screenshots for docs/engineering/img. */
test.describe.configure({ mode: 'default' });

const SHOTS = process.env.SHOTS;
const shot = async (page: Page, name: string) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
};

interface Box {
  cy: number;
  h: number;
  label: string;
}

/** Centre line and height of every visible control (switch, select, input, button) inside a row. */
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

async function expectAligned(row: Locator, what: string) {
  const boxes = await controlsOf(row);
  expect(boxes.length, `${what}: has controls`).toBeGreaterThan(0);
  const lo = Math.min(...boxes.map((b) => b.cy));
  const hi = Math.max(...boxes.map((b) => b.cy));
  expect(hi - lo, `${what}: centres ${JSON.stringify(boxes)}`).toBeLessThanOrEqual(1);
  const hs = boxes.map((b) => b.h);
  expect(Math.max(...hs) - Math.min(...hs), `${what}: heights ${JSON.stringify(boxes)}`).toBeLessThanOrEqual(1);
}

const SIZES = [
  { name: '1280', width: 1280, height: 900 },
  { name: '390', width: 390, height: 844 }
];

test.describe('seller center · settings layout and alignment', () => {
  test.beforeEach(async ({ request, baseURL }) => {
    await setActiveOrg(request, baseURL ?? '', SELLER_ORG);
    const off = await request.put(`${baseURL}/api/bff/v1/seller/ship-from-addresses/sfa-store/customer-pickup`, { data: { enabled: false, hours: [], readyHours: 0 } });
    expect(off.status(), await off.text()).toBe(200);
  });

  for (const size of SIZES) {
    test(`pickup at ${size.name}: one column, rows aligned, no sideways scroll`, async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.goto('/settings/pickup');
      // One content column: no settings sub-nav, a way back to Settings above the page.
      await expect(page.getByRole('navigation', { name: 'Settings sections' })).toHaveCount(0);
      await expect(page.getByTestId('settings-back')).toBeVisible();
      const form = page.getByTestId('pickup-location').first();
      await expect(form).toBeVisible();
      await shot(page, `seller-settings-pickup-off-${size.name}`);

      await form.getByRole('switch', { name: 'Let customers collect orders' }).click();
      await expect(form.getByTestId('weekly-hours')).toBeVisible();
      await expectAligned(form.getByTestId('weekly-row').first(), 'closed row');
      await form.getByRole('switch', { name: 'Monday' }).click();
      await form.getByLabel('From, Monday').selectOption('09:00');
      await form.getByLabel('To, Monday').selectOption('17:00');
      await form.getByRole('button', { name: 'Copy to weekdays' }).click();
      await form.getByLabel('Ready to collect').selectOption('2');

      // Every row of the week: switch, day and both selects on one centre line, one height, one line (not stacked).
      const rows = form.getByTestId('weekly-row');
      await expect(rows).toHaveCount(7);
      for (let i = 0; i < 7; i++) {
        const row = rows.nth(i);
        await expectAligned(row, `row ${i + 1}`);
        if (i < 5) {
          const from = await row.getByRole('combobox').first().boundingBox();
          const to = await row.getByRole('combobox').nth(1).boundingBox();
          expect(Math.abs((from?.y ?? 0) - (to?.y ?? 1)), `row ${i + 1}: from and to share a line`).toBeLessThanOrEqual(1);
        }
      }
      await expectAligned(form.locator('div', { has: page.getByLabel('Ready to collect') }).last(), 'ready row');
      await expectAligned(form.locator('div', { has: page.getByRole('button', { name: 'Save customer pickup' }) }).last(), 'save row');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
      await shot(page, `seller-settings-pickup-filled-${size.name}`);

      // The save payload: one window per open day, none for closed days.
      const saved = page.waitForResponse((r) => r.url().includes('/customer-pickup') && r.request().method() === 'PUT');
      await form.getByRole('button', { name: 'Save customer pickup' }).click();
      const body = (await (await saved).request().postDataJSON()) as { enabled: boolean; hours: { day: string; opens: string; closes: string }[]; readyHours: number; hoursConfirmed: boolean };
      expect(body).toMatchObject({ enabled: true, readyHours: 2, hoursConfirmed: true });
      expect(body.hours.map((h) => h.day)).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']);
      await expect(page.getByText('Customer pickup saved').first()).toBeVisible();
      await shot(page, `seller-settings-pickup-after-${size.name}`);
    });

    test(`shipping & delivery at ${size.name}: driver pickup rows aligned`, async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.goto('/settings/shipping');
      const katy = page.getByTestId('shipping-location').nth(1).getByRole('form', { name: 'AfroTransact pickup' });
      await expect(katy).toBeVisible();
      const sw = katy.getByRole('switch', { name: /AfroTransact picks up here/ });
      if ((await sw.getAttribute('aria-checked')) !== 'true') await sw.click();
      await expect(katy.getByTestId('weekly-hours')).toBeVisible();
      await katy.getByRole('switch', { name: 'Monday' }).click();
      await katy.getByLabel('From, Monday').selectOption('09:00');
      await katy.getByLabel('To, Monday').selectOption('17:00');
      const rows = katy.getByTestId('weekly-row');
      for (let i = 0; i < 7; i++) await expectAligned(rows.nth(i), `driver row ${i + 1}`);
      await shot(page, `seller-settings-shipping-${size.name}`);
    });

    test(`locations at ${size.name}: field rows and buttons share a centre line`, async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.goto('/settings/locations');
      await expect(page.getByTestId('ship-from-address').first()).toBeVisible();
      // The row of buttons under each address (Edit, Set as default, Remove...) is one line of equal-height controls.
      const actionRows = page.getByTestId('ship-from-address').first().locator('div:has(> button):not(:has(> div))');
      const n = await actionRows.count();
      for (let i = 0; i < n; i++) {
        const boxes = await controlsOf(actionRows.nth(i));
        if (boxes.length < 2) continue;
        // On a phone buttons may wrap to a second line; each line is checked on its own.
        const lines = new Map<number, Box[]>();
        for (const b of boxes) lines.set(Math.round(b.cy / 4), [...(lines.get(Math.round(b.cy / 4)) ?? []), b]);
        for (const line of lines.values()) {
          const hs = line.map((b) => b.h);
          expect(Math.max(...hs) - Math.min(...hs), `locations action row ${i}: ${JSON.stringify(line)}`).toBeLessThanOrEqual(1);
        }
      }
      await shot(page, `seller-settings-locations-${size.name}`);
    });
  }
});
