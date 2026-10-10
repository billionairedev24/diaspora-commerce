import { type APIRequestContext, type BrowserContext, expect } from '@playwright/test';

/* Seller Center mock BFF controls (see web/packages/api-client/src/mocks/seller/handlers.ts):
   - the active organisation is process-wide mock state, switched with PUT /v1/seller/session/active-org;
   - the cookie `at_mock_role` overrides the member's role in the active organisation (e.g. seller.fulfilment). */

export const SELLER_ORG = 'org-ade-kitchen';
export const PROVIDER_ORG = 'org-ama-braiding';
export const MOCK_ROLE_COOKIE = 'at_mock_role';

export async function setActiveOrg(request: APIRequestContext, baseURL: string, orgId: string): Promise<void> {
  const res = await request.put(`${baseURL}/api/bff/v1/seller/session/active-org`, { data: { orgId } });
  expect(res.status(), await res.text()).toBe(200);
}

export async function actAs(context: BrowserContext, baseURL: string, roleKey: string): Promise<void> {
  await context.addCookies([{ name: MOCK_ROLE_COOKIE, value: roleKey, url: baseURL }]);
}
