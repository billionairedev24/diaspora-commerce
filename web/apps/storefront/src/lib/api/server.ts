import 'server-only';

import { type ApiClient, createApiClient } from '@afrotransact/api-client';
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
  if (mocksEnabled()) {
    const { handleMockRequest } = await import('@afrotransact/api-client/mocks/runtime');
    return createApiClient({ baseUrl: 'http://storefront.internal/api/bff', headers: forwarded, fetch: (req) => handleMockRequest(req) });
  }
  return createApiClient({ baseUrl: bffOrigin(), headers: forwarded, fetch: (req) => fetch(req, { cache: 'no-store' }) });
}
