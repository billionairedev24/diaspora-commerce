import { isCrawlerAgent, SEEN_COOKIE, seenKeysForRequest,VARIANT_COOKIE, VISIT_COOKIE, VISIT_WINDOW_MINUTES, type VisitHeaders, VISITOR_COOKIE } from '@afrotransact/api-client/home-visit';

import { CONSENT_COOKIE, parseConsent } from '@/lib/consent/consent';

/*
 * The visit behind the varied home (docs/engineering/dynamic-home.md, "Variety"). Two first-party cookies the middleware keeps:
 *   at_vid    a random id, a session cookie (gone when the browser closes), no identity
 *   at_visit  a random id for this visit, 30 minute sliding expiry (a new one after 30 minutes without a page load)
 * and one the page keeps from the browser: at_hs (the keys of items shown on earlier visits, the novelty memory).
 * The server relays them to the gateway as X-Visitor-Id, X-Visit-Window and X-Home-Seen; the gateway seeds the page with
 * visitor + window, so one visit is stable on refresh and the next visit differs. Crawlers get no visit at all and therefore
 * the deterministic default page. A shopper who switched Functional cookies off gets no visit either (the default page).
 */

export interface VisitCookiePlan {
  /** A known crawler: no cookies, the deterministic page. */
  crawler: boolean;
  /** Cookies to set on the response (and to make visible to this very request). */
  set: Array<{ name: string; value: string; maxAge?: number }>;
  /** Cookies to clear (the shopper turned variety off). */
  clear: string[];
  /** Cookie values this request should see (existing plus new). */
  values: Record<string, string>;
}

export interface VisitInput {
  visitor?: string;
  window?: string;
  consentRaw?: string | null;
  userAgent?: string | null;
  /** A page (document) request: only those start or slide a visit. */
  document: boolean;
  newId: () => string;
}

/** Variety is on unless the shopper explicitly refused Functional cookies (no choice yet counts as on: these ids are random, session-scoped and identify nobody). */
export function varietyAllowed(consentRaw: string | null | undefined): boolean {
  const c = parseConsent(consentRaw);
  return c === null || c.functional;
}

export function planVisit(input: VisitInput): VisitCookiePlan {
  const none: VisitCookiePlan = { crawler: false, set: [], clear: [], values: {} };
  if (isCrawlerAgent(input.userAgent)) return { ...none, crawler: true };
  if (!input.document) return none;
  if (!varietyAllowed(input.consentRaw)) return { ...none, clear: [VISITOR_COOKIE, VISIT_COOKIE, SEEN_COOKIE].filter(Boolean) };
  const visitor = input.visitor || input.newId();
  const window = input.window || input.newId();
  return {
    crawler: false,
    set: [
      // Session cookie: no max-age.
      ...(input.visitor ? [] : [{ name: VISITOR_COOKIE, value: visitor }]),
      // Sliding: every page load keeps the visit alive for another 30 minutes.
      { name: VISIT_COOKIE, value: window, maxAge: VISIT_WINDOW_MINUTES * 60 }
    ],
    clear: [],
    values: { [VISITOR_COOKIE]: visitor, [VISIT_COOKIE]: window }
  };
}

/** The visit to relay to the gateway, read from a cookie jar (`get` returns the cookie's value). */
export function visitFromCookies(get: (name: string) => string | undefined, userAgentOrFlag: { userAgent?: string | null; crawlerFlag?: string | null }): VisitHeaders {
  if (userAgentOrFlag.crawlerFlag === '1' || isCrawlerAgent(userAgentOrFlag.userAgent)) return { crawler: true };
  if (!varietyAllowed(get(CONSENT_COOKIE))) return {};
  return {
    visitor: get(VISITOR_COOKIE),
    window: get(VISIT_COOKIE),
    seen: seenKeysForRequest(get(SEEN_COOKIE)),
    variant: get(VARIANT_COOKIE)
  };
}
