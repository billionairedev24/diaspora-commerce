import { readCookie } from '../http-kit';
import { settingsHandlers } from '../settings';
import { getStaffDb } from './db';
import { ROLE_LABELS } from './rbac-data';

const STAFF_ROLE_COOKIE = 'at_mock_staff_role';

/** My account for the signed-in staff member: platform roles from the mock role cookie (Super Admin by default). */
export const staffSettingsHandlers = settingsHandlers({
  kind: 'staff',
  person: (request) => (['guest', 'customer'].includes(readCookie(request, STAFF_ROLE_COOKIE) ?? '') ? null : getStaffDb().user),
  roles: (request) =>
    (readCookie(request, STAFF_ROLE_COOKIE)?.split(',').map((r) => r.trim()).filter(Boolean) ?? ['platform.super_admin']).map((key) => ({ key, label: ROLE_LABELS[key] ?? key }))
});
