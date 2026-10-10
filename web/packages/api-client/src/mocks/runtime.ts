/* Runs the MSW handlers in-process without a service worker or Node interceptor: used by the
   storefront's /api/bff route handler and by server components when NEXT_PUBLIC_API_MOCKS=1. */
import { getResponse } from 'msw';

import { handlers } from './handlers';

export async function handleMockRequest(request: Request): Promise<Response> {
  const res = await getResponse(handlers, request);
  if (res) return res;
  return new Response(
    JSON.stringify({ type: 'https://errors.afrotransact.com/not-found', title: 'Not found', status: 404, detail: `No mock for ${request.method} ${new URL(request.url).pathname}`, code: 'NOT_FOUND' }),
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } }
  );
}
