/* MSW handlers implementing contracts/openapi/gateway-bff-staff.yaml for the staff console in development and tests.
   Paths use a wildcard prefix (any base URL ending in /v1/staff/...). The cookie at_mock_staff_role picks the staff
   member's platform roles (comma-separated, e.g. platform.customer_support) to try RBAC without identity; the values
   "customer" and "guest" reproduce 403 NOT_STAFF and 401 UNAUTHENTICATED. */
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { z } from 'zod';

import { countSmsSegments, MAX_SMS_SEGMENTS, missingPlaceholders } from '../../notifications';
import { bffProblemType } from '../../problem-types';
import { ExportColumnKindSchema, ExportFormatSchema, type ExportJob } from '../../schemas/exports';
import { type AccessLevel, type ChartSpec } from '../../schemas/seller';
import {
  type Customer360,
  DeliveryTariffSchema,
  type DriverSuggestions,
  FieldValueSchema,
  NotificationCategorySchema,
  type NotificationChannel,
  type NotificationPreview,
  type NotificationSample,
  type NotificationTemplateDetail,
  type NotificationTemplateList,
  type NotificationTemplateSummary,
  type NotificationTemplateVariant,
  type NotificationTemplateVersion,
  type NotificationTestSendResult,
  type PickupAssignmentList,
  type PickupAssignResult,
  type PickupList,
  type RecordAction,
  type RecordDetail,
  type ResourceKey,
  ResourceKeySchema,
  type ResourcePage,
  type ShipFromLocation,
  type StaffAssistantStatus,
  type StaffDashboard,
  type StaffSession,
  type SupportActionResult
} from '../../schemas/staff';
import { validateStaffForm } from '../../validation/staff-form';
import { mockAddressSuggestions, mockResolveAddress } from '../addresses';
import { exportFileOf, type MockExportColumn } from '../export-file';
import { type HubGate, inventoryHubHandlers } from '../inventory-hub';
import { parseSort, queryRows, type SortKey } from '../table';
import { getStaffDb, type MockRecord, type MockStaffDb, type Scalar } from './db';
import { feeRateHandlers } from './fee-rates';
import { activeFeeSchedule, createFeeSchedule, feeRecords, feesOverview, feeView, quoteDelivery, searchFeeOrgs } from './fees';
import { marketHandlers } from './markets';
import { allows, levelOf, navigation, permissionsOf, refundLimit, RESOURCE_MODULE, roleLabel } from './model';
import { type MockTemplateRow } from './notification-templates';
import { planHandlers } from './plans';
import { ordersOf, PREORDER_ORDER_COLUMNS, preorderHandlers, preorderOrderRow } from './preorders';
import { type ActionCtx, detailKind, humanize, type MockAction, RESOURCES, toneOf } from './resources';
import { staffSettingsHandlers } from './settings';
import { MockRenderError, renderPart, type TemplatePart } from './template-render';

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
    { type: bffProblemType(p.code), title: p.title, status: p.status, detail: p.message, code: p.code, ...(p.errors ? { errors: p.errors } : {}) } as JsonBodyType,
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

/** A record as its table row (the cells the table shows). */
const rowOf = (resource: ResourceKey, r: MockRecord) => ({
  id: r.id,
  record: r,
  cells: Object.fromEntries(RESOURCES[resource].columns.map((c) => [c.key, c.key === 'id' ? r.id : cellOf(r.fields[c.key] ?? null)]))
});

/** One search over every column the table shows, then the sort (or the resource's default order), as the BFF does. */
function filtered(ctx: Ctx, resource: ResourceKey, q: string, status: string, sort: SortKey[] = [], ids?: string[]): MockRecord[] {
  const def = RESOURCES[resource];
  const base = records(ctx, resource).filter((r) => !status || !def.statusKey || r.fields[def.statusKey] === status);
  const ordered = def.sort ? [...base].sort(def.sort) : base;
  return queryRows(ordered.map((r) => rowOf(resource, r)), def.columns, q, sort, ids).map((r) => r.record);
}

const sortOf = (resource: ResourceKey, values: string[]): SortKey[] => {
  const sort = parseSort(values, (k) => RESOURCES[resource].columns.some((c) => c.key === k));
  if (!sort) throw invalid({ sort: 'This table can’t be sorted that way.' });
  return sort;
};

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
  const q = (qs.get('q') ?? '').trim();
  const sort = sortOf(resource, qs.getAll('sort'));
  const all = filtered(ctx, resource, q, qs.get('status') ?? '', sort);
  // Like the BFF: a search or a sort is paged with page numbers even where the upstream uses cursors.
  const cursor = def.cursor && !q && !sort.length;
  const start = cursor ? Number(qs.get('cursor') ?? 0) || 0 : (Number(qs.get('page') ?? 0) || 0) * size;
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
    page: cursor ? 0 : Number(qs.get('page') ?? 0) || 0,
    size,
    total: cursor ? null : all.length,
    nextCursor: cursor && hasNext ? String(start + size) : null,
    hasNext,
    facets: def.facets,
    searchable: true,
    searchPlaceholder: `Search ${def.title.toLowerCase()}`,
    canCreate,
    createForm: canCreate && def.create ? def.create.form : null,
    exportable: def.exportable,
    sort: sort.map((k) => `${k.key},${k.desc ? 'desc' : 'asc'}`),
    partial: false
  };
}

