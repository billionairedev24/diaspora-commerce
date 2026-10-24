/* Resource definitions of the mock staff BFF: columns, facets, detail sections, RBAC-gated actions and forms — the
   same shapes the Go BFF builds from the real upstreams (internal/app/staff_resources*.go). */
import { type AccessLevel } from '../../schemas/seller';
import {
  type ActivityEntry,
  type ColumnKind,
  type DetailField,
  type FieldValue,
  type FormField,
  type RecordLink,
  type RecordTone,
  type ResourceColumn,
  type ResourceFacet,
  type ResourceKey,
  type StaffForm
} from '../../schemas/staff';
import { type MockRecord, type Scalar } from './db';
import { IDENTITY_RESOURCES } from './identity';

export interface ActionCtx {
  actor: string;
  actorId: string;
  now: Date;
  refundLimit: number | null;
  /** Puts a secret in the answer of the current action or creation, and nowhere else (a new OAuth client secret). */
  showOnce?: (label: string, value: string, note: string) => void;
}

export interface MockAction {
  key: string;
  label: string;
  tone: 'primary' | 'neutral' | 'danger';
  permission: string;
  level?: AccessLevel;
  confirm?: string;
  form?: (r: MockRecord) => StaffForm;
  /** null when the action is possible in the record's state, otherwise the reason it is not. */
  when: (r: MockRecord, ctx: ActionCtx) => string | null;
  /**
   * The record already went past this action (like the BFF's settled actions): the caller's own repeat answers 200,
   * anyone else gets 409 ALREADY_DONE with the message (who and when).
   */
  already?: (r: MockRecord, ctx: ActionCtx) => { mine: boolean; message: string } | null;
  apply: (r: MockRecord, values: Record<string, FieldValue>, ctx: ActionCtx) => void;
  /** When true, the handler drops the record and returns a removed detail (advert:delete). */
  removes?: boolean;
}

export interface MockResource {
  title: string;
  columns: ResourceColumn[];
  statusKey?: string;
  facets: ResourceFacet[];
  search?: string[];
  searchPlaceholder?: string;
  /** Default row order (records keep their seed order otherwise). */
  sort?: (a: MockRecord, b: MockRecord) => number;
  cursor?: boolean;
  exportable: boolean;
  titleOf: (r: MockRecord) => string;
  subtitleOf: (r: MockRecord) => string;
  sections?: [string, string[]][];
  linesTitle?: string;
  lineColumns?: ResourceColumn[];
  links?: (r: MockRecord) => RecordLink[];
  actions: MockAction[];
  edit?: { permission: string; form: (r: MockRecord) => StaffForm; apply: (r: MockRecord, values: Record<string, FieldValue>) => void };
  create?: { permission: string; form: StaffForm; build: (values: Record<string, FieldValue>, id: string, ctx: ActionCtx) => MockRecord };
}

const MARKET_ORDER: Record<string, number> = { LIVE: 0, COMING_SOON: 1, PAUSED: 2, PLANNED: 3 };
const col = (key: string, label: string, kind: ColumnKind, width: number): ResourceColumn => ({ key, label, kind, width });
const facets = (...values: string[]): ResourceFacet[] => values.map((v) => ({ value: v, label: humanize(v) }));
const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const form = (title: string, submitLabel: string, fields: FormField[], intro?: string, changeKeys?: string[]): StaffForm => ({ title, submitLabel, fields, ...(intro ? { intro } : {}), ...(changeKeys ? { changeKeys } : {}) });
const options = (...values: string[]) => values.map((v) => ({ value: v, label: humanize(v) }));
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const is = (r: MockRecord, ...statuses: string[]) => statuses.includes(str(r.fields.status));
const stateReason = (r: MockRecord, ...ok: string[]) => (is(r, ...ok) ? null : `Not possible while ${humanize(str(r.fields.status)).toLowerCase()}.`);
const log = (r: MockRecord, ctx: ActionCtx, text: string) => r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text } satisfies ActivityEntry);
const WHEN = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'UTC' });
/** Seller's "already decided" answer: what the application's latest decision was, by whom and when. */
const decidedIn =
  (...statuses: string[]) =>
  (r: MockRecord, ctx: ActionCtx): { mine: boolean; message: string } | null => {
    if (!is(r, ...statuses)) return null;
    const last = r.activity[0];
    const mine = last?.actor === ctx.actor;
    const status = humanize(str(r.fields.status)).toLowerCase();
    const by = last ? ` (by ${mine ? 'you' : last.actor}, ${WHEN.format(new Date(last.at)).replace(' at ', ', ')} UTC)` : '';
    return { mine, message: `This application is already ${status}${by}` };
  };
const finalStep = (r: MockRecord, ctx: ActionCtx) =>
  stateReason(r, 'AWAITING_FINAL_APPROVAL', 'FLAGGED') ?? (r.fields.reviewerId === ctx.actorId ? 'You reviewed step 1 — a different approver must decide step 2.' : null);
const setStatus = (status: string, text: string) => (r: MockRecord, _v: Record<string, FieldValue>, ctx: ActionCtx) => {
  r.fields.status = status;
  log(r, ctx, text);
};

