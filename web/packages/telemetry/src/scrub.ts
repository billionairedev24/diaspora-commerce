/*
 * PII scrubbing applied to every event, breadcrumb and span before it is sent (beforeSend / beforeBreadcrumb /
 * beforeSendTransaction). It removes emails, phone numbers, bearer/JWT/API tokens and payment card numbers from
 * strings, and blanks values under sensitive keys (password, token, authorization, cookie, card…).
 */

const REDACTED = '[redacted]';

const PATTERNS: Array<[RegExp, string]> = [
  // Authorization headers and bearer tokens.
  [/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}/gi, '$1 [token]'],
  // JWTs (three base64url segments).
  [/\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\b/g, '[jwt]'],
  // Provider secrets and keys (Stripe sk_/pk_/rk_/whsec_, Sentry, GitHub, generic api keys in URLs).
  [/\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{8,}\b/g, '[key]'],
  [/\bwhsec_[A-Za-z0-9]{8,}\b/g, '[key]'],
  [/\bgh[pousr]_[A-Za-z0-9]{20,}\b/g, '[key]'],
  [/([?&#](?:access_token|id_token|refresh_token|token|code|api_key|apikey|key|secret|password|client_secret|session|sig|signature)=)[^&#\s]+/gi, '$1[redacted]'],
  // Emails.
  [/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[email]']
];

/** 13–19 digits, optionally grouped by spaces or dashes. */
const CARD = /\b\d(?:[ -]?\d){12,18}\b/g;
/** International or North American phone numbers (7+ digits with separators, or +country code). */
const PHONE = /(?:\+\d{1,3}[\s.-]?)?(?:\(\d{2,4}\)[\s.-]?)?\d{2,4}[\s.-]\d{3,4}[\s.-]?\d{0,4}\b|\+\d{8,15}\b/g;

function luhn(digits: string): boolean {
  let sum = 0;
  let double = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (double) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    double = !double;
  }
  return sum % 10 === 0;
}

/** Removes PII from a free-text string (messages, URLs, exception values, breadcrumb text). */
export function scrubString(input: string): string {
  let s = input;
  for (const [re, rep] of PATTERNS) s = s.replace(re, rep);
  s = s.replace(CARD, (m) => {
    const digits = m.replace(/\D/g, '');
    return digits.length >= 13 && digits.length <= 19 && luhn(digits) ? '[card]' : m;
  });
  s = s.replace(PHONE, (m) => (m.replace(/\D/g, '').length >= 7 ? '[phone]' : m));
  return s;
}

/** Key words whose values are blanked ("cardNumber", "x-api-key", "user_email", "sessionId"…). */
const SENSITIVE_WORDS = new Set([
  'password', 'passwd', 'passcode', 'secret', 'token', 'authorization', 'cookie', 'cookies', 'session', 'card', 'cvc', 'cvv',
  'pan', 'iban', 'ssn', 'email', 'phone', 'mobile', 'msisdn', 'address', 'postal', 'zip', 'otp', 'pin', 'verifier', 'apikey'
]);
const SENSITIVE_JOINED = /password|authorization|apikey|accesstoken|refreshtoken|idtoken|clientsecret|cardnumber|phonenumber|emailaddress/;

function sensitiveKey(key: string): boolean {
  const words = key.replace(/([a-z])([A-Z])/g, '$1_$2').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
  return words.some((w) => SENSITIVE_WORDS.has(w)) || SENSITIVE_JOINED.test(words.join(''));
}

/** Deep-scrubs any JSON-like value; values under sensitive keys are blanked. */
export function scrubValue<T>(value: T, depth = 0): T {
  if (depth > 8) return value;
  if (typeof value === 'string') return scrubString(value) as T;
  if (Array.isArray(value)) return value.map((v) => scrubValue(v, depth + 1)) as T;
  if (value && typeof value === 'object' && Object.getPrototypeOf(value) === Object.prototype) {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = sensitiveKey(k) && v !== null && v !== undefined && typeof v !== 'boolean' ? REDACTED : scrubValue(v, depth + 1);
    }
    return out as T;
  }
  return value;
}

interface ScrubbableEvent {
  message?: string;
  user?: unknown;
  request?: { url?: string; headers?: Record<string, string>; cookies?: unknown; data?: unknown; query_string?: unknown };
  exception?: { values?: Array<{ value?: string }> };
  breadcrumbs?: unknown[];
  extra?: Record<string, unknown>;
  contexts?: Record<string, unknown>;
  tags?: Record<string, unknown>;
  spans?: unknown[];
}

/** Scrubs a Sentry(-compatible) event in place and returns it; the user is reduced to its opaque id. */
export function scrubEvent<E>(event: E): E {
  const e = event as unknown as ScrubbableEvent;
  if (e.message) e.message = scrubString(e.message);
  if (e.user && typeof e.user === 'object') {
    const id = (e.user as { id?: unknown }).id;
    e.user = id === undefined ? undefined : { id };
  }
  if (e.request) {
    if (e.request.url) e.request.url = scrubString(e.request.url);
    if (e.request.headers) {
      const h: Record<string, string> = {};
      for (const [k, v] of Object.entries(e.request.headers)) h[k] = /authorization|cookie|x-api-key|x-csrf/i.test(k) ? REDACTED : scrubString(v);
      e.request.headers = h;
    }
    if (e.request.cookies) e.request.cookies = REDACTED;
    if (e.request.data !== undefined) e.request.data = REDACTED;
    if (e.request.query_string !== undefined) e.request.query_string = scrubValue(e.request.query_string);
  }
  for (const v of e.exception?.values ?? []) if (v.value) v.value = scrubString(v.value);
  if (e.breadcrumbs) e.breadcrumbs = scrubValue(e.breadcrumbs);
  if (e.extra) e.extra = scrubValue(e.extra);
  if (e.contexts) e.contexts = scrubValue(e.contexts);
  if (e.tags) e.tags = scrubValue(e.tags);
  if (e.spans) e.spans = scrubValue(e.spans);
  return event;
}
