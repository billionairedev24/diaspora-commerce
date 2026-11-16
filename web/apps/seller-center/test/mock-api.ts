import { createSellerApiClient, createSessionApi, createSettingsApiClient, type SellerApiClient, type SessionApi, type SettingsApiClient } from '@afrotransact/api-client';
import { MOCK_ROLE_COOKIE } from '@afrotransact/api-client/mocks/seller';
import { handleSellerMockRequest } from '@afrotransact/api-client/mocks/seller-runtime';

let role: string | null = null;

/** Every request the seller screens made to the in-process BFF, as "METHOD /path" (a test that must see no upload reads it). */
export const bffCalls: string[] = [];
const seen = (req: Request) => {
  bffCalls.push(`${req.method} ${new URL(req.url).pathname}`);
};

/** Act as another role on the active organisation (the mocks read the `at_mock_role` cookie). */
export function asRole(r: string | null): void {
  role = r;
}

let previewFailures = 0;

/** The next `times` calls to the store page preview answer the BFF's 404 "Store not found" (a test of the error state and Retry). */
export function failStorePreview(times: number): void {
  previewFailures = times;
}

export function mockApi(): SellerApiClient {
  return createSellerApiClient({
    baseUrl: 'http://seller.test/api/bff',
    headers: role ? { cookie: `${MOCK_ROLE_COOKIE}=${role}` } : {},
    fetch: (req) => {
      seen(req);
      if (previewFailures > 0 && new URL(req.url).pathname.endsWith('/store-page/preview')) {
        previewFailures -= 1;
        return Promise.resolve(
          new Response(JSON.stringify({ status: 404, code: 'NOT_FOUND', title: 'Store not found', detail: 'There is no store at this address.' }), {
            status: 404,
            headers: { 'Content-Type': 'application/problem+json' }
          })
        );
      }
      return handleSellerMockRequest(req);
    }
  });
}

/** The sign-out timer's calls (policy, activity, /v1/me) against the same in-process mocks. */
export function mockSessionApi(): SessionApi {
  return createSessionApi({ baseUrl: 'http://seller.test/api/bff', fetch: (req) => handleSellerMockRequest(req) });
}

/** My account (/v1/account/…) against the same in-process mocks. */
export function mockSettingsApi(): SettingsApiClient {
  return createSettingsApiClient({
    baseUrl: 'http://seller.test/api/bff',
    headers: role ? { cookie: `${MOCK_ROLE_COOKIE}=${role}` } : {},
    fetch: (req) => handleSellerMockRequest(req)
  });
}
