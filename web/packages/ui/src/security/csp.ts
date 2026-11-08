/* Content-Security-Policy for the three Next.js apps (storefront, seller-center, staff-console).
   Pure and edge-safe (no Node or React imports): each app's src/middleware.ts calls buildCspHeaders() per request.
   Docs: docs/engineering/security-headers.md.

   Scripts: a per-request nonce plus 'strict-dynamic' (no 'unsafe-inline'). Next.js reads the nonce from the request's CSP
   header and stamps it on its own scripts; scripts those load (Stripe.js, injected chunks) are trusted through
   'strict-dynamic'. The 'self' and host entries only matter to browsers without 'strict-dynamic' support.
   Styles: 'self' 'unsafe-inline' and NO nonce. React renders style="" attributes (which nonces cannot cover) and the staff
   console previews email HTML in a srcdoc frame that inherits this policy. A nonce in style-src would disable
   'unsafe-inline', so styles are the one place the policy is looser than scripts. */

export type CspApp = 'storefront' | 'seller-center' | 'staff-console';
export type CspMode = 'report-only' | 'enforce' | 'off';

/** The environment variables the policy depends on (read in the app's middleware, where NEXT_PUBLIC_* are inlined). */
export interface CspEnv {
  NODE_ENV?: string;
  APP_ENV?: string;
  CSP_MODE?: string;
  MEDIA_PUBLIC_URL?: string;
  NEXT_PUBLIC_MEDIA_PUBLIC_URL?: string;
  NEXT_PUBLIC_SENTRY_DSN?: string;
  NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT?: string;
}

export interface CspHeaders {
  nonce: string;
  /** Content-Security-Policy or Content-Security-Policy-Report-Only. */
  name: 'Content-Security-Policy' | 'Content-Security-Policy-Report-Only';
  value: string;
  /** Value of the Reporting-Endpoints response header (the report-to group named in the policy). */
  reportingEndpoints: string;
}

export const CSP_REPORT_PATH = '/api/csp-report';
export const CSP_REPORT_GROUP = 'csp-endpoint';
export const LOCAL_MEDIA_ORIGIN = 'http://localhost:9000';

const STRIPE_PAYMENT_HOSTS = ['https://js.stripe.com', 'https://*.js.stripe.com'];
const STRIPE_CONNECT_HOSTS = ['https://connect-js.stripe.com'];
const STRIPE_APPS: readonly CspApp[] = ['storefront', 'seller-center'];

/** CSP_MODE: report-only (the default, also for an unknown value), enforce, or off. */
export function resolveCspMode(env: CspEnv): CspMode {
  const mode = (env.CSP_MODE ?? '').trim().toLowerCase();
  return mode === 'enforce' || mode === 'off' ? mode : 'report-only';
}

/** A fresh base64 nonce (128 bits) for every request. */
export function generateNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** http(s) origin of a URL, or undefined (blank, relative, other scheme). Also reads a Sentry DSN (key@host/project). */
export function originOf(raw: string | undefined): string | undefined {
  if (!raw) return undefined;
  try {
    const url = new URL(raw.trim());
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.origin : undefined;
  } catch {
    return undefined;
  }
}

function unique(values: (string | undefined)[]): string[] {
  return [...new Set(values.filter((v): v is string => Boolean(v)))];
}

function mediaOrigins(env: CspEnv): string[] {
  const configured = unique([originOf(env.MEDIA_PUBLIC_URL), originOf(env.NEXT_PUBLIC_MEDIA_PUBLIC_URL)]);
  if (configured.length > 0) return configured;
  // Local stack: MinIO. Deployed environments must set MEDIA_PUBLIC_URL (docs/engineering/environments.md).
  const deployed = ['dev', 'uat', 'prod', 'production'].includes(env.APP_ENV ?? '');
  return deployed ? [] : [LOCAL_MEDIA_ORIGIN];
}

