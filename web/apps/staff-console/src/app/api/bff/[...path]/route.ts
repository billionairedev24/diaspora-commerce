import { forwardBffProxyRequestHeaders, forwardBffProxyResponseHeaders } from '@afrotransact/api-client/correlation';
import { bffProblemType } from '@afrotransact/api-client/problem-types';
import { type NextRequest } from 'next/server';

import { bffOrigin, mocksEnabled } from '@/lib/config';

/* Same-origin gateway for the browser: /api/bff/v1/... → gateway BFF (or the in-process MSW mock BFF for the staff
   contract when NEXT_PUBLIC_API_MOCKS=1). Keeps the BFF session cookie first-party and the BFF private. Export
   downloads pass through untouched (302 to the signed link, then the file). */

export const dynamic = 'force-dynamic';

async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  if (mocksEnabled()) {
    const { handleStaffMockRequest } = await import('@afrotransact/api-client/mocks/staff-runtime');
    return handleStaffMockRequest(req);
  }
  const { path } = await ctx.params;
  const target = `${bffOrigin().replace(/\/$/, '')}/${path.map(encodeURIComponent).join('/')}${req.nextUrl.search}`;
  const headers = new Headers();
  forwardBffProxyRequestHeaders(req.headers, headers);
  const fwdFor = req.headers.get('x-forwarded-for') ?? req.headers.get('cf-connecting-ip');
  if (fwdFor) headers.set('x-forwarded-for', fwdFor);
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method,
      headers,
      body: req.method === 'GET' || req.method === 'HEAD' ? undefined : await req.arrayBuffer(),
      redirect: 'manual',
      cache: 'no-store'
    });
  } catch {
    return Response.json(
      { type: bffProblemType('UPSTREAM_UNAVAILABLE'), title: 'Service unavailable', status: 503, code: 'UPSTREAM_UNAVAILABLE', detail: 'We couldn’t reach AfroTransact right now. Please try again.' },
      { status: 503, headers: { 'content-type': 'application/problem+json' } }
    );
  }
  const out = new Headers();
  forwardBffProxyResponseHeaders(upstream.headers, out);
  upstream.headers.getSetCookie().forEach((c) => out.append('set-cookie', c));
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

export { handle as DELETE, handle as GET, handle as PATCH, handle as POST, handle as PUT };
