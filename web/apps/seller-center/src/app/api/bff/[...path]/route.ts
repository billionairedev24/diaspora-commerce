import { bffProblemType } from '@afrotransact/api-client/problem-types';
import { type NextRequest } from 'next/server';

import { bffOrigin, mocksEnabled } from '@/lib/config';

/* Same-origin gateway for the browser: /api/bff/v1/... → gateway BFF (or the in-process MSW mock BFF for the seller
   contract when NEXT_PUBLIC_API_MOCKS=1). Keeps the BFF session cookie first-party and the BFF private. */

export const dynamic = 'force-dynamic';

const FORWARD_REQUEST = ['accept', 'accept-language', 'content-type', 'cookie', 'idempotency-key', 'if-none-match', 'origin', 'user-agent', 'x-request-id'];
const FORWARD_RESPONSE = ['cache-control', 'content-type', 'etag', 'location', 'retry-after', 'x-request-id'];

async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  if (mocksEnabled()) {
    const { handleSellerMockRequest } = await import('@afrotransact/api-client/mocks/seller-runtime');
    return handleSellerMockRequest(req);
  }
  const { path } = await ctx.params;
  const target = `${bffOrigin().replace(/\/$/, '')}/${path.map(encodeURIComponent).join('/')}${req.nextUrl.search}`;
  const headers = new Headers();
  FORWARD_REQUEST.forEach((h) => {
    const v = req.headers.get(h);
    if (v) headers.set(h, v);
  });
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
  FORWARD_RESPONSE.forEach((h) => {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  });
  upstream.headers.getSetCookie().forEach((c) => out.append('set-cookie', c));
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

export { handle as DELETE, handle as GET, handle as PATCH, handle as POST, handle as PUT };
