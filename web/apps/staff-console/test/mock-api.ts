import { createSettingsApiClient, createStaffApiClient, type SettingsApiClient, type StaffApiClient } from '@afrotransact/api-client';
import { MOCK_STAFF_ROLE_COOKIE } from '@afrotransact/api-client/mocks/staff';
import { handleStaffMockRequest } from '@afrotransact/api-client/mocks/staff-runtime';

let roles: string | null = null;
let intercept: ((req: Request, next: (r: Request) => Promise<Response>) => Promise<Response>) | null = null;

/** Wraps every staff mock request (simulate lost answers, timeouts); null restores the plain mocks. */
export function interceptStaff(fn: typeof intercept): void {
  intercept = fn;
}

const send = (req: Request): Promise<Response> => (intercept ? intercept(req, handleStaffMockRequest) : handleStaffMockRequest(req));

/** Act with other platform roles (the mocks read the `at_mock_staff_role` cookie; "customer" and "guest" too). */
export function asRoles(r: string | null): void {
  roles = r;
}

export function mockApi(): StaffApiClient {
  return createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: send
  });
}

/** My account (/v1/account/…) against the same in-process mocks. */
export function mockSettingsApi(): SettingsApiClient {
  return createSettingsApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });
}
