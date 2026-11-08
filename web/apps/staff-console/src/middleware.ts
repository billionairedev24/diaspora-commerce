/* Content-Security-Policy with a per-request nonce (docs/engineering/security-headers.md). CSP_MODE=report-only|enforce|off
   (default report-only). Edge runtime on purpose: the Cloudflare (OpenNext) deployment supports edge middleware, while a
   Node-runtime `proxy.ts` is experimental there. */
import { buildCspHeaders } from '@afrotransact/ui/csp';
import { type NextRequest,NextResponse } from 'next/server';

export function middleware(request: NextRequest): NextResponse {
  // NEXT_PUBLIC_* are inlined at build time, so each variable is named literally here.
  const csp = buildCspHeaders('staff-console', {
    NODE_ENV: process.env.NODE_ENV,
    APP_ENV: process.env.APP_ENV,
    CSP_MODE: process.env.CSP_MODE,
    MEDIA_PUBLIC_URL: process.env.MEDIA_PUBLIC_URL,
    NEXT_PUBLIC_MEDIA_PUBLIC_URL: process.env.NEXT_PUBLIC_MEDIA_PUBLIC_URL,
    NEXT_PUBLIC_SENTRY_DSN: process.env.NEXT_PUBLIC_SENTRY_DSN,
    NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT: process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT
  });
  if (!csp) return NextResponse.next();
  // Next.js finds the nonce in the request's CSP header and stamps it on its own scripts; x-nonce is for our components.
  const requestHeaders = new Headers(request.headers);
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
