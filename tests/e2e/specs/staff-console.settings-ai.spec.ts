import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';
import { noSidewaysScroll, SIZES, shotter } from '../support/layout';

/* Settings > AI and MCP spend cap against the in-process mock BFF (Super Admin): today's spend against the cap by feature, the cap shown
   read-only with where it is set. Measured at 1280 and 390: each feature's cost shares one right edge and its name one left edge, the meter
   fits the column, nothing scrolls sideways, and there is no control that could change the cap. */
test.describe.configure({ mode: 'default' });

for (const size of SIZES) {
  test.describe(`staff console · AI spend at ${size.name}`, () => {
    test.beforeEach(async ({ page }) => {
      await page.setViewportSize({ width: size.width, height: size.height });
    });

    test(`today's spend by feature lines up, the cap is read-only @a11y`, async ({ page }, testInfo) => {
      const shot = shotter(page);
      await page.goto('/settings/ai');
      await expect(page.getByRole('heading', { level: 1, name: 'AI and MCP spend cap' })).toBeVisible();
      await expect(page.getByTestId('ai-spent')).toContainText('of USD 2.00 used');
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
      // read-only: nothing here changes the cap
      await expect(page.getByTestId('ai-spend')).toContainText('AI_GLOBAL_DAILY_COST_USD');
      expect(await page.locator('main input, main select, main textarea, main [role="switch"]').count()).toBe(0);
      await noSidewaysScroll(page, 'ai spend');
      await shot(`staff-ai-spend-${size.name}`);
      await expectNoSeriousA11yViolations(page, testInfo, { page: `staff ai spend ${size.name}` });
    });
  });
}
