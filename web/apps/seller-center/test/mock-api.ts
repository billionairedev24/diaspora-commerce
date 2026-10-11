import { createSellerApiClient, createSettingsApiClient, type SellerApiClient, type SettingsApiClient } from '@afrotransact/api-client';
import { MOCK_ROLE_COOKIE } from '@afrotransact/api-client/mocks/seller';
import { handleSellerMockRequest } from '@afrotransact/api-client/mocks/seller-runtime';

let role: string | null = null;

/** Act as another role on the active organisation (the mocks read the `at_mock_role` cookie). */
export function asRole(r: string | null): void {
  role = r;
}

export function mockApi(): SellerApiClient {
  return createSellerApiClient({
    baseUrl: 'http://seller.test/api/bff',
    headers: role ? { cookie: `${MOCK_ROLE_COOKIE}=${role}` } : {},
    fetch: (req) => handleSellerMockRequest(req)
  });
}

/** My account (/v1/account/…) against the same in-process mocks. */
export function mockSettingsApi(): SettingsApiClient {
  return createSettingsApiClient({
    baseUrl: 'http://seller.test/api/bff',
    headers: role ? { cookie: `${MOCK_ROLE_COOKIE}=${role}` } : {},
    fetch: (req) => handleSellerMockRequest(req)
  });
}
