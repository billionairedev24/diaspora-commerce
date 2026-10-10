import { createStaffApiClient, type StaffApiClient } from '@afrotransact/api-client';
import { MOCK_STAFF_ROLE_COOKIE } from '@afrotransact/api-client/mocks/staff';
import { handleStaffMockRequest } from '@afrotransact/api-client/mocks/staff-runtime';

let roles: string | null = null;

/** Act with other platform roles (the mocks read the `at_mock_staff_role` cookie; "customer" and "guest" too). */
export function asRoles(r: string | null): void {
  roles = r;
}

export function mockApi(): StaffApiClient {
  return createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}` } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });
}
