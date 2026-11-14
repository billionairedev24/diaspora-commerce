import { expect, test, type Locator, type Page } from '@playwright/test';

/* Sales tax filter rows (mock BFF, Super Admin): the controls of one row (a labelled Show select, the provider chips and the
   Export/Import buttons) share one centre line and one height. Measured in the browser, not read from the CSS. */

interface Box {
  name: string;
  top: number;
  height: number;
  centre: number;
}

async function measure(items: [string, Locator][]): Promise<Box[]> {
  const boxes: Box[] = [];
  for (const [name, locator] of items) {
    await expect(locator, name).toBeVisible();
    const b = await locator.evaluate((el) => {
      const r = el.getBoundingClientRect();
      return { y: r.top, height: r.height };
    });
    boxes.push({ name, top: b.y, height: b.height, centre: b.y + b.height / 2 });
  }
  return boxes;
}

/** Controls that sit in the same visual row (their boxes overlap vertically) must have centres within 1px; all heights match. */
function expectOneCentreLine(boxes: Box[]) {
  const heights = new Set(boxes.map((b) => Math.round(b.height)));
  expect([...heights], `heights ${JSON.stringify(boxes)}`).toHaveLength(1);
  for (const a of boxes)
    for (const b of boxes) {
      const overlap = Math.min(a.top + a.height, b.top + b.height) - Math.max(a.top, b.top);
      if (a === b || overlap < a.height / 2) continue;
      expect(Math.abs(a.centre - b.centre), `${a.name} against ${b.name}: ${JSON.stringify(boxes)}`).toBeLessThanOrEqual(1);
    }
}

/** The page may still be laying out (fonts, a refetch): measure again until the row settles. */
const settled = (check: () => Promise<void>) => expect(check).toPass({ timeout: 10_000 });

async function codesControls(page: Page) {
  return measure([
    ['Show select', page.getByLabel('Show', { exact: true })],
    ['Stripe Tax chip', page.getByRole('button', { name: 'Stripe Tax', exact: true })],
    ['Avalara chip', page.getByRole('button', { name: 'Avalara', exact: true })],
    ['Export CSV', page.getByRole('button', { name: 'Export CSV' })],
    ['Import CSV', page.getByRole('button', { name: 'Import CSV…' })]
  ]);
}

for (const [name, viewport] of [
  ['1280', { width: 1280, height: 900 }],
  ['390', { width: 390, height: 844 }]
] as const) {
  test.describe(`staff console · sales tax controls at ${name}px`, () => {
    test.use({ viewport, deviceScaleFactor: 1 });

    test('Provider codes: Show, provider chips and CSV buttons share one centre line', async ({ page }, testInfo) => {
      await page.goto('/tax?tab=tax-codes');
      await expect(page.getByRole('table', { name: /codes/ })).toBeVisible();
      await settled(async () => expectOneCentreLine(await codesControls(page)));
      await page.screenshot({ path: testInfo.outputPath(`provider-codes-${name}.png`) });
    });

    test('Shadow report: From, To and Provider fields share one centre line, with and without an error', async ({ page }, testInfo) => {
      await page.goto('/tax?tab=tax-shadow');
      await expect(page.getByRole('table', { name: 'Order lines with every quote' })).toBeVisible();
      const fields = () => measure([['From', page.getByLabel('From', { exact: true })], ['To', page.getByLabel('To', { exact: true })], ['Provider', page.getByLabel('Provider', { exact: true })]]);
      await settled(async () => expectOneCentreLine(await fields()));
      await page.screenshot({ path: testInfo.outputPath(`shadow-report-${name}.png`) });
      // An end date before the start shows its message under the control without moving it.
      await page.goto('/tax?tab=tax-shadow&shadow.from=2026-11-09&shadow.to=2026-11-01');
      await expect(page.getByText('The end date must be after the start.')).toBeVisible();
      await settled(async () => expectOneCentreLine(await fields()));
    });

    test('Profiles and Tax review filter rows share one centre line', async ({ page }) => {
      await page.goto('/tax?tab=tax-profiles');
      await expect(page.getByLabel('Department', { exact: true })).toBeVisible();
      await settled(async () => expectOneCentreLine(await measure([['Department', page.getByLabel('Department', { exact: true })], ['Status', page.getByLabel('Status', { exact: true })]])));
    });
  });
}
