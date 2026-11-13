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

const BOT = /bot|crawl|spider|slurp|bingpreview|facebookexternalhit|embedly|quora link|pinterest|whatsapp|telegram|headlesschrome|lighthouse|pagespeed|gtmetrix/i;

/** Known crawlers and link-preview fetchers: they get the deterministic default. */
export function isCrawlerAgent(userAgent: string | null | undefined): boolean {
  return !!userAgent && BOT.test(userAgent);
}

/**
 * The cookie `at_hs` after a load: the keys this page showed are put first, the older loads follow, and only the last
 * `SEEN_LOADS` loads (and at most `SEEN_MAX_KEYS` keys) are kept. Loads are separated by `.`; keys inside one by `,`.
 */
export function nextSeenCookie(current: string | null | undefined, shown: readonly string[]): string {
  const loads = (current ?? '')
    .split('.')
    .map((l) => l.trim())
    .filter(Boolean);
  const mine = [...new Set(shown)].join(',');
  const all = [...(mine ? [mine] : []), ...loads].slice(0, SEEN_LOADS);
  let out = all.join('.');
  while (out.split(/[.,]/).length > SEEN_MAX_KEYS && all.length > 1) {
    all.pop();
    out = all.join('.');
  }
  return out;
}

/** The keys inside an `at_hs` cookie, as the gateway wants them in `X-Home-Seen` (comma separated, no load separators). */
export function seenKeysOf(cookie: string | null | undefined): string[] {
  return (cookie ?? '')
    .split(/[.,]/)
    .map((k) => k.trim())
    .filter(Boolean);
}