function audit(ctx: Ctx, action: string, entityId: string, summary: string) {
  ctx.db.audit.unshift({ at: ctx.now.toISOString(), actor: ctx.action.actor, action, summary, entityId });
}

/* ── Lola ── */

function assistantStatus(ctx: Ctx, used = ctx.db.lolaUsed): StaffAssistantStatus {
  return { dailyMessages: 200, used, remaining: Math.max(0, 200 - used), tools: ['staff_overview', 'search_records', 'get_record', 'seller_payouts', 'seller_account_health', 'open_record'] };
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

/* ── notifications: templates (S268) ── */

const CHANNEL_ORDER: NotificationChannel[] = ['email', 'sms', 'push', 'chat'];
const PART_FIELD: Record<TemplatePart, 'subject' | 'bodyText' | 'bodyHtml'> = { subject: 'subject', text: 'bodyText', html: 'bodyHtml' };
const KEY_PATTERN = /^[a-z0-9][a-z0-9_.-]{0,119}$/;
const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/;

const DraftBody = z.object({
  category: NotificationCategorySchema.optional(),
  subject: z.string().max(300, 'Keep it under 300 characters').optional(),
  bodyHtml: z.string().max(50_000, 'The HTML is too large (50,000 characters at most)').optional(),
  bodyText: z.string().max(5_000, 'Keep it under 5,000 characters')
});
const PreviewBody = z.object({
  channel: z.enum(['email', 'sms', 'push', 'chat'], { message: 'Choose email, sms, push or chat' }),
  locale: z.string().regex(LOCALE_PATTERN, 'Use a locale such as en or fr-CA').default('en'),
  version: z.number().int().min(0, 'Use 0 for the latest version').default(0),
  data: z.record(z.string(), z.unknown()).default({}),
  draft: DraftBody.optional()
});
const VersionBody = z.object({
  locale: z.string().regex(LOCALE_PATTERN, 'Use a locale such as en or fr-CA').default('en'),
  channel: z.enum(['email', 'sms', 'push', 'chat'], { message: 'Choose email, sms, push or chat' }),
  category: NotificationCategorySchema,
  subject: z.string().max(300, 'Keep it under 300 characters').default(''),
  bodyHtml: z.string().max(50_000, 'The HTML is too large (50,000 characters at most)').default(''),
  bodyText: z.string().max(5_000, 'Keep it under 5,000 characters')
});

function requireNotifications(ctx: Ctx, min: AccessLevel = 'VIEW') {
  if (!allows(ctx.roles, 'notifications', min)) throw forbidden(`Your roles can’t ${min === 'VIEW' ? 'open' : 'change'} notifications.`);
}

function requireTemplateWrite(ctx: Ctx) {
  requireNotifications(ctx, 'EDIT');
  if (!ctx.permissions.includes('notifications:templates:write')) throw forbidden('This needs the notifications:templates:write permission.');
}

const canWriteTemplates = (ctx: Ctx) => ctx.permissions.includes('notifications:templates:write') && allows(ctx.roles, 'notifications', 'EDIT');
const templateRows = (ctx: Ctx, key: string) => ctx.db.templates.rows.filter((r) => r.key === key);
const sampleOf = (ctx: Ctx, key: string): NotificationSample =>
  ctx.db.templates.samples[key] ?? { source: 'none', area: 'other', eventTypes: [], data: {} };

function templateList(ctx: Ctx, q: string): NotificationTemplateList {
  const term = q.trim().toLowerCase();
  const latest = new Map<string, MockTemplateRow>();
  ctx.db.templates.rows.forEach((r) => {
    const id = `${r.key}|${r.locale}|${r.channel}`;
    const cur = latest.get(id);
    if (!cur || r.version > cur.version) latest.set(id, r);
  });
  const byKey = new Map<string, NotificationTemplateSummary>();
  [...latest.values()]
    .filter((r) => !term || `${r.key} ${r.category} ${r.channel}`.toLowerCase().includes(term))
    .forEach((r) => {
      const s = byKey.get(r.key) ?? { key: r.key, category: r.category, channels: [], locales: [], latestVersion: 0, updatedBy: null, updatedAt: null };
      if (!s.channels.includes(r.channel)) s.channels.push(r.channel);
      if (!s.locales.includes(r.locale)) s.locales.push(r.locale);
      s.latestVersion = Math.max(s.latestVersion, r.version);
      if (!s.updatedAt || r.createdAt > s.updatedAt) {
        s.updatedAt = r.createdAt;
        s.updatedBy = r.createdBy;
      }
      if (r.channel === 'email') s.category = r.category;
      byKey.set(r.key, s);
    });
  const items = [...byKey.values()]
    .map((s) => ({ ...s, channels: [...s.channels].sort((a, b) => CHANNEL_ORDER.indexOf(a) - CHANNEL_ORDER.indexOf(b)), locales: [...s.locales].sort() }))
    .sort((a, b) => a.key.localeCompare(b.key));
  return { items, total: items.length, canEdit: canWriteTemplates(ctx) };
}

function templateDetail(ctx: Ctx, key: string): NotificationTemplateDetail {
  const rows = KEY_PATTERN.test(key) ? templateRows(ctx, key) : [];
  if (!rows.length) throw notFound('No such notification template.');
  const variants = new Map<string, NotificationTemplateVariant>();
  rows.forEach((r) => {
    const id = `${r.locale}|${r.channel}`;
    const v = variants.get(id) ?? { locale: r.locale, channel: r.channel, latestVersion: 0, versions: [] };
    v.versions.push({ locale: r.locale, channel: r.channel, version: r.version, category: r.category, subject: r.subject, bodyHtml: r.bodyHtml,
      bodyText: r.bodyText, createdBy: r.createdBy, createdAt: r.createdAt });
    v.latestVersion = Math.max(v.latestVersion, r.version);
    variants.set(id, v);
  });
  const list = [...variants.values()]
    .map((v) => ({ ...v, versions: [...v.versions].sort((a, b) => b.version - a.version) }))
    .sort((a, b) => (a.locale === b.locale ? CHANNEL_ORDER.indexOf(a.channel) - CHANNEL_ORDER.indexOf(b.channel) : a.locale === 'en' ? -1 : b.locale === 'en' ? 1 : a.locale.localeCompare(b.locale)));
  const email = list.find((v) => v.channel === 'email');
  const write = canWriteTemplates(ctx);
  return { key, category: (email ?? list[0])?.versions[0]?.category ?? 'transactional', variants: list, sample: sampleOf(ctx, key), canEdit: write, canTestSend: write };
}

function draftErrors(channel: NotificationChannel, subject: string, html: string, text: string): Record<string, string> {
  const errs: Record<string, string> = {};
  if (!text.trim()) errs.bodyText = 'Write the text';
  if (channel === 'email' && !html.trim()) errs.bodyHtml = 'Emails need an HTML body';
  if (channel !== 'email' && html.trim()) errs.bodyHtml = 'Only emails have an HTML body';
  if (channel === 'email' && !subject.trim()) errs.subject = 'Emails need a subject';
  if (channel === 'push' && !subject.trim()) errs.subject = 'Push notifications need a title';
  return errs;
}

function renderRow(row: Pick<MockTemplateRow, 'subject' | 'bodyHtml' | 'bodyText'>, data: Record<string, unknown>, prefix: string) {
  try {
    return { subject: renderPart('subject', row.subject, data).trim(), text: renderPart('text', row.bodyText, data), html: renderPart('html', row.bodyHtml, data) };
  } catch (e) {
    if (!(e instanceof MockRenderError)) throw e;
    const missing = /map has no entry for key "([^"]+)"/.exec(e.message);
    throw invalid({ [prefix + PART_FIELD[e.part]]: missing ? `Not in the sample data: ${missing[1]}` : e.message.replace(/^template render failed: \w+: /, '') });
  }
}

