import { expect, type Locator, type Page } from '@playwright/test';

/* Layout measurements shared by the staff Settings journeys: controls are measured in the browser, not just looked at. */

export const SIZES = [
  { name: '1280', width: 1280, height: 900 },
  { name: '390', width: 390, height: 844 }
] as const;

/** SHOTS (optional) is a folder: screenshots for docs/engineering/img. */
export const shotter = (page: Page) => async (name: string, maxHeight = 0) => {
  const dir = process.env.SHOTS;
  if (!dir) return;
  if (!maxHeight) return void (await page.screenshot({ path: `${dir}/${name}.png`, fullPage: true }));
  const width = page.viewportSize()?.width ?? 1280;
  await page.screenshot({ path: `${dir}/${name}.png`, fullPage: true, clip: { x: 0, y: 0, width, height: maxHeight } });
};

export interface Box {
  cx: number;
  cy: number;
  l: number;
  r: number;
  h: number;
  w: number;
  label: string;
}

/** Position of every visible control (input, select, textarea, button, switch) inside a container. */
export async function controlsOf(row: Locator): Promise<Box[]> {
  return row.evaluate((el) => {
    const out: { cx: number; cy: number; l: number; r: number; h: number; w: number; label: string }[] = [];
    for (const c of el.querySelectorAll<HTMLElement>('button, select, input, textarea, [role="switch"]')) {
      const b = c.getBoundingClientRect();
      if (b.width === 0 || b.height === 0) continue;
      out.push({ cx: b.left + b.width / 2, cy: b.top + b.height / 2, l: b.left, r: b.right, h: b.height, w: b.width, label: c.getAttribute('aria-label') ?? c.textContent?.trim() ?? c.tagName });
    }
    return out;
  });
}

/**
 * Controls of one row: one height always; on a wide screen one centre line too. On a phone a row wraps, and the controls that land on
 * the same line (centres within 12px) must share it to the pixel.
 */
export async function expectOneLine(row: Locator, what: string, wide: boolean) {
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

/** A stack of fields: every control starts and ends on the same vertical lines (left and right edges within 1px). */
export async function expectSameColumn(container: Locator, what: string, selector = 'input, select, textarea') {
  const edges = await container.evaluate((el, sel) => {
    return [...el.querySelectorAll<HTMLElement>(sel)]
      .map((c) => c.getBoundingClientRect())
      .filter((b) => b.width > 0 && b.height > 0)
      .map((b) => ({ l: b.left, r: b.right }));
  }, selector);
  expect(edges.length, `${what}: has fields`).toBeGreaterThan(1);
  expect(Math.max(...edges.map((e) => e.l)) - Math.min(...edges.map((e) => e.l)), `${what}: left edges ${JSON.stringify(edges)}`).toBeLessThanOrEqual(1);
  expect(Math.max(...edges.map((e) => e.r)) - Math.min(...edges.map((e) => e.r)), `${what}: right edges ${JSON.stringify(edges)}`).toBeLessThanOrEqual(1);
}

export const noSidewaysScroll = async (page: Page, what: string) => {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(over, `${what}: horizontal overflow`).toBeLessThanOrEqual(1);
};