export function humanize(s: string): string {
  const spaced = s
    .replace(/Minor$/, '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .toLowerCase()
    .trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const TONES: Record<string, RecordTone> = {
  PENDING_REVIEW: 'brand', FLAGGED: 'bad', AWAITING_FINAL_APPROVAL: 'warn', INFO_REQUESTED: 'warn', APPROVED: 'good', REJECTED: 'bad', LIVE: 'good',
  IN_REVIEW: 'brand', PAUSED: 'info', TAKEN_DOWN: 'bad', PENDING: 'warn', ESCALATED: 'bad', CLOSED: 'info', UNDER_REVIEW: 'brand', AWAITING_SELLER: 'warn',
  RESOLVED_REFUND: 'good', RESOLVED_DENIED: 'info', WITHDRAWN: 'info', REQUESTED: 'brand', ON_HOLD: 'bad', PAID: 'good', FAILED: 'bad', SELLABLE: 'good',
  QUARANTINED: 'bad', DRAFT: 'info', PENDING_APPROVAL: 'warn', SENT: 'info', CONFIRMED: 'info', RECEIVED: 'good', CANCELLED: 'bad', PLANNED: 'info',
  ASSIGNED: 'brand', IN_PROGRESS: 'brand', COMPLETED: 'good', OPEN: 'warn', RESOLVED: 'good', COMING_SOON: 'brand', SCHEDULED: 'brand', ENDED: 'info',
  ENABLED: 'good', DISABLED: 'bad', ACTIVE: 'good', IN_TRANSIT: 'info', REFUNDED: 'good', DELIVERED: 'good', PLACED: 'brand', DELIVERY_FAILED: 'bad',
  OUT_FOR_DELIVERY: 'info', PACKED: 'info', STATE: 'info', LOCAL: 'brand', ALERT: 'bad', BLOCKED: 'bad', AWAITING_ROUTE: 'warn', APPLIED: 'good',
  ACKNOWLEDGED: 'info', DUPLICATE: 'info'
};
export const toneOf = (status: string | null): RecordTone | null => (status ? (TONES[status] ?? 'info') : null);

export function detailKind(key: string, statusKey: string | undefined): DetailField['kind'] {
  if (key === statusKey) return 'status';
  if (/Minor$/.test(key)) return 'money';
  if (/At$/.test(key) || key === 'effectiveFrom') return 'datetime';
  if (/Date$/.test(key)) return 'date';
  if (/^(id|atin|sku|hash|lotNo|reference|number)$/.test(key)) return 'mono';
  return 'text';
}

const REASON_CODES = options('PROHIBITED_ITEM', 'UNAPPROVED_HEALTH_CLAIM', 'RESTRICTED_INGREDIENT', 'COUNTERFEIT', 'IMAGE_POLICY', 'MISLEADING_CONTENT', 'LABELLING', 'OTHER');
const noteForm = (title: string, submit: string, required = true) => () => form(title, submit, [field('note', 'Note', 'textarea', required, { maxLength: 2000 })]);
const reasonForm = (title: string, submit: string) => () => form(title, submit, [field('reason', 'Reason', 'textarea', true, { maxLength: 1000 })]);

export const RESOURCES: Record<ResourceKey, MockResource> = {
  users: {
    title: 'People',
    columns: [col('name', 'Name', 'strong', 24), col('email', 'Email', 'text', 30), col('principalType', 'Type', 'text', 12), col('roles', 'Roles', 'text', 22), col('status', 'Status', 'status', 12)],
    statusKey: 'status',
    facets: facets('ACTIVE', 'DISABLED'),
    search: ['name', 'email'],
    searchPlaceholder: 'Search by name or email',
    exportable: true,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => str(r.fields.email),
    actions: [
      {
        key: 'assign-role', label: 'Assign role', tone: 'primary', permission: 'access:admin', when: () => null,
        form: () => form('Assign a role', 'Assign', [field('roleKey', 'Role', 'select', true, { options: options('platform.customer_support', 'platform.finance', 'platform.trust_safety', 'platform.marketing', 'platform.auditor') }), field('reason', 'Reason', 'text', true, { maxLength: 300 })]),
        apply: (r, v, ctx) => {
          const roles = new Set(str(r.fields.roles).split(', ').filter(Boolean));
          roles.add(str(v.roleKey));
          r.fields.roles = [...roles].join(', ');
          log(r, ctx, `Assigned ${str(v.roleKey)} — ${str(v.reason)}`);
        }
      },
      { key: 'disable', label: 'Disable account', tone: 'danger', permission: 'access:admin', when: (r) => (r.fields.enabled ? null : 'Already disabled.'), form: reasonForm('Disable the account', 'Disable'), apply: (r, v, ctx) => { r.fields.enabled = false; r.fields.status = 'DISABLED'; log(r, ctx, `Disabled — ${str(v.reason)}`); } },
      { key: 'enable', label: 'Enable account', tone: 'neutral', permission: 'access:admin', when: (r) => (r.fields.enabled ? 'Already enabled.' : null), confirm: 'Re-enable this account?', apply: (r, _v, ctx) => { r.fields.enabled = true; r.fields.status = 'ACTIVE'; log(r, ctx, 'Enabled'); } }
    ],
    create: {
      permission: 'access:admin',
      form: form('New staff account', 'Create account', [
        field('firstName', 'First name', 'text', true, { maxLength: 80 }),
        field('lastName', 'Last name', 'text', true, { maxLength: 80 }),
        field('email', 'Work email', 'email', true, { pattern: '^[^@\\s]+@afrotransact\\.com$', help: 'Staff accounts use an @afrotransact.com address' }),
        field('phone', 'Mobile', 'text', true, { pattern: '^\\+[1-9][0-9]{7,14}$', placeholder: '+18325550100' }),
        field('password', 'Temporary password', 'password', true, { maxLength: 128 }),
        field('roles', 'Roles', 'multiselect', true, { options: options('platform.customer_support', 'platform.finance', 'platform.trust_safety', 'platform.marketing', 'platform.auditor') })
      ]),
      build: (v, id, ctx) => ({
        id,
        fields: { name: `${str(v.firstName)} ${str(v.lastName)}`, email: str(v.email), principalType: 'STAFF', roles: (Array.isArray(v.roles) ? v.roles : []).join(', '), enabled: true, status: 'ACTIVE', emailVerified: false, driverAppAccess: false },
        activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: 'Staff account provisioned' }]
      })
    }
  },
  roles: {
    title: 'Roles',
    columns: [col('name', 'Role', 'strong', 26), col('key', 'Key', 'mono', 28), col('description', 'What it can do', 'text', 46)],
    facets: [],
    exportable: false,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => str(r.fields.key),
    actions: [
      {
        key: 'add-permission', label: 'Add permission', tone: 'neutral', permission: 'access:admin', when: () => null,
        form: () => form('Add a permission to the role', 'Add', [field('permissionKey', 'Permission', 'text', true, { pattern: '^[a-z]+:[a-z.:-]+$' }), field('reason', 'Reason', 'text', true)]),
        apply: (r, v, ctx) => log(r, ctx, `Added ${str(v.permissionKey)} — ${str(v.reason)}`)
      }
    ]
  },
  applications: {
    title: 'Applications',
    columns: [col('reference', 'Reference', 'mono', 13), col('displayName', 'Business', 'strong', 25), col('kind', 'Kind', 'text', 10), col('state', 'State', 'text', 8), col('planCode', 'Plan', 'text', 10), col('submittedAt', 'Submitted', 'date', 14), col('status', 'Status', 'status', 20)],
    statusKey: 'status',
    facets: facets('PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED', 'APPROVED', 'REJECTED'),
    exportable: true,
    titleOf: (r) => str(r.fields.displayName),
    subtitleOf: (r) => `${str(r.fields.reference)} · ${humanize(str(r.fields.kind))}`,
    sections: [
      ['Business', ['displayName', 'legalName', 'kind', 'entityType', 'state', 'planCode', 'contactEmail']],
      ['Review', ['status', 'reviewStep', 'reviewerId', 'failedChecks', 'taxIdMasked', 'submittedAt']]
    ],
    actions: [
      { key: 'review-approve', label: 'Approve (step 1)', tone: 'primary', permission: 'applications:review', level: 'APPROVE', form: noteForm('Approve for final review', 'Approve', false), when: (r) => stateReason(r, 'PENDING_REVIEW'), already: decidedIn('APPROVED', 'REJECTED'), apply: (r, v, ctx) => { r.fields.status = 'AWAITING_FINAL_APPROVAL'; r.fields.reviewerId = ctx.actorId; r.fields.reviewStep = 2; log(r, ctx, `Step 1 approved${v.note ? ` — ${str(v.note)}` : ''}`); } },
      { key: 'review-flag', label: 'Flag for senior review', tone: 'neutral', permission: 'applications:review', level: 'APPROVE', form: () => form('Flag for senior review', 'Flag', [field('failedChecks', 'Failed checks', 'multiselect', true, { options: options('TIN_MISMATCH', 'DOCUMENT_UNREADABLE', 'PROHIBITED_CATEGORY', 'ADDRESS_UNVERIFIED') }), field('note', 'Note', 'textarea', false)]), when: (r) => stateReason(r, 'PENDING_REVIEW'), already: decidedIn('APPROVED', 'REJECTED'), apply: (r, v, ctx) => { r.fields.status = 'FLAGGED'; r.fields.reviewerId = ctx.actorId; r.fields.failedChecks = (Array.isArray(v.failedChecks) ? v.failedChecks : []).join(', '); log(r, ctx, 'Flagged'); } },
      { key: 'review-request-info', label: 'Return to applicant', tone: 'neutral', permission: 'applications:review', level: 'APPROVE', form: () => form('Return to the applicant', 'Return to applicant', [field('requestedItems', 'What is missing', 'textarea', true, { help: 'One item per line' }), field('note', 'Message to the applicant', 'textarea', true)]), when: (r) => stateReason(r, 'PENDING_REVIEW'), already: decidedIn('APPROVED', 'REJECTED'), apply: setStatus('INFO_REQUESTED', 'Returned to the applicant') },
      { key: 'final-request-info', label: 'Return to applicant', tone: 'neutral', permission: 'applications:approve', level: 'APPROVE', form: () => form('Return to the applicant', 'Return to applicant', [field('requestedItems', 'What is missing', 'textarea', true, { help: 'One item per line' }), field('note', 'Message to the applicant', 'textarea', true)]), when: finalStep, already: decidedIn('APPROVED', 'REJECTED'), apply: setStatus('INFO_REQUESTED', 'Returned to the applicant') },
      { key: 'final-approve', label: 'Final approval', tone: 'primary', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Give final approval', 'Approve', false), when: finalStep, already: decidedIn('APPROVED', 'REJECTED'), apply: setStatus('APPROVED', 'Final approval — organisation approved') },
      { key: 'send-back', label: 'Send back', tone: 'neutral', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Send back to the reviewer', 'Send back'), when: finalStep, already: decidedIn('APPROVED', 'REJECTED'), apply: setStatus('PENDING_REVIEW', 'Sent back to step 1') },
      { key: 'reject', label: 'Reject', tone: 'danger', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Reject the application', 'Reject'), when: finalStep, already: decidedIn('APPROVED', 'REJECTED'), apply: setStatus('REJECTED', 'Rejected') }
    ]
  },
  orders: {
    title: 'Orders',
    columns: [col('orderNumber', 'Order', 'mono', 13), col('customer', 'Customer', 'strong', 18), col('seller', 'Seller', 'text', 18), col('totalMinor', 'Total', 'money', 11), col('placedAt', 'Placed', 'datetime', 16), col('shipmentStatus', 'Shipment', 'status', 14), col('status', 'Order', 'status', 10)],
    statusKey: 'status',
    facets: facets('OPEN', 'COMPLETED', 'CANCELLED'),
    search: ['orderNumber'],
    searchPlaceholder: 'Order number (AT-…)',
    cursor: true,
    exportable: true,
    titleOf: (r) => `Order ${r.id}`,
    subtitleOf: (r) => `${str(r.fields.customer)} · ${str(r.fields.shipTo)}`,
    sections: [
      ['Order', ['orderNumber', 'status', 'placedAt', 'customer', 'shipTo', 'department']],
      ['Money', ['itemsMinor', 'shippingMinor', 'taxMinor', 'totalMinor', 'currency']]
    ],
    linesTitle: 'Shipments',
    lineColumns: [col('label', 'Shipment', 'strong', 18), col('status', 'Status', 'status', 18), col('carrier', 'Carrier', 'text', 14), col('trackingNumber', 'Tracking', 'mono', 30), col('totalMinor', 'Total', 'money', 20)],
    links: (r) => [{ label: 'Open in customer support', href: `/support?customer=${encodeURIComponent(str(r.fields.customerId))}` }],
    actions: [
      {
        key: 'shipment-status', label: 'Update a shipment', tone: 'primary', permission: 'orders:manage', when: (r) => stateReason(r, 'OPEN'),
        form: (r) => {
          const lines = r.lines ?? [];
          const verbs: [string, string][] = [['OUT_FOR_DELIVERY', 'Mark out for delivery'], ['DELIVERED', 'Mark delivered'], ['DELIVERY_FAILED', 'Record a failed delivery'], ['RETURNED', 'Mark returned'], ['CANCELLED', 'Cancel the shipment']];
          return form('What happened to the shipment?', 'Save', [
            field('step', 'Next step', 'select', true, {
              options: lines.flatMap((l) => verbs.map(([to, verb]) => ({ value: `${str(l.id)}|${to}`, label: lines.length > 1 ? `${str(l.label)} · ${verb}` : verb })))
            }),
            field('reason', 'Reason or note', 'text', false, { maxLength: 500 })
          ]);
        },
        apply: (r, v, ctx) => {
          const [shipmentId, to = ''] = str(v.step).split('|');
          const line = (r.lines ?? []).find((l) => l.id === shipmentId);
          if (line) line.status = to;
          r.fields.shipmentStatus = to;
          if (to === 'DELIVERED') r.fields.status = 'COMPLETED';
          if (to === 'CANCELLED') r.fields.status = 'CANCELLED';
          log(r, ctx, `Shipment moved to ${humanize(to).toLowerCase()}`);
        }
      }
    ]
  },
  products: {
    title: 'Products',
    columns: [col('atin', 'ATIN', 'mono', 15), col('title', 'Product', 'strong', 30), col('seller', 'Seller', 'text', 20), col('productType', 'Type', 'text', 15), col('status', 'Status', 'status', 20)],
    statusKey: 'status',
    facets: facets('IN_REVIEW', 'LIVE', 'PAUSED', 'REJECTED', 'TAKEN_DOWN'),
    search: ['title', 'atin'],
    searchPlaceholder: 'Title or ATIN',
    exportable: true,
    titleOf: (r) => str(r.fields.title),
    subtitleOf: (r) => `${str(r.fields.atin)} · ${str(r.fields.seller)}`,
    actions: [
      { key: 'approve', label: 'Approve', tone: 'primary', permission: 'catalog:moderate', confirm: 'Approve this product and make it live?', when: (r) => stateReason(r, 'IN_REVIEW'), apply: setStatus('LIVE', 'Approved') },
      { key: 'reject', label: 'Reject', tone: 'danger', permission: 'catalog:moderate', form: noteForm('Reject the product', 'Reject'), when: (r) => stateReason(r, 'IN_REVIEW'), apply: (r, v, ctx) => { r.fields.status = 'REJECTED'; r.fields.moderationNote = str(v.note); log(r, ctx, 'Rejected'); } },
      { key: 'takedown', label: 'Take down', tone: 'danger', permission: 'catalog:moderate', form: () => form('Take the listing down', 'Take down', [field('reasonCode', 'Reason', 'select', true, { options: REASON_CODES }), field('note', 'Note', 'textarea', false)]), when: (r) => stateReason(r, 'LIVE', 'PAUSED'), apply: setStatus('TAKEN_DOWN', 'Taken down') },
      { key: 'restore', label: 'Restore', tone: 'neutral', permission: 'catalog:moderate', form: noteForm('Restore the listing', 'Restore', false), when: (r) => stateReason(r, 'TAKEN_DOWN'), apply: setStatus('LIVE', 'Restored') }
    ]
  },
  moderation: {
    title: 'Moderation queue',
    columns: [col('title', 'Listing', 'strong', 28), col('seller', 'Seller', 'text', 18), col('type', 'Type', 'text', 14), col('aiFlag', 'AI flag', 'text', 18), col('dueAt', 'Due', 'datetime', 12), col('status', 'Status', 'status', 10)],
    statusKey: 'status',
    facets: facets('PENDING', 'ESCALATED', 'APPROVED', 'REJECTED'),
    exportable: true,
    titleOf: (r) => str(r.fields.title),
    subtitleOf: (r) => `${humanize(str(r.fields.type))} · ${str(r.fields.seller)}`,
    actions: [
      { key: 'assign', label: 'Assign to me', tone: 'neutral', permission: 'catalog:moderate', confirm: 'Take this item?', when: (r) => stateReason(r, 'PENDING', 'ESCALATED'), apply: (r, _v, ctx) => { r.fields.assignee = ctx.actor; log(r, ctx, 'Assigned to me'); } },
      { key: 'approve', label: 'Allow', tone: 'primary', permission: 'catalog:moderate', level: 'APPROVE', form: noteForm('Allow the listing', 'Allow', false), when: (r) => stateReason(r, 'PENDING', 'ESCALATED'), apply: setStatus('APPROVED', 'Allowed') },
      { key: 'reject', label: 'Remove', tone: 'danger', permission: 'catalog:moderate', level: 'APPROVE', form: () => form('Remove the listing', 'Remove', [field('reasonCode', 'Reason', 'select', true, { options: REASON_CODES }), field('note', 'Note', 'textarea', false)]), when: (r) => stateReason(r, 'PENDING', 'ESCALATED'), apply: setStatus('REJECTED', 'Removed') },
      { key: 'escalate', label: 'Escalate', tone: 'neutral', permission: 'catalog:moderate', form: reasonForm('Escalate to senior review', 'Escalate'), when: (r) => stateReason(r, 'PENDING'), apply: setStatus('ESCALATED', 'Escalated') }
    ]
  },
  claims: {
    title: 'A-to-z claims',
    columns: [col('reference', 'Claim', 'mono', 13), col('type', 'Type', 'text', 18), col('orderNumber', 'Order', 'mono', 13), col('seller', 'Seller', 'text', 18), col('amountMinor', 'Amount', 'money', 11), col('openedAt', 'Opened', 'date', 12), col('status', 'Status', 'status', 15)],
    statusKey: 'status',
    facets: facets('AWAITING_SELLER', 'UNDER_REVIEW', 'ESCALATED', 'RESOLVED_REFUND', 'RESOLVED_DENIED'),
    exportable: true,
    titleOf: (r) => `Claim ${str(r.fields.reference)}`,
    subtitleOf: (r) => `${humanize(str(r.fields.type))} · order ${str(r.fields.orderNumber)}`,
    links: (r) => [{ label: `Order ${str(r.fields.orderNumber)}`, href: `/orders?open=${encodeURIComponent(str(r.fields.orderNumber))}` }, { label: 'Customer 360', href: `/support?customer=${encodeURIComponent(str(r.fields.customerId))}` }],
    actions: [
      {
        key: 'refund', label: 'Refund', tone: 'primary', permission: 'disputes:manage', form: noteForm('Decide with a refund', 'Refund'),
        when: (r, ctx) => stateReason(r, 'UNDER_REVIEW', 'ESCALATED', 'AWAITING_SELLER') ?? (ctx.refundLimit !== null && num(r.fields.amountMinor) > ctx.refundLimit ? `Above your refund limit ($${(ctx.refundLimit / 100).toFixed(2)}).` : null),
        apply: (r, v, ctx) => { r.fields.status = 'RESOLVED_REFUND'; r.fields.refundMinor = num(r.fields.amountMinor); log(r, ctx, `Refunded — ${str(v.note)}`); }
      },
      { key: 'deny', label: 'Deny', tone: 'danger', permission: 'disputes:manage', form: noteForm('Deny the claim', 'Deny'), when: (r) => stateReason(r, 'UNDER_REVIEW', 'ESCALATED', 'AWAITING_SELLER'), apply: setStatus('RESOLVED_DENIED', 'Denied') },
      { key: 'escalate', label: 'Escalate', tone: 'neutral', permission: 'disputes:manage', form: reasonForm('Escalate the claim', 'Escalate'), when: (r) => stateReason(r, 'UNDER_REVIEW', 'AWAITING_SELLER'), apply: setStatus('ESCALATED', 'Escalated') }
    ]
  },
  returns: {
    title: 'Returns',
    columns: [col('number', 'RMA', 'mono', 18), col('orderNumber', 'Order', 'mono', 14), col('reason', 'Reason', 'text', 18), col('method', 'Method', 'text', 14), col('estimateMinor', 'Refund est.', 'money', 14), col('status', 'Status', 'status', 12), col('createdAt', 'Opened', 'date', 10)],
    statusKey: 'status',
    facets: facets('REQUESTED', 'APPROVED', 'IN_TRANSIT', 'RECEIVED', 'REFUNDED', 'REJECTED'),
    cursor: true,
    exportable: true,
    titleOf: (r) => `Return ${str(r.fields.number)}`,
    subtitleOf: (r) => `Order ${str(r.fields.orderNumber)}`,
    links: (r) => [{ label: 'Customer 360', href: `/support?customer=${encodeURIComponent(str(r.fields.customerId))}` }],
    actions: [
      { key: 'approve', label: 'Approve', tone: 'primary', permission: 'returns:manage', form: noteForm('Approve the return', 'Approve', false), when: (r) => stateReason(r, 'REQUESTED'), apply: setStatus('APPROVED', 'Approved') },
      { key: 'reject', label: 'Reject', tone: 'danger', permission: 'returns:manage', form: noteForm('Reject the return', 'Reject'), when: (r) => stateReason(r, 'REQUESTED'), apply: setStatus('REJECTED', 'Rejected') },
      { key: 'receive', label: 'Mark received', tone: 'neutral', permission: 'returns:manage', confirm: 'Mark the parcel as received?', when: (r) => stateReason(r, 'APPROVED', 'IN_TRANSIT'), apply: setStatus('RECEIVED', 'Parcel received') }
    ]
  },
  payouts: {
    title: 'Payout requests',
    columns: [col('id', 'Request', 'mono', 14), col('org', 'Organisation', 'strong', 26), col('amountMinor', 'Amount', 'money', 14), col('requestedAt', 'Requested', 'date', 14), col('holdReason', 'Hold reason', 'text', 18), col('status', 'Status', 'status', 14)],
    statusKey: 'status',
    facets: facets('REQUESTED', 'ON_HOLD', 'APPROVED', 'PAID', 'FAILED'),
    cursor: true,
    exportable: true,
    titleOf: (r) => `Payout to ${str(r.fields.org)}`,
    subtitleOf: (r) => r.id,
    actions: [
      { key: 'approve', label: 'Approve & pay', tone: 'primary', permission: 'payouts:approve', level: 'APPROVE', confirm: 'Create the Stripe payout now?', when: (r) => stateReason(r, 'REQUESTED'), apply: setStatus('APPROVED', 'Approved — Stripe payout created') },
      { key: 'hold', label: 'Hold', tone: 'danger', permission: 'payouts:approve', level: 'APPROVE', form: reasonForm('Put the payout on hold', 'Hold'), when: (r) => stateReason(r, 'REQUESTED'), apply: (r, v, ctx) => { r.fields.status = 'ON_HOLD'; r.fields.holdReason = str(v.reason); log(r, ctx, `Held — ${str(v.reason)}`); } },
      { key: 'release', label: 'Release', tone: 'neutral', permission: 'payouts:approve', level: 'APPROVE', confirm: 'Release the hold?', when: (r) => stateReason(r, 'ON_HOLD'), apply: (r, _v, ctx) => { r.fields.status = 'REQUESTED'; r.fields.holdReason = null; log(r, ctx, 'Released'); } }
    ]
  },
  inventory: {
    title: 'Lots',
    columns: [col('sku', 'SKU', 'mono', 14), col('lotNo', 'Lot', 'mono', 16), col('node', 'Centre', 'text', 13), col('qtyOnHand', 'On hand', 'number', 10), col('qtyReserved', 'Reserved', 'number', 10), col('qtyAvailable', 'Available', 'number', 10), col('expDate', 'Expires', 'date', 13), col('status', 'Status', 'status', 14)],
    statusKey: 'status',
    facets: facets('SELLABLE', 'QUARANTINED', 'DAMAGED', 'EXPIRED'),
    search: ['sku', 'lotNo'],
    searchPlaceholder: 'SKU or lot number',
    exportable: true,
    titleOf: (r) => `${str(r.fields.sku)} · ${str(r.fields.lotNo)}`,
    subtitleOf: (r) => `${str(r.fields.node)} · bin ${str(r.fields.bin)}`,
    actions: [
      {
        key: 'cycle-count', label: 'Cycle count', tone: 'primary', permission: 'inventory:adjust', when: () => null,
        form: (r) => form('Post a counted quantity', 'Post count', [field('countedQty', 'Counted quantity', 'number', true, { min: 0, max: 100_000, value: num(r.fields.qtyOnHand) }), field('reason', 'Reason', 'text', true, { maxLength: 200 })]),
        apply: (r, v, ctx) => { r.fields.qtyOnHand = num(v.countedQty); r.fields.qtyAvailable = num(v.countedQty) - num(r.fields.qtyReserved); log(r, ctx, `Counted ${num(v.countedQty)} — ${str(v.reason)}`); }
      },
      { key: 'quarantine', label: 'Quarantine', tone: 'danger', permission: 'inventory:adjust', form: reasonForm('Quarantine the lot', 'Quarantine'), when: (r) => stateReason(r, 'SELLABLE'), apply: setStatus('QUARANTINED', 'Quarantined') },
      { key: 'release', label: 'Release to sale', tone: 'neutral', permission: 'inventory:adjust', form: reasonForm('Release after inspection', 'Release'), when: (r) => stateReason(r, 'QUARANTINED', 'DAMAGED'), apply: setStatus('SELLABLE', 'Released to sale') }
    ]
  },
  'purchase-orders': {
    title: 'Purchase orders',
    columns: [col('number', 'PO', 'mono', 12), col('supplier', 'Supplier', 'strong', 24), col('destination', 'Ship to', 'text', 14), col('expectedDate', 'Expected', 'date', 13), col('totalMinor', 'Total', 'money', 14), col('status', 'Status', 'status', 23)],
    statusKey: 'status',
    facets: facets('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'CONFIRMED', 'RECEIVED', 'CANCELLED'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.number)} · ${str(r.fields.supplier)}`,
    subtitleOf: (r) => `${str(r.fields.incoterm)} → ${str(r.fields.destination)}`,
    linesTitle: 'Lines',
    lineColumns: [col('sku', 'SKU', 'mono', 20), col('title', 'Item', 'strong', 40), col('quantity', 'Qty', 'number', 15), col('unitCostMinor', 'Unit cost', 'money', 25)],
    actions: [
      { key: 'submit', label: 'Submit for approval', tone: 'primary', permission: 'retail:po:write', confirm: 'Submit this PO for approval?', when: (r) => stateReason(r, 'DRAFT'), apply: setStatus('PENDING_APPROVAL', 'Submitted for approval') },
      { key: 'approve', label: 'Approve', tone: 'primary', permission: 'retail:po:approve', level: 'APPROVE', form: noteForm('Approve the PO', 'Approve', false), when: (r) => stateReason(r, 'PENDING_APPROVAL'), apply: setStatus('APPROVED', 'Approved') },
      { key: 'send-back', label: 'Send back', tone: 'neutral', permission: 'retail:po:approve', level: 'APPROVE', form: noteForm('Send back to the buyer', 'Send back'), when: (r) => stateReason(r, 'PENDING_APPROVAL'), apply: setStatus('DRAFT', 'Sent back to the buyer') },
      { key: 'cancel', label: 'Cancel PO', tone: 'danger', permission: 'retail:po:write', form: noteForm('Cancel the PO', 'Cancel PO'), when: (r) => stateReason(r, 'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT'), apply: setStatus('CANCELLED', 'Cancelled') }
    ]
  },
  routes: {
    title: 'Routes',
    columns: [col('name', 'Route', 'mono', 16), col('zone', 'Zone', 'text', 14), col('window', 'Window', 'text', 12), col('driver', 'Driver', 'strong', 18), col('stops', 'Stops', 'number', 8), col('serviceDate', 'Date', 'date', 12), col('status', 'Status', 'status', 20)],
    statusKey: 'status',
    facets: facets('PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'),
    cursor: true,
    exportable: true,
    titleOf: (r) => `Route ${str(r.fields.name)}`,
    subtitleOf: (r) => `${str(r.fields.zone)} · ${str(r.fields.window)}`,
    linesTitle: 'Stops',
    lineColumns: [col('seq', '#', 'number', 8), col('address', 'Address', 'text', 52), col('packages', 'Packages', 'number', 15), col('status', 'Status', 'status', 25)],
    actions: [
      {
        key: 'assign', label: 'Assign driver', tone: 'primary', permission: 'delivery:dispatch', when: (r) => stateReason(r, 'PLANNED', 'ASSIGNED'),
        form: () => form('Assign a driver', 'Assign', [field('driverId', 'Driver', 'select', true, { options: [{ value: 'drv-1', label: 'Tunde Adeyemi' }, { value: 'drv-2', label: 'Chinedu Okafor' }] }), field('vehicleId', 'Vehicle', 'text', false, { maxLength: 40 })]),
        apply: (r, v, ctx) => { r.fields.driver = v.driverId === 'drv-1' ? 'Tunde Adeyemi' : 'Chinedu Okafor'; r.fields.vehicle = str(v.vehicleId) || null; r.fields.status = 'ASSIGNED'; log(r, ctx, `Assigned ${str(r.fields.driver)}`); }
      },
      { key: 'cancel', label: 'Cancel route', tone: 'danger', permission: 'delivery:dispatch', form: reasonForm('Cancel the route', 'Cancel route'), when: (r) => stateReason(r, 'PLANNED', 'ASSIGNED'), apply: setStatus('CANCELLED', 'Cancelled — shipments back to the pool') }
    ],
    /* The route builder (fulfilment POST /v1/dispatch/routes/build): the depot's waiting shipments planned into routes per zone. */
    create: {
      permission: 'delivery:dispatch',
      form: form('Build routes from the waiting shipments', 'Build routes', [
        field('depotNodeId', 'Depot (fulfilment centre)', 'text', true, { maxLength: 40, value: 'HOU-FC' }),
        field('serviceDate', 'Service date', 'date', true),
        field('maxDropoffs', 'Drop-offs per route', 'number', false, { min: 0, max: 200, value: 25 }),
        field('window', 'Delivery window', 'text', false, { maxLength: 60 })
      ]),
      build: (v, id, ctx) => ({
        id,
        fields: { name: `${str(v.depotNodeId).slice(0, 3)}-NEW-${str(v.serviceDate).slice(5)}`, zone: 'Alief', serviceDate: str(v.serviceDate), window: str(v.window) || '9am–1pm', driver: null, vehicle: null, status: 'PLANNED', stops: 0, perishable: false },
        activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: 'Route built from the waiting shipments' }],
        lines: []
      })
    }
  },
  'dispatch-shipments': {
    title: 'Waiting shipments',
    columns: [col('orderNumber', 'Order', 'mono', 14), col('sellerName', 'Seller', 'strong', 16), col('origin', 'Origin', 'text', 10), col('place', 'Deliver to', 'text', 18), col('units', 'Units', 'number', 7), col('perishable', 'Perishable', 'text', 9), col('promisedBy', 'Promised by', 'date', 11), col('status', 'Status', 'status', 15)],
    statusKey: 'status',
    facets: [],
    search: ['orderNumber', 'sellerName', 'place'],
    cursor: true,
    exportable: true,
    titleOf: (r) => `Shipment ${str(r.fields.orderNumber)}`,
    subtitleOf: (r) => `${str(r.fields.sellerName)} · ${str(r.fields.place)}`,
    actions: []
  },
  'sos-alerts': {
    title: 'SOS alerts',
    columns: [col('kind', 'Emergency', 'strong', 14), col('driverId', 'Driver', 'mono', 16), col('routeId', 'Route', 'mono', 16), col('where', 'Where (GPS)', 'mono', 18), col('note', 'Note', 'text', 22), col('raisedAt', 'Raised', 'datetime', 14), col('status', 'Status', 'status', 10)],
    statusKey: 'status',
    facets: [],
    cursor: true,
    exportable: true,
    titleOf: (r) => `SOS: ${str(r.fields.kind)}`,
    subtitleOf: (r) => `Driver ${str(r.fields.driverId)} · route ${str(r.fields.routeId)}`,
    actions: [
      { key: 'acknowledge', label: 'Acknowledge', tone: 'primary', permission: 'delivery:dispatch', confirm: 'Acknowledge this SOS? The driver sees that dispatch is on it.', when: (r) => stateReason(r, 'ALERT'), apply: setStatus('ACKNOWLEDGED', 'SOS acknowledged') }
    ]
  },
  'vehicle-defects': {
    title: 'Vehicle defects',
    columns: [col('vehicleId', 'Vehicle', 'mono', 12), col('driverId', 'Driver', 'mono', 14), col('routeId', 'Route', 'mono', 14), col('failed', 'Failed checks', 'text', 20), col('note', 'Note', 'text', 20), col('reportedAt', 'Reported', 'datetime', 12), col('status', 'Status', 'status', 10)],
    statusKey: 'status',
    facets: [],
    cursor: true,
    exportable: true,
    titleOf: (r) => `Defect on ${str(r.fields.vehicleId)}`,
    subtitleOf: (r) => `Route ${str(r.fields.routeId)} · driver ${str(r.fields.driverId)}`,
    actions: [
      { key: 'resolve', label: 'Resolve and unblock the route', tone: 'primary', permission: 'delivery:dispatch', form: noteForm('Resolve the defect (the driver re-inspects before starting)', 'Resolve', false), when: (r) => stateReason(r, 'OPEN', 'BLOCKED'), apply: setStatus('RESOLVED', 'Defect resolved, route unblocked') }
    ]
  },
  'driver-events': {
    title: 'Driver events',
    columns: [col('capturedAt', 'When', 'datetime', 14), col('type', 'Event', 'strong', 18), col('driverId', 'Driver', 'mono', 14), col('routeId', 'Route', 'mono', 14), col('stopId', 'Stop', 'mono', 12), col('message', 'Detail', 'text', 18), col('outcome', 'Outcome', 'status', 10)],
    statusKey: 'outcome',
    facets: facets('APPLIED', 'DUPLICATE', 'REJECTED'),
    search: ['type', 'driverId', 'routeId'],
    cursor: true,
    exportable: true,
    titleOf: (r) => str(r.fields.type),
    subtitleOf: (r) => `Driver ${str(r.fields.driverId)} · ${str(r.fields.capturedAt)}`,
    actions: []
  },
  'support-cases': {
    title: 'Support cases',
    columns: [col('reference', 'Case', 'mono', 12), col('customer', 'Customer', 'strong', 18), col('reason', 'Reason', 'text', 22), col('assignee', 'Assignee', 'text', 16), col('openedAt', 'Opened', 'datetime', 16), col('status', 'Status', 'status', 16)],
    statusKey: 'status',
    facets: facets('OPEN', 'IN_PROGRESS', 'RESOLVED'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.reference)} · ${str(r.fields.reason)}`,
    subtitleOf: (r) => str(r.fields.customer),
    sections: [['Case', ['reference', 'source', 'status', 'assignee', 'slaState', 'openedAt']], ['Lola hand-off', ['customer', 'orderNumber', 'reason', 'summary']]],
    links: (r) => [
      { label: 'Open customer 360', href: `/support?customer=${encodeURIComponent(str(r.fields.customerId))}&case=${encodeURIComponent(r.id)}` },
      ...(r.fields.orderNumber ? [{ label: `Order ${str(r.fields.orderNumber)}`, href: `/orders?open=${encodeURIComponent(str(r.fields.orderNumber))}` }] : [])
    ],
    actions: [
      { key: 'assign', label: 'Assign to me', tone: 'neutral', permission: 'support:respond', confirm: 'Take this case?', when: (r) => stateReason(r, 'OPEN', 'IN_PROGRESS'), apply: (r, _v, ctx) => { r.fields.assignee = ctx.actor; r.fields.status = 'IN_PROGRESS'; log(r, ctx, 'Assigned'); } },
      { key: 'resolve', label: 'Resolve', tone: 'primary', permission: 'support:respond', form: () => form('Resolve the case', 'Resolve', [field('resolution', 'Resolution', 'textarea', true, { maxLength: 2000 })]), when: (r) => stateReason(r, 'OPEN', 'IN_PROGRESS'), apply: setStatus('RESOLVED', 'Resolved') }
    ]
  },
  audit: {
    title: 'Audit log',
    columns: [col('seq', '#', 'number', 8), col('occurredAt', 'When', 'datetime', 16), col('actor', 'Actor', 'strong', 16), col('action', 'Action', 'mono', 18), col('entityType', 'Entity', 'text', 12), col('summary', 'Summary', 'text', 30)],
    facets: [],
    exportable: true,
    titleOf: (r) => `${str(r.fields.action)} #${str(r.fields.seq)}`,
    subtitleOf: (r) => `${str(r.fields.actor)} · ${str(r.fields.entityType)} ${str(r.fields.entityId)}`,
    actions: []
  },
  markets: {
    title: 'Countries',
    columns: [col('code', 'Code', 'mono', 10), col('name', 'Country', 'strong', 30), col('liveRegions', 'Live states', 'number', 15), col('regionCount', 'States', 'number', 12), col('status', 'Status', 'status', 33)],
    statusKey: 'status',
    facets: facets('LIVE', 'COMING_SOON', 'PLANNED', 'PAUSED'),
    // Backoffice Countries filter (mktCq / mktSf): name or exact code, status chips; Live first, then Coming soon, Paused, Planned.
    search: ['name', 'code'],
    searchPlaceholder: 'Filter countries',
    sort: (a, b) => (MARKET_ORDER[str(a.fields.status)] ?? 9) - (MARKET_ORDER[str(b.fields.status)] ?? 9) || str(a.fields.name).localeCompare(str(b.fields.name)),
    exportable: false,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => `${str(r.fields.liveRegions)} of ${str(r.fields.regionCount)} states live`,
    actions: [{ key: 'publish', label: 'Publish markets', tone: 'primary', permission: 'content:markets.edit', form: noteForm('Publish the draft as a new version', 'Publish', false), when: () => null, apply: (r, _v, ctx) => log(r, ctx, 'Published a new markets version') }],
    edit: {
      permission: 'content:markets.edit',
      form: (r) => form('Edit country', 'Save draft', [field('status', 'Status', 'select', true, { options: options('LIVE', 'PAUSED', 'COMING_SOON', 'PLANNED'), value: str(r.fields.status) }), field('launchNote', 'Launch note', 'text', false, { maxLength: 200, value: str(r.fields.launchNote) })]),
      apply: (r, v) => { r.fields.status = str(v.status); r.fields.launchNote = str(v.launchNote) || null; }
    }
  },
  'site-settings': {
    title: 'Site settings',
    columns: [col('supportPhoneDisplay', 'Support phone', 'text', 22), col('supportEmail', 'Support email', 'text', 28), col('smsProgramName', 'SMS program', 'text', 20), col('revision', 'Revision', 'number', 12), col('updatedAt', 'Updated', 'datetime', 18)],
    facets: [],
    exportable: false,
    titleOf: () => 'Contacts & social links',
    subtitleOf: (r) => `Revision ${str(r.fields.revision)}`,
    sections: [['Contacts', ['supportPhone', 'supportPhoneDisplay', 'supportEmail', 'smsProgramName']], ['Social links', ['instagram', 'facebook', 'tiktok', 'whatsappCommunityUrl']], ['Revision', ['revision', 'updatedBy', 'updatedAt']]],
    actions: [],
    edit: {
      permission: 'content:site.edit',
      form: (r) => form('Edit contacts & social links', 'Publish', [
        field('supportPhone', 'Support phone (E.164)', 'text', true, { pattern: '^\\+[1-9][0-9]{7,14}$', value: str(r.fields.supportPhone) }),
        field('supportPhoneDisplay', 'Phone as shown', 'text', true, { maxLength: 30, value: str(r.fields.supportPhoneDisplay) }),
        field('supportEmail', 'Support email', 'email', true, { value: str(r.fields.supportEmail) }),
        field('smsProgramName', 'SMS program name', 'text', true, { maxLength: 60, value: str(r.fields.smsProgramName) }),
        field('whatsappCommunityUrl', 'WhatsApp community link (email footers)', 'text', false, { pattern: '^https://(chat\\.whatsapp\\.com/|(www\\.)?whatsapp\\.com/channel/)', value: str(r.fields.whatsappCommunityUrl) }),
        field('instagram', 'Instagram', 'text', false, { pattern: '^https://', value: str(r.fields.instagram) }),
        field('facebook', 'Facebook', 'text', false, { pattern: '^https://', value: str(r.fields.facebook) }),
        field('tiktok', 'TikTok', 'text', false, { pattern: '^https://', value: str(r.fields.tiktok) })
      ]),
      apply: (r, v) => {
        Object.entries(v).forEach(([k, val]) => { r.fields[k] = typeof val === 'string' ? val : r.fields[k] ?? null; });
        r.fields.revision = num(r.fields.revision) + 1;
      }
    }
  },
  'legal-documents': {
    title: 'Legal documents',
    columns: [col('title', 'Document', 'strong', 36), col('key', 'Key', 'mono', 20), col('version', 'Version', 'text', 14), col('effectiveDate', 'Effective', 'date', 30)],
    facets: [],
    exportable: false,
    titleOf: (r) => str(r.fields.title),
    subtitleOf: (r) => `Version ${str(r.fields.version)}`,
    actions: [
      {
        key: 'raise-version', label: 'New version', tone: 'primary', permission: 'content:legal.edit', when: () => null,
        form: () => form('Raise the version (the current one is archived)', 'Publish version', [field('version', 'Version', 'text', true, { pattern: '^[0-9]+\\.[0-9]+$', placeholder: '3.3' }), field('effectiveDate', 'Effective date', 'date', true), field('changeSummary', 'What changed', 'textarea', true, { maxLength: 1000 }), field('body', 'Document (Markdown)', 'textarea', true)]),
        apply: (r, v, ctx) => { r.fields.version = str(v.version); r.fields.effectiveDate = str(v.effectiveDate); log(r, ctx, `Version ${str(v.version)} published — ${str(v.changeSummary)}`); }
      }
    ],
    edit: {
      permission: 'content:legal.edit',
      form: (r) => form('Amend the current version', 'Save', [field('effectiveDate', 'Effective date', 'date', true, { value: str(r.fields.effectiveDate) }), field('draftBanner', 'Show the draft banner', 'toggle', false, { value: r.fields.draftBanner === true })]),
      apply: (r, v) => { r.fields.effectiveDate = str(v.effectiveDate); r.fields.draftBanner = v.draftBanner === true; }
    }
  },
  'help-articles': {
    title: 'Help center',
    columns: [col('title', 'Page', 'strong', 30), col('slug', 'Address', 'mono', 22), col('topicTitle', 'Topic', 'text', 20), col('status', 'Status', 'status', 13), col('updatedAt', 'Updated', 'date', 15)],
    statusKey: 'status',
    facets: facets('PUBLISHED', 'DRAFT'),
    exportable: false,
    titleOf: (r) => str(r.fields.title),
    subtitleOf: (r) => `${str(r.fields.topicTitle)} · ${str(r.fields.href)}`,
    actions: [],
    edit: {
      permission: 'content:site.edit',
      form: (r) => form('Edit help page', 'Save', [
        field('title', 'Title', 'text', true, { maxLength: 120, value: str(r.fields.title) }),
        field('summary', 'Summary (shown in the help center list)', 'text', true, { maxLength: 300, value: str(r.fields.summary) }),
        field('status', 'Status', 'select', true, { options: [{ value: 'PUBLISHED', label: 'Published' }, { value: 'DRAFT', label: 'Draft (staff only)' }], value: str(r.fields.status) }),
        field('sections', 'Page (Markdown: a “## Heading” line before each section)', 'textarea', true, { value: str(r.fields.sections), help: 'Paragraphs, “-” lists, **bold** and [links](/orders); email addresses become links.' })
      ]),
      apply: (r, v) => { r.fields.title = str(v.title); r.fields.summary = str(v.summary); r.fields.status = str(v.status); r.fields.sections = str(v.sections); }
    }
  },
  'privacy-requests': {
    title: 'Privacy requests',
    columns: [col('reference', 'Reference', 'mono', 14), col('type', 'Request', 'strong', 14), col('email', 'Email', 'text', 26), col('state', 'State', 'text', 8), col('dueAt', 'Due', 'date', 14), col('submittedAt', 'Received', 'datetime', 14), col('status', 'Status', 'status', 10)],
    statusKey: 'status',
    facets: facets('RECEIVED', 'COMPLETED', 'DENIED'),
    exportable: false,
    titleOf: (r) => `${str(r.fields.reference)} · ${humanize(str(r.fields.type))}`,
    subtitleOf: (r) => `${str(r.fields.email)} · ${str(r.fields.state)} · due ${str(r.fields.dueAt).slice(0, 10)}`,
    actions: [
      { key: 'complete', label: 'Mark completed', tone: 'primary', permission: 'content:legal.edit', form: noteForm('What was done (kept with the request)', 'Mark completed', false), when: (r) => stateReason(r, 'RECEIVED'), apply: (r, v, ctx) => { r.fields.status = 'COMPLETED'; r.fields.resolutionNote = str(v.note) || null; log(r, ctx, 'Completed'); } },
      { key: 'deny', label: 'Deny', tone: 'danger', permission: 'content:legal.edit', form: noteForm('Why the request is denied (the requester can appeal)', 'Deny request'), when: (r) => stateReason(r, 'RECEIVED'), apply: (r, v, ctx) => { r.fields.status = 'DENIED'; r.fields.resolutionNote = str(v.note); log(r, ctx, `Denied — ${str(v.note)}`); } }
    ]
  },
  announcements: placementsDef('Announcements', true),
  placements: placementsDef('Placements & ads', false),
  'identity-providers': {
    title: 'Identity providers',
    columns: [col('displayName', 'Provider', 'strong', 30), col('configured', 'Configured', 'text', 20), col('updatedBy', 'Changed by', 'text', 25), col('status', 'Sign-in button', 'status', 25)],
    statusKey: 'status',
    facets: [],
    exportable: false,
    titleOf: (r) => str(r.fields.displayName),
    subtitleOf: (r) => (r.fields.enabled ? 'Shown on sign-in and sign-up' : 'Hidden from sign-in and sign-up'),
    actions: [
      { key: 'enable', label: 'Show button', tone: 'primary', permission: 'access:admin', confirm: 'Show this social login on the storefront?', when: (r) => (r.fields.enabled ? 'Already shown.' : r.fields.configured ? null : 'Not configured at identity yet.'), apply: (r, _v, ctx) => { r.fields.enabled = true; r.fields.status = 'ENABLED'; log(r, ctx, 'Enabled'); } },
      { key: 'disable', label: 'Hide button', tone: 'danger', permission: 'access:admin', confirm: 'Hide this social login? People who use it must sign in another way.', when: (r) => (r.fields.enabled ? null : 'Already hidden.'), apply: (r, _v, ctx) => { r.fields.enabled = false; r.fields.status = 'DISABLED'; log(r, ctx, 'Disabled'); } }
    ]
  },
  'fee-schedules': {
    title: 'Fee schedules',
    columns: [col('version', 'Version', 'number', 9), col('effectiveFrom', 'Effective from', 'datetime', 18), col('status', 'Status', 'status', 12), col('defaultCommissionBps', 'Commission', 'percent', 12), col('cardPctBps', 'Card fee', 'percent', 11), col('fbaPerUnitMinor', 'FBA / unit', 'money', 11), col('note', 'Note', 'text', 27)],
    statusKey: 'status',
    facets: [],
    exportable: true,
    titleOf: (r) => `Fee schedule v${str(r.fields.version)}`,
    subtitleOf: (r) => str(r.fields.note),
    actions: []
  },
  'pickup-locations': {
    title: 'Pickup points',
    columns: [col('name', 'Name', 'strong', 22), col('kindLabel', 'Kind', 'text', 14), col('place', 'Address', 'text', 26), col('hoursLabel', 'Hours', 'text', 18), col('readyHours', 'Ready after (h)', 'number', 8), col('state', 'Status', 'status', 10)],
    statusKey: 'state',
    facets: facets('ACTIVE', 'PAUSED'),
    search: ['name', 'place'],
    searchPlaceholder: 'Name, city or ZIP',
    exportable: false,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => `${str(r.fields.kindLabel)} · ${str(r.fields.place)}`,
    actions: [],
    // identity grants shipping:admin (what carrier-gateway checks) to every role with shipping:manage.
    edit: {
      permission: 'shipping:manage',
      form: (r) => pickupForm('Edit the pickup point', 'Save pickup point', r),
      apply: (r, v) => applyPickup(r, v)
    },
    create: {
      permission: 'shipping:manage',
      form: pickupForm('New pickup point', 'Add pickup point', null),
      build: (v, id) => {
        const r: MockRecord = { id, fields: {}, activity: [] };
        applyPickup(r, v);
        return r;
      }
    }
  },
  'tax-codes': {
    title: 'Tax codes',
    columns: [col('code', 'Code', 'mono', 18), col('label', 'What it is', 'strong', 30), col('category', 'Rate column', 'text', 16), col('providerCode', 'Provider code', 'mono', 18), col('taxableLabel', 'Taxable', 'text', 12), col('exemptStates', 'Exempt in', 'mono', 14), col('state', 'State', 'status', 10)],
    statusKey: 'state',
    facets: facets('ACTIVE', 'INACTIVE'),
    exportable: true,
    titleOf: (r) => str(r.fields.label),
    subtitleOf: (r) => str(r.fields.code),
    actions: []
  },
  reconciliation: {
    title: 'Differences',
    columns: [col('stripeRef', 'Stripe object', 'mono', 20), col('windowStart', 'Day', 'date', 11), col('ledgerMinor', 'Ledger', 'money', 12), col('stripeMinor', 'Stripe', 'money', 12), col('differenceMinor', 'Difference', 'money', 12), col('note', 'Why', 'text', 21), col('status', 'Status', 'status', 12)],
    statusKey: 'status',
    facets: facets('OPEN', 'EXPLAINED', 'ADJUSTED', 'SUPERSEDED'),
    exportable: true,
    titleOf: (r) => `Difference on ${str(r.fields.stripeRef)}`,
    subtitleOf: (r) => str(r.fields.status),
    sections: [
      ['The difference', ['stripeRef', 'windowStart', 'ledgerMinor', 'stripeMinor', 'differenceMinor', 'meaning']],
      ['Review', ['status', 'note', 'resolvedByName', 'resolvedAt']]
    ],
    actions: [
      {
        key: 'explain', label: 'Mark as explained', tone: 'neutral', permission: 'ledger:adjust', when: (r) => stateReason(r, 'OPEN'),
        form: () => form('Why is this not an error?', 'Mark explained', [field('note', 'Explanation (at least 10 characters)', 'textarea', true, { maxLength: 2000 })], 'Use this when the difference is expected (a fee that settles the next day, a timing gap). Nothing is posted to the ledger.'),
        apply: (r, v, ctx) => { r.fields.status = 'EXPLAINED'; r.fields.note = str(v.note); r.fields.resolvedByName = ctx.actor; r.fields.resolvedAt = ctx.now.toISOString(); log(r, ctx, `Explained — ${str(v.note)}`); }
      },
      {
        key: 'propose-correction', label: 'Propose a correction', tone: 'primary', permission: 'ledger:adjust', when: (r) => stateReason(r, 'OPEN', 'EXPLAINED'),
        form: () => form('Propose a correction', 'Propose', [
          field('account', 'Book the other side against', 'select', true, { options: [{ value: '5000', label: '5000 · Stripe processing fees' }, { value: '2000', label: '2000 · Seller payable (needs the organisation id)' }, { value: '4000', label: '4000 · Commission revenue' }], value: '5000' }),
          field('key', 'Organisation id or jurisdiction (only for 2000 and 2100)', 'text', false, { maxLength: 80 }),
          field('reason', 'Why the ledger is wrong (at least 15 characters)', 'textarea', true, { maxLength: 1000 })
        ], 'Nothing reaches the ledger until a different person approves it under Ledger adjustments.'),
        apply: (r, _v, ctx) => { log(r, ctx, 'Correction proposed — waiting for approval'); }
      }
    ]
  },
  'reconciliation-runs': {
    title: 'Daily runs',
    columns: [col('windowStart', 'Day', 'date', 14), col('ledgerNetMinor', 'Ledger net', 'money', 16), col('stripeNetMinor', 'Stripe net', 'money', 16), col('driftMinor', 'Total difference', 'money', 16), col('differences', 'Objects that differ', 'number', 16), col('state', 'Result', 'status', 14), col('createdAt', 'Run at', 'datetime', 8)],
    statusKey: 'state',
    facets: facets('RECONCILED', 'DIFFERENCES'),
    exportable: true,
    titleOf: (r) => `Reconciliation of ${str(r.fields.windowStart).slice(0, 10)}`,
    subtitleOf: (r) => str(r.fields.state),
    sections: [['Result', ['windowStart', 'ledgerNetMinor', 'stripeNetMinor', 'driftMinor', 'createdAt']]],
    actions: [],
    create: {
      permission: 'payments:configure',
      form: form('Reconcile a day', 'Run', [field('date', 'Day', 'date', true, {})], 'Compares what the ledger booked on 1000 Stripe clearing with Stripe\'s balance transactions for that day.'),
      build: (v, id, ctx) => ({ id, fields: { windowStart: str(v.date), ledgerNetMinor: 0, stripeNetMinor: 0, driftMinor: 0, differences: 0, state: 'RECONCILED', createdAt: ctx.now.toISOString() }, activity: [] })
    }
  },
  'ledger-adjustments': {
    title: 'Ledger adjustments',
    columns: [col('reason', 'Why', 'text', 34), col('amountMinor', 'Amount', 'money', 12), col('stripeRef', 'Stripe object', 'mono', 16), col('proposedByName', 'Proposed by', 'text', 14), col('decidedByName', 'Decided by', 'text', 14), col('status', 'Status', 'status', 10)],
    statusKey: 'status',
    facets: facets('PROPOSED', 'POSTED', 'REJECTED'),
    exportable: true,
    titleOf: (r) => `Adjustment of $${(num(r.fields.amountMinor) / 100).toFixed(2)}`,
    subtitleOf: (r) => str(r.fields.reason),
    sections: [
      ['The correction', ['reason', 'amountMinor', 'stripeRef', 'status', 'journalId']],
      ['Who decided', ['proposedByName', 'proposedAt', 'decidedByName', 'decidedAt', 'decisionNote']]
    ],
    actions: [
      {
        key: 'approve', label: 'Approve and post', tone: 'primary', permission: 'ledger:approve', confirm: 'Post this correction to the ledger now? It cannot be edited afterwards.',
        when: (r, ctx) => stateReason(r, 'PROPOSED') ?? (str(r.fields.proposedByName) === ctx.actor ? 'You proposed this, so a different person must approve it.' : null),
        apply: (r, _v, ctx) => { r.fields.status = 'POSTED'; r.fields.decidedByName = ctx.actor; r.fields.decidedAt = ctx.now.toISOString(); r.fields.journalId = 'jrnl-' + r.id; log(r, ctx, 'Approved and posted'); }
      },
      {
        key: 'reject', label: 'Reject', tone: 'danger', permission: 'ledger:approve', when: (r) => stateReason(r, 'PROPOSED'),
        form: () => form('Why is it rejected?', 'Reject', [field('note', 'Explanation (at least 10 characters)', 'textarea', true, { maxLength: 2000 })]),
        apply: (r, v, ctx) => { r.fields.status = 'REJECTED'; r.fields.decidedByName = ctx.actor; r.fields.decidedAt = ctx.now.toISOString(); r.fields.decisionNote = str(v.note); log(r, ctx, `Rejected — ${str(v.note)}`); }
      }
    ],
    create: {
      permission: 'ledger:adjust',
      form: form('Propose an adjustment', 'Propose', [
        field('debitAccount', 'Debit', 'select', true, { options: [{ value: '5000', label: '5000 · Stripe processing fees' }, { value: '1000', label: '1000 · Stripe clearing' }] }),
        field('creditAccount', 'Credit', 'select', true, { options: [{ value: '5000', label: '5000 · Stripe processing fees' }, { value: '1000', label: '1000 · Stripe clearing' }] }),
        field('amount', 'Amount in dollars', 'number', true, { min: 0, max: 10_000_000 }),
        field('stripeRef', 'Stripe object it corrects (needed when 1000 is used)', 'text', false, { maxLength: 120 }),
        field('reason', 'Why the ledger is wrong (at least 15 characters)', 'textarea', true, { maxLength: 1000 })
      ], 'Nothing reaches the ledger until a different person approves it.'),
      build: (v, id, ctx) => ({ id, fields: { reason: str(v.reason), amountMinor: Math.round(num(v.amount) * 100), stripeRef: str(v.stripeRef) || null, proposedByName: ctx.actor, decidedByName: null, status: 'PROPOSED', proposedAt: ctx.now.toISOString(), decidedAt: null, decisionNote: null, journalId: null }, activity: [] })
    }
  },
  'stripe-lookup': {
    title: 'Stripe lookup',
    columns: [col('ref', 'Stripe id', 'mono', 30), col('kind', 'What it is', 'text', 20), col('stripeStatus', 'Status at Stripe', 'status', 20), col('amountMinor', 'Amount', 'money', 14), col('created', 'Created', 'datetime', 16)],
    statusKey: 'stripeStatus',
    facets: [],
    exportable: false,
    titleOf: (r) => `Stripe ${str(r.fields.ref)}`,
    subtitleOf: (r) => str(r.fields.kind),
    sections: [['What Stripe says', ['ref', 'kind', 'stripeStatus', 'amountMinor', 'created']]],
    actions: [],
    create: {
      permission: 'payments:read',
      form: form('Look up in Stripe', 'Look up', [
        field('ref', 'Stripe id', 'text', true, { maxLength: 120, pattern: '^[a-z]{2,6}_[A-Za-z0-9_]{6,100}$', help: 'For example po_…, tr_…, pi_…, ch_…, re_…, txn_…, dp_…, acct_…' }),
        field('orgId', "Seller's organisation id (for a payout)", 'text', false, { maxLength: 80 })
      ], 'Reads the object from Stripe and shows what the ledger booked for it.'),
      build: (v, _id, ctx) => ({ id: `${str(v.ref)} ${str(v.orgId)}`.trim(), fields: { ref: str(v.ref), kind: 'Payout', stripeStatus: 'in_transit', amountMinor: 50_000, created: ctx.now.toISOString() }, activity: [] })
    }
  },
  'payment-settings': {
    title: 'Payments & payouts',
    columns: [col('label', 'Setting', 'strong', 30), col('value', 'In force', 'text', 40), col('source', 'Set in', 'mono', 22)],
    facets: [],
    exportable: false,
    titleOf: (r) => str(r.fields.label),
    subtitleOf: (r) => str(r.fields.value),
    sections: [['This setting', ['label', 'value', 'help', 'source']]],
    actions: []
  },
  'tax-nexus': {
    title: 'Nexus tracker',
    columns: [col('jurisdiction', 'State', 'mono', 9), col('name', 'Name', 'strong', 16), col('salesMinor', 'Sales, last 12 months', 'money', 16), col('salesThresholdMinor', 'Sales threshold', 'money', 14), col('orders', 'Orders', 'text', 9), col('percentLabel', 'Of threshold', 'text', 11), col('registrationNumber', 'Registration', 'mono', 16), col('status', 'Status', 'status', 16)],
    statusKey: 'status',
    facets: facets('EXCEEDED', 'APPROACHING', 'REGISTERED', 'BELOW'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.name)} (${str(r.fields.jurisdiction)})`,
    subtitleOf: (r) => str(r.fields.status),
    sections: [['Where sales stand', ['status', 'salesMinor', 'orders', 'salesThresholdMinor', 'percentLabel', 'facilitatorLabel']], ['Registration', ['registrationNumber']]],
    actions: [],
    edit: {
      permission: 'tax:configure',
      form: (r) => form(`Thresholds and registration for ${str(r.fields.name)}`, 'Save', [
        field('salesThreshold', 'Sales threshold (dollars)', 'number', true, { value: num(r.fields.salesThresholdMinor) / 100 }),
        field('registered', 'Registered in this state', 'toggle', false, { value: r.fields.registered === true }),
        field('registrationNumber', 'Registration or permit number', 'text', false, { maxLength: 80, value: str(r.fields.registrationNumber) }),
        field('registeredOn', 'Registration effective on', 'date', false)
      ], 'The defaults are a common state figure, not legal advice: confirm each state with your tax advisor.'),
      apply: (r, v) => {
        const registered = v.registered === true;
        if (registered && !str(v.registrationNumber).trim()) throw new Error("Give the state's registration or permit number");
        r.fields.salesThresholdMinor = Math.round(num(v.salesThreshold) * 100);
        r.fields.registered = registered;
        r.fields.registrationNumber = registered ? str(v.registrationNumber) : null;
        const percent = Math.round((num(r.fields.salesMinor) * 100) / Math.max(num(r.fields.salesThresholdMinor), 1));
        r.fields.percent = percent;
        r.fields.percentLabel = `${percent}%`;
        r.fields.status = registered ? 'REGISTERED' : percent >= 100 ? 'EXCEEDED' : percent >= 80 ? 'APPROACHING' : 'BELOW';
      }
    }
  },
  'tax-filings': {
    title: 'Tax filings',
    columns: [col('month', 'Month', 'mono', 10), col('jurisdiction', 'State', 'mono', 10), col('dueMinor', 'Due now', 'money', 12), col('paidMinor', 'Paid', 'money', 12), col('outstandingMinor', 'Outstanding', 'money', 12), col('filedOn', 'Filed', 'date', 12), col('confirmationRef', 'State confirmation', 'mono', 18), col('state', 'Status', 'status', 12)],
    statusKey: 'state',
    facets: facets('NOT_FILED', 'OWES_MORE', 'OVERPAID', 'SETTLED', 'NOTHING_DUE'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.jurisdiction)} · ${str(r.fields.month)}`,
    subtitleOf: (r) => str(r.fields.statusLabel),
    sections: [['What the ledger says', ['month', 'jurisdiction', 'dueMinor', 'state', 'outstandingMinor']], ['What was filed and paid', ['paidMinor', 'filedOn', 'confirmationRef']]],
    actions: [
      {
        key: 'record', label: 'Record filing and payment', tone: 'primary', permission: 'tax:configure',
        when: (r) => (r.fields.remittanceId ? 'A remittance is already recorded; void it first if it was a mistake.' : null),
        form: (r) => form('Record what was filed and paid', 'Record', [
          field('paid', 'Amount paid to the state (dollars)', 'number', true, { value: Math.max(num(r.fields.dueMinor), 0) / 100 }),
          field('filedOn', 'Return filed on', 'date', true),
          field('paidOn', 'Payment made on', 'date', true),
          field('confirmationRef', 'State confirmation number', 'text', false, { maxLength: 80 }),
          field('note', 'Note', 'textarea', false, { maxLength: 1000 })
        ], 'Do this after you have filed the return and paid it. A mistake is voided, not edited.'),
        apply: (r, v) => {
          const paid = Math.round(num(v.paid) * 100);
          r.fields.paidMinor = paid;
          r.fields.filedOn = str(v.filedOn);
          r.fields.confirmationRef = str(v.confirmationRef) || null;
          r.fields.remittanceId = `rem-${Date.now()}`;
          r.fields.outstandingMinor = num(r.fields.dueMinor) - paid;
          r.fields.state = r.fields.outstandingMinor === 0 ? 'SETTLED' : r.fields.outstandingMinor > 0 ? 'OWES_MORE' : 'OVERPAID';
          r.fields.statusLabel = str(r.fields.state);
        }
      },
      {
        key: 'void', label: 'Void this record', tone: 'danger', permission: 'tax:configure',
        when: (r) => (r.fields.remittanceId ? null : 'Nothing is recorded for this month and state.'),
        form: () => form('Why is it voided?', 'Void', [field('note', 'Explanation (at least 10 characters)', 'textarea', true, { maxLength: 2000 })]),
        apply: (r) => {
          r.fields.paidMinor = null; r.fields.filedOn = null; r.fields.confirmationRef = null; r.fields.remittanceId = null;
          r.fields.outstandingMinor = num(r.fields.dueMinor); r.fields.state = 'NOT_FILED'; r.fields.statusLabel = 'Not filed';
        }
      }
    ]
  },
  maintenance: {
    title: 'Maintenance mode',
    columns: [col('setting', 'Setting', 'strong', 24), col('stateLabel', 'Now', 'status', 40), col('updatedByName', 'Changed by', 'text', 16), col('updatedAt', 'Changed', 'datetime', 12)],
    facets: [],
    exportable: false,
    titleOf: () => 'Maintenance mode',
    subtitleOf: (r) => str(r.fields.stateLabel),
    sections: [['Now', ['stateLabel', 'title', 'message', 'expectedBackAt', 'updatedByName', 'updatedAt']]],
    actions: [],
    edit: {
      permission: 'content:site.edit',
      form: (r) => form('Maintenance mode', 'Save', [
        field('enabled', 'Maintenance mode on', 'toggle', false, { value: str(r.fields.state) === 'ON' }),
        field('scope', 'Who is paused', 'select', true, { options: [{ value: 'SHOPPERS', label: 'Shoppers only (sellers can keep working)' }, { value: 'ALL', label: 'Shoppers and sellers' }], value: str(r.fields.scope) || 'SHOPPERS' }),
        field('title', 'Heading', 'text', false, { maxLength: 80, value: str(r.fields.title) }),
        field('message', 'What customers are told (required when turning on)', 'textarea', false, { maxLength: 600, value: str(r.fields.message) }),
        field('backIn', 'When we expect to be back', 'select', false, { options: [{ value: '0', label: 'Not sure yet' }, { value: '15', label: 'About 15 minutes' }, { value: '30', label: 'About 30 minutes' }, { value: '60', label: 'About 1 hour' }], value: '0' })
      ], 'While it is on, shoppers see the maintenance page instead of the site. Staff can still sign in.'),
      apply: (r, v) => {
        const on = v.enabled === true;
        if (on && str(v.message).trim().length < 10) throw new Error('Tell customers what is happening, in at least 10 characters.');
        r.fields.state = on ? 'ON' : 'OFF';
        r.fields.stateLabel = on ? 'On: Shoppers only see the maintenance page' : 'Off: the platform is open';
        r.fields.scope = str(v.scope);
        r.fields.title = str(v.title) || 'We’ll be right back';
        r.fields.message = str(v.message);
      }
    }
  },
  'tax-settings': {
    title: 'Sales tax method',
    columns: [col('setting', 'Setting', 'strong', 30), col('methodLabel', 'In force', 'text', 24), col('stripeLabel', 'Stripe Tax', 'text', 18), col('changedByName', 'Changed by', 'text', 16), col('changedAt', 'Changed', 'datetime', 12)],
    facets: [],
    exportable: false,
    titleOf: () => 'Who calculates sales tax',
    subtitleOf: (r) => `${str(r.fields.methodLabel)} is in force`,
    sections: [['In force', ['methodLabel', 'stripeLabel', 'configuredDefault', 'changedByName', 'changedAt']]],
    actions: [],
    edit: {
      permission: 'tax:configure',
      form: (r) => form('Change who calculates sales tax', 'Change method', [
        field('method', 'Method', 'select', true, { options: [{ value: 'RULES', label: 'Our own rules (rate tables and tax codes)' }, { value: 'STRIPE_TAX', label: 'Stripe Tax (needs to be set up)' }], value: str(r.fields.method) }),
        field('reason', 'Why (at least 10 characters)', 'textarea', true, { maxLength: 500 })
      ], 'Our own rules are always available. Stripe Tax can only be chosen once it is set up. The change applies to new quotes; orders already placed keep their tax.', ['method']),
      apply: (r, v) => {
        if (str(v.method) === 'STRIPE_TAX') throw new Error('Stripe Tax is not set up yet');
        r.fields.method = str(v.method);
        r.fields.methodLabel = 'Our own rules';
      }
    }
  },
  'tax-rules': {
    title: 'Tax classification rules',
    columns: [col('productType', 'Product type', 'mono', 16), col('attrKey', 'Question', 'mono', 14), col('attrValue', "Seller's answer", 'text', 28), col('taxCode', 'Tax code', 'strong', 18), col('kind', 'Rule', 'status', 10), col('note', 'Why', 'text', 24)],
    statusKey: 'kind',
    facets: facets('TYPE', 'ANSWER'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.productType)} ${str(r.fields.attrValue)}`.trim(),
    subtitleOf: (r) => `→ ${str(r.fields.taxCode)}`,
    actions: []
  },
  'tax-review': {
    title: 'Tax review queue',
    columns: [col('productType', 'Product type', 'mono', 16), col('answer', "Seller's answer", 'text', 28), col('taxCode', 'Taxed as', 'strong', 18), col('count', 'Order lines', 'number', 10), col('lastSeen', 'Last seen', 'datetime', 16), col('state', 'State', 'status', 12)],
    statusKey: 'state',
    facets: facets('OPEN', 'REVIEWED'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.productType)} ${str(r.fields.answer)}`.trim(),
    subtitleOf: () => 'Taxed as general goods because no rule matched. Add a rule, or mark it reviewed if general goods is right.',
    actions: []
  },
  'tax-rates': {
    title: 'Tax rates',
    columns: [col('kind', 'Rule', 'status', 10), col('country', 'Country', 'mono', 8), col('state', 'State', 'mono', 8), col('zipPrefix', 'ZIP prefix', 'mono', 10), col('name', 'Name', 'strong', 20), col('generalRate', 'General', 'percent', 10), col('groceryRate', 'Grocery', 'percent', 10), col('preparedFoodRate', 'Prepared food', 'percent', 12), col('version', 'Version', 'number', 8)],
    statusKey: 'kind',
    facets: facets('STATE', 'LOCAL'),
    exportable: true,
    titleOf: (r) => `${str(r.fields.name)} (${str(r.fields.state)}${r.fields.zipPrefix ? ` ${str(r.fields.zipPrefix)}` : ''})`,
    subtitleOf: (r) => (r.fields.kind === 'STATE' ? 'State rule' : 'Local rate'),
    actions: [],
    edit: {
      permission: 'tax:configure',
      form: (r) =>
        r.fields.kind === 'STATE'
          ? form('Edit the state rule', 'Save rule', [
            field('name', 'Name', 'text', true, { value: str(r.fields.name) }),
            field('generalRate', 'General rate', 'number', true, { min: 0, max: 0.2, value: num(r.fields.generalRate), help: 'Decimal, e.g. 0.0625 for 6.25 %' }),
            field('groceryRate', 'Grocery rate', 'number', true, { min: 0, max: 0.2, value: num(r.fields.groceryRate) }),
            field('preparedFoodRate', 'Prepared food rate', 'number', true, { min: 0, max: 0.2, value: num(r.fields.preparedFoodRate) }),
            field('shippingTaxable', 'Shipping is taxable', 'toggle', false, { value: r.fields.shippingTaxable === true }),
            field('facilitator', 'Marketplace facilitator', 'toggle', false, { value: r.fields.facilitator === true })
          ])
          : form('Edit the local rate', 'Save rate', [field('name', 'Name', 'text', true, { value: str(r.fields.name) }), field('generalRate', 'Rate', 'number', true, { min: 0, max: 0.1, value: num(r.fields.generalRate) })]),
      apply: (r, v) => {
        Object.entries(v).forEach(([k, val]) => { if (typeof val === 'string' || typeof val === 'number' || typeof val === 'boolean') r.fields[k] = val; });
        if (r.fields.version !== null) r.fields.version = num(r.fields.version) + 1;
      }
    },
    create: {
      permission: 'tax:configure',
      form: form('New local rate', 'Add rate', [
        field('state', 'State', 'text', true, { pattern: '^[A-Z]{2}$', placeholder: 'TX', help: 'Two-letter state code, e.g. TX' }),
        field('zipPrefix', 'ZIP or prefix', 'text', true, { pattern: '^([0-9]{3}|[0-9]{5})$', placeholder: '770', help: 'A 3-digit prefix or a 5-digit ZIP' }),
        field('name', 'Name', 'text', true, { maxLength: 80 }),
        field('rate', 'Rate', 'number', true, { min: 0, max: 0.1, help: 'Decimal, e.g. 0.02 for 2 %' })
      ]),
      build: (v) => ({ id: `local:${str(v.state)}:${str(v.zipPrefix)}`, fields: { kind: 'LOCAL', state: str(v.state), zipPrefix: str(v.zipPrefix), name: str(v.name), generalRate: num(v.rate), groceryRate: null, preparedFoodRate: null, shippingTaxable: null, facilitator: null, version: null }, activity: [] })
    }
  },
  organisations: {
    title: 'Organisations',
    columns: [col('name', 'Organisation', 'strong', 30), col('kind', 'Kind', 'text', 14), col('plan', 'Plan', 'text', 16), col('createdAt', 'Since', 'date', 14), col('status', 'Status', 'status', 14)],
    statusKey: 'status',
    facets: facets('APPROVED', 'SUSPENDED'),
    exportable: true,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => str(r.fields.kind),
    actions: []
  },
  'connect-accounts': {
    title: 'Stripe Connect accounts',
    columns: [col('organisation', 'Organisation', 'strong', 30), col('accountId', 'Account', 'mono', 22), col('payoutsEnabled', 'Payouts', 'text', 12), col('status', 'Status', 'status', 14)],
    statusKey: 'status',
    facets: facets('ACTIVE', 'RESTRICTED'),
    exportable: true,
    titleOf: (r) => str(r.fields.organisation),
    subtitleOf: (r) => str(r.fields.accountId),
    actions: []
  },
  coupons: {
    title: 'Platform coupons',
    columns: [col('code', 'Code', 'mono', 10), col('description', 'Description', 'text', 22), col('type', 'Type', 'status', 10), col('value', 'Value', 'number', 8), col('scope', 'Scope', 'text', 12), col('status', 'Status', 'status', 10), col('redeemedCount', 'Redeemed', 'number', 8)],
    statusKey: 'status',
    facets: facets('ACTIVE', 'PAUSED', 'EXPIRED'),
    exportable: true,
    titleOf: (r) => str(r.fields.code),
    subtitleOf: (r) => str(r.fields.description),
    actions: [
      { key: 'pause', label: 'Pause', tone: 'neutral', permission: 'coupons:manage', confirm: 'Pause this coupon?', when: (r) => (r.fields.status === 'ACTIVE' ? null : 'Only active coupons can be paused'), apply: (r) => { r.fields.status = 'PAUSED'; } },
      { key: 'activate', label: 'Activate', tone: 'primary', permission: 'coupons:manage', confirm: 'Activate this coupon?', when: (r) => (r.fields.status === 'PAUSED' ? null : 'Only paused coupons can be activated'), apply: (r) => { r.fields.status = 'ACTIVE'; } }
    ],
    create: {
      permission: 'coupons:manage',
      form: form('New platform coupon', 'Create coupon', [
        field('code', 'Code', 'text', true, { pattern: '^[A-Za-z0-9]{3,20}$', placeholder: 'WELCOME10' }),
        field('description', 'Description', 'text', false, { maxLength: 300 }),
        field('type', 'Type', 'select', true, { options: options('PERCENT', 'FIXED', 'FREE_SHIPPING'), value: 'PERCENT' }),
        field('value', 'Value', 'number', true, { min: 0, value: 10 }),
        field('scope', 'Scope', 'select', true, { options: options('CART', 'CATEGORY', 'PRODUCT', 'SELLER', 'FULFILLED_BY_AFROTRANSACT'), value: 'CART' })
      ]),
      build: (v, id) => ({
        id,
        fields: { code: str(v.code).toUpperCase(), description: str(v.description), type: str(v.type), value: num(v.value), scope: str(v.scope), status: 'ACTIVE', funding: 'PLATFORM', redeemedCount: 0, version: 1 },
        activity: []
      })
    }
  },
  // Identity & access (S291)
  ...IDENTITY_RESOURCES
};

