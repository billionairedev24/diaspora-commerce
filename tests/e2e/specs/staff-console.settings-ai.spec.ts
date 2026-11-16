import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { expectOneLine, noSidewaysScroll, shotter,SIZES } from '../support/layout';

/* Settings > AI and MCP spend cap against the in-process mock BFF (Super Admin, who holds ai:cap.manage): today's spend against the cap by
   feature, what is in force (cap, source, ceiling, left today; the mock server keeps a saved cap for the whole run, so the second size starts from the first one's change), the change form with its confirm step and required reason, and the history
   by name. Measured at 1280 and 390: each feature's cost shares one right edge and its name one left edge, the "in force" and history rows
   share their name and value columns, the dollar field and its button share one height (and one line on a wide screen), the meter fits
   the column, and nothing scrolls sideways. */
test.describe.configure({ mode: 'default' });

for (const size of SIZES) {
  test.describe(`staff console · AI spend at ${size.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
    });

    test(`today's spend by feature lines up, what is in force is aligned, the form's field and button line up @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/ai');
      await expect(page.getByRole('heading', { level: 1, name: 'AI and MCP spend cap' })).toBeVisible();
      await expect(page.getByTestId('ai-spent')).toContainText(/of USD \d+\.\d{2} used/);
      const list = page.getByTestId('ai-by-feature');
      await expect(list.getByText('Lola')).toBeVisible();
      const edges = await list.locator('li').evaluateAll((rows) =>
        rows.map((r) => {
          const kids = [...r.children].map((c) => c.getBoundingClientRect()).filter((b) => b.width > 0 && b.height > 0);
          const first = kids[0];
          const last = kids[kids.length - 1];
          return { l: Math.round(first?.left ?? 0), r: Math.round(last?.right ?? 0), cy: kids.map((b) => Math.round(b.top + b.height / 2)) };
        })
      );
      expect(edges.length).toBeGreaterThan(1);
      expect(new Set(edges.map((e) => e.l)).size, `name left edges ${JSON.stringify(edges)}`).toBe(1);
      expect(new Set(edges.map((e) => e.r)).size, `cost right edges ${JSON.stringify(edges)}`).toBe(1);
      for (const e of edges) expect(Math.max(...e.cy) - Math.min(...e.cy), `row centres ${JSON.stringify(e)}`).toBeLessThanOrEqual(1);
      // the meter stays inside the content column
      const meter = await page.getByRole('progressbar').boundingBox();
      expect((meter?.x ?? 0) + (meter?.width ?? 0)).toBeLessThanOrEqual(size.width);
      // what is in force: names share one left edge, values another; the ceiling and where the cap comes from are stated in words
      const force = page.getByTestId('ai-spend');
      await expect(force).toContainText('USD 20.00');
      await expect(force).toContainText(/starting value set on the deployment|Set by/);
      const cols = await force.locator('> div').evaluateAll((rows) =>
        rows.map((r) => {
          const [dt, dd] = [r.querySelector('dt')?.getBoundingClientRect(), r.querySelector('dd')?.getBoundingClientRect()];
          return { dtL: Math.round(dt?.left ?? 0), ddL: Math.round(dd?.left ?? 0), ddR: Math.round(dd?.right ?? 0) };
        })
      );
      expect(cols.length).toBeGreaterThan(5);
      expect(new Set(cols.map((c) => c.dtL)).size, `names ${JSON.stringify(cols)}`).toBe(1);
      if (size.width >= 640) expect(new Set(cols.map((c) => c.ddL)).size, `values ${JSON.stringify(cols)}`).toBe(1);
      for (const c of cols) expect(c.ddR, `value inside the screen ${JSON.stringify(c)}`).toBeLessThanOrEqual(size.width);
      // the form: the dollar field and the review button share a height, and one centre line when they sit side by side
      const editor = page.getByTestId('ai-cap-editor');
      await expect(editor.getByRole('textbox', { name: 'New daily limit (USD)' })).toHaveValue(/^\d+\.\d{2}$/);
      await expectOneLine(editor.locator('form'), 'limit field and review button', size.width >= 640);
      await noSidewaysScroll(page, 'ai spend');
      await shot(`staff-ai-spend-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ai spend ${size.name}` });
    });

    test(`change the limit: a confirm step with a required reason, then the new cap and a history row by name @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/ai');
      const editor = page.getByTestId('ai-cap-editor');
      const amount = size.name === '390' ? '3.25' : '4.50';
      await editor.getByRole('textbox', { name: 'New daily limit (USD)' }).fill('abc');
      await expect(page.getByTestId('ai-edit-note')).toContainText('Enter an amount in dollars');
      await expect(editor.getByRole('button', { name: 'Review the change' })).toBeDisabled();
      await editor.getByRole('textbox', { name: 'New daily limit (USD)' }).fill(amount);
      await editor.getByRole('button', { name: 'Review the change' }).click();
      const dialog = page.getByRole('dialog', { name: 'Change the daily AI limit' });
      await expect(dialog).toBeVisible();
      await expect(dialog).toContainText(`to USD ${amount} per UTC day`);
      // the reason is required: nothing is saved without it, and the problem is on the field
      await dialog.getByRole('button', { name: 'Change the limit' }).click();
      await expect(dialog).toContainText('Say why, at least 3 characters');
      await shot(`staff-ai-cap-confirm-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ai cap confirm ${size.name}` });
      await dialog.getByRole('textbox', { name: /Why are you changing it/ }).fill(`Promotion week (${size.name})`);
      await dialog.getByRole('button', { name: 'Change the limit' }).click();
      await expect(dialog).toBeHidden();
      await expect(page.getByTestId('ai-spend')).toContainText(`USD ${amount} per UTC day`);
      await expect(page.getByTestId('ai-spend')).toContainText(`Promotion week (${size.name})`);
      // the history lists the change by name (never an id), newest first, and its rows share their name and detail columns
      const rows = page.getByTestId('ai-cap-change');
      await expect(rows.first()).toContainText(`Promotion week (${size.name})`);
      await expect(rows.first()).toContainText(`to USD ${amount}`);
      await expect(rows.first()).not.toContainText(/[0-9a-f]{8}-[0-9a-f]{4}-/);
      const lefts = await rows.evaluateAll((els) => els.map((e) => [...e.children].map((c) => Math.round(c.getBoundingClientRect().left))));
      expect(new Set(lefts.map((l) => l[0])).size, `history name edges ${JSON.stringify(lefts)}`).toBe(1);
      if (size.width >= 640 && lefts.length > 1) expect(new Set(lefts.map((l) => l[1])).size, `history detail edges ${JSON.stringify(lefts)}`).toBe(1);
      await noSidewaysScroll(page, 'ai spend after a change');
      await shot(`staff-ai-cap-changed-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ai cap changed ${size.name}` });
    });
  });
}
