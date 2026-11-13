/* The visit headers of the dynamic home (docs/engineering/dynamic-home.md, contracts/openapi/gateway-bff.yaml `getHome`).
   The storefront keeps three first-party cookies (`at_vid`, `at_visit`, `at_hs`) and its server relays them as these headers.
   Nothing here identifies a person: the visitor id is a random id that lives as long as the browser session. */

/** Opaque first-party visitor id (cookie `at_vid`). Absent: the page is the deterministic default. */
export const VISITOR_HEADER = 'X-Visitor-Id';
/** Opaque id of the current visit (cookie `at_visit`, 30 minute sliding expiry, new on a new visit). Part of the variety seed. */
export const VISIT_WINDOW_HEADER = 'X-Visit-Window';
/** Guests: comma separated short keys of the last few home loads (cookie `at_hs`). */
export const HOME_SEEN_HEADER = 'X-Home-Seen';
/** Experiment variant assigned at the Worker (cookie `at_variant`); part of the shared cache key. */
export const HOME_VARIANT_HEADER = 'X-Home-Variant';
/** Set for known crawlers: the deterministic default page, no randomness. */
export const CRAWLER_HEADER = 'X-Crawler';

export const VISITOR_COOKIE = 'at_vid';
export const VISIT_COOKIE = 'at_visit';
export const SEEN_COOKIE = 'at_hs';
export const VARIANT_COOKIE = 'at_variant';

/** A visit window slides: it ends after this long without a page load. */
export const VISIT_WINDOW_MINUTES = 30;
/** Guests remember the last 3 loads, about 120 keys. */
export const SEEN_LOADS = 3;
export const SEEN_MAX_KEYS = 120;

export interface VisitHeaders {
  visitor?: string;
  window?: string;
  seen?: readonly string[];
  variant?: string;
  crawler?: boolean;
}

/** The request headers for a visit; empty for a crawler's flag-only request or a visitor without cookies. */
export function homeVisitHeaders(v: VisitHeaders): Record<string, string> {
  const h: Record<string, string> = {};
  if (v.crawler) {
    h[CRAWLER_HEADER] = '1';
    return h;
  }
  if (v.visitor) h[VISITOR_HEADER] = v.visitor;
  if (v.window) h[VISIT_WINDOW_HEADER] = v.window;
  if (v.seen && v.seen.length > 0) h[HOME_SEEN_HEADER] = v.seen.join(',').slice(0, 600);
  if (v.variant) h[HOME_VARIANT_HEADER] = v.variant;
  return h;
}

const BOT = /bot|crawl|spider|slurp|bingpreview|facebookexternalhit|embedly|quora link|pinterest|whatsapp|telegram|lighthouse|pagespeed|gtmetrix/i;

/** Known crawlers and link-preview fetchers: they get the deterministic default. */
export function isCrawlerAgent(userAgent: string | null | undefined): boolean {
  return !!userAgent && BOT.test(userAgent);
}

/*
 * The guest novelty memory (cookie `at_hs`): `<window>~<keys of this visit>~<load>.<load>.<load>`, keys joined by `-`.
 * The page of one visit must be the same on refresh, so what the CURRENT visit showed is kept apart and is never sent back
 * to the gateway; only the loads of earlier visits (at most SEEN_LOADS) travel in `X-Home-Seen`. A new visit window moves the
 * previous visit's keys into the earlier loads. All values are short base36 keys, safe in a cookie.
 */
export interface SeenState {
  window: string;
  current: string[];
  previous: string[][];
}

const keysOf = (part: string | undefined): string[] => (part ?? '').split('-').map((k) => k.trim()).filter(Boolean);

export function parseSeenCookie(raw: string | null | undefined): SeenState {
  const [window = '', current = '', previous = ''] = (raw ?? '').split('~');
  return { window, current: keysOf(current), previous: previous.split('.').map(keysOf).filter((l) => l.length > 0) };
}

export function serializeSeen(s: SeenState): string {
  return `${s.window}~${s.current.join('-')}~${s.previous.map((l) => l.join('-')).join('.')}`;
}

/** The cookie value after this page showed `shown` in visit `window`. Idempotent for the same window (a refresh changes nothing). */
export function recordLoad(raw: string | null | undefined, window: string, shown: readonly string[]): string {
  const state = parseSeenCookie(raw);
  // Keys are short alphanumerics (the contract's `visit.seen`); anything else would break the cookie's separators, so it is dropped.
  const mine = [...new Set(shown)].filter((k) => /^[A-Za-z0-9]{1,8}$/.test(k));
  let previous = state.previous;
  if (state.window && state.window !== window && state.current.length > 0) previous = [state.current, ...previous];
  previous = previous.slice(0, SEEN_LOADS);
  while (previous.flat().length > SEEN_MAX_KEYS && previous.length > 1) previous = previous.slice(0, -1);
  return serializeSeen({ window, current: mine, previous });
}

/** The keys of the visits BEFORE the current one, for the `X-Home-Seen` header. */
export function seenKeysForRequest(raw: string | null | undefined): string[] {
  return parseSeenCookie(raw).previous.flat().slice(0, SEEN_MAX_KEYS);
}
