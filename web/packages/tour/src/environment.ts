/*
 * Everything the tour code needs to know about the page lives behind TourEnvironment, so the service can be tested
 * without a browser and never touches `window` or `document` while rendering on the server (Next.js, Cloudflare Workers).
 */

/** What the tour did. `drift` lists the steps whose target (or page) could not be found although the step is not optional. */
export type TourEvent =
  | { type: 'step'; tourId: string; stepId: string; ordinal: number; total: number }
  | { type: 'ended'; tourId: string; reason: string; shown: number; drift: string[] };

export interface TourEnvironment {
  /** The visible element carrying `data-tour="<id>"`, or null (absent, or hidden such as a collapsed menu). */
  findTarget(id: string): HTMLElement | null;
  /** Resolves when the target is visible, or null after `timeoutMs` (or when aborted). */
  waitForTarget(id: string, timeoutMs: number, signal?: AbortSignal): Promise<HTMLElement | null>;
  /** Calls `listener` whenever the page's markup changes; returns the stop function. */
  observe(listener: () => void): () => void;
  /** Adds a one-time-per-call click listener on a target (capture phase). */
  onClick(target: HTMLElement, listener: () => void): () => void;
  viewportWidth(): number;
  reducedMotion(): boolean;
  direction(): 'ltr' | 'rtl';
  /**
   * The page is driven by automation (navigator.webdriver: Playwright, Selenium, Lighthouse): first-time tours then never start by
   * themselves, so they cannot cover a test run. A test that wants them sets localStorage `at.tour.autostart` to `force`.
   */
  automated(): boolean;
  /** A dialog, menu or sheet is open: tours never start on top of one. */
  hasBlockingUi(): boolean;
  /** A form on the page holds text the person typed and has not saved. */
  hasUnsavedInput(): boolean;
  /**
   * Tells the page what the tour did, as a DOM CustomEvent `at-tour` on window ({ type: 'step' | 'ended', tourId, ... }).
   * Nothing listens in production and nothing is sent anywhere; end-to-end tests listen to prove every step found its target.
   */
  emit(detail: TourEvent): void;
  /** Development builds log skipped steps; production stays quiet. */
  isDev(): boolean;
  log(message: string): void;
}

/** Selector for a target id. Ids are validated words, but the value is escaped anyway. */
export function targetSelector(id: string): string {
  const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function' ? CSS.escape(id) : id.replace(/"/g, '\\"');
  return `[data-tour="${escaped}"]`;
}

function visible(el: HTMLElement): boolean {
  if (el.hidden || el.closest('[hidden], [inert], [aria-hidden="true"]')) return false;
  const style = getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden') return false;
  return el.getClientRects().length > 0;
}

/** Open dialogs, drawers, menus and listboxes (ARIA modal dialogs, native dialogs, Radix menus). */
const BLOCKING = 'dialog[open], [role="dialog"]:not([data-tour-popover]), [role="alertdialog"], [aria-modal="true"]:not([data-tour-popover]), [role="menu"], [role="listbox"]';

/** The browser implementation. Create it on the client only (inside an effect or an event handler). */
export function createBrowserEnvironment(options: { dev?: boolean } = {}): TourEnvironment {
  const dev = options.dev ?? process.env.NODE_ENV !== 'production';
  return {
    findTarget(id) {
      const matches = Array.from(document.querySelectorAll<HTMLElement>(targetSelector(id)));
      return matches.find(visible) ?? null;
    },
    waitForTarget(id, timeoutMs, signal) {
      return new Promise((resolve) => {
        const found = (): HTMLElement | null => this.findTarget(id);
        const first = found();
        if (first) return resolve(first);
        let done = false;
        const finish = (el: HTMLElement | null) => {
          if (done) return;
          done = true;
          observer.disconnect();
          window.clearTimeout(timer);
          window.clearInterval(poll);
          signal?.removeEventListener('abort', onAbort);
          resolve(el);
        };
        const check = () => {
          const el = found();
          if (el) finish(el);
        };
        const observer = new MutationObserver(check);
        observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden', 'style', 'class', 'data-tour'] });
        // layout-only changes (a menu opening by CSS) do not mutate the DOM, so poll as well
        const poll = window.setInterval(check, 150);
        const timer = window.setTimeout(() => finish(null), timeoutMs);
        const onAbort = () => finish(null);
        signal?.addEventListener('abort', onAbort);
      });
    },
    observe(listener) {
      const observer = new MutationObserver(listener);
      observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden', 'data-tour'] });
      return () => observer.disconnect();
    },
    onClick(target, listener) {
      target.addEventListener('click', listener, true);
      return () => target.removeEventListener('click', listener, true);
    },
    viewportWidth: () => window.innerWidth,
    reducedMotion: () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false,
    direction: () => (document.documentElement.dir === 'rtl' || getComputedStyle(document.documentElement).direction === 'rtl' ? 'rtl' : 'ltr'),
    automated() {
      try {
        return navigator.webdriver === true && window.localStorage.getItem('at.tour.autostart') !== 'force';
      } catch {
        return navigator.webdriver === true;
      }
    },
    hasBlockingUi: () => document.querySelector(BLOCKING) !== null,
    hasUnsavedInput() {
      if (document.querySelector('[data-unsaved="true"]')) return true;
      const fields = document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('form input:not([type="hidden"],[type="checkbox"],[type="radio"],[type="file"],[type="search"]), form textarea');
      return Array.from(fields).some((f) => f.value !== f.defaultValue && f.value.trim() !== '');
    },
    emit: (detail) => {
      window.dispatchEvent(new CustomEvent('at-tour', { detail }));
    },
    isDev: () => dev,
    log: (message) => {
      if (dev) console.info(`[tour] ${message}`);
    }
  };
}
