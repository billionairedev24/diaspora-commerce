/* Delivery quotes (Customer care) in the mock staff BFF: the queue tabs, who has each case (With / Waiting / SLA /
   Escalation), claim, assign, release, escalate, agree an amount (reason required, within the bounds) or decline, and the
   "Delivery quotes: SLA, escalation and amounts" settings. Mirrors internal/app/staff_resources_quotes.go of the Go BFF over
   orders-service (docs/runbooks/delivery-quotes.md). */
import { type DetailSection, type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { type ActionCtx, type MockAction, type MockResource } from './resources';

const HOUR = 3_600;
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const money = (minor: number) => `$${(minor / 100).toFixed(2)}`;

/** The smallest and largest amount staff may agree (orders' default settings). */
export const QUOTE_MIN_MINOR = 500;
export const QUOTE_MAX_MINOR = 50_000;
export const QUOTE_MIN_REASON = 5;

export const QUOTE_QUEUES = [
  { value: 'MY', label: 'My queue' },
  { value: 'UNASSIGNED', label: 'Unassigned' },
  { value: 'WAITING', label: 'Waiting on customer' },
  { value: 'CLOSED', label: 'Closed' },
  { value: 'ESCALATED', label: 'Escalated' },
  { value: 'OVERDUE', label: 'Overdue' },
  { value: 'ALL', label: 'All' }
];

const WAITING_ON_STAFF = ['REQUESTED', 'IN_PROGRESS'];
const STATUS_LABELS: Record<string, string> = { REQUESTED: 'Requested', IN_PROGRESS: 'Being quoted', READY: 'Quote ready', DECLINED: 'Declined', EXPIRED: 'Expired', USED: 'Used' };
const awaitingStaff = (r: MockRecord) => WAITING_ON_STAFF.includes(str(r.fields.status));

/** Whether the record belongs to a queue tab for the signed-in person. */
export function inQuoteQueue(r: MockRecord, queue: string, me: string): boolean {
  const status = str(r.fields.status);
  switch (queue) {
    case '':
    case 'ALL':
      return true;
    case 'MY':
      return status === 'IN_PROGRESS' && r.fields.assigneeId === me;
    case 'UNASSIGNED':
      return status === 'REQUESTED';
    case 'WAITING':
      return status === 'READY';
    case 'CLOSED':
      return ['DECLINED', 'EXPIRED', 'USED'].includes(status);
    case 'ESCALATED':
      return awaitingStaff(r) && num(r.fields.escalationLevel) > 0;
    case 'OVERDUE':
      return awaitingStaff(r) && r.fields.sla === 'Overdue';
    default:
      return status === queue;
  }
}

/** "12 min", "3 h", "2 days". */
const ago = (seconds: number) => (seconds < 60 ? 'under a minute' : seconds < HOUR ? `${Math.floor(seconds / 60)} min` : seconds < 48 * HOUR ? `${Math.floor(seconds / HOUR)} h` : `${Math.floor(seconds / (24 * HOUR))} days`);

export const quoteStageLine = (r: MockRecord): string =>
  awaitingStaff(r) ? `${STATUS_LABELS[str(r.fields.status)]} · ${r.fields.assigneeName ? `with ${str(r.fields.assigneeName)}` : 'any customer care agent'} · waiting ${ago(num(r.fields.waiting))}` : STATUS_LABELS[str(r.fields.status)] ?? '';

interface SeedQuote {
  id: string;
  ref: string;
  customer: string;
  place: string;
  items: number;
  status: string;
  waiting?: number;
  sla?: string;
  assignee?: [string, string];
  level?: number;
  amountMinor?: number;
  agreedBy?: string;
  note?: string;
}

const SEED: SeedQuote[] = [
  { id: 'dq-1', ref: 'DQ-2026-K7M2XQ', customer: 'Adaeze Nwosu', place: 'Marfa, TX 79843', items: 3, status: 'REQUESTED', waiting: 30 * 60, sla: 'On time', note: 'Gate code 4821' },
  { id: 'dq-2', ref: 'DQ-2026-P4N8TB', customer: 'Bayo Adeyemi', place: 'Alpine, TX 79830', items: 1, status: 'IN_PROGRESS', waiting: 26 * HOUR, sla: 'Overdue', assignee: ['stf-3', 'Amina Bello'], level: 1 },
  { id: 'dq-3', ref: 'DQ-2026-R2W6CD', customer: 'Chidi Eze', place: 'Fort Davis, TX 79734', items: 2, status: 'REQUESTED', waiting: 5 * HOUR, sla: 'On time' },
  { id: 'dq-4', ref: 'DQ-2026-M9X3FG', customer: 'Dara Okon', place: 'Terlingua, TX 79852', items: 4, status: 'READY', amountMinor: 2250, agreedBy: 'Amina Bello' },
  { id: 'dq-5', ref: 'DQ-2026-H5J7LK', customer: 'Efua Mensah', place: 'Presidio, TX 79845', items: 1, status: 'DECLINED', agreedBy: 'Amina Bello' },
  { id: 'dq-6', ref: 'DQ-2026-B8V1NM', customer: 'Femi Ade', place: 'Van Horn, TX 79855', items: 2, status: 'USED', amountMinor: 1800, agreedBy: 'Rita Okoro' }
];

export function seedQuotes(now: Date): MockRecord[] {
  const at = (s: number) => new Date(now.getTime() - s * 1000).toISOString();
  return SEED.map((q) => {
    const open = WAITING_ON_STAFF.includes(q.status);
    const waiting = q.waiting ?? 0;
    const activity = [{ at: at(waiting || 2 * HOUR), actor: q.customer, text: q.note ?? 'Asked for a delivery quote' }];
    if (q.assignee) activity.unshift({ at: at(waiting - HOUR), actor: q.assignee[1], text: `Claimed by ${q.assignee[1]}` });
    if (q.amountMinor !== undefined) activity.unshift({ at: at(HOUR), actor: q.agreedBy ?? 'Amina Bello', text: `Agreed ${money(q.amountMinor)}: Rural route` });
    if (q.status === 'DECLINED') activity.unshift({ at: at(HOUR), actor: q.agreedBy ?? 'Amina Bello', text: 'Declined: We do not run there' });
    return {
      id: q.id,
      fields: {
        reference: q.ref, status: q.status, statusLabel: STATUS_LABELS[q.status] ?? q.status, customerName: q.customer, place: q.place, itemCount: q.items, contact: 'Email', note: q.note ?? null,
        stage: open ? 'QUOTE' : '', with: open ? (q.assignee?.[1] ?? 'Any agent') : q.status === 'READY' ? 'The customer' : null, waiting: open ? waiting : null, sla: open ? (q.sla ?? 'On time') : null,
        escalation: q.level ? `Level ${q.level}` : null, escalationLevel: q.level ?? 0, assigneeId: q.assignee?.[0] ?? null, assigneeName: q.assignee?.[1] ?? null, amountMinor: q.amountMinor ?? null,
        agreedByName: q.amountMinor !== undefined ? (q.agreedBy ?? null) : null, agreedReason: q.amountMinor !== undefined ? 'Rural route, 140 miles' : null, createdAt: at(waiting || 2 * HOUR)
      },
      activity
    };
  });
}

/** The sheet as the BFF lays it out: the request, who has it, the escalation and the agreed amount, with names. */
export function quoteSheetSections(r: MockRecord): DetailSection[] {
  const text = (label: string, value: string) => ({ label, value, kind: 'text' as const });
  const out: DetailSection[] = [
    { title: 'Request', fields: [text('Customer', str(r.fields.customerName)), text('Deliver to', str(r.fields.place)), text('Items', str(r.fields.itemCount)), text('How to reach them', str(r.fields.contact)), ...(r.fields.note ? [text('Their note', str(r.fields.note))] : [])] },
    { title: 'Who has it', fields: [text('Where it is', quoteStageLine(r)), ...(awaitingStaff(r) ? [text('With', str(r.fields.assigneeName) || 'Nobody yet: any customer care agent'), text('Waiting', ago(num(r.fields.waiting))), text('Service level', str(r.fields.sla))] : [])] }
  ];
  if (num(r.fields.escalationLevel) > 0) out.push({ title: 'Escalation', fields: [text('Level', str(r.fields.escalationLevel))] });
  if (r.fields.amountMinor !== null && r.fields.amountMinor !== undefined)
    out.push({ title: 'Agreed amount', fields: [text('Delivery charge', money(num(r.fields.amountMinor))), text('Agreed by', str(r.fields.agreedByName)), text('Why this amount', str(r.fields.agreedReason))] });
  return out;
}

const touch = (r: MockRecord, ctx: ActionCtx, text: string) => r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text });
const reasonOf = (v: Record<string, FieldValue>) => str(v.reason).trim();
const needWaiting = (r: MockRecord) => (awaitingStaff(r) ? null : 'This request is no longer waiting for customer care.');
/** Someone else holds it: only they, or a supervisor, decide. The mock caller is a supervisor, so only closed cases refuse. */
const noteForm = (title: string, submit: string, key: 'note' | 'reason', label: string, required: boolean) => (): StaffForm => ({ title, submitLabel: submit, fields: [field(key, label, 'textarea', required, { maxLength: 500 })] });

