/* Pre-order channel attribution (S273, spec §1 Attribution). Every advertorial link carries ?src= (ig, fb, tt, wa,
   email, sms, app) plus optional cr= (creative slot) and po= (window code); on-site placements set hero, ribbon, pdp
   or page when clicked. Last touch wins. The record is kept for the visit (sessionStorage) and, only when the shopper
   has consented to analytics or advertising cookies, for 30 days in the first-party cookie `at_attr`. Checkout sends
   { src, cr, po } with the order.

   Consent: the cookie banner's `at_consent` cookie (lib/consent). Analytics or advertising consent counts for
   attribution (Global Privacy Control switches advertising off). No cookie, or one that cannot be parsed, means no
   consent. Withdrawing consent deletes `at_attr` on the next read. */
import { type PreorderAttribution } from '@afrotransact/api-client';

import { browserGpc, effectiveConsent, readConsent, readCookie } from '../consent/consent';

export { CONSENT_COOKIE } from '../consent/consent';
export const ATTRIBUTION_COOKIE = 'at_attr';
const SESSION_KEY = 'at.attr.v1';
const MAX_AGE_S = 30 * 86_400;

/** Sources a link may carry plus the on-site placements. Anything else is ignored. */
export const ATTRIBUTION_SOURCES = ['ig', 'fb', 'tt', 'wa', 'email', 'sms', 'app', 'hero', 'ribbon', 'pdp', 'page'] as const;
export type AttributionSource = (typeof ATTRIBUTION_SOURCES)[number];
export type PlacementSource = Extract<AttributionSource, 'hero' | 'ribbon' | 'pdp' | 'page'>;

interface StoredAttribution extends PreorderAttribution {
  at: number;
}

const TOKEN = /^[A-Za-z0-9._-]{1,64}$/;
const isSource = (v: string): v is AttributionSource => (ATTRIBUTION_SOURCES as readonly string[]).includes(v);

const docCookie = (): string => (typeof document === 'undefined' ? '' : document.cookie);

/** True when the consent cookie grants analytics or advertising (marketing) cookies. */
export function hasAttributionConsent(jar: string = docCookie()): boolean {
  const c = effectiveConsent(readConsent(jar), browserGpc());
  return c !== null && (c.analytics || c.advertising);
}

function parse(raw: string | null): StoredAttribution | null {
  if (!raw) return null;
  try {
    const r = JSON.parse(raw) as Partial<StoredAttribution>;
    if (typeof r.src !== 'string' || !isSource(r.src) || typeof r.at !== 'number') return null;
    return { src: r.src, at: r.at, ...(typeof r.cr === 'string' && TOKEN.test(r.cr) ? { cr: r.cr } : {}), ...(typeof r.po === 'string' && TOKEN.test(r.po) ? { po: r.po } : {}) };
  } catch {
    return null;
  }
}

const secure = () => (typeof location !== 'undefined' && location.protocol === 'https:' ? '; Secure' : '');

/** Records a touch (last touch wins): for the visit always, for 30 days only with consent. */
export function setAttribution(src: AttributionSource, extra: { cr?: string | null; po?: string | null } = {}, now = Date.now()): void {
  const rec: StoredAttribution = { src, at: now, ...(extra.cr && TOKEN.test(extra.cr) ? { cr: extra.cr } : {}), ...(extra.po && TOKEN.test(extra.po) ? { po: extra.po } : {}) };
  const json = JSON.stringify(rec);
  try {
    sessionStorage.setItem(SESSION_KEY, json);
  } catch {
    /* storage blocked: the visit record is best effort */
  }
  if (typeof document === 'undefined') return;
  if (hasAttributionConsent()) document.cookie = `${ATTRIBUTION_COOKIE}=${encodeURIComponent(json)}; Path=/; Max-Age=${MAX_AGE_S}; SameSite=Lax${secure()}`;
}

/** Reads ?src= (+ cr=, po=) from a landing URL. Returns the recorded touch, or null when the URL carries none. */
export function captureAttribution(params: URLSearchParams, now = Date.now()): PreorderAttribution | null {
  const src = (params.get('src') ?? '').toLowerCase();
  if (!isSource(src)) return null;
  setAttribution(src, { cr: params.get('cr'), po: params.get('po') }, now);
  return readAttribution(now);
}

/** The touch to send with checkout: this visit's, else the consented 30-day cookie; null when none or expired. */
export function readAttribution(now = Date.now()): PreorderAttribution | null {
  let rec: StoredAttribution | null = null;
  try {
    rec = parse(sessionStorage.getItem(SESSION_KEY));
  } catch {
    rec = null;
  }
  if (!rec && typeof document !== 'undefined') {
    const raw = readCookie(ATTRIBUTION_COOKIE, document.cookie);
    if (raw) {
      if (hasAttributionConsent()) rec = parse(decodeURIComponent(raw));
      else document.cookie = `${ATTRIBUTION_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax${secure()}`;
    }
  }
  if (!rec || now - rec.at > MAX_AGE_S * 1000) return null;
  return { src: rec.src, ...(rec.cr ? { cr: rec.cr } : {}), ...(rec.po ? { po: rec.po } : {}) };
}
