/* Where the storefront may be indexed. ONE rule, fail closed: a page is indexable only when the Worker's own environment says
   "prod" (APP_ENV, written next to PUBLIC_WEB_URL in wrangler.jsonc and checked against infra/cloudflare/hosts.json by
   test/seo-env-safety.test.ts) AND the request arrived on the canonical host PUBLIC_WEB_URL names. Everything else (uat-, dev-,
   test/trial on the real zone, workers.dev, PR previews, a www or look-alike host, local runs, a missing variable) is noindex.
   The prod host is never spelled in code: it is PUBLIC_WEB_URL, which the env map decides. */

export type IndexingReason = 'prod' | 'not-prod' | 'host-mismatch' | 'no-public-url';

export interface IndexingPolicy {
  indexable: boolean;
  reason: IndexingReason;
  /** The canonical origin (PUBLIC_WEB_URL, no trailing slash); undefined when it is unset or not absolute. */
  origin: string | undefined;
}

export interface SeoEnv {
  APP_ENV?: string | undefined;
  PUBLIC_WEB_URL?: string | undefined;
}

/** The two variables the policy reads, from the Worker's vars (bracket access: resolved per request, one build serves every environment). */
export function seoEnvFromProcess(): SeoEnv {
  return { APP_ENV: process.env['APP_ENV'], PUBLIC_WEB_URL: process.env['PUBLIC_WEB_URL'] };
}

const hostOf = (value: string | null | undefined): string | undefined => {
  if (!value) return undefined;
  try {
    return new URL(value.includes('://') ? value : `https://${value}`).host.toLowerCase();
  } catch {
    return undefined;
  }
};

const isProdEnv = (env: SeoEnv): boolean => (env.APP_ENV ?? '').trim().toLowerCase() === 'prod';

/** Decides whether the request's host may be indexed. `requestHost` is the Host (or X-Forwarded-Host) the visitor used. */
export function indexingPolicy(env: SeoEnv, requestHost: string | null | undefined): IndexingPolicy {
  let origin: string | undefined;
  try {
    origin = env.PUBLIC_WEB_URL ? new URL(env.PUBLIC_WEB_URL).origin : undefined;
  } catch {
    origin = undefined;
  }
  if (!isProdEnv(env)) return { indexable: false, reason: 'not-prod', origin };
  if (!origin) return { indexable: false, reason: 'no-public-url', origin };
  if (hostOf(requestHost) !== hostOf(origin)) return { indexable: false, reason: 'host-mismatch', origin };
  return { indexable: true, reason: 'prod', origin };
}

/** The X-Robots-Tag value of a response that must not be indexed. */
export const NOINDEX_HEADER = 'noindex, nofollow, noarchive';

/** Path prefixes that are never indexed, even in prod: per-shopper, transactional or machine endpoints. */
export const PRIVATE_PATH_PREFIXES = [
  '/account',
  '/cart',
  '/checkout',
  '/orders',
  '/signin',
  '/signup',
  '/r/',
  '/api/',
  '/back-office',
  '/sell',
  '/provide',
  '/offline',
  '/sw.js',
  '/manifest.webmanifest',
  '/gift-cards',
  '/delivery-runs/subscriptions',
  '/pre-orders/waitlist'
] as const;

/** True for a path that is never indexed. */
export function isPrivatePath(pathname: string): boolean {
  return PRIVATE_PATH_PREFIXES.some((p) => (p.endsWith('/') ? pathname.startsWith(p) : pathname === p || pathname.startsWith(`${p}/`)));
}

/** The X-Robots-Tag of one response: undefined means "none" (an indexable page in prod). */
export function robotsHeaderFor(policy: IndexingPolicy, pathname: string): string | undefined {
  if (!policy.indexable) return NOINDEX_HEADER;
  return isPrivatePath(pathname) ? NOINDEX_HEADER : undefined;
}

/** A request to www.<canonical host> (prod only): the canonical URL to 301 to. The host map has no www; this covers a stray DNS record. */
export function wwwRedirectTarget(env: SeoEnv, requestUrl: URL): URL | undefined {
  if (!isProdEnv(env) || !env.PUBLIC_WEB_URL) return undefined;
  const canonical = hostOf(env.PUBLIC_WEB_URL);
  if (!canonical || requestUrl.host.toLowerCase() !== `www.${canonical}`) return undefined;
  return new URL(requestUrl.pathname + requestUrl.search, new URL(env.PUBLIC_WEB_URL).origin);
}
