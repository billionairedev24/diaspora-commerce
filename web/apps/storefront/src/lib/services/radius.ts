import { SERVICE_RADIUS_STEPS, type ServiceRadius } from '@afrotransact/api-client';

/**
 * How far the shopper will go to a provider's shop. The shopper decides: nothing limits the choice, these are only the
 * steps the control offers. The choice is remembered in this browser (localStorage; no cookie is set) and mirrored in
 * the URL as ?radius=25 (or ?radius=any).
 */
const KEY = 'at.servicesRadius';
export const RADIUS_PARAM = 'radius';

/** A radius the control offers: 5, 10, 25, 50, 100 or "any"; anything else is ignored (null). */
export function parseRadius(raw: string | null | undefined): ServiceRadius | null {
  const v = (raw ?? '').trim().toLowerCase();
  if (v === 'any') return 'any';
  const n = Number(v);
  return SERVICE_RADIUS_STEPS.find((s): s is Exclude<ServiceRadius, 'any'> => s !== 'any' && s === n && /^\d+$/.test(v)) ?? null;
}

/** The remembered choice, or null when none (or storage is blocked: private window, site data cleared). */
export function readStoredRadius(): ServiceRadius | null {
  try {
    return parseRadius(window.localStorage.getItem(KEY));
  } catch {
    return null;
  }
}

/** Remembers the choice; a blocked storage is ignored and the page keeps working. */
export function storeRadius(radius: ServiceRadius): void {
  try {
    window.localStorage.setItem(KEY, String(radius));
  } catch {
    /* blocked storage: the choice just is not remembered */
  }
}

/** Puts the choice in the address bar without a navigation, keeping the other parameters. */
export function reflectRadiusInUrl(radius: ServiceRadius): void {
  try {
    const url = new URL(window.location.href);
    if (url.searchParams.get(RADIUS_PARAM) === String(radius)) return;
    url.searchParams.set(RADIUS_PARAM, String(radius));
    window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
  } catch {
    /* a sandboxed frame may refuse history changes */
  }
}

/** The next wider step (for "Search wider"), or null when already searching any distance. */
export function widerRadius(current: number | 'any'): ServiceRadius | null {
  if (current === 'any' || current === 0) return null;
  const next = SERVICE_RADIUS_STEPS.find((s) => s === 'any' || s > current);
  return next ?? null;
}
