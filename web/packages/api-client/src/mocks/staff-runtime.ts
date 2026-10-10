/* Runs the staff MSW handlers in-process (no service worker, no Node interceptor): used by the staff console's
   /api/bff route handler and server components when NEXT_PUBLIC_API_MOCKS=1. */
import { getResponse } from 'msw';

import { bffProblemType } from '../problem-types';
import { staffHandlers } from './staff/handlers';

export async function handleStaffMockRequest(request: Request): Promise<Response> {
  const res = await getResponse(staffHandlers, request);
  if (res) return res;
  return new Response(
    JSON.stringify({ type: bffProblemType('NOT_FOUND'), title: 'Not found', status: 404, detail: `No mock for ${request.method} ${new URL(request.url).pathname}`, code: 'NOT_FOUND' }),
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } }
  );
}
