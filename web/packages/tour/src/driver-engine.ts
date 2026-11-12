import { type Alignment, type Driver, type driver as createDriver, type DriveStep, type PopoverDOM, type Side } from 'driver.js';

import { type EngineCallbacks, type StepView, type TourEngine } from './engine';
import { type Placement } from './schema';

/*
 * TourEngine on Driver.js (MIT, no dependencies, ~14 kB gzipped). Driver draws the spotlight cut-out, positions the
 * popover and scrolls the target into view; everything else is ours: the popover's contents and buttons (so labels are
 * translated and styled with tokens), keyboard handling (Esc, arrows that follow the reading direction), the live
 * region, focus. Driver's own keyboard control is switched off.
 *
 * Driver.js touches `document` when it runs, never when it is imported; it is loaded on first use, so server
 * rendering and Cloudflare Workers never execute it.
 */

const FOCUSABLE_FIELD = 'input, textarea, select, [contenteditable=""], [contenteditable="true"]';

function sideOf(placement: Placement, dir: 'ltr' | 'rtl'): Side | undefined {
  switch (placement) {
    case 'top':
      return 'top';
    case 'bottom':
      return 'bottom';
    case 'start':
      return dir === 'rtl' ? 'right' : 'left';
    case 'end':
      return dir === 'rtl' ? 'left' : 'right';
    default:
      return undefined;
  }
}

/** Where the popover lines up with the target along the other axis: with the reading start of the target. */
function alignOf(placement: Placement, dir: 'ltr' | 'rtl'): Alignment {
  if (placement === 'top' || placement === 'bottom') return dir === 'rtl' ? 'end' : 'start';
  return 'center';
}

