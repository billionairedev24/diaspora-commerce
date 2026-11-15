/* Settings > Roles and permissions (contracts/openapi/gateway-bff-staff.yaml, tag Access): the permission catalogue, the roles of each
   portal, who holds a role, a person's effective permissions with direct grants, reasoned changes and the change log.
   Identity owns the data; the console never hard-codes a permission, so a permission added by an identity migration shows up as is. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';

export const ACCESS_PORTALS = ['platform', 'seller', 'provider', 'account'] as const;
export const AccessPortalSchema = z.enum(ACCESS_PORTALS);
export type AccessPortal = z.infer<typeof AccessPortalSchema>;

/** A change needs a reason of 3 to 300 characters (the BFF checks it again). */
export const ACCESS_REASON_MIN = 3;
export const ACCESS_REASON_MAX = 300;

export const AccessPermissionSchema = z.object({ key: z.string(), module: z.string(), description: z.string() });
export type AccessPermission = z.infer<typeof AccessPermissionSchema>;
export const AccessPermissionListSchema = z.object({ items: z.array(AccessPermissionSchema) });
export type AccessPermissionList = z.infer<typeof AccessPermissionListSchema>;

export const AccessRoleSchema = z.object({
  key: z.string(),
  portal: z.string(),
  name: z.string(),
  description: z.string().default(''),
  permissions: z.array(z.string()),
  permissionCount: z.number().int()
});
export type AccessRole = z.infer<typeof AccessRoleSchema>;
export const AccessRoleListSchema = z.object({ portal: z.string(), items: z.array(AccessRoleSchema), canAdmin: z.boolean() });
export type AccessRoleList = z.infer<typeof AccessRoleListSchema>;