function previewTemplate(ctx: Ctx, key: string, b: z.infer<typeof PreviewBody>): NotificationPreview {
  if (!KEY_PATTERN.test(key) || !templateRows(ctx, key).length) throw notFound('No such notification template.');
  const fallbacks = [b.locale, b.locale.split('-')[0] ?? 'en', 'en'];
  const stored = templateRows(ctx, key)
    .filter((r) => r.channel === b.channel && fallbacks.includes(r.locale) && (b.version === 0 || r.version === b.version))
    .sort((x, y) => fallbacks.indexOf(x.locale) - fallbacks.indexOf(y.locale) || y.version - x.version)[0];
  let out: Omit<NotificationPreview, 'sms'>;
  if (b.draft) {
    const d = b.draft;
    const errs = draftErrors(b.channel, d.subject ?? '', d.bodyHtml ?? '', d.bodyText);
    if (Object.keys(errs).length) throw invalid(Object.fromEntries(Object.entries(errs).map(([k, v]) => [`draft.${k}`, v])));
    const r = renderRow({ subject: d.subject ?? '', bodyHtml: d.bodyHtml ?? '', bodyText: d.bodyText }, b.data, 'draft.');
    out = { ...r, version: 0, category: d.category ?? stored?.category ?? 'transactional' };
  } else {
    if (!stored) throw notFound('No version of this template for that channel and locale.');
    out = { ...renderRow(stored, b.data, ''), version: stored.version, category: stored.category };
  }
  return { ...out, sms: b.channel === 'sms' || b.channel === 'chat' ? countSmsSegments(out.text) : null };
}

