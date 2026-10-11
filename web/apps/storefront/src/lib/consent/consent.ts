/* Cookie consent (S192). The visitor's choice lives in the first-party cookie `at_consent` (readable by scripts so the
   page can gate analytics and advertising without a round trip; it holds no personal data): URL-encoded JSON
   { necessary: true, functional, analytics, advertising, dns, gpc, v, at, id } — the design's consent record
   (design/at-site.js `at.consent.v1`) plus `dns` ("Do not sell or share my personal information"), `gpc` (the
   Global Privacy Control signal was present when it was saved) and `id` (content-service's audit record).
   The choice is kept 12 months (Cookie Notice §2). Global Privacy Control (navigator.globalPrivacyControl or the
   Sec-GPC request header) is honoured on every read: advertising is off and the visitor is opted out of sale and
   sharing, whatever was stored. Every save is also recorded server side (POST /v1/consent/records). */

export const CONSENT_COOKIE = 'at_consent';
/** 12 months, as the Cookie Notice says. */
export const CONSENT_MAX_AGE_S = 365 * 86_400;

/** Categories the visitor can switch (strictly necessary cookies are always on). */
export const OPTIONAL_CATEGORIES = ['functional', 'analytics', 'advertising'] as const;
export type OptionalCategory = (typeof OPTIONAL_CATEGORIES)[number];
export type ConsentCategory = 'necessary' | OptionalCategory;

export interface ConsentChoice {
  functional: boolean;
  analytics: boolean;
  advertising: boolean;
  /** Do not sell or share my personal information (opt-out of sale, sharing and targeted ads). */
  dns: boolean;
}

export interface ConsentState extends ConsentChoice {
  necessary: true;
  /** GPC was present when this choice was saved. */
  gpc: boolean;
  /** Cookie Notice version the choice was made under. */
  v: string;
  /** When it was saved (ISO 8601). */
  at: string;
  /** content-service consent record id, when the server stored it. */
  id?: string;
}

export const REJECT_ALL: ConsentChoice = { functional: false, analytics: false, advertising: false, dns: false };
export const ACCEPT_ALL: ConsentChoice = { functional: true, analytics: true, advertising: true, dns: false };

const bool = (v: unknown): boolean => v === true;

/** Parses the cookie value (URL-encoded or raw JSON); null when absent or unreadable (= no choice made). */
export function parseConsent(raw: string | null | undefined): ConsentState | null {
  if (!raw) return null;
  let text = raw;
  try {
    text = decodeURIComponent(raw);
  } catch {
    return null;
  }
  try {
    const c = JSON.parse(text) as Record<string, unknown>;
    if (typeof c !== 'object' || c === null || Array.isArray(c)) return null;
    // Older records (and other writers) may say all/accepted for "Accept all" or marketing for advertising.
    const all = bool(c.all) || bool(c.accepted);
    return {
      necessary: true,
      functional: all || bool(c.functional),
      analytics: all || bool(c.analytics),
      advertising: all || bool(c.advertising) || bool(c.marketing),
      dns: bool(c.dns),
      gpc: bool(c.gpc),
      v: typeof c.v === 'string' ? c.v : '',
      at: typeof c.at === 'string' ? c.at : '',
      ...(typeof c.id === 'string' && c.id ? { id: c.id } : {})
    };
  } catch {
    return null;
  }
}

/** Cookie value for a state (URL-encoded JSON, field order fixed). */
export function serializeConsent(s: ConsentState): string {
  const o: Record<string, unknown> = { necessary: true, functional: s.functional, analytics: s.analytics, advertising: s.advertising, dns: s.dns, gpc: s.gpc, v: s.v, at: s.at };
  if (s.id) o.id = s.id;
  return encodeURIComponent(JSON.stringify(o));
}

/** Builds the state to store for a choice; GPC and the sale/sharing opt-out switch advertising off. */
export function buildConsent(choice: ConsentChoice, opts: { gpc: boolean; version: string; now?: Date; id?: string }): ConsentState {
  const dns = choice.dns || opts.gpc;
  return {
    necessary: true,
    functional: choice.functional,
    analytics: choice.analytics,
    advertising: choice.advertising && !dns,
    dns,
    gpc: opts.gpc,
    v: opts.version,
    at: (opts.now ?? new Date()).toISOString(),
    ...(opts.id ? { id: opts.id } : {})
  };
}

/** The state to act on: GPC present now always means no advertising and opted out of sale and sharing. */
export function effectiveConsent(s: ConsentState | null, gpc: boolean): ConsentState | null {
  if (!s) return null;
  if (!gpc) return s;
  return { ...s, advertising: false, dns: true };
}

/** Whether a category may run. Strictly necessary always; others only with a stored, effective choice. */
export function allows(s: ConsentState | null, category: ConsentCategory): boolean {
  if (category === 'necessary') return true;
  return s ? s[category] : false;
}

/** `Set-Cookie`-style string for document.cookie. */
export function consentCookieString(s: ConsentState, secure: boolean): string {
  return `${CONSENT_COOKIE}=${serializeConsent(s)}; Path=/; Max-Age=${CONSENT_MAX_AGE_S}; SameSite=Lax${secure ? '; Secure' : ''}`;
}

/** Reads one cookie from a `document.cookie`-style jar. */
export function readCookie(name: string, jar: string): string | null {
  for (const part of jar.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return v.join('=');
  }
  return null;
}

export const readConsent = (jar: string): ConsentState | null => parseConsent(readCookie(CONSENT_COOKIE, jar));

/** The browser's Global Privacy Control signal (https://globalprivacycontrol.org). */
export function browserGpc(nav: { globalPrivacyControl?: unknown } | undefined = typeof navigator === 'undefined' ? undefined : (navigator as { globalPrivacyControl?: unknown })): boolean {
  return nav?.globalPrivacyControl === true;
}

/** The `Sec-GPC` request header (servers see it before any script runs). */
export const headerGpc = (value: string | null | undefined): boolean => value?.trim() === '1';
