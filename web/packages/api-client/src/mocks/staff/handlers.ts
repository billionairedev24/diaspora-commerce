/* MSW handlers implementing contracts/openapi/gateway-bff-staff.yaml for the staff console in development and tests.
   Paths use a wildcard prefix (any base URL ending in /v1/staff/...). The cookie at_mock_staff_role picks the staff
   member's platform roles (comma-separated, e.g. platform.customer_support) to try RBAC without identity; the values
   "customer" and "guest" reproduce 403 NOT_STAFF and 401 UNAUTHENTICATED. */
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { z } from 'zod';

import { type AccessLevel, type ChartSpec } from '../../schemas/seller';
import {
  type Customer360,
  type ExportJob,
  FieldValueSchema,
  type RecordAction,
  type RecordDetail,
  type ResourceKey,
  ResourceKeySchema,
  type ResourcePage,
  type StaffDashboard,
  type StaffSession,
  type SupportActionResult
} from '../../schemas/staff';
import { validateStaffForm } from '../../validation/staff-form';
import { getStaffDb, type MockRecord, type MockStaffDb, type Scalar } from './db';
import { allows, levelOf, navigation, permissionsOf, refundLimit, RESOURCE_MODULE, roleLabel } from './model';
import { type ActionCtx, detailKind, humanize, type MockAction, RESOURCES, toneOf } from './resources';

const PROBLEM_BASE = 'https://errors.afrotransact.com/bff.';
export const MOCK_STAFF_ROLE_COOKIE = 'at_mock_staff_role';
const DEFAULT_ROLES = ['platform.super_admin'];
const SYNC_EXPORT_ROWS = 5000;
const RANK: Record<AccessLevel, number> = { VIEW: 1, EDIT: 2, APPROVE: 3, FULL: 4 };

class StaffProblem extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly title: string,
    detail: string,
    readonly errors?: Record<string, string>
  ) {
    super(detail);
  }
}

const problemResponse = (p: StaffProblem) =>
  HttpResponse.json(
    { type: PROBLEM_BASE + p.code.toLowerCase().replace(/_/g, '-'), title: p.title, status: p.status, detail: p.message, code: p.code, ...(p.errors ? { errors: p.errors } : {}) } as JsonBodyType,
    { status: p.status, headers: { 'Content-Type': 'application/problem+json' } }
  );
const invalid = (errors: Record<string, string>) => new StaffProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
const forbidden = (detail: string) => new StaffProblem(403, 'FORBIDDEN', 'Forbidden', detail);
const notFound = (detail = 'We couldn’t find that.') => new StaffProblem(404, 'NOT_FOUND', 'Not found', detail);
const conflict = (detail: string) => new StaffProblem(409, 'CONFLICT', 'Conflict', detail);
const unavailable = (what: string) =>
  new StaffProblem(503, 'UPSTREAM_UNAVAILABLE', 'Service unavailable', `This part of AfroTransact is temporarily unavailable (${what}). Please try again shortly.`);

function readCookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get('cookie') ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return null;
}

interface Ctx {
  db: MockStaffDb;
  roles: string[];
  permissions: string[];
  request: Request;
  now: Date;
  action: ActionCtx;
}

function context(request: Request): Ctx {
  const db = getStaffDb();
  const cookie = readCookie(request, MOCK_STAFF_ROLE_COOKIE);
  if (cookie === 'guest') throw new StaffProblem(401, 'UNAUTHENTICATED', 'Sign in required', 'Sign in to continue.');
  if (cookie === 'customer') throw new StaffProblem(403, 'NOT_STAFF', 'Staff only', 'The staff console is for AfroTransact staff accounts.');
  const roles = cookie ? cookie.split(',').map((r) => r.trim()).filter(Boolean) : DEFAULT_ROLES;
  const now = new Date();
  const actor = `${db.user.firstName} ${db.user.lastName}`;
  return { db, roles, permissions: permissionsOf(roles), request, now, action: { actor, actorId: db.user.id, now, refundLimit: refundLimit(roles) } };
}

function requireModule(ctx: Ctx, resource: ResourceKey, min: AccessLevel = 'VIEW') {
  const module = RESOURCE_MODULE[resource];
  if (!allows(ctx.roles, module, min)) throw forbidden(`Your roles can’t ${min === 'VIEW' ? 'open' : 'change'} ${humanize(module).toLowerCase()}.`);
}

