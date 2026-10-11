import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { type Browser, type BrowserContext, expect, type Locator, type Page, type TestInfo } from '@playwright/test';

/*
 * Responsive audit (S-number in docs/backlog/backlog.csv): every page of an app at nine viewports, phone to desktop.
 *
 * Each page is loaded once per device class (phones: touch + mobile viewport meta; tablets: touch; desktops: mouse) and
 * resized through that class's widths. At each width an in-page probe measures:
 *
 *   page-overflow      critical  the document scrolls sideways (scrollWidth > clientWidth)
 *   element-overflow   major     an element sticks out past the viewport and nothing clips or scrolls it
 *   text-clipped       major     text is cut off by an overflow:hidden/clip box (ellipsis / line clamps are "truncated")
 *   covered            major     at the end of the page a fixed/sticky layer covers a control (nothing scrolls it free)
 *   cta-covered        critical  the page's primary action, scrolled into view, sits under a fixed/sticky layer
 *   sticky-tall        major     the sticky top layer is taller than 25% of a phone's viewport once the page has scrolled
 *   cramped-cell       major     a table cell squeezed under 56px with text in it (wraps letter by letter)
 *   content-pushed     major     on a phone, <main> starts below 60% of the screen (a sidebar or banners above it)
 *   text-truncated     minor     ellipsis or line clamp (intentional; listed so long values stay reviewable)
 *   tap-target         minor     a control under 44×44 px at touch widths (inline links in running text excepted)
 *   tap-target-tiny    major     a control under 24×24 px at touch widths (WCAG 2.2 2.5.8 minimum, spacing exception aside)
 *   font-small         minor     text under 12px
 *   field-narrow       minor     a phone form field narrower than 80% of its column with nothing beside it
 *   image-no-box       minor     an <img> whose box height comes from the loaded file (no width/height or aspect ratio)
 *   cls                minor     cumulative layout shift over 0.1 while the page loaded
 *
 * RESPONSIVE_OUT=<dir> writes one JSON per page (and screenshots with RESPONSIVE_SHOTS=viewport|full) for
 * tests/e2e/scripts/responsive-report.mjs; the specs fail on any critical/major issue that isn't allow-listed below.
 */

export type DeviceKind = 'phone' | 'tablet' | 'desktop';
export type Severity = 'critical' | 'major' | 'minor';

export interface Viewport {
  width: number;
  height: number;
  kind: DeviceKind;
}

export const VIEWPORTS: readonly Viewport[] = [
  { width: 320, height: 568, kind: 'phone' },
  { width: 360, height: 800, kind: 'phone' },
  { width: 390, height: 844, kind: 'phone' },
  { width: 414, height: 896, kind: 'phone' },
  { width: 768, height: 1024, kind: 'tablet' },
  { width: 834, height: 1194, kind: 'tablet' },
  { width: 1024, height: 768, kind: 'tablet' },
  { width: 1280, height: 800, kind: 'desktop' },
  { width: 1440, height: 900, kind: 'desktop' }
];

export interface Issue {
  check: string;
  severity: Severity;
  selector: string;
  detail: string;
}

export interface PageResult {
  app: string;
  route: string;
  label: string;
  width: number;
  height: number;
  kind: DeviceKind;
  issues: Issue[];
  metrics: { scrollWidth: number; clientWidth: number; stickyTop: number; cls: number };
}

export interface AuditRoute {
  /** Short name (report row, screenshot file). */
  label: string;
  path: string;
  /** The page's primary action (Add to cart, Check out…): it must not sit under a fixed layer once scrolled into view. */
  cta?: (page: Page) => Locator;
  /** Runs once per browser context before the page is opened (e.g. fills the cart). */
  setup?: (page: Page) => Promise<void>;
  /** Waits for the page's content (default: the first h1). */
  ready?: (page: Page) => Promise<void>;
  /** An interaction to audit as well (opens a sheet, a menu…); runs after the at-rest audit at every width. */
  open?: { label: string; run: (page: Page, vp: Viewport) => Promise<void> };
}