function createTemplateVersion(ctx: Ctx, key: string, b: z.infer<typeof VersionBody>): NotificationTemplateVersion {
  if (!KEY_PATTERN.test(key)) throw notFound('No such notification template.');
  const errs = draftErrors(b.channel, b.subject, b.bodyHtml, b.bodyText);
  if (Object.keys(errs).length) throw invalid(errs);
  requireTemplateWrite(ctx);
  if (!templateRows(ctx, key).length) throw notFound('No such notification template.');
  const sample = sampleOf(ctx, key);
  if (sample.source !== 'none') {
    const missing: Record<string, string> = {};
    ([['subject', b.subject], ['bodyHtml', b.bodyHtml], ['bodyText', b.bodyText]] as const).forEach(([field, src]) => {
      const m = missingPlaceholders(src, sample.data);
      if (m.length) missing[field] = `Not in the sample data: ${m.join(', ')}`;
    });
    if (Object.keys(missing).length) throw invalid(missing);
  }
  const r = renderRow(b, sample.data, '');
  if (b.channel === 'sms') {
    const seg = countSmsSegments(r.text);
    if (seg.segments > MAX_SMS_SEGMENTS) throw invalid({ bodyText: `This SMS is ${seg.segments} segments (${seg.encoding}) with the sample data; keep it to 3 or fewer` });
  }
  const version = Math.max(0, ...templateRows(ctx, key).filter((x) => x.locale === b.locale && x.channel === b.channel).map((x) => x.version)) + 1;
  const row: MockTemplateRow = { key, locale: b.locale, channel: b.channel, version, category: b.category, subject: b.subject, bodyHtml: b.bodyHtml,
    bodyText: b.bodyText, createdBy: ctx.db.user.id, createdAt: ctx.now.toISOString() };
  ctx.db.templates.rows.push(row);
  audit(ctx, 'notifications.template.version_created', key, `Created ${key} ${b.channel} ${b.locale} v${version}`);
  const { key: _key, ...out } = row;
  return out;
}

const W = '*/v1/staff';

/* ── fees & commissions ── */

const FeeDraftBody = z.object({
  effectiveFrom: z.string().nullable().optional(),
  immediate: z.boolean().optional(),
  defaultCommissionBps: z.number().int(),
  categoryRates: z.array(z.object({ category: z.string(), commissionBps: z.number().int() })),
  overrides: z.array(z.object({ orgId: z.string(), category: z.string().nullable().optional(), commissionBps: z.number().int() })),
  cardPctBps: z.number().int(),
  cardFixedMinor: z.number().int(),
  fbaPerUnitMinor: z.number().int(),
  reverseCommissionOnRefund: z.boolean(),
  deliveryPricing: z
    .object({
      enabled: z.boolean(),
      maxRadiusMiles: z.number().nullable(),
      customerCharge: DeliveryTariffSchema.nullable(),
      sellerFee: DeliveryTariffSchema.nullable(),
      sellerOverrides: z.array(z.object({ orgId: z.string(), sellerFee: DeliveryTariffSchema }))
    })
    .nullable()
    .optional(),
  planRates: z.array(z.object({ planCode: z.string(), category: z.string(), bps: z.number().int() })).nullable().optional(),
  planFba: z.array(z.object({ planCode: z.string(), feeMinor: z.number().int() })).nullable().optional(),
  connectionFee: z
    .object({
      defaultBps: z.number().int(),
      categoryRates: z.array(z.object({ category: z.string(), bps: z.number().int() })),
      planRates: z.array(z.object({ planCode: z.string(), category: z.string(), bps: z.number().int() }))
    })
    .nullable()
    .optional(),
  note: z.string()
});

const canConfigureFees = (ctx: Ctx) => ctx.permissions.includes('payments:configure') && allows(ctx.roles, 'fees', 'EDIT');

function requireFeesConfigure(ctx: Ctx) {
  if (!allows(ctx.roles, 'fees', 'EDIT')) throw forbidden('Your roles can’t change fees, commissions & tax.');
  if (!ctx.permissions.includes('payments:configure')) throw forbidden('This needs the payments:configure permission.');
}

function requireTaxConfigure(ctx: Ctx) {
  if (!allows(ctx.roles, 'fees', 'EDIT')) throw forbidden('Your roles can’t change fees, commissions & tax.');
  if (!ctx.permissions.includes('tax:configure')) throw forbidden('This needs the tax:configure permission.');
}

const canAssignPickups = (ctx: Ctx) => ctx.permissions.includes('delivery:dispatch') && allows(ctx.roles, 'dispatch', 'EDIT');

function syncFeeRecords(ctx: Ctx) {
  ctx.db.records['fee-schedules'] = feeRecords(ctx.db.feeSchedules, ctx.now);
}