type Resolver = (ctx: Ctx, params: Record<string, string>) => Promise<unknown> | unknown;

function route(fn: Resolver, status = 200) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (typeof v === 'string') flat[k] = v;
    });
    try {
      const out = await fn(context(request), flat);
      if (out instanceof Response) return out;
      return HttpResponse.json(out as JsonBodyType, { status });
    } catch (e) {
      if (e instanceof StaffProblem) return problemResponse(e);
      throw e;
    }
  };
}

async function json<S extends z.ZodType>(request: Request, schema: S): Promise<z.infer<S>> {
  let raw: unknown = {};
  try {
    const text = await request.text();
    raw = text ? JSON.parse(text) : {};
  } catch {
    throw new StaffProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
  const parsed = schema.safeParse(raw);
  if (!parsed.success) {
    const errors: Record<string, string> = {};
    parsed.error.issues.forEach((i) => {
      errors[i.path.join('.') || '_'] = i.message;
    });
    throw invalid(errors);
  }
  return parsed.data;
}

const ValuesBody = z.object({ values: z.record(z.string(), FieldValueSchema).default({}), version: z.string().nullable().optional() });

/** Server-side validation of a submitted form (the same rules the Go BFF applies). */
export const validateForm = validateStaffForm;

/* ── resource helpers ── */

const query = (ctx: Ctx) => new URL(ctx.request.url).searchParams;
const resourceOf = (p: Record<string, string>): ResourceKey => {
  const parsed = ResourceKeySchema.safeParse(p.resource);
  if (!parsed.success) throw notFound('No such table.');
  return parsed.data;
};
const records = (ctx: Ctx, r: ResourceKey) => ctx.db.records[r];
const find = (ctx: Ctx, r: ResourceKey, id: string) => {
  const rec = records(ctx, r).find((x) => x.id === id);
  if (!rec) throw notFound();
  return rec;
};
const cellOf = (v: Scalar): string | number | null => (typeof v === 'boolean' ? (v ? 'Yes' : 'No') : v);
const display = (v: Scalar): string => (v === null ? '—' : typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v));

function filtered(ctx: Ctx, resource: ResourceKey, q: string, status: string): MockRecord[] {
  const def = RESOURCES[resource];
  const needle = q.trim().toLowerCase();
  return records(ctx, resource).filter((r) => {
    if (status && def.statusKey && r.fields[def.statusKey] !== status) return false;
    if (needle && def.search && !def.search.some((k) => String(r.fields[k] ?? '').toLowerCase().includes(needle))) return false;
    return true;
  });
}

function actionView(ctx: Ctx, resource: ResourceKey, r: MockRecord, a: MockAction): RecordAction {
  const min = a.level ?? 'EDIT';
  const lv = levelOf(ctx.roles, RESOURCE_MODULE[resource]);
  const reason = !ctx.permissions.includes(a.permission)
    ? `Needs the ${a.permission} permission.`
    : !lv || RANK[lv] < RANK[min]
      ? `Needs ${min.toLowerCase()} access to ${humanize(RESOURCE_MODULE[resource]).toLowerCase()}.`
      : a.when(r, ctx.action);
  return { key: a.key, label: a.label, tone: a.tone, allowed: reason === null, reason, permission: a.permission, confirm: a.confirm ?? null, form: a.form ? a.form(r) : null };
}