function placementsDef(title: string, announcementsOnly: boolean): MockResource {
  const baseActions: MockAction[] = [
    { key: 'submit', label: 'Send for review', tone: 'primary', permission: 'content:placements.edit', confirm: 'Send this placement for review?', when: (r) => stateReason(r, 'DRAFT'), apply: setStatus('IN_REVIEW', 'Sent for review') },
    { key: 'approve', label: 'Approve', tone: 'primary', permission: 'content:placements.approve', level: 'APPROVE', confirm: 'Approve and schedule?', when: (r) => stateReason(r, 'IN_REVIEW'), apply: setStatus('LIVE', 'Approved — live') },
    { key: 'reject', label: 'Reject', tone: 'danger', permission: 'content:placements.approve', level: 'APPROVE', form: noteForm('Reject back to draft', 'Reject'), when: (r) => stateReason(r, 'IN_REVIEW'), apply: setStatus('DRAFT', 'Rejected back to draft') },
    { key: 'end', label: 'End now', tone: 'danger', permission: 'content:placements.edit', confirm: 'End this placement now?', when: (r) => stateReason(r, 'SCHEDULED', 'LIVE'), apply: setStatus('ENDED', 'Ended') }
  ];
  const actions =
    announcementsOnly
      ? baseActions
      : [
          ...baseActions,
          {
            key: 'delete',
            label: 'Delete',
            tone: 'danger' as const,
            permission: 'advert:delete',
            confirm: 'Permanently delete this advert?',
            when: (r: MockRecord) => (r.fields.paid ? stateReason(r, 'DRAFT', 'ENDED') : 'Only paid adverts can be deleted.'),
            removes: true,
            apply: () => {}
          }
        ];
  return {
    title,
    columns: [col('name', 'Name', 'strong', 28), col('slot', 'Slot', 'mono', 18), col('advertiser', 'Advertiser', 'text', 16), col('startsAt', 'Starts', 'date', 12), col('endsAt', 'Ends', 'date', 12), col('status', 'Status', 'status', 14)],
    statusKey: 'status',
    facets: facets('DRAFT', 'IN_REVIEW', 'SCHEDULED', 'LIVE', 'ENDED'),
    exportable: true,
    titleOf: (r) => str(r.fields.name),
    subtitleOf: (r) => `${str(r.fields.slot)}${r.fields.paid ? ` · paid by ${str(r.fields.advertiser)}` : ''}`,
    actions,
    edit: {
      permission: 'content:placements.edit',
      form: (r) => form('Edit draft', 'Save draft', [
        field('name', 'Name', 'text', true, { maxLength: 120, value: str(r.fields.name) }),
        field('text', announcementsOnly ? 'Announcement text' : 'Headline', 'text', true, { maxLength: 140, value: str(r.fields.text) }),
        field('startsAt', 'Starts', 'datetime', true, { value: str(r.fields.startsAt) }),
        field('endsAt', 'Ends', 'datetime', false, { value: str(r.fields.endsAt) })
      ]),
      apply: (r, v) => { r.fields.name = str(v.name); r.fields.text = str(v.text); r.fields.startsAt = str(v.startsAt); r.fields.endsAt = str(v.endsAt) || null; r.fields.version = num(r.fields.version) + 1; }
    },
    create: {
      permission: 'content:placements.edit',
      form: form(announcementsOnly ? 'New announcement' : 'New placement', 'Create draft', [
        field('name', 'Name', 'text', true, { maxLength: 120 }),
        ...(announcementsOnly ? [] : [field('slot', 'Slot', 'select', true, { options: options('hero_carousel', 'display_banner', 'home_tiles', 'home_product_row') })]),
        field('text', announcementsOnly ? 'Announcement text' : 'Headline', 'text', true, { maxLength: 140 }),
        field('href', 'Link', 'text', false, { pattern: '^/' }),
        field('startsAt', 'Starts', 'datetime', true),
        field('endsAt', 'Ends', 'datetime', false),
        ...(announcementsOnly ? [] : [field('advertiser', 'Advertiser (paid)', 'text', false, { maxLength: 80 })])
      ]),
      build: (v, id, ctx) => ({
        id,
        fields: { name: str(v.name), slot: announcementsOnly ? 'announcement_bar' : str(v.slot), status: 'DRAFT', paid: !!v.advertiser, advertiser: str(v.advertiser) || null, text: str(v.text), priority: 10, startsAt: str(v.startsAt), endsAt: str(v.endsAt) || null, version: 1 },
        activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: 'Draft created' }]
      })
    }
  };
}