const stop = (r: MockRecord, status: string) => {
  r.fields.status = status;
  r.fields.statusLabel = STATUS_LABELS[status] ?? status;
  r.fields.stage = '';
  r.fields.sla = null;
  r.fields.waiting = null;
  r.fields.escalationLevel = 0;
  r.fields.escalation = null;
};

export const QUOTE_ACTIONS: MockAction[] = [
  { key: 'claim', label: 'Claim', tone: 'primary', permission: 'delivery-quotes:agree', when: (r) => needWaiting(r) ?? (r.fields.assigneeId === null ? null : 'It is with someone. A supervisor can reassign it.'),
    apply: (r, _v, ctx) => { r.fields.assigneeId = ctx.actorId; r.fields.assigneeName = ctx.actor; r.fields.with = ctx.actor; r.fields.status = 'IN_PROGRESS'; r.fields.statusLabel = 'Being quoted'; touch(r, ctx, `Claimed by ${ctx.actor}`); } },
  { key: 'assign', label: 'Assign…', tone: 'neutral', permission: 'delivery-quotes:assign', when: needWaiting,
    form: () => ({ title: 'Assign or take over', submitLabel: 'Assign', intro: 'Gives the case to one person. The person is told, and the change is recorded with their name.', fields: [field('assigneeId', 'Assign to', 'select', true, { options: [{ value: 'stf-3', label: 'Amina Bello' }, { value: 'stf-4', label: 'Rita Okoro' }] }), field('note', 'Note (internal)', 'textarea', false, { maxLength: 500 })] }),
    apply: (r, v, ctx) => { const name = v.assigneeId === 'stf-3' ? 'Amina Bello' : 'Rita Okoro'; r.fields.assigneeId = str(v.assigneeId); r.fields.assigneeName = name; r.fields.with = name; r.fields.status = 'IN_PROGRESS'; r.fields.statusLabel = 'Being quoted'; touch(r, ctx, `Assigned to ${name}`); } },
  { key: 'release', label: 'Release to the pool', tone: 'neutral', permission: 'delivery-quotes:agree', when: (r) => needWaiting(r) ?? (r.fields.assigneeId === null ? 'Nobody holds this case.' : null), form: noteForm('Release to the pool', 'Release', 'note', 'Note (internal)', false),
    apply: (r, _v, ctx) => { r.fields.assigneeId = null; r.fields.assigneeName = null; r.fields.with = 'Any agent'; r.fields.status = 'REQUESTED'; r.fields.statusLabel = 'Requested'; touch(r, ctx, 'Released: back to the pool'); } },
  { key: 'escalate', label: 'Escalate now', tone: 'danger', permission: 'delivery-quotes:agree', when: (r) => needWaiting(r) ?? (num(r.fields.escalationLevel) >= 2 ? 'It is already at the highest level.' : null), form: noteForm('Escalate now', 'Escalate', 'reason', 'Why are you escalating?', true),
    apply: (r, v, ctx) => { const level = num(r.fields.escalationLevel) + 1; r.fields.escalationLevel = level; r.fields.escalation = `Level ${level}`; touch(r, ctx, `Escalated to level ${level}: ${reasonOf(v)}`); } },
  { key: 'agree', label: 'Agree a charge…', tone: 'primary', permission: 'delivery-quotes:agree', when: (r) => needWaiting(r),
    form: () => ({ title: 'Agree a delivery charge', submitLabel: 'Send the quote', intro: 'The shopper is told by email and can use the quote at checkout until it expires; it is for this cart and address only and can be used once.',
      fields: [field('amount', 'Delivery charge (US dollars)', 'number', true, { min: QUOTE_MIN_MINOR / 100, max: QUOTE_MAX_MINOR / 100, help: `Between ${money(QUOTE_MIN_MINOR)} and ${money(QUOTE_MAX_MINOR)}.` }), field('reason', 'Why this amount (kept in the audit trail)', 'textarea', true, { maxLength: 500 })] }),
    apply: (r, v, ctx) => {
      const minor = Math.round(Number(v.amount) * 100);
      const errors: Record<string, string> = {};
      if (!Number.isFinite(minor) || minor < QUOTE_MIN_MINOR || minor > QUOTE_MAX_MINOR) errors.amount = `The amount must be between ${money(QUOTE_MIN_MINOR)} and ${money(QUOTE_MAX_MINOR)}`;
      if (reasonOf(v).length < QUOTE_MIN_REASON) errors.reason = `Say why this amount (at least ${QUOTE_MIN_REASON} characters)`;
      if (Object.keys(errors).length) ctx.fail?.(errors);
      stop(r, 'READY');
      r.fields.amountMinor = minor; r.fields.agreedByName = ctx.actor; r.fields.agreedReason = reasonOf(v); r.fields.with = 'The customer';
      touch(r, ctx, `Agreed ${money(minor)}: ${reasonOf(v)}`);
    } },
  { key: 'decline', label: 'Decline', tone: 'danger', permission: 'delivery-quotes:agree', when: (r) => needWaiting(r),
    form: noteForm('Decline to quote', 'Decline', 'reason', 'Why (kept in the audit trail)', true),
    apply: (r, v, ctx) => { if (reasonOf(v).length < QUOTE_MIN_REASON) ctx.fail?.({ reason: `Say why you are declining (at least ${QUOTE_MIN_REASON} characters)` }); stop(r, 'DECLINED'); r.fields.with = null; touch(r, ctx, `Declined: ${reasonOf(v)}`); } }
];

