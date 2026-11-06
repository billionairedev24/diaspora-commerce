import 'server-only';

import { type ApiClient, createApiClient, EDGE_GEO_HEADERS } from '@afrotransact/api-client';
import { cookies, headers } from 'next/headers';

import { bffOrigin, mocksEnabled } from '../config';

/**
 * Server-side API client for React Server Components. Forwards the shopper's cookies (BFF session)
 * and Accept-Language. With mocks enabled the MSW handlers run in-process — no network hop.
 */
export async function serverApi(): Promise<ApiClient> {
  const [jar, h] = await Promise.all([cookies(), headers()]);
  const cookie = jar.toString();
  const forwarded: Record<string, string> = {};
  if (cookie) forwarded.cookie = cookie;
  const lang = h.get('accept-language');
  if (lang) forwarded['accept-language'] = lang;
  // The edge's visitor location: a first visit's deliver-to city is detected on the server-rendered page too.
  for (const name of EDGE_GEO_HEADERS) {
    const v = h.get(name);
    if (v) forwarded[name] = v;
  }
  if (mocksEnabled()) {
    const { handleMockRequest } = await import('@afrotransact/api-client/mocks/runtime');
    return createApiClient({ baseUrl: 'http://storefront.internal/api/bff', headers: forwarded, fetch: (req) => handleMockRequest(req) });
  }
  return createApiClient({ baseUrl: bffOrigin(), headers: forwarded, fetch: (req) => fetch(req, { cache: 'no-store' }) });
}