/** Module levels of the Inventory hub and Retail (1P) routes (S281), as the BFF checks them. */
const hubGate: HubGate = (request, area, level) => {
  try {
    const ctx = context(request);
    const module = area === 'retail' ? 'retail' : 'inventory';
    if (!allows(ctx.roles, module, level)) {
      return { status: 403, code: 'FORBIDDEN', title: 'Forbidden', detail: `Your roles can’t ${level === 'VIEW' ? 'open' : 'change'} ${module === 'retail' ? 'AfroTransact Retail (1P)' : 'inventory'}.` };
    }
    return { ownerOrg: null, actor: ctx.action.actor };
  } catch (e) {
    if (e instanceof StaffProblem) return { status: e.status, code: e.code, title: e.title, detail: e.message };
    throw e;
  }
};

export const staffHandlers = [
  // Address fields (Google-like: resolved when picked), as the shopper mocks answer.
  http.get('*/v1/geo/address-suggestions', ({ request }) => {
    const p = new URL(request.url).searchParams;
    return HttpResponse.json(mockAddressSuggestions(p.get('q') ?? '', p.get('sessionToken')));
  }),
  http.get('*/v1/geo/address-suggestions/:id', ({ params }) => {
    const a = mockResolveAddress(decodeURIComponent(String(params.id ?? '')));
    return a ? HttpResponse.json(a) : HttpResponse.json({ status: 404, title: 'Not found' }, { status: 404 });
  }),
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
  /* ── Lola (read-only) ── */
  http.get(
    `${W}/assistant`,
    route((ctx) => assistantStatus(ctx))
  ),
  http.post(
    `${W}/assistant/chat`,
    route(async (ctx) => {
      const b = await json(ctx.request, z.object({ message: z.string().trim().min(1, 'Type a question for Lola').max(1500) }).passthrough());
      const text = b.message.toLowerCase();
      const used = ++ctx.db.lolaUsed;
      const status = assistantStatus(ctx, used);
      if (text.includes('paid') || text.includes('payout')) {
        return {
          answer: 'Org-9 has $780.00 on hold. AT-2026-000777 ($700.00) is released on 2030-01-05; AT-2026-000778 ($80.00) is not delivered yet. Payouts are paused: Stripe needs overdue information.',
          steps: [{ tool: 'seller_payouts', label: 'Checking payouts of org-9' }, { tool: 'seller_account_health', label: 'Checking account health of org-9' }],
          links: [{ label: 'Open org-9 Stripe account', resource: 'connect-accounts', id: 'org-9' }],
          status
        };
      }
      return { answer: 'Ask me about queues, orders, applications and payouts.', steps: [], links: [], status };
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
        if (permissionIssue) throw forbidden(view.reason ?? 'Not allowed.');
        // Already past this action (a stale sheet, a second click): the caller's own repeat is answered with the
        // record; anyone else is told who did it and when (the BFF's ALREADY_DONE).
        const done = def.already?.(rec, ctx.action) ?? null;
        if (done?.mine) return detail(ctx, resource, rec);
        if (done) throw new StaffProblem(409, 'ALREADY_DONE', 'Already done', done.message);
        throw conflict(view.reason ?? 'Not possible now.');
      }
      const { values } = await json(ctx.request, ValuesBody);
      if (view.form) {
        const errors = validateForm(view.form, values);
        if (Object.keys(errors).length) throw invalid(errors);
      }
      def.apply(rec, values, ctx.action);
      audit(ctx, `${resource}.${def.key}`, rec.id, `${def.label}: ${RESOURCES[resource].titleOf(rec)}`);
      if (def.removes) {
        const list = records(ctx, resource);
        const idx = list.indexOf(rec);
        if (idx >= 0) list.splice(idx, 1);
        return { resource, id: rec.id, title: 'Deleted', subtitle: '', status: null, tone: null, sections: [], tables: [], activity: [], notes: [], notesEnabled: false, actions: [], editForm: null, links: [], version: null, removed: true };
      }
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
      const body = await json(
        ctx.request,
        z.object({
          resource: ResourceKeySchema.optional(),
          table: z.literal('preorder-orders').optional(),
          parent: z.string().max(64).optional(),
          format: ExportFormatSchema,
          q: z.string().max(120).optional(),
          status: z.string().max(60).optional(),
          sort: z.array(z.string().max(90)).max(3).optional(),
          ids: z.array(z.string().max(200)).max(1000).optional(),
          columns: z.array(z.string().max(80)).max(40).optional(),
          dataset: z
            .object({
              table: z.string().regex(/^[a-z0-9-]{1,60}$/),
              title: z.string().max(120).optional(),
              scope: z.string().max(200).optional(),
              columns: z.array(z.object({ key: z.string().max(80), label: z.string().min(1).max(80), kind: ExportColumnKindSchema })).min(1).max(40),
              rows: z.array(z.array(z.union([z.string(), z.number(), z.boolean(), z.null()]))).max(5000)
            })
            .optional()
        })
      );
      let key: string;
      let title: string;
      let file: { body: string; rows: number };
      if (body.dataset) {
        const ds = body.dataset;
        if (ds.rows.some((r) => r.length !== ds.columns.length)) throw invalid({ 'dataset.rows': 'Each row needs one cell per column' });
        key = ds.table;
        title = ds.title ?? humanize(ds.table);
        file = exportFileOf(ds.columns, ds.rows);
      } else if (body.table === 'preorder-orders') {
        if (!allows(ctx.roles, 'preorders')) throw forbidden('Your roles can’t export pre-orders.');
        const w = ctx.db.preorders.windows.find((x) => x.id === body.parent);
        if (!w) throw invalid({ parent: 'Choose a pre-order window' });
        const sort = parseSort(body.sort ?? [], (k) => PREORDER_ORDER_COLUMNS.some((c) => c.key === k));
        if (!sort) throw invalid({ sort: 'This table can’t be sorted that way.' });
        const cols = PREORDER_ORDER_COLUMNS.filter((c) => !body.columns?.length || body.columns.includes(c.key)).map((c) => ({ ...c, label: humanize(c.key) })) as MockExportColumn[];
        const rows = queryRows(ordersOf(ctx.db.preorders, w).map(preorderOrderRow), PREORDER_ORDER_COLUMNS, body.q, sort, body.ids);
        key = 'preorder-orders';
        title = 'Pre-order orders';
        file = exportFileOf(cols, rows.map((r) => cols.map((c) => (c.kind === 'money' && typeof r.cells[c.key] === 'number' ? Number(r.cells[c.key]) / 100 : (r.cells[c.key] ?? null)))));
      } else {
        const resource = body.resource;
        if (!resource) throw invalid({ resource: 'Choose a table to export' });
        requireModule(ctx, resource);
        const def = RESOURCES[resource];
        if (!def.exportable) throw invalid({ resource: 'This table can’t be exported.' });
        const unknown = (body.columns ?? []).find((k) => !def.columns.some((c) => c.key === k));
        if (unknown) throw invalid({ columns: `Unknown column ${unknown}` });
        const cols = (body.columns?.length ? body.columns.map((k) => def.columns.find((c) => c.key === k)) : def.columns).filter((c) => c !== undefined);
        const rows = filtered(ctx, resource, body.q ?? '', body.status ?? '', sortOf(resource, body.sort ?? []), body.ids);
        key = resource;
        title = def.title;
        file = exportFileOf(
          cols,
          rows.map((r) => cols.map((c) => {
            const v = c.key === 'id' ? r.id : cellOf(r.fields[c.key] ?? null);
            return c.kind === 'money' && typeof v === 'number' ? v / 100 : v;
          }))
        );
      }
      const id = `exp-${ctx.db.seq++}`;
      const ready = file.rows <= SYNC_EXPORT_ROWS;
      const job: ExportJob & { owner: string; body: string } = {
        id,
        resource: key,
        title,
        // The mock writes CSV text for every format (the Go BFF writes real XLSX with charts and PDF).
        format: body.format,
        status: ready ? 'READY' : 'QUEUED',
        rows: ready ? file.rows : 0,
        async: !ready,
        fileName: `${key}-${ctx.now.toISOString().slice(0, 10)}.${body.format}`,
        createdAt: ctx.now.toISOString(),
        finishedAt: ready ? ctx.now.toISOString() : null,
        expiresAt: ready ? new Date(ctx.now.getTime() + 86_400_000).toISOString() : null,
        downloadUrl: ready ? `/api/bff/v1/staff/exports/${id}/download` : null,
        error: null,
        owner: ctx.db.user.id,
        body: file.body
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
    return new HttpResponse(job.body, { status: 200, headers: { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${job.fileName.replace(/\.(xlsx|pdf)$/, '.csv')}"` } });
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
  /* ── fees & commissions ── */
  http.get(
    `${W}/fees`,
    route((ctx) => {
      requireModule(ctx, 'fee-schedules');
      if (!ctx.permissions.includes('payments:read') && !ctx.permissions.includes('payments:configure')) throw forbidden('This needs the payments:read permission.');
      return feesOverview(ctx.db.feeSchedules, ctx.now, canConfigureFees(ctx));
    })
  ),
  http.get(
    `${W}/fees/orgs`,
    route((ctx) => {
      requireModule(ctx, 'fee-schedules');
      const q = (query(ctx).get('q') ?? '').trim();
      if (q.length < 2 || q.length > 120) throw invalid({ q: 'Type 2 to 120 characters' });
      return { items: searchFeeOrgs(q) };
    })
  ),
  http.post(
    `${W}/fees/schedules`,
    route(async (ctx) => {
      requireFeesConfigure(ctx);
      const body = await json(ctx.request, FeeDraftBody);
      const out = createFeeSchedule(
        ctx.db.feeSchedules,
        { ...body, effectiveFrom: body.effectiveFrom ?? null, immediate: body.immediate ?? false, overrides: body.overrides.map((o) => ({ ...o, category: o.category ?? null })) },
        ctx.now,
        ctx.action.actor
      );
      if ('errors' in out) throw invalid(out.errors);
      syncFeeRecords(ctx);
      audit(ctx, 'fee-schedule.created', out.created.id, `Created fee schedule v${out.created.version} (effective ${out.created.effectiveFrom})`);
      return feeView(out.created, ctx.db.feeSchedules, ctx.now, true);
    }, 201)
  ),
  http.post(
    `${W}/fees/schedules/:id/cancel`,
    route(async (ctx, p) => {
      const { reason } = await json(ctx.request, z.object({ reason: z.string().trim().min(3, 'Say why the version is cancelled (3 to 500 characters)').max(500) }));
      requireFeesConfigure(ctx);
      const s = ctx.db.feeSchedules.find((x) => x.id === p.id);
      if (!s) throw notFound('No such fee schedule version.');
      if (s.cancelledAt) throw conflict(`Version ${s.version} is already cancelled.`);
      if (new Date(s.effectiveFrom).getTime() <= ctx.now.getTime()) throw conflict(`Version ${s.version} already took effect; only scheduled versions can be cancelled.`);
      s.cancelledAt = ctx.now.toISOString();
      s.cancelledBy = ctx.action.actor;
      s.cancelReason = reason;
      syncFeeRecords(ctx);
      audit(ctx, 'fee-schedule.cancelled', s.id, `Cancelled fee schedule v${s.version}: ${reason}`);
      return feeView(s, ctx.db.feeSchedules, ctx.now, true);
    })
  ),
  http.post(
    `${W}/fees/delivery-quote`,
    route(async (ctx) => {
      requireModule(ctx, 'fee-schedules');
      const b = await json(
        ctx.request,
        z.object({
          sellerOrgId: z.string().trim().min(1, 'Choose a seller organisation').max(80),
          distanceMiles: z.number().min(0, 'Enter a distance between 0 and 1,000 miles').max(1000, 'Enter a distance between 0 and 1,000 miles'),
          packages: z.number().int().min(1, 'Enter 1 to 50 packages').max(50, 'Enter 1 to 50 packages').optional()
        })
      );
      return quoteDelivery(ctx.db.feeSchedules, ctx.now, b.sellerOrgId, b.distanceMiles, b.packages ?? 1);
    })
  ),
  http.post(
    `${W}/tax-rates/import`,
    route(async (ctx) => {
      requireTaxConfigure(ctx);
      const b = await json(ctx.request, z.object({ csv: z.string().trim().min(1, 'Paste or upload a CSV file') }));
      const lines = b.csv.split(/\r?\n/).filter((l) => l.trim() && !l.trim().startsWith('kind,'));
      audit(ctx, 'tax-rates.import', 'csv', `Imported ${lines.length} row(s) from CSV`);
      return {
        statesUpserted: lines.filter((l) => /^STATE,/i.test(l)).length,
        localsUpserted: lines.filter((l) => /^LOCAL,/i.test(l)).length,
        warnings: []
      };
    })
  ),
  /* ── dispatch: seller pickups (S255) ── */
  http.get(
    `${W}/dispatch/pickups`,
    route((ctx): PickupList => {
      requireModule(ctx, 'routes');
      return { items: ctx.db.dispatch.pickups.filter((p) => p.status === 'AWAITING_ROUTE'), canAssign: canAssignPickups(ctx) };
    })
  ),
  http.get(
    `${W}/dispatch/pickups/:id/driver-suggestions`,
    route((ctx, p): DriverSuggestions => {
      requireModule(ctx, 'routes');
      const drivers = ctx.db.dispatch.suggestions[p.id ?? ''];
      if (!drivers) throw notFound('No such pickup.');
      return { shipmentId: p.id ?? '', drivers: drivers.slice(0, 5), canAssign: canAssignPickups(ctx) };
    })
  ),
  http.post(
    `${W}/dispatch/pickups/:id/assign`,
    route(async (ctx, p): Promise<PickupAssignResult> => {
      const b = await json(
        ctx.request,
        z.object({
          routeId: z.string().trim().min(1, 'Choose a route').max(20, 'Choose a route'),
          reason: z.string().trim().min(5, 'Say why you assign this route (5 to 500 characters)').max(500, 'Say why you assign this route (5 to 500 characters)')
        })
      );
      requireModule(ctx, 'routes', 'EDIT');
      if (!ctx.permissions.includes('delivery:dispatch')) throw forbidden('This needs the delivery:dispatch permission.');
      const d = ctx.db.dispatch;
      const pickup = d.pickups.find((x) => x.shipmentId === p.id);
      const driver = (d.suggestions[p.id ?? ''] ?? []).find((x) => x.routeId === b.routeId);
      if (!pickup) throw notFound('No such pickup.');
      if (pickup.status !== 'AWAITING_ROUTE' && pickup.status !== 'ROUTED') throw conflict('The pickup was already collected, or its drop-off carries other shipments.');
      const previous = pickup.routeId;
      pickup.routeId = b.routeId;
      pickup.status = 'ROUTED';
      d.assignments.push({
        id: `pa-${ctx.db.seq++}`, shipmentId: pickup.shipmentId, routeId: b.routeId, driverId: driver?.driverId ?? null, mode: 'DISPATCH_OVERRIDE', reason: b.reason,
        previousRouteId: previous, straightMiles: driver?.straightLineMiles ?? null, roadMiles: driver?.roadMiles ?? null, distanceMethod: driver?.distanceMethod ?? null,
        actor: ctx.action.actor, createdAt: ctx.now.toISOString()
      });
      audit(ctx, 'pickup.dispatch_assigned', pickup.shipmentId, `Assigned ${pickup.orderNumber} pickup to ${b.routeId}: ${b.reason}`);
      return { shipmentId: pickup.shipmentId, routeId: b.routeId, routeName: driver?.routeName ?? null, driverId: driver?.driverId ?? null, status: 'ASSIGNED' };
    })
  ),
  http.get(
    `${W}/dispatch/pickups/:id/assignments`,
    route((ctx, p): PickupAssignmentList => {
      requireModule(ctx, 'routes');
      if (!ctx.db.dispatch.suggestions[p.id ?? '']) throw notFound('No such pickup.');
      return { items: ctx.db.dispatch.assignments.filter((a) => a.shipmentId === p.id).sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '')) };
    })
  ),
  http.get(
    `${W}/ship-from-locations/:id`,
    route((ctx, p): ShipFromLocation => {
      requireModule(ctx, 'routes');
      const loc = ctx.db.dispatch.locations.find((l) => l.id === p.id);
      if (!loc) throw notFound('No such location.');
      return loc;
    })
  ),
  /* ── notifications: templates (S268) ── */
  http.get(
    `${W}/notifications/templates`,
    route((ctx): NotificationTemplateList => {
      const q = query(ctx).get('q') ?? '';
      if (q.length > 120) throw invalid({ q: 'Too long' });
      requireNotifications(ctx);
      return templateList(ctx, q);
    })
  ),
  http.get(
    `${W}/notifications/templates/:key`,
    route((ctx, p): NotificationTemplateDetail => {
      requireNotifications(ctx);
      return templateDetail(ctx, p.key ?? '');
    })
  ),
  http.post(
    `${W}/notifications/templates/:key/preview`,
    route(async (ctx, p): Promise<NotificationPreview> => {
      const b = await json(ctx.request, PreviewBody);
      requireNotifications(ctx);
      return previewTemplate(ctx, p.key ?? '', b);
    })
  ),
  http.post(
    `${W}/notifications/templates/:key/versions`,
    route(async (ctx, p): Promise<NotificationTemplateVersion> => {
      const b = await json(ctx.request, VersionBody);
      return createTemplateVersion(ctx, p.key ?? '', b);
    }, 201)
  ),
  http.post(
    `${W}/notifications/templates/:key/test-send`,
    route(async (ctx, p): Promise<NotificationTestSendResult> => {
      const b = await json(ctx.request, PreviewBody);
      if (b.channel === 'push') throw invalid({ channel: 'Push notifications can’t be test-sent; use the preview' });
      requireTemplateWrite(ctx);
      previewTemplate(ctx, p.key ?? '', b);
      if (b.channel !== 'email') {
        throw new StaffProblem(422, 'VALIDATION_FAILED', 'No address to send to', 'Test messages go only to your own email or phone, and none is on your account.');
      }
      audit(ctx, 'notifications.template.test_sent', p.key ?? '', `Test-sent ${p.key ?? ''} (${b.channel}) to ${ctx.db.user.email}`);
      return { to: ctx.db.user.email, channel: b.channel, provider: 'log' };
    })
  ),
  /* ── pre-orders (S274): delivery-runs' staff API through the BFF ── */
  ...preorderHandlers(W, { route, json, problem: (status, code, title, detail, errors) => new StaffProblem(status, code, title, detail, errors) }),
  /* Settings → Markets & locations (location tree) and the shared place pickers */
  ...marketHandlers(W, { route, json, problem: (status, code, title, detail, errors) => new StaffProblem(status, code, title, detail, errors) }),
  /* Plans & billing (S292–S297): the seller service's catalogue, trials, rewards and per-seller billing */
  ...planHandlers(W, { route, json, problem: (status, code, title, detail, errors) => new StaffProblem(status, code, title, detail, errors) }),
  ...feeRateHandlers(W, { route, json, problem: (status, code, title, detail, errors) => new StaffProblem(status, code, title, detail, errors) }, (ctx) => activeFeeSchedule(ctx.db.feeSchedules, ctx.now)),
  /* Inventory hub and AfroTransact Retail (1P) (S281) */
  ...inventoryHubHandlers(W, hubGate),
  /* Anything else under /v1/staff is not part of the contract. */
  http.all(`${W}/*`, () => problemResponse(notFound('No such staff route.'))),
  /* My account (profile, sign-in & security, alerts, preferences): /v1/account/… */
  ...staffSettingsHandlers
];
