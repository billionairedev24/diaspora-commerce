import { type HomeModuleEvent, type HomeTracking } from '@afrotransact/api-client';

import { browserApi } from '@/lib/api/browser';

/*
 * Organic home module analytics (S427, docs/engineering/dynamic-home.md). Aggregate counters only: the module key, its
 * position, the experiment variant and what was clicked. Nothing identifies the shopper. Everything is gated on the Analytics
 * cookie choice by the caller, and best effort: a failing counter never reaches the shopper.
 */

export type ModuleTarget = NonNullable<HomeModuleEvent['target']>;

/** What a link inside a module points at, from its URL. */
export function targetOf(href: string): ModuleTarget {
  const path = href.replace(/^https?:\/\/[^/]+/i, '');
  if (path.startsWith('/p/')) return 'product';
  if (/[?&]dept=/.test(path)) return 'department';
  if (path.startsWith('/communities') || /[?&]community=/.test(path)) return 'community';
  if (path.startsWith('/services')) return 'service';
  return 'other';
}

export function sendModuleEvent(t: HomeTracking, event: HomeModuleEvent['event'], target?: ModuleTarget): void {
  void browserApi()
    .recordHomeModuleEvent({ event, module: t.module, position: t.position, variant: t.variant, ...(target ? { target } : {}) })
    .catch(() => undefined);
}

/** The last module click, kept 24 hours in a first-party cookie so an order placed soon after can be attributed to its module (last click wins). */
export const ATTRIBUTION_COOKIE = 'at_hm';

export function rememberModuleClick(t: HomeTracking, secure: boolean): void {
  try {
    document.cookie = `${ATTRIBUTION_COOKIE}=${encodeURIComponent(`${t.module}:${t.position}`)}; path=/; max-age=86400; samesite=lax${secure ? '; secure' : ''}`;
  } catch {
    /* cookies blocked: no attribution, nothing else changes */
  }
}
