/* Content-Security-Policy with a per-request nonce (docs/engineering/security-headers.md). CSP_MODE=report-only|enforce|off
   (default report-only). Edge runtime on purpose: the Cloudflare (OpenNext) deployment supports edge middleware, while a
   Node-runtime `proxy.ts` is experimental there. */
import { VISIT_COOKIE, VISITOR_COOKIE } from '@afrotransact/api-client/home-visit';
import { buildCspHeaders } from '@afrotransact/ui/csp';
import { type NextRequest,NextResponse } from 'next/server';

import { CONSENT_COOKIE } from '@/lib/consent/consent';
import { planVisit } from '@/lib/home/visit';
import { indexingPolicy, robotsHeaderFor, seoEnvFromProcess, wwwRedirectTarget } from '@/lib/seo/policy';

/** Crawler directives on EVERY response this middleware sees (pages, 404s, route handlers, the BFF proxy): a non-prod host,
    or a private path anywhere, always carries X-Robots-Tag: noindex (docs/engineering/seo.md, test/seo-env-safety.test.ts). */
function withRobots(request: NextRequest, response: NextResponse): NextResponse {
  const tag = robotsHeaderFor(indexingPolicy(seoEnvFromProcess(), request.headers.get('host')), request.nextUrl.pathname);
  if (tag) response.headers.set('X-Robots-Tag', tag);
  return response;
}

export function middleware(request: NextRequest): NextResponse {
  // One canonical host: www.<canonical> (not in the host map; a stray DNS record) 301s to the canonical origin.
  const www = wwwRedirectTarget(seoEnvFromProcess(), request.nextUrl);
  if (www) return NextResponse.redirect(www, 301);
  const visit = planVisit({
    visitor: request.cookies.get(VISITOR_COOKIE)?.value,
    window: request.cookies.get(VISIT_COOKIE)?.value,
    consentRaw: request.cookies.get(CONSENT_COOKIE)?.value,
    userAgent: request.headers.get('user-agent'),
    document: request.method === 'GET' && (request.headers.get('accept') ?? '').includes('text/html') && !request.nextUrl.pathname.startsWith('/api/'),
    newId: () => crypto.randomUUID().replace(/-/g, '')
  });
  // This request's own server components must see a new visit's cookies too.
  for (const [name, value] of Object.entries(visit.values)) request.cookies.set(name, value);
  const response = withRobots(request, withCsp(request, visit.crawler));
  const secure = request.nextUrl.protocol === 'https:';
  for (const c of visit.set) response.cookies.set(c.name, c.value, { path: '/', sameSite: 'lax', httpOnly: true, secure, ...(c.maxAge ? { maxAge: c.maxAge } : {}) });
  for (const name of visit.clear) if (request.cookies.has(name)) response.cookies.set(name, '', { path: '/', maxAge: 0 });
  // The home page is composed per visit: its HTML is never cached by a browser, the edge or a proxy.
  if (request.nextUrl.pathname === '/') response.headers.set('Cache-Control', 'private, no-store');
  return response;
}

function withCsp(request: NextRequest, crawler: boolean): NextResponse {
  // NEXT_PUBLIC_* are inlined at build time, so each variable is named literally here.
  const csp = buildCspHeaders('storefront', {
    NODE_ENV: process.env.NODE_ENV,
    APP_ENV: process.env.APP_ENV,
    CSP_MODE: process.env.CSP_MODE,
    MEDIA_PUBLIC_URL: process.env.MEDIA_PUBLIC_URL,
    NEXT_PUBLIC_MEDIA_PUBLIC_URL: process.env.NEXT_PUBLIC_MEDIA_PUBLIC_URL,
    NEXT_PUBLIC_SENTRY_DSN: process.env.NEXT_PUBLIC_SENTRY_DSN,
    NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT: process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT
  });
  const requestHeaders = new Headers(request.headers);
  // Known crawlers get the deterministic default home (no randomness): the server relays this to the gateway as X-Crawler.
  requestHeaders.delete('x-crawler');
  if (crawler) requestHeaders.set('x-crawler', '1');
  if (!csp) return NextResponse.next({ request: { headers: requestHeaders } });
  // Next.js finds the nonce in the request's CSP header and stamps it on its own scripts; x-nonce is for our components.
  requestHeaders.set('x-nonce', csp.nonce);
  requestHeaders.set(csp.name, csp.value);
  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set(csp.name, csp.value);
  response.headers.set('Reporting-Endpoints', csp.reportingEndpoints);
  return response;
}

// Next.js needs a statically analysable matcher: keep in step with CSP_MATCHER in @afrotransact/ui/csp (a test compares them).
export const config = {
  matcher: [
    {
      source: '/((?!_next/static|_next/image|favicon.ico|logo-mark.svg|api/csp-report).*)',
      missing: [
        { type: 'header', key: 'next-router-prefetch' },
        { type: 'header', key: 'purpose', value: 'prefetch' }
      ]
    }
  ]
};