/* ── Settings ── */

const SETTINGS_ID = 'current';
const MODES: Record<string, string> = { NONE: 'Off: cases wait in the pool', ROUND_ROBIN: 'Round robin', LEAST_LOADED: 'Least loaded' };

export function seedQuoteSettings(now: Date): MockRecord[] {
  return [{
    id: SETTINGS_ID,
    fields: { name: 'Delivery quotes', sla: '1 business day', bounds: `${money(QUOTE_MIN_MINOR)} to ${money(QUOTE_MAX_MINOR)}`, validity: '48 h', updatedByName: 'Sue Supervisor', updatedAt: now.toISOString(), slaBusinessDays: 1, dueSoonPercent: 75, unassignedAfterHours: 4, level2AfterHours: 24, mode: 'NONE', level1Role: 'platform.operations_manager', level2Role: null, minAmount: QUOTE_MIN_MINOR / 100, maxAmount: QUOTE_MAX_MINOR / 100, validityHours: 48, requestsPerHour: 5, duplicateWindowMinutes: 15 },
    activity: []
  }];
}

export const quoteSettingsDef: MockResource = {
  title: 'Delivery quotes: SLA, escalation and amounts',
  columns: [
    { key: 'name', label: 'Settings', kind: 'strong', width: 24 }, { key: 'sla', label: 'Answer within', kind: 'text', width: 16 }, { key: 'bounds', label: 'Amount bounds', kind: 'text', width: 18 },
    { key: 'validity', label: 'Valid for', kind: 'text', width: 10 }, { key: 'updatedByName', label: 'Last changed by', kind: 'text', width: 18 }, { key: 'updatedAt', label: 'Changed', kind: 'datetime', width: 14 }
  ],
  facets: [],
  exportable: false,
  titleOf: () => 'Delivery quotes: SLA, escalation and amounts',
  subtitleOf: () => 'How long a request may wait, who is told when it is late, what amounts may be agreed and how long a quote stays valid',
  sheetSections: (r) => [
    { title: 'Service level', fields: [
      { label: 'A request is answered within', value: `${num(r.fields.slaBusinessDays)} business day${num(r.fields.slaBusinessDays) === 1 ? '' : 's'}`, kind: 'text' }, { label: 'Due soon after', value: `${num(r.fields.dueSoonPercent)}% of the time`, kind: 'text' },
      { label: 'Escalate a request nobody has taken after', value: `${num(r.fields.unassignedAfterHours)} hours`, kind: 'text' }] },
    { title: 'Escalation', fields: [{ label: 'Level 1', value: str(r.fields.level1Role) ? `everyone with the role ${str(r.fields.level1Role).replace('platform.', '').replace(/_/g, ' ')}` : 'Nobody set', kind: 'text' }, { label: 'Level 2', value: 'Nobody set', kind: 'text' }] },
    { title: 'Handing out requests', fields: [{ label: 'Auto-assignment', value: MODES[str(r.fields.mode)] ?? '', kind: 'text' }] },
    { title: 'Amounts and validity', fields: [{ label: 'Smallest amount', value: money(num(r.fields.minAmount) * 100), kind: 'text' }, { label: 'Largest amount', value: money(num(r.fields.maxAmount) * 100), kind: 'text' }, { label: 'A quote can be used for', value: `${num(r.fields.validityHours)} hours`, kind: 'text' }] }
  ],
  actions: [],
  edit: {
    permission: 'delivery-quotes:assign',
    form: (r) => ({
      title: 'Delivery quotes: SLA, escalation and amounts', submitLabel: 'Save', intro: 'Level 1 comes before level 2. The change is recorded with your reason and what it was before.',
      fields: [
        field('slaBusinessDays', 'A request is answered within, business days', 'number', true, { min: 1, max: 10, value: num(r.fields.slaBusinessDays) }),
        field('dueSoonPercent', 'Due soon after this percent of the time', 'number', true, { min: 1, max: 99, value: num(r.fields.dueSoonPercent) }),
        field('unassignedAfterHours', 'Escalate a request nobody has taken after, hours', 'number', true, { min: 1, max: 720, value: num(r.fields.unassignedAfterHours) }),
        field('autoAssign', 'Auto-assignment', 'select', true, { options: Object.entries(MODES).map(([value, label]) => ({ value, label })), value: str(r.fields.mode) }),
        field('minAmount', 'Smallest amount staff may agree (US dollars)', 'number', true, { min: 0, max: 10_000, value: num(r.fields.minAmount) }),
        field('maxAmount', 'Largest amount staff may agree (US dollars)', 'number', true, { min: 0, max: 10_000, value: num(r.fields.maxAmount) }),
        field('validityHours', 'A quote can be used for, hours', 'number', true, { min: 1, max: 720, value: num(r.fields.validityHours) }),
        field('reason', 'Reason (10 to 500 characters, kept in the access log)', 'textarea', true, { maxLength: 500, minLength: 10 })
      ],
      changeKeys: ['slaBusinessDays', 'dueSoonPercent', 'unassignedAfterHours', 'autoAssign', 'minAmount', 'maxAmount', 'validityHours']
    }),
    validate: (_r, v) => {
      const errors: Record<string, string> = {};
      if (Number(v.minAmount) > Number(v.maxAmount)) errors.maxAmount = 'The largest amount must be at least the smallest';
      return errors;
    },
    apply: (r, v, ctx) => {
      r.fields.slaBusinessDays = Number(v.slaBusinessDays); r.fields.dueSoonPercent = Number(v.dueSoonPercent); r.fields.unassignedAfterHours = Number(v.unassignedAfterHours);
      r.fields.mode = str(v.autoAssign); r.fields.minAmount = Number(v.minAmount); r.fields.maxAmount = Number(v.maxAmount); r.fields.validityHours = Number(v.validityHours);
      r.fields.sla = `${Number(v.slaBusinessDays)} business day${Number(v.slaBusinessDays) === 1 ? '' : 's'}`; r.fields.bounds = `${money(Number(v.minAmount) * 100)} to ${money(Number(v.maxAmount) * 100)}`; r.fields.validity = `${Number(v.validityHours)} h`;
      r.fields.updatedByName = ctx?.actor ?? 'Unknown user'; r.fields.updatedAt = ctx?.now.toISOString() ?? new Date().toISOString();
      r.activity.unshift({ at: ctx?.now.toISOString() ?? new Date().toISOString(), actor: ctx?.actor ?? 'Unknown user', text: `Changed: ${str(v.reason)}` });
    }
  }
};