function detail(ctx: Ctx, resource: ResourceKey, r: MockRecord): RecordDetail {
  const def = RESOURCES[resource];
  const keys = Object.keys(r.fields);
  const grouped = def.sections ?? [['Details', keys]];
  const used = new Set(grouped.flatMap(([, ks]) => ks));
  const rest = keys.filter((k) => !used.has(k));
  const sections = [...grouped, ...(def.sections && rest.length ? ([['More', rest]] as [string, string[]][]) : [])].map(([title, ks]) => ({
    title,
    fields: ks.filter((k) => k in r.fields).map((k) => ({ label: humanize(k), value: display(r.fields[k] ?? null), kind: detailKind(k, def.statusKey) }))
  }));
  const status = def.statusKey ? (r.fields[def.statusKey] as string | null) ?? null : null;
  const canEdit = !!def.edit && ctx.permissions.includes(def.edit.permission) && allows(ctx.roles, RESOURCE_MODULE[resource], 'EDIT');
  return {
    resource,
    id: r.id,
    title: def.titleOf(r),
    subtitle: def.subtitleOf(r),
    status,
    tone: toneOf(status),
    sections,
    tables: r.lines && def.lineColumns ? [{ title: def.linesTitle ?? 'Items', columns: def.lineColumns, rows: r.lines.map((l, i) => ({ id: String(l.id ?? i), cells: Object.fromEntries(Object.entries(l).map(([k, v]) => [k, cellOf(v)])), tone: toneOf(typeof l.status === 'string' ? l.status : null) })) }] : [],
    activity: r.activity,
    notes: [],
    notesEnabled: false,
    actions: def.actions.map((a) => actionView(ctx, resource, r, a)),
    editForm: canEdit && def.edit ? def.edit.form(r) : null,
    links: def.links ? def.links(r) : [],
    version: r.fields.version === undefined || r.fields.version === null ? null : String(r.fields.version)
  };
}

function page(ctx: Ctx, resource: ResourceKey): ResourcePage {
  const def = RESOURCES[resource];
  const qs = query(ctx);
  const size = Math.min(100, Math.max(1, Number(qs.get('size') ?? 25) || 25));
  const all = filtered(ctx, resource, qs.get('q') ?? '', qs.get('status') ?? '');
  const start = def.cursor ? Number(qs.get('cursor') ?? 0) || 0 : (Number(qs.get('page') ?? 0) || 0) * size;
  const rows = all.slice(start, start + size);
  const hasNext = start + size < all.length;
  const canCreate = !!def.create && ctx.permissions.includes(def.create.permission) && allows(ctx.roles, RESOURCE_MODULE[resource], 'EDIT');
  return {
    resource,
    title: def.title,
    columns: def.columns,
    rows: rows.map((r) => ({
      id: r.id,
      cells: Object.fromEntries(def.columns.map((c) => [c.key, c.key === 'id' ? r.id : cellOf(r.fields[c.key] ?? null)])),
      tone: toneOf(def.statusKey ? ((r.fields[def.statusKey] as string | null) ?? null) : null)
    })),
    page: def.cursor ? 0 : Number(qs.get('page') ?? 0) || 0,
    size,
    total: def.cursor ? null : all.length,
    nextCursor: def.cursor && hasNext ? String(start + size) : null,
    hasNext,
    facets: def.facets,
    searchable: !!def.search,
    searchPlaceholder: def.searchPlaceholder,
    canCreate,
    createForm: canCreate && def.create ? def.create.form : null,
    exportable: def.exportable
  };
}

function audit(ctx: Ctx, action: string, entityId: string, summary: string) {
  ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });
}

/* ── dashboard ── */