const SEVERITY_FOR: Record<string, Severity> = {
  'page-overflow': 'critical',
  'element-overflow': 'major',
  'text-clipped': 'major',
  covered: 'major',
  'cta-covered': 'critical',
  'sticky-tall': 'major',
  'tap-target-tiny': 'major',
  'cramped-cell': 'major',
  'content-pushed': 'major',
  'text-truncated': 'minor',
  'tap-target': 'minor',
  'font-small': 'minor',
  'field-narrow': 'minor',
  'image-no-box': 'minor',
  cls: 'minor'
};

interface ProbeOptions {
  kind: DeviceKind;
  cta: string | null;
  scope: string | null;
  cls: boolean;
  /** The page at rest (no sheet or menu open): fixed layers must not cover controls. */
  rest: boolean;
}

interface RawIssue {
  check: string;
  selector: string;
  detail: string;
}

interface ProbeResult {
  issues: RawIssue[];
  scrollWidth: number;
  clientWidth: number;
  stickyTop: number;
  cls: number;
}

/** Collects layout shifts from the first paint (read back by the probe). */
export const CLS_INIT_SCRIPT = `(() => {
  window.__atCls = 0;
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (!e.hadRecentInput) window.__atCls += e.value;
    }).observe({ type: 'layout-shift', buffered: true });
  } catch (e) { /* not supported */ }
})();`;

