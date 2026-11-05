/* Mock of Department requests (/v1/staff/department-requests/**, /v1/staff/organisations/{orgId}/departments): the
   seller service's requests and organisation departments through the BFF. It follows the Go BFF: the queue is filtered
   (department and organisation names, the days the request was made), sorted and paged here; a rejection needs a
   reason; an override needs a reason and a real change; compliance prompts come from the department and what the
   organisation already has on file. Names only, as the real BFF. */
import { http } from 'msw';
import { z } from 'zod';

import { type DepartmentRequirement } from '../../schemas/seller';
import {
  type DepartmentRequestStatus,
  type StaffCompliancePrompt,
  type StaffDepartment,
  type StaffDepartmentRequest,
  type StaffDepartmentRequestPage,
  type StaffDepartmentRequestRow,
  type StaffOrgDepartments
} from '../../staff-departments';
import { levelOf } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';

export interface MockDeptOrg {
  id: string;
  name: string;
  departments: string[];
  /** AfroTransact Retail sells in every department. */
  exempt?: boolean;
  /** Evidence the organisation already has on file (its compliance data). */
  onFile: DepartmentRequirement[];
}

export interface MockDeptRequest {
  id: string;
  orgId: string;
  departmentCode: string;
  note: string | null;
  status: DepartmentRequestStatus;
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

export interface MockDepartmentsState {
  catalog: StaffDepartment[];
  orgs: MockDeptOrg[];
  requests: MockDeptRequest[];
}

const DAY_MS = 86_400_000;

export const MOCK_DEPARTMENT_CATALOG: StaffDepartment[] = [
  // Groceries asks for no food permit any more (catalog migration V17): the requirements come from the department's compliance data.
  { code: 'groceries', name: 'Groceries & Food', requires: [] },
  { code: 'beauty', name: 'Beauty & Personal Care', requires: ['BEAUTY_LABELLING'] },
  { code: 'fashion', name: 'Fashion', requires: [] },
  { code: 'home-art', name: 'Home & Art', requires: [] },
  { code: 'jewellery', name: 'Jewellery & Accessories', requires: [] },
  { code: 'books-music', name: 'Books & Music', requires: [] }
];

export const MOCK_DEPARTMENT_ORGS: MockDeptOrg[] = [
  { id: 'org-ade', name: "Ade's Kitchen", departments: ['groceries'], onFile: [] },
  { id: 'org-mama-efe', name: 'Mama Efe Braids', departments: ['fashion'], onFile: [] },
  { id: 'org-kente', name: 'Kente Naturals', departments: ['fashion', 'home-art'], onFile: [] },
  { id: 'org-nile', name: 'Nile Beauty', departments: ['beauty'], onFile: ['BEAUTY_LABELLING'] },
  { id: 'org-retail', name: 'AfroTransact Retail', departments: [], exempt: true, onFile: [] }
];

export function seedDepartments(now: Date): MockDepartmentsState {
  const ago = (days: number, hour = 10) => new Date(now.getTime() - days * DAY_MS + (hour - 12) * 3_600_000).toISOString();
  const req = (n: number, orgId: string, departmentCode: string, status: DepartmentRequestStatus, requestedBy: string, requestedDaysAgo: number, extra: Partial<MockDeptRequest> = {}): MockDeptRequest => ({
    id: `7d6b1d2a-${String(n).padStart(4, '0')}-4a6e-9c11-${String(n).padStart(12, '0')}`,
    orgId,
    departmentCode,
    note: null,
    status,
    requestedBy,
    requestedAt: ago(requestedDaysAgo, 9),
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    ...extra
  });
  return {
    catalog: structuredClone(MOCK_DEPARTMENT_CATALOG),
    orgs: structuredClone(MOCK_DEPARTMENT_ORGS),
    requests: [
      req(1, 'org-ade', 'fashion', 'PENDING', 'Adebayo Ogunleye', 5, { note: 'We have started making ankara tote bags and head wraps next to the kitchen range.' }),
      req(2, 'org-mama-efe', 'beauty', 'PENDING', 'Efe Okonkwo', 2, { note: 'I want to list shea butter and hair oils alongside my braiding services.' }),
      req(3, 'org-kente', 'groceries', 'PENDING', 'Kofi Mensah', 7, { note: 'Dried hibiscus and spices from our cooperative in Kumasi.' }),
      req(4, 'org-nile', 'jewellery', 'APPROVED', 'Sara Hassan', 12, { decidedBy: 'Amara Diallo', decidedAt: ago(11), decisionNote: 'Beaded jewellery photos look good.' }),
      req(5, 'org-mama-efe', 'groceries', 'REJECTED', 'Efe Okonkwo', 20, { decidedBy: 'Amara Diallo', decidedAt: ago(19), decisionNote: 'Tell us more about what you will sell, then ask again.' }),
      req(6, 'org-kente', 'jewellery', 'APPROVED', 'Kofi Mensah', 30, { decidedBy: 'Tunde Bakare', decidedAt: ago(29) })
    ]
  };
}

const norm = (s: string) => s.trim().toLowerCase();
const PROMPT_LABELS: Record<DepartmentRequirement, string> = { FOOD_PERMIT: 'Food permit on file', BEAUTY_LABELLING: 'Beauty product labelling confirmed' };

export function pendingDepartmentRequests(state: MockDepartmentsState): number {
  return state.requests.filter((r) => r.status === 'PENDING').length;
}

export function departmentHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const invalid = (errors: Record<string, string>) => problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
  const need = (ctx: PreorderCtx, change: boolean) => {
    const lv = levelOf(ctx.roles, 'deptRequests');
    if (!lv) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open department requests.');
    if (change && !ctx.permissions.includes('applications:approve')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the applications:approve permission.');
  };
  const needOrg = (ctx: PreorderCtx, change: boolean) => {
    const can = (p: string) => ctx.permissions.includes(p);
    if (change ? !can('applications:approve') : !(can('applications:review') || can('applications:approve'))) {
      throw problem(403, 'FORBIDDEN', 'Forbidden', `This needs the ${change ? 'applications:approve' : 'applications:review'} permission.`);
    }
  };
  const dept = (state: MockDepartmentsState, code: string): StaffDepartment => state.catalog.find((d) => d.code === code) ?? { code, name: code.replace(/[-_]/g, ' '), requires: [] };
  const org = (state: MockDepartmentsState, id: string): MockDeptOrg => {
    const found = state.orgs.find((o) => o.id === id);
    if (!found) throw problem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that.');
    return found;
  };
  const row = (state: MockDepartmentsState, r: MockDeptRequest): StaffDepartmentRequestRow => ({
    id: r.id,
    orgId: r.orgId,
    orgName: state.orgs.find((o) => o.id === r.orgId)?.name ?? 'Unknown organisation',
    departmentName: dept(state, r.departmentCode).name,
    requestedByName: r.requestedBy,
    requestedAt: r.requestedAt,
    status: r.status,
    decidedByName: r.decidedBy,
    decidedAt: r.decidedAt
  });
  const detail = (ctx: PreorderCtx, r: MockDeptRequest): StaffDepartmentRequest => {
    const state = ctx.db.departments;
    const o = org(state, r.orgId);
    const prompts: StaffCompliancePrompt[] = dept(state, r.departmentCode).requires.map((requirement) => ({
      requirement,
      label: PROMPT_LABELS[requirement],
      satisfied: r.status === 'REJECTED' ? null : o.onFile.includes(requirement)
    }));
    return {
      ...row(state, r),
      note: r.note,
      decisionNote: r.decisionNote,
      prompts,
      currentDepartments: o.departments.map((c) => dept(state, c)).sort((a, b) => a.name.localeCompare(b.name)),
      exempt: Boolean(o.exempt),
      canDecide: ctx.permissions.includes('applications:approve')
    };
  };
  const found = (ctx: PreorderCtx, id: string): MockDeptRequest => {
    const r = ctx.db.departments.requests.find((x) => x.id === id);
    if (!r) throw problem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that.');
    return r;
  };
  const decide = (ctx: PreorderCtx, id: string, approve: boolean, note: string): StaffDepartmentRequest => {
    need(ctx, true);
    const r = found(ctx, id);
    if (approve && note.length > 500) throw invalid({ note: 'Keep the note under 500 characters.' });
    if (!approve && (note.length < 3 || note.length > 500)) throw invalid({ reason: note.length < 3 ? 'Say why: the seller reads it.' : 'Keep the reason under 500 characters.' });
    if (r.status !== 'PENDING') {
      throw problem(409, 'ALREADY_DONE', 'Already done', `This request was already ${r.status.toLowerCase()}`);
    }
    const state = ctx.db.departments;
    r.status = approve ? 'APPROVED' : 'REJECTED';
    r.decidedBy = ctx.action.actor;
    r.decidedAt = ctx.now.toISOString();
    r.decisionNote = note || null;
    if (approve) {
      const o = org(state, r.orgId);
      if (!o.departments.includes(r.departmentCode)) o.departments.push(r.departmentCode);
    }
    ctx.db.audit.unshift({
      at: ctx.now.toISOString(),
      actor: ctx.action.actor,
      action: 'seller.department_request.decided',
      summary: `${approve ? 'Approved' : 'Rejected'} the request to sell in ${dept(state, r.departmentCode).name}${note ? ` — ${note}` : ''}`,
      entityId: r.id
    });
    return detail(ctx, r);
  };
  const orgView = (ctx: PreorderCtx, id: string): StaffOrgDepartments => {
    const state = ctx.db.departments;
    const o = org(state, id);
    return {
      orgId: o.id,
      orgName: o.name,
      exempt: Boolean(o.exempt),
      departments: o.departments.map((c) => dept(state, c)).sort((a, b) => a.name.localeCompare(b.name)),
      catalog: [...state.catalog].sort((a, b) => a.name.localeCompare(b.name)),
      canEdit: ctx.permissions.includes('applications:approve') && !o.exempt,
      pendingRequests: state.requests.filter((r) => r.orgId === o.id && r.status === 'PENDING').length
    };
  };
  const SORTS = ['organisation', 'department', 'requestedAt', 'status', 'decidedAt'];
  return [
    http.get(
      `${W}/department-requests`,
      route((ctx): StaffDepartmentRequestPage => {
        need(ctx, false);
        const state = ctx.db.departments;
        const p = new URL(ctx.request.url).searchParams;
        const errors: Record<string, string> = {};
        const status = (p.get('status') || 'PENDING').toUpperCase();
        if (!['PENDING', 'DECIDED', 'APPROVED', 'REJECTED'].includes(status)) errors.status = 'Choose pending, approved, rejected or decided.';
        const day = /^\d{4}-\d{2}-\d{2}$/;
        const from = p.get('from') ?? '';
        const to = p.get('to') ?? '';
        if (from && !day.test(from)) errors.from = 'Use a date like 2026-10-31.';
        if (to && !day.test(to)) errors.to = 'Use a date like 2026-10-31.';
        if (!errors.from && !errors.to && from && to && from > to) errors.from = 'The first day is after the last day.';
        const page = Number(p.get('page') || 1);
        const size = Number(p.get('size') || 25);
        if (!Number.isInteger(page) || page < 1 || !Number.isInteger(size) || size < 1 || size > 100) errors.page = 'Out of range';
        const [sortName = '', sortDir = 'asc'] = (p.get('sort') ?? '').split(',');
        if (sortName && !SORTS.includes(sortName)) errors.sort = 'Sort by organisation, department, requested date, status or decided date.';
        if (Object.keys(errors).length > 0) throw invalid(errors);
        const column = sortName || (status === 'PENDING' ? 'requestedAt' : 'decidedAt');
        const desc = sortName ? sortDir.toLowerCase() === 'desc' : status !== 'PENDING';
        const wantDept = norm(p.get('department') ?? '');
        const wantOrg = norm(p.get('organisation') ?? '');
        const rows = state.requests
          .filter((r) => (status === 'DECIDED' ? r.status !== 'PENDING' : r.status === status))
          .map((r) => row(state, r))
          .filter((r) => (!wantDept || norm(r.departmentName).includes(wantDept)) && (!wantOrg || norm(r.orgName).includes(wantOrg)))
          .filter((r) => (!from || r.requestedAt.slice(0, 10) >= from) && (!to || r.requestedAt.slice(0, 10) <= to));
        const key = (r: StaffDepartmentRequestRow): string => (column === 'organisation' ? norm(r.orgName) : column === 'department' ? norm(r.departmentName) : column === 'status' ? r.status : column === 'decidedAt' ? (r.decidedAt ?? '') : r.requestedAt);
        rows.sort((a, b) => {
          const x = key(a);
          const y = key(b);
          if (x === y) return 0;
          if (!x) return 1; // empty cells sort last in both directions
          if (!y) return -1;
          return desc ? y.localeCompare(x) : x.localeCompare(y);
        });
        return {
          items: rows.slice((page - 1) * size, page * size),
          total: rows.length,
          page,
          size,
          pending: pendingDepartmentRequests(state),
          truncated: false,
          canDecide: ctx.permissions.includes('applications:approve')
        };
      })
    ),
    http.get(
      `${W}/department-requests/:id`,
      route((ctx, params): StaffDepartmentRequest => {
        need(ctx, false);
        return detail(ctx, found(ctx, String(params.id)));
      })
    ),
    http.post(
      `${W}/department-requests/:id/approve`,
      route(async (ctx, params): Promise<StaffDepartmentRequest> => {
        need(ctx, true);
        const text = await ctx.request.text();
        const body = text.trim() ? z.object({ note: z.string().optional() }).parse(JSON.parse(text)) : {};
        return decide(ctx, String(params.id), true, (body.note ?? '').trim());
      })
    ),
    http.post(
      `${W}/department-requests/:id/reject`,
      route(async (ctx, params): Promise<StaffDepartmentRequest> => {
        need(ctx, true);
        const body = await json(ctx.request, z.object({ reason: z.string().default('') }));
        return decide(ctx, String(params.id), false, body.reason.trim());
      })
    ),
    http.get(
      `${W}/organisations/:orgId/departments`,
      route((ctx, params): StaffOrgDepartments => {
        needOrg(ctx, false);
        return orgView(ctx, String(params.orgId));
      })
    ),
    http.put(
      `${W}/organisations/:orgId/departments`,
      route(async (ctx, params): Promise<StaffOrgDepartments> => {
        needOrg(ctx, true);
        const state = ctx.db.departments;
        const o = org(state, String(params.orgId));
        const body = await json(ctx.request, z.object({ departments: z.array(z.string()), reason: z.string().default('') }));
        const reason = body.reason.trim();
        if (reason.length < 10) throw invalid({ reason: 'Say why the departments change (at least 10 characters).' });
        if (o.exempt) throw problem(409, 'CONFLICT', 'Conflict', 'AfroTransact Retail sells in every department; there is nothing to change.');
        if (body.departments.some((c) => !state.catalog.some((d) => d.code === c))) throw invalid({ departments: 'Choose departments from the list.' });
        const next = [...new Set(body.departments)].sort();
        if (next.join('|') === [...o.departments].sort().join('|')) throw invalid({ departments: 'Nothing changed. Choose different departments before saving.' });
        const before = o.departments;
        o.departments = next;
        const names = (codes: string[]) => (codes.length ? codes.map((c) => dept(state, c).name).join(', ') : 'none');
        ctx.db.audit.unshift({
          at: ctx.now.toISOString(),
          actor: ctx.action.actor,
          action: 'seller.org.departments_changed',
          summary: `Departments set to ${names(next)} (added ${names(next.filter((c) => !before.includes(c)))}; removed ${names(before.filter((c) => !next.includes(c)))}) — ${reason}`,
          entityId: o.id
        });
        return orgView(ctx, o.id);
      })
    )
  ];
}