function directives(app: CspApp, nonce: string, env: CspEnv): Record<string, string[]> {
  const dev = env.NODE_ENV === 'development';
  const stripe = STRIPE_APPS.includes(app);
  const stripeHosts = stripe ? [...STRIPE_PAYMENT_HOSTS, ...(app === 'seller-center' ? STRIPE_CONNECT_HOSTS : [])] : [];
  const media = mediaOrigins(env);
  const telemetry = unique([originOf(env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT), originOf(env.NEXT_PUBLIC_SENTRY_DSN)]);

  return {
    'default-src': ["'self'"],
    // 'unsafe-eval' only for `next dev` (React refresh / HMR); never in a production build.
    'script-src': ["'self'", `'nonce-${nonce}'`, "'strict-dynamic'", ...stripeHosts, ...(dev ? ["'unsafe-eval'"] : [])],
    'style-src': ["'self'", "'unsafe-inline'"],
    'img-src': ["'self'", 'data:', 'blob:', ...media, ...(stripe ? ['https://*.stripe.com'] : [])],
    'media-src': ["'self'", 'blob:', ...media],
    'font-src': ["'self'"],
    // The browser only talks to its own origin (/api/bff proxies the BFF) plus telemetry (OTLP, Sentry) and Stripe.
    'connect-src': ["'self'", ...telemetry, ...(stripe ? ['https://api.stripe.com'] : []), ...(dev ? ['ws:', 'wss:'] : [])],
    'frame-src': stripe ? [...stripeHosts, 'https://hooks.stripe.com'] : ["'none'"],
    'worker-src': ["'self'", 'blob:'],
    'manifest-src': ["'self'"],
    'object-src': ["'none'"],
    'base-uri': ["'self'"],
    'form-action': ["'self'"],
    'frame-ancestors': ["'none'"]
  };
}

/** The policy string for one request (the nonce is per request). */
export function buildCsp(app: CspApp, nonce: string, env: CspEnv = {}): string {
  const parts = Object.entries(directives(app, nonce, env)).map(([name, sources]) => `${name} ${sources.join(' ')}`);
  parts.push(`report-uri ${CSP_REPORT_PATH}`, `report-to ${CSP_REPORT_GROUP}`);
  return parts.join('; ');
}

/** Headers for one request, or undefined when CSP_MODE=off. */
export function buildCspHeaders(app: CspApp, env: CspEnv = {}): CspHeaders | undefined {
  const mode = resolveCspMode(env);
  if (mode === 'off') return undefined;
  const nonce = generateNonce();
  return {
    nonce,
    name: mode === 'enforce' ? 'Content-Security-Policy' : 'Content-Security-Policy-Report-Only',
    value: buildCsp(app, nonce, env),
    reportingEndpoints: `${CSP_REPORT_GROUP}="${CSP_REPORT_PATH}"`
  };
}

/** Request paths the middleware skips: build assets, images, icons and the report endpoint; prefetches. */
export const CSP_MATCHER = [
  {
    source: '/((?!_next/static|_next/image|favicon.ico|logo-mark.svg|api/csp-report).*)',
    missing: [
      { type: 'header', key: 'next-router-prefetch' },
      { type: 'header', key: 'purpose', value: 'prefetch' }
    ]
  }
];

/* ---- Violation reports ------------------------------------------------------------------------------------------ */

export interface CspViolationLine {
  event: 'csp.violation';
  app: CspApp;
  directive: string;
  disposition: string;
  /** Origin of the blocked resource, or inline / eval / data / blob / unknown. Never a path or query. */
  blocked: string;
  /** Path of the page (no query, no fragment). */
  page: string;
  /** Script/style file origin + path (no query) when the browser sent one. */
  source?: string;
  line?: number;
  column?: number;
}

const KNOWN_KEYWORDS = new Set(['inline', 'eval', 'data', 'blob', 'wasm-eval', 'trusted-types-policy', 'trusted-types-sink']);

function stripToOriginAndPath(raw: unknown, keepPath: boolean): string | undefined {
  if (typeof raw !== 'string' || raw === '') return undefined;
  try {
    const url = new URL(raw);
    return keepPath ? `${url.origin}${url.pathname}`.slice(0, 200) : url.origin;
  } catch {
    return undefined;
  }
}

function pathOf(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  try {
    return new URL(raw, 'http://placeholder.invalid').pathname.slice(0, 200);
  } catch {
    return '';
  }
}

function blockedOf(raw: unknown): string {
  if (typeof raw !== 'string' || raw === '') return 'unknown';
  const lowered = raw.toLowerCase();
  if (KNOWN_KEYWORDS.has(lowered)) return lowered;
  const scheme = /^([a-z][a-z0-9+.-]*):/.exec(lowered)?.[1];
  if (scheme === 'data' || scheme === 'blob') return scheme;
  return stripToOriginAndPath(raw, false) ?? 'unknown';
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : undefined;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;
}

