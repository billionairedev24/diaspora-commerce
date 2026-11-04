/* Department requests (contracts/openapi/gateway-bff-staff.yaml, tag Department requests): sellers ask to sell in another
   catalog department; staff approve or reject each request, or replace an organisation's departments with a reason. The
   BFF joins every name (organisation, department, people) so the console never shows an id; `code` is the catalog
   department's node code, which the API sends back but screens never print. */
import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { z } from 'zod';

import { type Requester } from './http';
import { DepartmentRequirementSchema } from './schemas/seller';
import { tableSearchParams } from './table-query';

export const DepartmentRequestStatusSchema = z.enum(['PENDING', 'APPROVED', 'REJECTED']);
export type DepartmentRequestStatus = z.infer<typeof DepartmentRequestStatusSchema>;

/** The table's status filter: `DECIDED` is approved and rejected together (the Decided tab). */
export type DepartmentRequestFilter = DepartmentRequestStatus | 'DECIDED';

export const StaffDepartmentSchema = z.object({ code: z.string(), name: z.string(), requires: z.array(DepartmentRequirementSchema) });
export type StaffDepartment = z.infer<typeof StaffDepartmentSchema>;

export const StaffCompliancePromptSchema = z.object({
  requirement: DepartmentRequirementSchema,
  label: z.string(),
  /** Null when it was not checked (a rejected request). */
  satisfied: z.boolean().nullable()
});
export type StaffCompliancePrompt = z.infer<typeof StaffCompliancePromptSchema>;

export const StaffDepartmentRequestRowSchema = z.object({
  id: z.string(),
  /** Only links to the organisation; screens show `orgName`. */
  orgId: z.string(),
  orgName: z.string(),
  departmentName: z.string(),
  requestedByName: z.string(),
  requestedAt: z.string(),
  status: DepartmentRequestStatusSchema,
  decidedByName: z.string().nullable(),
  decidedAt: z.string().nullable()
});
export type StaffDepartmentRequestRow = z.infer<typeof StaffDepartmentRequestRowSchema>;

export const StaffDepartmentRequestPageSchema = z.object({
  items: z.array(StaffDepartmentRequestRowSchema),
  total: z.number().int(),
  page: z.number().int(),
  size: z.number().int(),
  /** Requests waiting for a decision, whatever the filters say (the tab count and the nav badge). */
  pending: z.number().int(),
  /** The seller service holds more requests than it lists (200 per status). */
  truncated: z.boolean(),
  canDecide: z.boolean()
});
export type StaffDepartmentRequestPage = z.infer<typeof StaffDepartmentRequestPageSchema>;

export const StaffDepartmentRequestSchema = StaffDepartmentRequestRowSchema.extend({
  /** What the seller wrote when asking. */
  note: z.string().nullable(),
  /** The approver's note; for a rejection, the reason the seller reads. */
  decisionNote: z.string().nullable(),
  prompts: z.array(StaffCompliancePromptSchema),
  currentDepartments: z.array(StaffDepartmentSchema),
  /** AfroTransact Retail sells in every department. */
  exempt: z.boolean(),
  canDecide: z.boolean()
});
export type StaffDepartmentRequest = z.infer<typeof StaffDepartmentRequestSchema>;

export const StaffOrgDepartmentsSchema = z.object({
  orgId: z.string(),
  orgName: z.string(),
  exempt: z.boolean(),
  departments: z.array(StaffDepartmentSchema),
  /** Every department of the catalog: what an override chooses from. */
  catalog: z.array(StaffDepartmentSchema),
  canEdit: z.boolean(),
  pendingRequests: z.number().int()
});
export type StaffOrgDepartments = z.infer<typeof StaffOrgDepartmentsSchema>;

/** A table request; `page` is 1-based, days are YYYY-MM-DD (the days the request was made, inclusive). */
export interface StaffDepartmentQuery {
  status?: DepartmentRequestFilter;
  department?: string;
  organisation?: string;
  from?: string;
  to?: string;
  sort?: string[];
  page?: number;
  size?: number;
}

/* ── Client ────────────────────────────────────────────── */

function params(q: StaffDepartmentQuery): URLSearchParams {
  const p = tableSearchParams({ page: q.page ?? 1, size: q.size ?? 25 }, q.sort);
  if (q.status) p.set('status', q.status);
  if (q.department?.trim()) p.set('department', q.department.trim());
  if (q.organisation?.trim()) p.set('organisation', q.organisation.trim());
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  return p;
}

/** Department request routes of the staff BFF (mounted as `staffApi().departments`). */
export function createStaffDepartmentsApi(request: Requester) {
  const one = (id: string) => `/v1/staff/department-requests/${encodeURIComponent(id)}`;
  const org = (orgId: string) => `/v1/staff/organisations/${encodeURIComponent(orgId)}/departments`;
  return {
    requests: (q: StaffDepartmentQuery, signal?: AbortSignal): Promise<StaffDepartmentRequestPage> =>
      request('/v1/staff/department-requests', StaffDepartmentRequestPageSchema, { query: params(q), signal }),
    request: (id: string, signal?: AbortSignal): Promise<StaffDepartmentRequest> => request(one(id), StaffDepartmentRequestSchema, { signal }),
    /** Approves; the department joins the organisation's set at once. The note is optional and shown to the seller. */
    approve: (id: string, note: string): Promise<StaffDepartmentRequest> =>
      request(`${one(id)}/approve`, StaffDepartmentRequestSchema, { method: 'POST', body: note.trim() ? { note: note.trim() } : {} }),
    /** Rejects; the reason is required (422 with `errors.reason`) and the seller reads it. */
    reject: (id: string, reason: string): Promise<StaffDepartmentRequest> =>
      request(`${one(id)}/reject`, StaffDepartmentRequestSchema, { method: 'POST', body: { reason: reason.trim() } }),
    orgDepartments: (orgId: string, signal?: AbortSignal): Promise<StaffOrgDepartments> => request(org(orgId), StaffOrgDepartmentsSchema, { signal }),
    /** Replaces the organisation's departments (422 on `departments` when nothing changed, on `reason` when it is short). */
    setOrgDepartments: (orgId: string, departments: string[], reason: string): Promise<StaffOrgDepartments> =>
      request(org(orgId), StaffOrgDepartmentsSchema, { method: 'PUT', body: { departments, reason: reason.trim() } })
  };
}

export type StaffDepartmentsApi = ReturnType<typeof createStaffDepartmentsApi>;

/* ── Query keys & options ──────────────────────────────── */

export const staffDepartmentKeys = {
  all: ['staff', 'departments'] as const,
  requests: (q: StaffDepartmentQuery) => [...staffDepartmentKeys.all, 'requests', q] as const,
  request: (id: string) => [...staffDepartmentKeys.all, 'request', id] as const,
  org: (orgId: string) => [...staffDepartmentKeys.all, 'org', orgId] as const
};

export const staffDepartmentQueries = {
  requests: (api: StaffDepartmentsApi, q: StaffDepartmentQuery) =>
    queryOptions({ queryKey: staffDepartmentKeys.requests(q), queryFn: ({ signal }) => api.requests(q, signal), placeholderData: keepPreviousData, staleTime: 15_000 }),
  request: (api: StaffDepartmentsApi, id: string) => queryOptions({ queryKey: staffDepartmentKeys.request(id), queryFn: ({ signal }) => api.request(id, signal), staleTime: 10_000 }),
  org: (api: StaffDepartmentsApi, orgId: string) => queryOptions({ queryKey: staffDepartmentKeys.org(orgId), queryFn: ({ signal }) => api.orgDepartments(orgId, signal), staleTime: 15_000 })
};
