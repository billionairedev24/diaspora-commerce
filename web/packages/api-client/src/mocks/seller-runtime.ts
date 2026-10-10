/* Runs the seller MSW handlers in-process (no service worker, no Node interceptor): used by the Seller Center's
   /api/bff route handler and server components when NEXT_PUBLIC_API_MOCKS=1. */
import { getResponse } from 'msw';

import { bffProblemType } from '../problem-types';
import { sellerHandlers } from './seller/handlers';

export async function handleSellerMockRequest(request: Request): Promise<Response> {
  const res = await getResponse(sellerHandlers, request);
  if (res) return res;
  return new Response(
    JSON.stringify({ type: bffProblemType('NOT_FOUND'), title: 'Not found', status: 404, detail: `No mock for ${request.method} ${new URL(request.url).pathname}`, code: 'NOT_FOUND' }),
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } }
  );
}