/** Reduces one browser report (legacy report-uri or Reporting API body) to a compact, PII-free line; the script sample,
    full URLs, query strings and referrers are dropped. */
export function toViolationLine(app: CspApp, body: Record<string, unknown>): CspViolationLine | undefined {
  const directive = body['effectiveDirective'] ?? body['effective-directive'] ?? body['violatedDirective'] ?? body['violated-directive'];
  if (typeof directive !== 'string') return undefined;
  const line: CspViolationLine = {
    event: 'csp.violation',
    app,
    directive: directive.split(/\s+/)[0]?.slice(0, 40) ?? 'unknown',
    disposition: body['disposition'] === 'enforce' ? 'enforce' : 'report',
    blocked: blockedOf(body['blockedURL'] ?? body['blocked-uri']),
    page: pathOf(body['documentURL'] ?? body['document-uri'])
  };
  const source = stripToOriginAndPath(body['sourceFile'] ?? body['source-file'], true);
  if (source) line.source = source;
  const lineNumber = numberOrUndefined(body['lineNumber'] ?? body['line-number']);
  if (lineNumber !== undefined) line.line = lineNumber;
  const columnNumber = numberOrUndefined(body['columnNumber'] ?? body['column-number']);
  if (columnNumber !== undefined) line.column = columnNumber;
  return line;
}

/** Violation lines of a request body: `application/csp-report` ({"csp-report": {...}}) or `application/reports+json`
    ([{type: 'csp-violation', body: {...}}]); at most `limit` lines, anything else is ignored. */
export function violationLines(app: CspApp, payload: unknown, limit = 10): CspViolationLine[] {
  const bodies: Record<string, unknown>[] = [];
  const single = asRecord(payload);
  if (single && asRecord(single['csp-report'])) {
    bodies.push(single['csp-report'] as Record<string, unknown>);
  } else if (Array.isArray(payload)) {
    for (const entry of payload) {
      const report = asRecord(entry);
      const inner = report && report['type'] === 'csp-violation' ? asRecord(report['body']) : undefined;
      if (inner) bodies.push(inner);
    }
  }
  const lines: CspViolationLine[] = [];
  for (const body of bodies.slice(0, limit)) {
    const line = toViolationLine(app, body);
    if (line) lines.push(line);
  }
  return lines;
}

/** Fixed-window limiter (per runtime instance, best effort): at most `max` reports per `windowMs`. */
export function createReportLimiter(max: number, windowMs: number, now: () => number = Date.now): () => boolean {
  let windowStart = now();
  let count = 0;
  return () => {
    const t = now();
    if (t - windowStart >= windowMs) {
      windowStart = t;
      count = 0;
    }
    count += 1;
    return count <= max;
  };
}

const MAX_REPORT_BYTES = 16_384;

/** The /api/csp-report handler of an app: 204 for a logged or dropped report, 413 for a large body, 429 over the rate limit.
    Logs one compact JSON line per violation (console.warn); nothing is stored and no request metadata is logged. */
export function createCspReportHandler(
  app: CspApp,
  options: { limiter?: () => boolean; log?: (line: string) => void } = {}
): (request: Request) => Promise<Response> {
  const limiter = options.limiter ?? createReportLimiter(120, 60_000);
  const log = options.log ?? ((line: string) => console.warn(line));
  return async (request) => {
    if (!limiter()) return new Response(null, { status: 429, headers: { 'Retry-After': '60' } });
    const declared = Number(request.headers.get('content-length') ?? '0');
    if (declared > MAX_REPORT_BYTES) return new Response(null, { status: 413 });
    let text = '';
    try {
      text = await request.text();
    } catch {
      return new Response(null, { status: 204 });
    }
    if (text.length > MAX_REPORT_BYTES) return new Response(null, { status: 413 });
    let payload: unknown;
    try {
      payload = JSON.parse(text);
    } catch {
      return new Response(null, { status: 204 });
    }
    for (const line of violationLines(app, payload)) log(JSON.stringify(line));
    return new Response(null, { status: 204 });
  };
}