export const AccessRoleMemberSchema = z.object({ id: z.string(), name: z.string(), email: z.string(), enabled: z.boolean(), markets: z.array(z.string()).default([]) });
export type AccessRoleMember = z.infer<typeof AccessRoleMemberSchema>;
export const AccessRoleMembersSchema = z.object({ role: z.string(), items: z.array(AccessRoleMemberSchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type AccessRoleMembers = z.infer<typeof AccessRoleMembersSchema>;

export const AccessPersonSchema = z.object({
  id: z.string(),
  name: z.string(),
  email: z.string(),
  enabled: z.boolean(),
  principalType: z.string(),
  roles: z.array(z.object({ key: z.string(), name: z.string() })),
  directGrants: z.array(z.string()),
  effectivePermissions: z.array(z.string()),
  roleMarkets: z.record(z.string(), z.array(z.string())).default({})
});
export type AccessPerson = z.infer<typeof AccessPersonSchema>;
export const AccessPersonPageSchema = z.object({ items: z.array(AccessPersonSchema), total: z.number().int(), page: z.number().int(), size: z.number().int() });
export type AccessPersonPage = z.infer<typeof AccessPersonPageSchema>;

export const AccessLogEntrySchema = z.object({
  id: z.string(),
  at: z.string(),
  actorName: z.string(),
  action: z.string(),
  actionLabel: z.string(),
  targetName: z.string(),
  role: z.string(),
  roleName: z.string().default(''),
  subject: z.string(),
  detail: z.string()
});
export type AccessLogEntry = z.infer<typeof AccessLogEntrySchema>;
export const AccessLogPageSchema = z.object({ items: z.array(AccessLogEntrySchema), page: z.number().int(), size: z.number().int(), total: z.number().int() });
export type AccessLogPage = z.infer<typeof AccessLogPageSchema>;

/** An invitation to be staff, as the console lists it: names and emails (the id only keys resend and revoke, and is never shown). */
export const StaffInvitationSchema = z.object({
  id: z.string(),
  name: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  email: z.string(),
  roleKey: z.string(),
  roleName: z.string(),
  status: z.enum(['PENDING', 'EXPIRED']),
  reason: z.string(),
  invitedByName: z.string(),
  createdAt: z.string(),
  expiresAt: z.string(),
  lastSentAt: z.string(),
  sendCount: z.number().int()
});
export type StaffInvitation = z.infer<typeof StaffInvitationSchema>;
export const StaffInvitationPageSchema = z.object({ items: z.array(StaffInvitationSchema), total: z.number().int(), page: z.number().int(), size: z.number().int(), canAdmin: z.boolean() });
export type StaffInvitationPage = z.infer<typeof StaffInvitationPageSchema>;

export interface StaffInviteInput {
  firstName: string;
  lastName: string;
  email: string;
  roleKey: string;
  reason: string;
}

/** What an invite link is for: name, email, role and whether it can still be used. The token never comes back. */
export const StaffInvitationPreviewSchema = z.object({
  firstName: z.string(),
  lastName: z.string(),
  name: z.string(),
  email: z.string(),
  roleName: z.string(),
  status: z.enum(['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED']),
  expiresAt: z.string()
});
export type StaffInvitationPreview = z.infer<typeof StaffInvitationPreviewSchema>;
export const StaffInvitationAcceptedSchema = z.object({ email: z.string(), roleName: z.string() });
export type StaffInvitationAccepted = z.infer<typeof StaffInvitationAcceptedSchema>;
const NoBodySchema = z.unknown();

/** The module access levels, lowest first. */
export const ACCESS_MODULE_LEVELS = ['none', 'view', 'edit', 'approve', 'full'] as const;
export const AccessModuleLevelSchema = z.enum(ACCESS_MODULE_LEVELS);
export type AccessModuleLevel = z.infer<typeof AccessModuleLevelSchema>;
export const AccessModuleMatrixSchema = z.object({
  portal: z.string(),
  levels: z.array(AccessModuleLevelSchema),
  roles: z.array(z.object({ key: z.string(), name: z.string() })),
  modules: z.array(z.object({ key: z.string(), label: z.string(), levels: z.record(z.string(), AccessModuleLevelSchema) })),
  locked: z.array(z.object({ role: z.string(), module: z.string(), minimum: z.literal('edit') })),
  canAdmin: z.boolean()
});
export type AccessModuleMatrix = z.infer<typeof AccessModuleMatrixSchema>;
export const AccessModuleCellSchema = z.object({ portal: z.string(), module: z.string(), role: z.string(), level: AccessModuleLevelSchema });
export type AccessModuleCell = z.infer<typeof AccessModuleCellSchema>;

export interface AccessLogQuery {
  userId?: string;
  role?: string;
  permission?: string;
  page?: number;
  size?: number;
}

const A = '/v1/staff/access';
const enc = encodeURIComponent;

/** Roles and permissions routes of the staff BFF (mounted as `staffApi().access`). */
export function createStaffAccessApi(request: Requester) {
  return {
    permissions: (signal?: AbortSignal): Promise<AccessPermissionList> => request(`${A}/permissions`, AccessPermissionListSchema, { signal }),
    roles: (portal: AccessPortal, signal?: AbortSignal): Promise<AccessRoleList> => request(`${A}/roles`, AccessRoleListSchema, { query: { portal }, signal }),
    roleMembers: (role: string, page: number, size: number, signal?: AbortSignal): Promise<AccessRoleMembers> =>
      request(`${A}/roles/${enc(role)}/members`, AccessRoleMembersSchema, { query: { page: String(page), size: String(size) }, signal }),
    setRolePermission: (role: string, permission: string, on: boolean, reason: string): Promise<AccessRole> =>
      request(`${A}/roles/${enc(role)}/permissions/${enc(permission)}`, AccessRoleSchema, { method: on ? 'PUT' : 'DELETE', body: { reason } }),
    people: (q: string, page: number, size: number, signal?: AbortSignal): Promise<AccessPersonPage> =>
      request(`${A}/people`, AccessPersonPageSchema, { query: { q: q || undefined, page: String(page), size: String(size) }, signal }),
    person: (id: string, signal?: AbortSignal): Promise<AccessPerson> => request(`${A}/people/${enc(id)}`, AccessPersonSchema, { signal }),
    setPersonPermission: (id: string, permission: string, on: boolean, reason: string): Promise<AccessPerson> =>
      request(`${A}/people/${enc(id)}/permissions/${enc(permission)}`, AccessPersonSchema, { method: on ? 'PUT' : 'DELETE', body: { reason } }),
    modules: (portal: AccessPortal, signal?: AbortSignal): Promise<AccessModuleMatrix> => request(`${A}/modules`, AccessModuleMatrixSchema, { query: { portal }, signal }),
    setModuleLevel: (portal: AccessPortal, module: string, role: string, level: AccessModuleLevel, reason: string): Promise<AccessModuleCell> =>
      request(`${A}/modules/${enc(portal)}/${enc(module)}/${enc(role)}`, AccessModuleCellSchema, { method: 'PUT', body: { level, reason } }),
    invitations: (page: number, size: number, signal?: AbortSignal): Promise<StaffInvitationPage> =>
      request(`${A}/invitations`, StaffInvitationPageSchema, { query: { page: String(page), size: String(size) }, signal }),
    invite: (body: StaffInviteInput): Promise<StaffInvitation> => request(`${A}/invitations`, StaffInvitationSchema, { method: 'POST', body }),
    resendInvitation: (id: string): Promise<StaffInvitation> => request(`${A}/invitations/${enc(id)}/resend`, StaffInvitationSchema, { method: 'POST', body: {} }),
    revokeInvitation: (id: string, reason: string): Promise<unknown> => request(`${A}/invitations/${enc(id)}/revoke`, NoBodySchema, { method: 'POST', body: { reason } }),
    /** The invitee's two pages need no session: the token goes in the body, never the address. */
    previewInvitation: (token: string, signal?: AbortSignal): Promise<StaffInvitationPreview> =>
      request('/v1/staff/invitations/preview', StaffInvitationPreviewSchema, { method: 'POST', body: { token }, signal }),
    acceptInvitation: (body: { token: string; password: string; confirmPassword: string }): Promise<StaffInvitationAccepted> =>
      request('/v1/staff/invitations/accept', StaffInvitationAcceptedSchema, { method: 'POST', body }),
    log: (q: AccessLogQuery, signal?: AbortSignal): Promise<AccessLogPage> =>
      request(`${A}/log`, AccessLogPageSchema, {
        query: { userId: q.userId || undefined, role: q.role || undefined, permission: q.permission || undefined, page: String(q.page ?? 0), size: String(q.size ?? 50) },
        signal
      })
  };
}
export type StaffAccessApi = ReturnType<typeof createStaffAccessApi>;

export const staffAccessKeys = {
  all: ['staff', 'access'] as const,
  permissions: () => [...staffAccessKeys.all, 'permissions'] as const,
  roles: (portal: AccessPortal) => [...staffAccessKeys.all, 'roles', portal] as const,
  members: (role: string, page: number, size: number) => [...staffAccessKeys.all, 'members', role, page, size] as const,
  people: (q: string, page: number, size: number) => [...staffAccessKeys.all, 'people', q, page, size] as const,
  person: (id: string) => [...staffAccessKeys.all, 'person', id] as const,
  log: (q: AccessLogQuery) => [...staffAccessKeys.all, 'log', q] as const,
  invitations: (page: number, size: number) => [...staffAccessKeys.all, 'invitations', page, size] as const,
  modules: (portal: AccessPortal) => [...staffAccessKeys.all, 'modules', portal] as const
};

export const staffAccessQueries = {
  permissions: (api: StaffAccessApi) => queryOptions({ queryKey: staffAccessKeys.permissions(), queryFn: ({ signal }) => api.permissions(signal), staleTime: 60_000 }),
  roles: (api: StaffAccessApi, portal: AccessPortal) => queryOptions({ queryKey: staffAccessKeys.roles(portal), queryFn: ({ signal }) => api.roles(portal, signal), staleTime: 10_000 }),
  members: (api: StaffAccessApi, role: string, page: number, size: number) =>
    queryOptions({ queryKey: staffAccessKeys.members(role, page, size), queryFn: ({ signal }) => api.roleMembers(role, page, size, signal), placeholderData: keepPreviousData, staleTime: 30_000 }),
  people: (api: StaffAccessApi, q: string, page: number, size: number) =>
    queryOptions({ queryKey: staffAccessKeys.people(q, page, size), queryFn: ({ signal }) => api.people(q, page, size, signal), placeholderData: keepPreviousData, staleTime: 5_000 }),
  person: (api: StaffAccessApi, id: string) => queryOptions({ queryKey: staffAccessKeys.person(id), queryFn: ({ signal }) => api.person(id, signal), staleTime: 5_000 }),
  modules: (api: StaffAccessApi, portal: AccessPortal) => queryOptions({ queryKey: staffAccessKeys.modules(portal), queryFn: ({ signal }) => api.modules(portal, signal), staleTime: 10_000 }),
  invitations: (api: StaffAccessApi, page: number, size: number) =>
    queryOptions({ queryKey: staffAccessKeys.invitations(page, size), queryFn: ({ signal }) => api.invitations(page, size, signal), placeholderData: keepPreviousData, staleTime: 5_000 }),
  log: (api: StaffAccessApi, q: AccessLogQuery) => queryOptions({ queryKey: staffAccessKeys.log(q), queryFn: ({ signal }) => api.log(q, signal), placeholderData: keepPreviousData, staleTime: 5_000 })
};
