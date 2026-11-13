import { type APIRequestContext, expect, type Page, type TestInfo } from '@playwright/test';

import { expectNoSeriousA11yViolations } from './a11y';

/*
 * Guided-tour drift guard. A tour is run for real, from the Help menu, through the mock BFF: each step must find its
 * `data-tour` target (or be marked optional), otherwise the tour reports "drift" and the test fails. The tour service
 * (web/packages/tour) dispatches an `at-tour` DOM event for every step and when it ends; nothing is sent anywhere.
 */

export interface TourEnded {
  type: 'ended';
  tourId: string;
  reason: string;
  shown: number;
  drift: string[];
}
interface TourStep {
  type: 'step';
  tourId: string;
  stepId: string;
  ordinal: number;
  total: number;
}
export type TourEvent = TourEnded | TourStep;

declare global {
  interface Window {
    __atTour?: TourEvent[];
  }
}

export const popover = (page: Page) => page.locator('[data-tour-popover]');
export const helpMenu = (page: Page) => page.locator('[data-tour="help-menu"]');

/** Starts recording tour events (call after the page has loaded; client-side navigation keeps the recorder). */
export async function recordTourEvents(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.__atTour = [];
    window.addEventListener('at-tour', (e) => window.__atTour?.push((e as CustomEvent<TourEvent>).detail));
  });
}

const eventsOf = (page: Page): Promise<TourEvent[]> => page.evaluate(() => window.__atTour ?? []);

/** Opens the Tour hub from the Help menu (a client-side navigation, so the event recorder stays). */
export async function openTourHub(page: Page): Promise<void> {
  await helpMenu(page).click();
  await page.locator('[data-tour-entry="hub"]').click();
  await expect(page.locator('[data-tour="tour-hub"]')).toBeVisible();
}

/** Opens Help, then the Tour hub, and starts the chapter (the hub's buttons carry data-tour-entry="<chapter id>"). */
export async function openTourFromHelp(page: Page, tourId: string): Promise<void> {
  await openTourHub(page);
  await page.locator(`[data-tour-entry="${tourId}"]`).click();
}

/** Forgets the signed-in person's tour progress on the (mock) server, so a test starts from "never seen anything". */
export async function resetTourProgress(request: APIRequestContext, baseURL: string): Promise<void> {
  const res = await request.delete(`${baseURL}/api/bff/v1/account/tour-progress`);
  expect(res.status(), await res.text()).toBe(204);
}

export interface ServerTourProgress {
  items: { tourId: string; contentVersion: number; status: string; lastStep: string | null }[];
  autoStartEnabled: boolean;
}

/** What the mock server holds for the person (ids, version, status, last step). */
export async function serverTourProgress(request: APIRequestContext, baseURL: string): Promise<ServerTourProgress> {
  const res = await request.get(`${baseURL}/api/bff/v1/account/tour-progress`);
  expect(res.status(), await res.text()).toBe(200);
  return (await res.json()) as ServerTourProgress;
}

export interface RunOptions {
  /** Run axe on the first popover that opens. */
  axe?: { testInfo: TestInfo; page: string };
  /** Advance with ArrowRight (ArrowLeft in right-to-left languages) instead of the Next button. */
  keyboard?: boolean;
  /**
   * What the person does on a step, by step id, instead of pressing Next (the tour never does it for them): for a step that waits
   * for a real click or for the page to change. After the act the run waits for the tour to move on by itself, or for Next if the
   * act is `{ then: 'next' }`.
   */
  acts?: Record<string, { act: (page: Page) => Promise<void>; then?: 'next' }>;
}

/** Runs one tour to its end from the Help menu and returns how it ended. Fails when a step is stuck. */
export async function runTour(page: Page, tourId: string, options: RunOptions = {}): Promise<{ ended: TourEnded; steps: TourStep[] }> {
  await recordTourEvents(page);
  await openTourFromHelp(page, tourId);
  let axeDone = false;
  for (let i = 0; i < 20; i++) {
    const seen = (await eventsOf(page)).length;
    const ended = (await eventsOf(page)).find((e): e is TourEnded => e.type === 'ended');
    if (ended) break;
    await expect(popover(page)).toBeVisible({ timeout: 15_000 });
    if (options.axe && !axeDone) {
      axeDone = true;
      await expectNoSeriousA11yViolations(page, options.axe.testInfo, { page: options.axe.page, include: '[data-tour-popover]' });
    }
    const stepId = [...(await eventsOf(page))].reverse().find((e): e is TourStep => e.type === 'step')?.stepId ?? '';
    const own = options.acts?.[stepId];
    if (own) {
      await own.act(page);
      if (own.then !== 'next') {
        await page.waitForFunction((n) => (window.__atTour?.length ?? 0) > n, seen, { timeout: 20_000 });
        continue;
      }
    }
    if (options.keyboard) {
      const rtl = (await popover(page).getAttribute('dir')) === 'rtl';
      await page.keyboard.press(rtl ? 'ArrowLeft' : 'ArrowRight');
    } else {
      await popover(page).locator('.at-tour-next').click();
    }
    await page.waitForFunction((n) => (window.__atTour?.length ?? 0) > n, seen, { timeout: 20_000 });
  }
  const events = await eventsOf(page);
  const ended = events.find((e): e is TourEnded => e.type === 'ended');
  expect(ended, `tour "${tourId}" did not end`).toBeDefined();
  await expect(popover(page)).toHaveCount(0);
  return { ended: ended as TourEnded, steps: events.filter((e): e is TourStep => e.type === 'step') };
}

/** The drift guard: every non-optional step of the tour found its target and its page. */
export function expectNoDrift(result: { ended: TourEnded }, expectedShown: number): void {
  expect(result.ended.drift, `steps of "${result.ended.tourId}" whose data-tour target or page is missing (the page changed and the tour did not)`).toEqual([]);
  expect(result.ended.shown, `steps of "${result.ended.tourId}" shown`).toBe(expectedShown);
  expect(result.ended.reason).toBe('completed');
}