/* The in-page probe. Self-contained: Playwright serialises it into the page. */
async function probe(opts: ProbeOptions): Promise<ProbeResult> {
  const doc = document.documentElement;
  const vw = doc.clientWidth;
  const vh = window.innerHeight;
  const touch = opts.kind !== 'desktop';
  const issues: RawIssue[] = [];
  const scopeRoot: Element = (opts.scope ? document.querySelector(opts.scope) : null) ?? document.body;

  const cleanClass = (c: string) => {
    // CSS-module names: Header-module__XyZ12__bar → Header.bar; keep plain utility / BEM names as they are.
    const m = /^(.+?)-module__[\w-]+?__(.+)$/.exec(c);
    return m ? `${m[1]}.${m[2]}` : c;
  };
  const describe = (el: Element): string => {
    const parts: string[] = [];
    let cur: Element | null = el;
    for (let depth = 0; cur && cur !== document.body && depth < 3; depth += 1) {
      const tag = cur.tagName.toLowerCase();
      const testid = cur.getAttribute('data-testid');
      const id = cur.id && !/^[:«r]|^radix-|^headlessui-/.test(cur.id) ? `#${cur.id}` : '';
      const role = cur.getAttribute('role');
      const aria = cur.getAttribute('aria-label');
      const cls = Array.from(cur.classList)
        .filter((c) => !/^(hover|focus|md|sm|lg|xl|2xl|max|min|data|aria|group|peer|dark|print):/.test(c))
        .slice(0, 2)
        .map(cleanClass);
      let s = tag + id + (testid ? `[data-testid=${testid}]` : '') + (role ? `[role=${role}]` : '') + (cls.length ? `.${cls.join('.')}` : '');
      if (aria && depth === 0) s += `[aria-label="${aria.slice(0, 40)}"]`;
      parts.unshift(s);
      if (id || testid) break;
      cur = cur.parentElement;
    }
    return parts.join(' > ');
  };
  const textOf = (el: Element) => (el.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 50);
  const styleOf = (el: Element) => getComputedStyle(el);
  const isRendered = (el: Element): boolean => {
    if (el.closest('[hidden], [aria-hidden="true"] [aria-hidden="true"], template, nextjs-portal')) return false;
    if ((el as HTMLElement).inert || el.closest('[inert]')) return false;
    const cs = styleOf(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') return false;
    if (Number(cs.opacity) === 0) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };
  /** Visually hidden helpers (sr-only, skip links) and anything inside one. */
  const isVisuallyHidden = (el: Element): boolean => {
    for (let cur: Element | null = el; cur && cur !== document.body; cur = cur.parentElement) {
      const r = cur.getBoundingClientRect();
      const cs = styleOf(cur);
      if (r.width <= 2 || r.height <= 2) {
        if (cs.overflow !== 'visible' || cs.position === 'absolute' || cs.clip !== 'auto') return true;
      }
      if (cs.clipPath && cs.clipPath !== 'none' && /inset\(50%|inset\(100%/.test(cs.clipPath)) return true;
      if (cs.clip && cs.clip !== 'auto' && /rect\(0px,? 0px,? 0px,? 0px\)|rect\(1px,? 1px,? 1px,? 1px\)/.test(cs.clip)) return true;
      if (Number(cs.opacity) === 0) return true;
    }
    return false;
  };
  const clips = (v: string) => v === 'hidden' || v === 'clip';
  const scrolls = (v: string) => v === 'auto' || v === 'scroll';
  const fixedLayerOf = (el: Element): Element | null => {
    for (let cur: Element | null = el; cur && cur !== doc; cur = cur.parentElement) {
      const p = styleOf(cur).position;
      if (p === 'fixed' || p === 'sticky') return cur;
    }
    return null;
  };

  const all = Array.from(scopeRoot.querySelectorAll('*')).filter((el) => !(el instanceof SVGElement && el.tagName.toLowerCase() !== 'svg'));
  const rendered = all.filter(isRendered);

  // 1. Page overflow.
  const scrollWidth = doc.scrollWidth;
  if (scrollWidth > vw + 1) issues.push({ check: 'page-overflow', selector: 'html', detail: `scrollWidth ${scrollWidth}px > ${vw}px` });

  // 2. Elements past the viewport edge that nothing clips (html/body clipping only hides the problem).
  const offenders = new Set<Element>();
  for (const el of rendered) {
    const r = el.getBoundingClientRect();
    if (r.right <= vw + 1 && r.left >= -1) continue;
    if (isVisuallyHidden(el)) continue;
    let clipped = false;
    let fixed = false;
    for (let cur = el.parentElement; cur && cur !== document.body && cur !== doc; cur = cur.parentElement) {
      const cs = styleOf(cur);
      if (cs.position === 'fixed') fixed = true;
      if (cs.overflowX !== 'visible') {
        clipped = true;
        break;
      }
    }
    if (clipped) continue;
    const cs = styleOf(el);
    if (cs.position === 'fixed' || fixed) {
      // Off-canvas layers (fully outside) are not reachable; only partly visible ones are cut off.
      if (r.left >= vw || r.right <= 0) continue;
    }
    if (el.parentElement && offenders.has(el.parentElement)) {
      offenders.add(el);
      continue;
    }
    offenders.add(el);
    issues.push({ check: 'element-overflow', selector: describe(el), detail: `[${Math.round(r.left)}, ${Math.round(r.right)}] vs 0–${vw} "${textOf(el)}"` });
  }

  // 3. Clipped / truncated text: the text's own box against every clipping ancestor up to the nearest scroller.
  const textEls = rendered.filter((el) => Array.from(el.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim() !== ''));
  for (const el of textEls) {
    if (el.closest('input, textarea, select, option, script, style, noscript')) continue;
    if (isVisuallyHidden(el)) continue;
    const range = document.createRange();
    let tr: DOMRect | null = null;
    for (const n of Array.from(el.childNodes)) {
      if (n.nodeType !== Node.TEXT_NODE || (n.textContent ?? '').trim() === '') continue;
      range.selectNodeContents(n);
      const rr = range.getBoundingClientRect();
      if (rr.width === 0 && rr.height === 0) continue;
      tr = tr ? new DOMRect(Math.min(tr.left, rr.left), Math.min(tr.top, rr.top), Math.max(tr.right, rr.right) - Math.min(tr.left, rr.left), Math.max(tr.bottom, rr.bottom) - Math.min(tr.top, rr.top)) : rr;
    }
    if (!tr) continue;
    for (let cur: Element | null = el; cur && cur !== document.body && cur !== doc; cur = cur.parentElement) {
      const cs = styleOf(cur);
      const sx = scrolls(cs.overflowX);
      const sy = scrolls(cs.overflowY);
      const cx = clips(cs.overflowX);
      const cy = clips(cs.overflowY);
      if (!cx && !cy) {
        if (sx || sy) break;
        continue;
      }
      const box = cur.getBoundingClientRect();
      if ((cur as HTMLElement).clientWidth < 2 || (cur as HTMLElement).clientHeight < 2) break;
      const left = box.left + (cur as HTMLElement).clientLeft;
      const top = box.top + (cur as HTMLElement).clientTop;
      const right = left + (cur as HTMLElement).clientWidth;
      const bottom = top + (cur as HTMLElement).clientHeight;
      // Fully outside: an off-screen slide or a collapsed panel, hidden on purpose.
      const outside = tr.right <= left || tr.left >= right || tr.bottom <= top || tr.top >= bottom;
      if (outside) break;
      const overX = cx && (tr.right > right + 1 || tr.left < left - 1);
      const overY = cy && (tr.bottom > bottom + 2 || tr.top < top - 2);
      if (overX || overY) {
        const ellipsis = styleOf(el).textOverflow === 'ellipsis' || cs.textOverflow === 'ellipsis';
        const clamp = (styleOf(el).webkitLineClamp && styleOf(el).webkitLineClamp !== 'none') || (cs.webkitLineClamp && cs.webkitLineClamp !== 'none');
        if (ellipsis || clamp) {
          issues.push({ check: 'text-truncated', selector: describe(el), detail: `${ellipsis ? 'ellipsis' : 'line clamp'} "${textOf(el)}"` });
        } else {
          issues.push({
            check: 'text-clipped',
            selector: describe(el),
            detail: `${overX ? 'x' : 'y'} by ${Math.round(overX ? Math.max(tr.right - right, left - tr.left) : Math.max(tr.bottom - bottom, top - tr.top))}px in ${describe(cur).split(' > ').pop()} "${textOf(el)}"`
          });
        }
        break;
      }
      if (sx || sy) break;
    }
  }

  // 3b. Cramped table cells: a column squeezed below ~56px wraps its text letter by letter.
  for (const el of rendered) {
    if (el.tagName !== 'TD' && el.tagName !== 'TH') continue;
    if (styleOf(el).display !== 'table-cell' || isVisuallyHidden(el)) continue;
    const w = (el as HTMLElement).clientWidth;
    const text = (el as HTMLElement).innerText.replace(/\s+/g, ' ').trim();
    if (w > 0 && w < 56 && text.length >= 4) issues.push({ check: 'cramped-cell', selector: describe(el), detail: `${w}px wide "${text.slice(0, 40)}"` });
  }

  // 3c. Content pushed down: the page's main region starts below 60% of a phone screen (a sidebar or banners above it).
  if (opts.kind === 'phone' && opts.rest) {
    const main = document.querySelector('main');
    if (main) {
      const top = Math.round(main.getBoundingClientRect().top + window.scrollY);
      if (top > vh * 0.6) issues.push({ check: 'content-pushed', selector: describe(main), detail: `main starts at ${top}px of a ${vh}px screen` });
    }
  }

  // 4. Fonts under 12px.
  for (const el of textEls) {
    if (isVisuallyHidden(el)) continue;
    const fs = parseFloat(styleOf(el).fontSize);
    if (fs < 12) issues.push({ check: 'font-small', selector: describe(el), detail: `${fs}px "${textOf(el)}"` });
  }

  // 5. Tap targets at touch widths. An absolutely positioned ::before/::after inside the control (a hit-area extender,
  //    e.g. `inset: -6px 0`) counts as part of the target: taps on it reach the control.
  const hitBox = (el: Element): DOMRect => {
    const r = el.getBoundingClientRect();
    let { left, top, right, bottom } = r;
    // The extender only counts when the control doesn't clip it (overflow: hidden would cut it off).
    if (styleOf(el).position !== 'static' && styleOf(el).overflow === 'visible') {
      for (const pseudo of ['::before', '::after']) {
        const ps = getComputedStyle(el, pseudo);
        if (ps.content === 'none' || ps.content === 'normal' || ps.position !== 'absolute' || ps.display === 'none' || ps.pointerEvents === 'none') continue;
        const px = (v: string) => (v.endsWith('px') ? parseFloat(v) : 0);
        left = Math.min(left, r.left + px(ps.left));
        top = Math.min(top, r.top + px(ps.top));
        right = Math.max(right, r.right - px(ps.right));
        bottom = Math.max(bottom, r.bottom - px(ps.bottom));
      }
    }
    return new DOMRect(left, top, right - left, bottom - top);
  };
  const TARGETS = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="link"], [role="tab"], [role="checkbox"], [role="radio"], [role="switch"], [role="menuitem"], [role="option"]';
  if (touch) {
    const targets: { el: Element; r: DOMRect }[] = [];
    const small: { el: Element; r: DOMRect; w: number; h: number }[] = [];
    for (const el of rendered) {
      if (!el.matches(TARGETS)) continue;
      if ((el as HTMLButtonElement).disabled || el.getAttribute('aria-disabled') === 'true') continue;
      // Parked above the document until focused (skip links).
      if (el.getBoundingClientRect().bottom + window.scrollY <= 0) continue;
      if (el.parentElement?.closest(TARGETS) && !el.matches('input, select, textarea')) continue;
      let r = hitBox(el);
      // Checkboxes / radios: the label is the target.
      if (el.matches('input[type="checkbox"], input[type="radio"], [role="checkbox"], [role="radio"]')) {
        const label = el.closest('label') ?? (el.id ? document.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null);
        if (label) r = hitBox(label);
      } else if (isVisuallyHidden(el)) {
        continue;
      }
      // Inline links inside running text are exempt (WCAG 2.5.8 "inline").
      if (el.tagName === 'A' && styleOf(el).display === 'inline') {
        const parent = el.parentElement;
        if (parent && Array.from(parent.childNodes).some((n) => n !== el && n.nodeType === Node.TEXT_NODE && (n.textContent ?? '').trim().length > 1)) continue;
      }
      const w = Math.round(r.width);
      const h = Math.round(r.height);
      targets.push({ el, r });
      if (w < 24 || h < 24) small.push({ el, r, w, h });
      else if (w < 44 || h < 44) issues.push({ check: 'tap-target', selector: describe(el), detail: `${w}×${h} "${textOf(el) || el.getAttribute('aria-label') || ''}"` });
    }
    // Under 24 px: allowed when a 24 px square centred on it touches no other target (WCAG 2.2 2.5.8 spacing exception).
    const grow = (r: DOMRect) => {
      const w = Math.max(24, r.width);
      const h = Math.max(24, r.height);
      return { l: r.left + r.width / 2 - w / 2, t: r.top + r.height / 2 - h / 2, r: r.left + r.width / 2 + w / 2, b: r.top + r.height / 2 + h / 2 };
    };
    for (const s of small) {
      const a = grow(s.r);
      const crowded = targets.some((o) => {
        if (o.el === s.el || o.el.contains(s.el) || s.el.contains(o.el)) return false;
        const b = o.r;
        return a.l < b.right - 0.5 && a.r > b.left + 0.5 && a.t < b.bottom - 0.5 && a.b > b.top + 0.5;
      });
      issues.push({ check: crowded ? 'tap-target-tiny' : 'tap-target', selector: describe(s.el), detail: `${s.w}×${s.h}${crowded ? ' (crowded)' : ''} "${textOf(s.el) || s.el.getAttribute('aria-label') || ''}"` });
    }
  }

  // 6. Phone form fields narrower than their column.
  if (opts.kind === 'phone') {
    const fields = rendered.filter((el) =>
      el.matches('input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="color"]):not([type="file"]):not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="number"]), select, textarea')
    );
    const controls = rendered.filter((el) => el.matches('input:not([type="hidden"]), select, textarea, button, a[href], [role="button"]'));
    for (const f of fields) {
      if (isVisuallyHidden(f)) continue;
      const col = f.closest('form, fieldset, [role="dialog"], [role="search"], section, main') ?? document.body;
      const cs = styleOf(col);
      const avail = (col as HTMLElement).clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      const r = f.getBoundingClientRect();
      if (r.width >= avail * 0.8) continue;
      const shares = controls.some((o) => {
        if (o === f || f.contains(o) || o.contains(f)) return false;
        const q = o.getBoundingClientRect();
        return q.bottom > r.top + 4 && q.top < r.bottom - 4 && (q.right <= r.left + 2 || q.left >= r.right - 2);
      });
      if (shares) continue;
      issues.push({ check: 'field-narrow', selector: describe(f), detail: `${Math.round(r.width)}px of ${Math.round(avail)}px` });
    }
  }

  // 7. Images whose height depends on the file: an empty clone in the same place collapses.
  for (const img of rendered.filter((el): el is HTMLImageElement => el instanceof HTMLImageElement)) {
    if (img.hasAttribute('width') && img.hasAttribute('height')) continue;
    const ar = styleOf(img).aspectRatio;
    if (ar && ar !== 'auto') continue;
    const h0 = img.getBoundingClientRect().height;
    if (h0 < 24) continue;
    const clone = img.cloneNode(false) as HTMLImageElement;
    clone.removeAttribute('srcset');
    clone.removeAttribute('src');
    clone.alt = '';
    clone.removeAttribute('id');
    img.after(clone);
    const h1 = clone.getBoundingClientRect().height;
    clone.remove();
    if (h1 < h0 * 0.5) issues.push({ check: 'image-no-box', selector: describe(img), detail: `${Math.round(h0)}px tall from the file, ${Math.round(h1)}px without it` });
  }

  // 8. Fixed / sticky layers covering controls at rest (top and bottom of the page) and the primary CTA.
  const covered = new Set<Element>();
  const checkCovered = (scrolled: boolean) => {
    for (const el of rendered) {
      if (!el.matches(TARGETS) || covered.has(el)) continue;
      if (fixedLayerOf(el)) continue;
      if (isVisuallyHidden(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.top < 0 || r.bottom > vh || r.left < 0 || r.right > vw || r.width < 4 || r.height < 4) continue;
      const cx0 = r.left + r.width / 2;
      const cy0 = r.top + r.height / 2;
      // Scrolled out of its own scroll box (a tab row, a carousel): not on screen, nothing covers it.
      let scrolledAway = false;
      for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
        if (styleOf(a).overflowX === 'visible' && styleOf(a).overflowY === 'visible') continue;
        const b = a.getBoundingClientRect();
        if (cx0 < b.left || cx0 > b.right || cy0 < b.top || cy0 > b.bottom) {
          scrolledAway = true;
          break;
        }
      }
      if (scrolledAway) continue;
      const hit = document.elementFromPoint(cx0, cy0);
      if (!hit || hit === el || el.contains(hit) || hit.contains(el)) continue;
      const layer = fixedLayerOf(hit);
      if (!layer || layer.contains(el)) continue;
      // Scrolled: content passing under the sticky header is expected; only layers off the top edge count.
      if (scrolled && layer.getBoundingClientRect().top <= 2) continue;
      covered.add(el);
      issues.push({ check: 'covered', selector: describe(el), detail: `under ${describe(layer)} "${textOf(el) || el.getAttribute('aria-label') || ''}"` });
    }
  };
  const y0 = window.scrollY;
  window.scrollTo(0, 0);
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  // At the end of the page nothing can scroll out from under a layer any more: a control covered there is unreachable.
  // (Higher up, sticky bars cover content that scrolling reveals; the primary CTA is checked on its own below.)
  if (opts.rest) {
    window.scrollTo(0, doc.scrollHeight);
    await new Promise((r) => setTimeout(r, 120));
    checkCovered(window.scrollY > 0);
  }

  // Sticky top layer once the page has scrolled down from the top (collapsing bars have collapsed).
  window.scrollTo(0, 0);
  await new Promise((r) => setTimeout(r, 120));
  window.scrollTo(0, Math.min(800, Math.max(0, doc.scrollHeight - vh)));
  await new Promise((r) => setTimeout(r, 250));
  let stickyTop = 0;
  for (const el of Array.from(document.querySelectorAll('body *'))) {
    const cs = styleOf(el);
    if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
    if (!isRendered(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.top <= 1 && r.bottom > 0 && r.width >= vw * 0.9) stickyTop = Math.max(stickyTop, Math.round(r.bottom));
  }
  if (opts.kind === 'phone' && stickyTop > vh * 0.25) issues.push({ check: 'sticky-tall', selector: 'sticky top layer', detail: `${stickyTop}px of ${vh}px (${Math.round((stickyTop / vh) * 100)}%)` });

  if (opts.cta) {
    const cta = document.querySelector(opts.cta);
    if (cta && isRendered(cta)) {
      cta.scrollIntoView({ block: 'center' });
      await new Promise((r) => setTimeout(r, 150));
      const r = cta.getBoundingClientRect();
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      if (hit && hit !== cta && !cta.contains(hit) && !hit.contains(cta)) {
        const layer = fixedLayerOf(hit);
        issues.push({ check: 'cta-covered', selector: describe(cta), detail: `under ${layer ? describe(layer) : describe(hit)}` });
      }
    }
  }
  window.scrollTo(0, y0);

  // Layout shift is a load-time measure: only the first width of each device class reports it (resizes shift too).
  const cls = opts.cls ? Math.round(((window as unknown as { __atCls?: number }).__atCls ?? 0) * 1000) / 1000 : 0;
  if (cls > 0.1) issues.push({ check: 'cls', selector: 'page', detail: `CLS ${cls}` });

  return { issues, scrollWidth, clientWidth: vw, stickyTop, cls };
}

/** Allow-listed findings (selector regex per check) with the reason they stand. Keep this short. */
export interface Allowance {
  check: string;
  selector: RegExp;
  reason: string;
}

function dedupe(issues: Issue[]): Issue[] {
  const seen = new Map<string, Issue>();
  for (const i of issues) {
    const k = `${i.check}|${i.selector}|${i.detail}`;
    if (!seen.has(k)) seen.set(k, i);
  }
  return [...seen.values()];
}

const OUT = process.env.RESPONSIVE_OUT;
const SHOTS = process.env.RESPONSIVE_SHOTS ?? (OUT ? 'viewport' : 'none');

const slug = (s: string) => s.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();

async function settle(page: Page): Promise<void> {
  await page.evaluate(() => document.fonts?.ready.then(() => undefined));
  await page.waitForTimeout(250);
}

/** Runs the probe at the current viewport, saves the result (and a screenshot) when RESPONSIVE_OUT is set. */
async function auditHere(page: Page, app: string, label: string, route: string, vp: Viewport, ctaOf: AuditRoute['cta'] | null, cls: boolean, rest = true): Promise<PageResult> {
  await settle(page);
  let cta: string | null = null;
  if (ctaOf) {
    const marked = await ctaOf(page)
      .first()
      .evaluate((el) => el.setAttribute('data-audit-cta', ''), undefined, { timeout: 2_000 })
      .then(() => true)
      .catch(() => false);
    if (marked) cta = '[data-audit-cta]';
  }
  const raw = await page.evaluate(probe, { kind: vp.kind, cta, scope: null, cls, rest });
  const issues = dedupe(raw.issues.map((i) => ({ ...i, severity: SEVERITY_FOR[i.check] ?? 'minor' })));
  const result: PageResult = { app, route, label, width: vp.width, height: vp.height, kind: vp.kind, issues, metrics: { scrollWidth: raw.scrollWidth, clientWidth: raw.clientWidth, stickyTop: raw.stickyTop, cls: raw.cls } };
  if (OUT) {
    const dir = join(OUT, app);
    mkdirSync(join(dir, 'json'), { recursive: true });
    writeFileSync(join(dir, 'json', `${slug(label)}--${vp.width}.json`), JSON.stringify(result, null, 2));
    if (SHOTS !== 'none') {
      mkdirSync(join(dir, 'shots'), { recursive: true });
      await page.screenshot({ path: join(dir, 'shots', `${slug(label)}--${vp.width}.jpg`), type: 'jpeg', quality: 60, fullPage: SHOTS === 'full', animations: 'disabled', caret: 'hide' });
    }
  }
  return result;
}

export interface AuditOptions {
  app: string;
  baseURL: string;
  /** Cookies every context starts with (consent, mock role…). */
  cookies?: Parameters<BrowserContext['addCookies']>[0];
  allow?: Allowance[];
  /** Restricts the widths (RESPONSIVE_WIDTHS=360,768 does the same from the environment). */
  widths?: number[];
}

const contextFor = async (browser: Browser, kind: DeviceKind, vp: Viewport, opts: AuditOptions): Promise<BrowserContext> => {
  const ctx = await browser.newContext({
    baseURL: opts.baseURL,
    viewport: { width: vp.width, height: vp.height },
    deviceScaleFactor: 1,
    isMobile: kind === 'phone',
    hasTouch: kind !== 'desktop',
    locale: 'en-US',
    timezoneId: 'America/New_York',
    reducedMotion: 'reduce'
  });
  if (opts.cookies?.length) await ctx.addCookies(opts.cookies);
  await ctx.addInitScript(CLS_INIT_SCRIPT);
  return ctx;
};

const envWidths = (process.env.RESPONSIVE_WIDTHS ?? '')
  .split(',')
  .map((s) => Number(s.trim()))
  .filter((n) => n > 0);

/** Audits one route at every viewport and fails on critical/major findings that are not allow-listed. */
export async function auditRoute(browser: Browser, route: AuditRoute, opts: AuditOptions, testInfo: TestInfo): Promise<PageResult[]> {
  const widths = opts.widths ?? (envWidths.length ? envWidths : null);
  const vps = VIEWPORTS.filter((v) => !widths || widths.includes(v.width));
  const results: PageResult[] = [];
  for (const kind of ['phone', 'tablet', 'desktop'] as const) {
    const group = vps.filter((v) => v.kind === kind);
    if (!group.length) continue;
    const ctx = await contextFor(browser, kind, group[0] as Viewport, opts);
    try {
      const page = await ctx.newPage();
      if (route.setup) await route.setup(page);
      await page.goto(route.path);
      if (route.ready) await route.ready(page);
      else await expect(page.locator('h1').first()).toBeVisible();
      let first = true;
      for (const vp of group) {
        await page.setViewportSize({ width: vp.width, height: vp.height });
        results.push(await auditHere(page, opts.app, route.label, route.path, vp, route.cta ?? null, first));
        first = false;
        if (route.open) {
          await route.open.run(page, vp);
          results.push(await auditHere(page, opts.app, `${route.label} · ${route.open.label}`, route.path, vp, null, false, false));
          await page.keyboard.press('Escape').catch(() => undefined);
          // Reload so the next width starts from the page at rest.
          await page.goto(route.path);
          if (route.ready) await route.ready(page);
          else await expect(page.locator('h1').first()).toBeVisible();
        }
      }
    } finally {
      await ctx.close();
    }
  }
  const allow = opts.allow ?? [];
  const failing = results.flatMap((r) =>
    r.issues
      .filter((i) => i.severity !== 'minor' && !allow.some((a) => a.check === i.check && a.selector.test(i.selector)))
      .map((i) => `${r.width}px ${r.label}: [${i.check}] ${i.selector} — ${i.detail}`)
  );
  const minors = results.reduce((n, r) => n + r.issues.filter((i) => i.severity === 'minor').length, 0);
  await testInfo.attach('responsive.json', { body: JSON.stringify(results, null, 2), contentType: 'application/json' });
  if (minors) testInfo.annotations.push({ type: 'responsive-minor', description: `${minors} minor findings (tap targets, fonts, fields, images)` });
  expect(failing, `responsive issues on ${route.label}`).toEqual([]);
  return results;
}