/* Delivery dispatch › Pickup points (carrier-gateway pickup locations, like staff_resources_pickup.go). */
/** Opening-day patterns (a function: the resource table above builds its create form at module load). */
function pickupDays(): [string, string][] {
  return [['MON_SAT', 'Monday to Saturday'], ['MON_FRI', 'Monday to Friday'], ['EVERY_DAY', 'Every day'], ['TUE_SUN', 'Tuesday to Sunday']];
}

function pickupForm(title: string, submit: string, r: MockRecord | null): StaffForm {
  const f = r?.fields ?? {};
  return form(title, submit, [
    field('name', 'Name customers see', 'text', true, { maxLength: 80, value: str(f.name) }),
    field('kind', 'Kind', 'select', true, { options: [{ value: 'PLATFORM_COUNTER', label: 'Pickup counter' }, { value: 'LOCKER', label: 'Locker' }], value: str(f.kind) || 'PLATFORM_COUNTER' }),
    field('line1', 'Street address', 'text', true, { maxLength: 120, value: str(f.line1) }),
    field('city', 'City', 'text', true, { maxLength: 60, value: str(f.city) }),
    field('state', 'State', 'text', true, { pattern: '^[A-Z]{2}$', help: 'Two-letter state code, e.g. TX', value: str(f.stateCode) }),
    field('postalCode', 'ZIP code', 'text', true, { pattern: '^[0-9]{5}$', help: '5-digit ZIP', value: str(f.postalCode) }),
    field('days', 'Open on', 'select', true, { options: pickupDays().map(([value, label]) => ({ value, label })), value: str(f.days) || 'MON_SAT' }),
    field('opens', 'Opens (24-hour)', 'text', true, { pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$', help: 'HH:MM, e.g. 09:00', value: str(f.opens) || '09:00' }),
    field('closes', 'Closes (24-hour)', 'text', true, { pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$', help: 'HH:MM, e.g. 18:00', value: str(f.closes) || '18:00' }),
    field('readyHours', 'Ready after (hours)', 'number', false, { min: 0, max: 168, value: num(f.readyHours) }),
    field('active', 'Customers can choose it at checkout', 'toggle', false, { value: r ? f.state === 'ACTIVE' : true })
  ]);
}

function applyPickup(r: MockRecord, v: Record<string, FieldValue>): void {
  const days = str(v.days) || 'MON_SAT';
  const kind = str(v.kind) === 'LOCKER' ? 'LOCKER' : 'PLATFORM_COUNTER';
  Object.assign(r.fields, {
    name: str(v.name), kind, kindLabel: kind === 'LOCKER' ? 'AfroTransact locker' : 'AfroTransact counter', line1: str(v.line1), city: str(v.city),
    stateCode: str(v.state).toUpperCase(), postalCode: str(v.postalCode), days, opens: str(v.opens), closes: str(v.closes), readyHours: num(v.readyHours),
    place: `${str(v.line1)}, ${str(v.city)}, ${str(v.state).toUpperCase()} ${str(v.postalCode)}`,
    hoursLabel: `${pickupDays().find(([k]) => k === days)?.[1] ?? days} ${str(v.opens)}–${str(v.closes)}`, state: v.active === false ? 'PAUSED' : 'ACTIVE'
  });
}