function fill(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function createDriverEngine(): TourEngine {
  return new DriverEngine();
}

class DriverEngine implements TourEngine {
  private factory: typeof createDriver | null = null;
  private driver: Driver | null = null;
  private callbacks: EngineCallbacks | null = null;
  private view: StepView | null = null;
  private opener: HTMLElement | null = null;
  private live: HTMLElement | null = null;
  private hiding = false;
  private keyHandler: ((e: KeyboardEvent) => void) | null = null;
  private frame = 0;

  async show(view: StepView, callbacks: EngineCallbacks): Promise<void> {
    const first = this.view === null;
    this.view = view;
    this.callbacks = callbacks;
    if (first) this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.factory ??= (await import('driver.js')).driver;
    if (this.view !== view) return; // hidden or replaced while Driver.js loaded
    if (!this.driver) {
      this.driver = this.factory({
        allowKeyboardControl: false,
        allowClose: false,
        allowScroll: true,
        overlayClickBehavior: 'none',
        overlayColor: 'currentColor',
        overlayOpacity: 1,
        stagePadding: 6,
        stageRadius: 8,
        popoverOffset: 12,
        disableActiveInteraction: false,
        onDestroyed: () => {
          // Driver ended by itself (not through hide()): treat it as the person closing the tour.
          if (!this.hiding && this.view) this.callbacks?.close();
        }
      });
      this.attachKeys();
    }
    this.driver.setConfig({ animate: !view.reducedMotion, smoothScroll: !view.reducedMotion });
    const step: DriveStep = {
      element: view.target ?? undefined,
      popover: {
        title: view.title,
        description: view.body,
        side: sideOf(view.placement, view.direction),
        align: alignOf(view.placement, view.direction),
        popoverClass: `at-tour-popover${view.sheet ? ' at-tour-sheet' : ''}${view.target ? '' : ' at-tour-centered'}`,
        showButtons: ['close'],
        closeBtnLabel: view.labels.close,
        onCloseClick: () => this.callbacks?.close(),
        onPopoverRender: (pop) => this.decorate(pop, view)
      }
    };
    this.driver.highlight(step);
    if (view.sheet && view.target) {
      // The sheet covers the lower part of the screen: bring the target to the top.
      const target = view.target;
      this.frame = window.requestAnimationFrame(() => target.scrollIntoView({ block: 'start', behavior: view.reducedMotion ? 'auto' : 'smooth' }));
    }
    this.say(`${first ? `${view.labels.started} ` : ''}${fill(view.labels.stepOf, { n: view.ordinal, total: view.total })}. ${view.title}. ${view.body}`);
  }

  hide(): void {
    this.view = null;
    this.callbacks = null;
    window.cancelAnimationFrame(this.frame);
    this.detachKeys();
    if (this.driver) {
      this.hiding = true;
      try {
        this.driver.destroy();
      } finally {
        this.hiding = false;
      }
      this.driver = null;
    }
    // Where focus was when the tour opened; a tour started from the Help menu opened from a menu item that is gone by now, so fall back to the Help button.
    const back = this.opener?.isConnected ? this.opener : document.querySelector<HTMLElement>('[data-tour="help-menu"]');
    this.opener = null;
    back?.focus({ preventScroll: true });
  }

  announce(message: string): void {
    this.say(message);
  }

  // ---------------------------------------------------------------- popover

  private decorate(pop: PopoverDOM, view: StepView): void {
    const { wrapper } = pop;
    wrapper.setAttribute('role', 'dialog');
    wrapper.setAttribute('aria-modal', 'false');
    wrapper.setAttribute('aria-label', view.labels.dialog);
    wrapper.setAttribute('data-tour-popover', '');
    wrapper.setAttribute('dir', view.direction);
    wrapper.setAttribute('tabindex', '-1');
    pop.title.setAttribute('role', 'heading');
    pop.title.setAttribute('aria-level', '2');
    pop.closeButton.setAttribute('aria-label', view.labels.close);
    // Driver keeps its own live region; ours is the only one that speaks.
    document.querySelector('.driver-live-region')?.setAttribute('aria-live', 'off');

    if (view.succeeded) pop.description.append(el('p', 'at-tour-success', `✓ ${view.labels.stepDone}`));

    const cb = this.callbacks;
    const count = el('span', 'at-tour-count', fill(view.labels.stepOf, { n: view.ordinal, total: view.total }));
    const never = el('label', 'at-tour-never');
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = view.dontShowAgain;
    box.addEventListener('change', () => cb?.setDontShowAgain(box.checked));
    never.append(box, el('span', '', view.labels.dontShowAgain));
    const meta = el('div', 'at-tour-meta');
    meta.append(count, never);

    const skip = el('button', 'at-tour-btn at-tour-skip', view.labels.skip);
    skip.type = 'button';
    skip.addEventListener('click', () => cb?.skip());
    const actions = el('div', 'at-tour-actions');
    actions.append(skip, el('span', 'at-tour-spacer'));
    if (!view.isFirst) {
      const back = el('button', 'at-tour-btn at-tour-back', view.labels.back);
      back.type = 'button';
      back.addEventListener('click', () => cb?.back());
      actions.append(back);
    }
    const next = el('button', 'at-tour-btn at-tour-next', view.isLast ? view.labels.done : view.labels.next);
    next.type = 'button';
    next.addEventListener('click', () => cb?.next());
    actions.append(next);

    pop.footer.replaceChildren(meta, actions);
    pop.footer.classList.add('at-tour-footer');
    // Driver hides its footer when it has no buttons of its own.
    pop.footer.style.setProperty('display', 'flex');
    window.requestAnimationFrame(() => next.focus({ preventScroll: true }));
  }

  // ---------------------------------------------------------------- keys

  private attachKeys(): void {
    if (this.keyHandler) return;
    this.keyHandler = (e: KeyboardEvent) => {
      const view = this.view;
      const cb = this.callbacks;
      if (!view || !cb || e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        cb.close();
        return;
      }
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
      const t = e.target;
      // Arrow keys belong to the page while someone is typing or choosing in a field.
      if (t instanceof HTMLElement && t.matches(FOCUSABLE_FIELD) && !t.closest('[data-tour-popover]')) return;
      if (t instanceof HTMLInputElement && t.closest('[data-tour-popover]')) return;
      e.preventDefault();
      const forward = view.direction === 'rtl' ? 'ArrowLeft' : 'ArrowRight';
      if (e.key === forward) cb.next();
      else if (!view.isFirst) cb.back();
    };
    document.addEventListener('keydown', this.keyHandler, true);
  }

  private detachKeys(): void {
    if (this.keyHandler) document.removeEventListener('keydown', this.keyHandler, true);
    this.keyHandler = null;
  }

  // ---------------------------------------------------------------- live region

  private say(message: string): void {
    if (!this.live || !this.live.isConnected) {
      const region = document.createElement('div');
      region.setAttribute('aria-live', 'polite');
      region.setAttribute('aria-atomic', 'true');
      region.setAttribute('data-tour-live', '');
      region.style.cssText = 'position:fixed;width:1px;height:1px;margin:-1px;padding:0;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap;border:0';
      document.body.append(region);
      this.live = region;
    }
    const region = this.live;
    // Clearing first makes a repeated message speak again.
    region.textContent = '';
    window.setTimeout(() => {
      region.textContent = message;
    }, 60);
  }
}