function dashboard(ctx: Ctx, rangeDays: number): StaffDashboard {
  const since = ctx.now.getTime() - rangeDays * 86_400_000;
  const orders = records(ctx, 'orders').filter((o) => new Date(String(o.fields.placedAt)).getTime() >= since);
  const byDay = new Map<string, { sales: number; net: number }>();
  for (let d = rangeDays - 1; d >= 0; d -= 1) byDay.set(new Date(ctx.now.getTime() - d * 86_400_000).toISOString().slice(5, 10), { sales: 0, net: 0 });
  const byDept = new Map<string, number>();
  orders.forEach((o) => {
    const k = String(o.fields.placedAt).slice(5, 10);
    const b = byDay.get(k);
    if (b) {
      b.sales += Number(o.fields.totalMinor) / 100;
      b.net += Number(o.fields.itemsMinor) / 100;
    }
    const dept = String(o.fields.department);
    byDept.set(dept, (byDept.get(dept) ?? 0) + 1);
  });
  const apps = new Map<string, number>();
  records(ctx, 'applications').forEach((a) => apps.set(humanize(String(a.fields.status)), (apps.get(humanize(String(a.fields.status))) ?? 0) + 1));
  const weeks = ['4 wks ago', '3 wks ago', '2 wks ago', 'Last week', 'This week'];
  const payouts = records(ctx, 'payouts');
  const claims = records(ctx, 'claims').filter((c) => c.fields.status === 'RESOLVED_REFUND');
  const sales = orders.reduce((s, o) => s + Number(o.fields.totalMinor), 0);
  const charts: ChartSpec[] = [
    { id: 'sales', title: 'Sales and net sales', kind: 'trend', categoryLabel: 'Day', valueFormat: 'money', series: [{ key: 'sales', label: 'Gross sales', tone: 'ink' }, { key: 'net', label: 'Net sales', tone: 'gold' }], rows: [...byDay].map(([category, v]) => ({ category, values: { sales: Math.round(v.sales), net: Math.round(v.net) } })), available: true },
    { id: 'departments', title: 'Orders by department', kind: 'hbar', categoryLabel: 'Department', valueFormat: 'count', series: [{ key: 'orders', label: 'Orders', tone: 'gold' }], rows: [...byDept].sort((a, b) => b[1] - a[1]).map(([category, n]) => ({ category, values: { orders: n } })), available: true },
    { id: 'applications', title: 'Applications by status', kind: 'donut', categoryLabel: 'Status', valueFormat: 'count', series: [{ key: 'count', label: 'Applications', tone: 'ink' }], rows: [...apps].map(([category, n]) => ({ category, values: { count: n } })), available: true },
    {
      id: 'payouts-refunds', title: 'Payouts vs refunds', kind: 'stack', categoryLabel: 'Week', valueFormat: 'money',
      series: [{ key: 'payouts', label: 'Payouts', tone: 'green' }, { key: 'refunds', label: 'Refunds', tone: 'red' }],
      rows: weeks.map((category, i) => ({ category, values: { payouts: Math.round(payouts.filter((_, k) => k % 5 === i).reduce((s, p) => s + Number(p.fields.amountMinor), 0) / 100), refunds: Math.round(claims.filter((_, k) => k % 5 === i).reduce((s, c) => s + Number(c.fields.refundMinor ?? 0), 0) / 100) } })),
      available: true
    }
  ];
  const usd = (minor: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(minor / 100);
  const pendingApps = records(ctx, 'applications').filter((a) => ['PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL'].includes(String(a.fields.status))).length;
  const openClaims = records(ctx, 'claims').filter((c) => !String(c.fields.status).startsWith('RESOLVED')).length;
  const queued = payouts.filter((p) => p.fields.status === 'REQUESTED');
  return {
    rangeDays,
    generatedAt: ctx.now.toISOString(),
    kpis: [
      { label: 'Gross sales', value: usd(sales), detail: `${orders.length} orders in ${rangeDays} days`, tone: 'positive' },
      { label: 'Applications to review', value: String(pendingApps), detail: 'Two-step review queue', tone: pendingApps ? 'attention' : 'neutral' },
      { label: 'Open claims', value: String(openClaims), detail: 'A-to-z claims awaiting a decision', tone: openClaims ? 'attention' : 'neutral' },
      { label: 'Payouts to approve', value: usd(queued.reduce((s, p) => s + Number(p.fields.amountMinor), 0)), detail: `${queued.length} requests`, tone: 'neutral' }
    ],
    charts
  };
}

/* ── exports ── */

function csvOf(ctx: Ctx, resource: ResourceKey, q: string, status: string): { body: string; rows: number } {
  const def = RESOURCES[resource];
  const cell = (v: Scalar) => {
    const s = v === null ? '' : String(v);
    return /[",\n\r]/.test(s) || /^[=+\-@]/.test(s) ? `"${s.replace(/^([=+\-@])/, "'$1").replace(/"/g, '""')}"` : s;
  };
  const rows = filtered(ctx, resource, q, status);
  const lines = [def.columns.map((c) => cell(c.label)).join(','), ...rows.map((r) => def.columns.map((c) => cell(c.key === 'id' ? r.id : (r.fields[c.key] ?? null))).join(','))];
  return { body: `\uFEFF${lines.join('\r\n')}`, rows: rows.length };
}

const jobView = ({ owner: _o, body: _b, ...job }: ExportJob & { owner: string; body: string }): ExportJob => job;

/* ── support ── */

function customer360(ctx: Ctx, id: string): Customer360 {
  const u = records(ctx, 'users').find((x) => x.id === id);
  if (!u) throw notFound('No such customer.');
  const orders = records(ctx, 'orders').filter((o) => o.fields.customerId === id);
  const orderNumbers = new Set(orders.map((o) => o.id));
  const perms = ctx.permissions;
  return {
    customer: { id, name: String(u.fields.name), email: String(u.fields.email), enabled: u.fields.enabled === true, emailVerified: u.fields.emailVerified === true, roles: String(u.fields.roles).split(', ') },
    orders: orders.map((o) => ({
      id: o.id,
      orderNumber: o.id,
      status: String(o.fields.status),
      placedAt: String(o.fields.placedAt),
      totalMinor: Number(o.fields.totalMinor),
      currency: 'USD',
      shipments: (o.lines ?? []).map((l) => ({ id: String(l.id), label: String(l.label), status: String(l.status), sellerName: String(o.fields.seller), carrier: (l.carrier as string | null) ?? null, trackingNumber: (l.trackingNumber as string | null) ?? null, cancellable: l.cancellable === true, totalMinor: Number(l.totalMinor) }))
    })),
    bookings: [],
    claims: records(ctx, 'claims').filter((c) => orderNumbers.has(String(c.fields.orderNumber))).map((c) => ({ id: c.id, reference: String(c.fields.reference), type: String(c.fields.type), status: String(c.fields.status), orderNumber: String(c.fields.orderNumber), amountMinor: Number(c.fields.amountMinor), refundMinor: (c.fields.refundMinor as number | null) ?? null, open: !String(c.fields.status).startsWith('RESOLVED') })),
    returns: records(ctx, 'returns').filter((r) => orderNumbers.has(String(r.fields.orderNumber))).map((r) => ({ id: r.id, number: String(r.fields.number), status: String(r.fields.status), orderNumber: String(r.fields.orderNumber), refundState: (r.fields.refundState as string | null) ?? null, estimateMinor: Number(r.fields.estimateMinor) })),
    notifications: ctx.db.notifications.filter((n) => n.recipientId === id).map(({ recipientId: _r, ...n }) => n),
    cases: records(ctx, 'support-cases').filter((c) => c.fields.customerId === id).map((c) => ({ id: c.id, reference: String(c.fields.reference), reason: String(c.fields.reason), summary: String(c.fields.summary), status: String(c.fields.status), openedAt: String(c.fields.openedAt) })),
    audit: ctx.db.audit.filter((a) => a.entityId === id || orderNumbers.has(a.entityId)).map(({ entityId: _e, ...a }) => a),
    refundLimitMinor: refundLimit(ctx.roles),
    can: {
      refund: perms.includes('refunds:issue') || perms.includes('disputes:manage'),
      resend: perms.includes('notifications:manage'),
      cancelShipment: perms.includes('orders:manage') || perms.includes('orders:update-status')
    },
    unavailable: ['bookings']
  };
}

const RefundBody = z.object({
  customerId: z.string().min(1),
  orderNumber: z.string().min(1),
  amountMinor: z.number().int().min(1),
  reason: z.string().trim().min(3).max(500),
  via: z.enum(['CLAIM', 'ORDER']),
  claimId: z.string().optional(),
  caseId: z.string().optional()
});
const ResendBody = z.object({ customerId: z.string().min(1), caseId: z.string().optional() });
const CancelBody = z.object({ customerId: z.string().min(1), orderNumber: z.string().min(1), reason: z.string().trim().min(3).max(500), caseId: z.string().optional() });

function requireSupport(ctx: Ctx, min: AccessLevel) {
  if (!allows(ctx.roles, 'support', min)) throw forbidden('Your roles can’t use the support console.');
}

const W = '*/v1/staff';

export const staffHandlers = [
  http.get(
    `${W}/session`,
    route((ctx): StaffSession => {
      const nav = navigation(ctx.roles, {
        apps: records(ctx, 'applications').filter((a) => ['PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL'].includes(String(a.fields.status))).length,
        support: records(ctx, 'support-cases').filter((c) => c.fields.status === 'OPEN').length,
        disputes: records(ctx, 'claims').filter((c) => ['UNDER_REVIEW', 'ESCALATED'].includes(String(c.fields.status))).length
      });
      if (!nav.length) throw new StaffProblem(403, 'FORBIDDEN', 'No access', 'Your roles don’t open any part of the staff console.');
      const u = ctx.db.user;
      return {
        user: { ...u, initials: `${u.firstName.charAt(0)}${u.lastName.charAt(0)}` },
        roles: ctx.roles.map((key) => ({ key, label: roleLabel(key) })),
        roleLabel: ctx.roles.map(roleLabel).join(' · '),
        permissions: ctx.permissions,
        navGroups: nav,
        landing: nav.some((g) => g.modules.some((m) => m.key === 'dash')) ? 'dash' : (nav[0]?.modules[0]?.key ?? 'dash'),
        accessNote: 'Least privilege by role · enforced server-side',
        refundLimitMinor: refundLimit(ctx.roles)
      };
    })
  ),
  http.get(
    `${W}/dashboard`,
    route((ctx) => {
      if (!allows(ctx.roles, 'dash')) throw forbidden('Your roles can’t open the dashboard.');
      const range = Number(query(ctx).get('rangeDays') ?? 30);
      if (![7, 30, 90].includes(range)) throw invalid({ rangeDays: 'Choose 7, 30 or 90 days' });
      return dashboard(ctx, range);
    })
  ),
  /* ── resources ── */
  http.get(
    `${W}/resources/:resource`,
    route((ctx, p) => {
      const resource = resourceOf(p);
      requireModule(ctx, resource);
      return page(ctx, resource);
    })
  ),
  http.post(
    `${W}/resources/:resource`,
    route(async (ctx, p) => {
      const resource = resourceOf(p);
      const def = RESOURCES[resource].create;
      if (!def) throw notFound('Records of this table can’t be created here.');
      requireModule(ctx, resource, 'EDIT');
      if (!ctx.permissions.includes(def.permission)) throw forbidden(`Needs the ${def.permission} permission.`);
      const { values } = await json(ctx.request, ValuesBody);
      const errors = validateForm(def.form, values);
      if (Object.keys(errors).length) throw invalid(errors);
      const rec = def.build(values, `${resource}-${ctx.db.seq++}`, ctx.action);
      if (records(ctx, resource).some((r) => r.id === rec.id)) throw conflict('That record already exists.');
      records(ctx, resource).unshift(rec);
      if (resource === 'announcements') records(ctx, 'placements').unshift(rec);
      audit(ctx, `${resource}.created`, rec.id, `Created ${RESOURCES[resource].titleOf(rec)}`);
      return detail(ctx, resource, rec);
    }, 201)
  ),
  http.get(
    `${W}/resources/:resource/:id`,
    route((ctx, p) => {
      const resource = resourceOf(p);
      requireModule(ctx, resource);
      return detail(ctx, resource, find(ctx, resource, p.id ?? ''));
    })
  ),
  http.patch(
    `${W}/resources/:resource/:id`,
    route(async (ctx, p) => {
      const resource = resourceOf(p);
      const def = RESOURCES[resource].edit;
      const rec = find(ctx, resource, p.id ?? '');
      if (!def) throw conflict('This record can’t be edited.');
      requireModule(ctx, resource, 'EDIT');
      if (!ctx.permissions.includes(def.permission)) throw forbidden(`Needs the ${def.permission} permission.`);
      const { values, version } = await json(ctx.request, ValuesBody);
      const current = rec.fields.version === undefined || rec.fields.version === null ? null : String(rec.fields.version);
      if (version && current && version !== current) throw conflict('Someone else changed this record. Reload and try again.');
      const errors = validateForm(def.form(rec), values);
      if (Object.keys(errors).length) throw invalid(errors);
      def.apply(rec, values);
      rec.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, text: 'Edited' });
      audit(ctx, `${resource}.updated`, rec.id, `Edited ${RESOURCES[resource].titleOf(rec)}`);
      return detail(ctx, resource, rec);
    })
  ),
  http.post(
    `${W}/resources/:resource/:id/actions/:action`,
    route(async (ctx, p) => {
      const resource = resourceOf(p);
      requireModule(ctx, resource);
      const rec = find(ctx, resource, p.id ?? '');
      const def = RESOURCES[resource].actions.find((a) => a.key === p.action);
      if (!def) throw notFound('No such action.');
      const view = actionView(ctx, resource, rec, def);
      if (!view.allowed) {
        const permissionIssue = !ctx.permissions.includes(def.permission) || (view.reason ?? '').startsWith('Needs');
        throw permissionIssue ? forbidden(view.reason ?? 'Not allowed.') : conflict(view.reason ?? 'Not possible now.');
      }
      const { values } = await json(ctx.request, ValuesBody);
      if (view.form) {
        const errors = validateForm(view.form, values);
        if (Object.keys(errors).length) throw invalid(errors);
      }
      def.apply(rec, values, ctx.action);
      audit(ctx, `${resource}.${def.key}`, rec.id, `${def.label}: ${RESOURCES[resource].titleOf(rec)}`);
      return detail(ctx, resource, rec);
    })
  ),
  http.post(
    `${W}/resources/:resource/:id/notes`,
    route(() => {
      throw unavailable('notes');
    })
  ),
  /* ── exports ── */
  http.get(
    `${W}/exports`,
    route((ctx) => ({ items: ctx.db.exports.filter((j) => j.owner === ctx.db.user.id).map(jobView) }))
  ),
  http.post(
    `${W}/exports`,
    route(async (ctx) => {
      const body = await json(ctx.request, z.object({ resource: ResourceKeySchema, format: z.enum(['csv', 'xlsx']), q: z.string().max(120).optional(), status: z.string().max(60).optional() }));
      requireModule(ctx, body.resource);
      if (!RESOURCES[body.resource].exportable) throw invalid({ resource: 'This table can’t be exported.' });
      const { body: csv, rows } = csvOf(ctx, body.resource, body.q ?? '', body.status ?? '');
      const id = `exp-${ctx.db.seq++}`;
      const ready = rows <= SYNC_EXPORT_ROWS;
      const job: ExportJob & { owner: string; body: string } = {
        id,
        resource: body.resource,
        // The mock writes CSV for both formats (the Go BFF writes real XLSX).
        format: body.format,
        status: ready ? 'READY' : 'QUEUED',
        rows: ready ? rows : 0,
        async: !ready,
        fileName: `${body.resource}-${ctx.now.toISOString().slice(0, 10)}.${body.format}`,
        createdAt: ctx.now.toISOString(),
        finishedAt: ready ? ctx.now.toISOString() : null,
        expiresAt: ready ? new Date(ctx.now.getTime() + 86_400_000).toISOString() : null,
        downloadUrl: ready ? `/api/bff/v1/staff/exports/${id}/download` : null,
        error: null,
        owner: ctx.db.user.id,
        body: csv
      };
      ctx.db.exports.unshift(job);
      return HttpResponse.json(jobView(job) as JsonBodyType, { status: ready ? 201 : 202 });
    })
  ),
  http.get(
    `${W}/exports/:id`,
    route((ctx, p) => {
      const job = ctx.db.exports.find((j) => j.id === p.id && j.owner === ctx.db.user.id);
      if (!job) throw notFound('No such export.');
      return jobView(job);
    })
  ),
  http.get(
    `${W}/exports/:id/download`,
    route((ctx, p) => {
      const job = ctx.db.exports.find((j) => j.id === p.id && j.owner === ctx.db.user.id);
      if (!job) throw notFound('No such export.');
      if (job.status !== 'READY') throw new StaffProblem(409, 'EXPORT_NOT_READY', 'Not ready', 'The export is still being built.');
      const exp = Math.floor(new Date(job.expiresAt ?? 0).getTime() / 1000);
      return new HttpResponse(null, { status: 302, headers: { Location: `/api/bff/v1/staff/export-files/${job.id}?expires=${exp}&sig=mock` } });
    })
  ),
  http.get(`*/v1/staff/export-files/:id`, ({ params }) => {
    const job = getStaffDb().exports.find((j) => j.id === params.id);
    if (!job) return problemResponse(notFound('No such export.'));
    return new HttpResponse(job.body, { status: 200, headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${job.fileName.replace(/\.xlsx$/, '.csv')}"` } });
  }),
  /* ── support ── */
  http.get(
    `${W}/support/customers`,
    route((ctx) => {
      requireSupport(ctx, 'VIEW');
      const q = (query(ctx).get('q') ?? '').trim().toLowerCase();
      if (q.length < 2) throw invalid({ q: 'Type at least 2 characters' });
      return {
        items: records(ctx, 'users')
          .filter((u) => u.fields.principalType === 'CUSTOMER' && (String(u.fields.name).toLowerCase().includes(q) || String(u.fields.email).toLowerCase().includes(q)))
          .map((u) => ({ id: u.id, name: String(u.fields.name), email: String(u.fields.email), enabled: u.fields.enabled === true, principalType: 'CUSTOMER' }))
      };
    })
  ),
  http.get(
    `${W}/support/customers/:id`,
    route((ctx, p) => {
      requireSupport(ctx, 'VIEW');
      return customer360(ctx, p.id ?? '');
    })
  ),
  http.post(
    `${W}/support/refunds`,
    route(async (ctx): Promise<SupportActionResult> => {
      requireSupport(ctx, 'EDIT');
      const b = await json(ctx.request, RefundBody);
      const limit = refundLimit(ctx.roles);
      if (limit !== null && b.amountMinor > limit) {
        throw new StaffProblem(403, 'REFUND_LIMIT', 'Above your refund limit', `Your roles can refund up to $${(limit / 100).toFixed(2)}. Ask Finance for larger refunds.`);
      }
      const order = find(ctx, 'orders', b.orderNumber);
      if (order.fields.customerId !== b.customerId) throw notFound('That order isn’t this customer’s.');
      if (b.via === 'CLAIM') {
        if (!ctx.permissions.includes('disputes:manage')) throw forbidden('Needs the disputes:manage permission.');
        const claim = find(ctx, 'claims', b.claimId ?? '');
        if (String(claim.fields.status).startsWith('RESOLVED')) throw conflict('That claim is already decided.');
        claim.fields.status = 'RESOLVED_REFUND';
        claim.fields.refundMinor = b.amountMinor;
        claim.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, text: `Refunded from support — ${b.reason}` });
      } else if (!ctx.permissions.includes('refunds:issue')) throw forbidden('Needs the refunds:issue permission.');
      audit(ctx, 'refund.issued', b.customerId, `Refund of $${(b.amountMinor / 100).toFixed(2)} on ${b.orderNumber} (${b.via.toLowerCase()})`);
      return { ok: true, message: `Refund of $${(b.amountMinor / 100).toFixed(2)} issued on ${b.orderNumber}.`, reference: `ref-${ctx.db.seq++}`, audited: true };
    })
  ),
  http.post(
    `${W}/support/notifications/:id/resend`,
    route(async (ctx, p): Promise<SupportActionResult> => {
      requireSupport(ctx, 'EDIT');
      const b = await json(ctx.request, ResendBody);
      const n = ctx.db.notifications.find((x) => x.id === p.id && x.recipientId === b.customerId);
      if (!n) throw notFound('No such notification.');
      if (!ctx.permissions.includes('notifications:manage')) throw forbidden('Needs the notifications:manage permission.');
      n.status = 'QUEUED';
      audit(ctx, 'notification.resent', b.customerId, `Resent ${n.templateKey} by ${n.channel}`);
      return { ok: true, message: `${humanize(n.templateKey)} queued again by ${n.channel}.`, reference: n.id, audited: true };
    })
  ),
  http.post(
    `${W}/support/shipments/:id/cancel`,
    route(async (ctx, p): Promise<SupportActionResult> => {
      requireSupport(ctx, 'EDIT');
      const b = await json(ctx.request, CancelBody);
      if (!ctx.permissions.includes('orders:manage') && !ctx.permissions.includes('orders:update-status')) throw forbidden('Needs the orders:manage permission.');
      const order = find(ctx, 'orders', b.orderNumber);
      if (order.fields.customerId !== b.customerId) throw notFound('That order isn’t this customer’s.');
      const s = (order.lines ?? []).find((l) => l.id === p.id);
      if (!s) throw notFound('No such shipment.');
      if (s.cancellable !== true) throw conflict('This shipment can’t be cancelled any more — it is already packed.');
      s.status = 'CANCELLED';
      s.cancellable = false;
      order.fields.shipmentStatus = 'CANCELLED';
      order.fields.status = 'CANCELLED';
      audit(ctx, 'shipment.cancelled', b.customerId, `Cancelled ${String(s.label)} of ${b.orderNumber}: ${b.reason}`);
      return { ok: true, message: `${String(s.label)} of ${b.orderNumber} cancelled.`, reference: String(s.id), audited: true };
    })
  ),
  /* Anything else under /v1/staff is not part of the contract. */
  http.all(`${W}/*`, () => problemResponse(notFound('No such staff route.')))
];
