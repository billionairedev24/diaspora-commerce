/* Selling approvals in the mock staff BFF: the case type "Selling approval" in the queue next to Applications (same
   queues, assignment, two-step review, with a "cannot apply" disqualification), the approvals sellers hold (pause,
   restore, withdraw) and the selling rules (gated brands, required documents). Mirrors
   internal/app/staff_resources_selling.go of the Go BFF over seller-service; docs/runbooks/selling-approvals.md. */
import { type FieldValue, type FormField, type StaffForm } from '../../schemas/staff';
import { APPLICATION_QUEUES, ASSIGNMENT_ACTIONS, inQueue, seedWorkflow, stageLine } from './application-flow';
import { type MockRecord, type Scalar } from './db';
import { type ActionCtx, type MockAction, type MockResource } from './resources';

const field = (key: string, label: string, type: FormField['type'], required: boolean, extra: Partial<FormField> = {}): FormField => ({ key, label, type, required, ...extra });
const str = (v: FieldValue | Scalar | undefined): string => (v === null || v === undefined ? '' : String(v));
const num = (v: FieldValue | Scalar | undefined): number => (typeof v === 'number' ? v : Number(v ?? 0));
const form = (title: string, submitLabel: string, fields: FormField[], intro = ''): StaffForm => ({ title, submitLabel, fields, ...(intro ? { intro } : {}) });
const log = (r: MockRecord, ctx: ActionCtx, text: string) => r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text });
const humanize = (s: string) => s.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
const is = (r: MockRecord, ...statuses: string[]) => statuses.includes(str(r.fields.status));
const stateReason = (r: MockRecord, ...ok: string[]) => (is(r, ...ok) ? null : `Not possible while ${humanize(str(r.fields.status)).toLowerCase()}.`);
const finalStep = (r: MockRecord, ctx: ActionCtx) =>
  stateReason(r, 'AWAITING_FINAL_APPROVAL', 'FLAGGED') ?? (r.fields.reviewerId === ctx.actorId ? 'You reviewed step 1: a different approver must decide step 2.' : null);
const reasonField = (): FormField => field('reason', 'Reason (the seller reads it)', 'textarea', true, { maxLength: 500, pattern: '^[\\s\\S]{10,}$', help: 'Give a reason of at least 10 characters' });
const requireReason = (v: Record<string, FieldValue>) => {
  if (str(v.reason).trim().length < 10) throw new Error('Give a reason of at least 10 characters');
};
const decided = (r: MockRecord, ctx: ActionCtx) => {
  if (!is(r, 'APPROVED', 'REJECTED')) return null;
  const last = r.activity[0];
  return { mine: last?.actor === ctx.actor, message: `This case is already ${humanize(str(r.fields.status)).toLowerCase()}${last ? ` (by ${last.actor})` : ''}` };
};

export const DOCUMENT_KINDS = [
  { value: 'PURCHASE_INVOICE', label: 'Purchase invoice (within 180 days)' },
  { value: 'PHOTOS_ALL_SIDES', label: 'Photos of all sides' },
  { value: 'BRAND_AUTHORIZATION', label: 'Brand authorization' },
  { value: 'SAFETY_CERTIFICATE', label: 'Safety or lab certificate' },
  { value: 'PERMIT_LICENCE', label: 'Permit or licence (with expiry)' },
  { value: 'LIABILITY_INSURANCE', label: 'Liability insurance naming AfroTransact (with expiry)' }
];
const SCOPE_WORDS: Record<string, string> = { CATEGORY: 'Category', PRODUCT_TYPE: 'Product type', BRAND: 'Brand', BRAND_IN_CATEGORY: 'Brand in a category', PRODUCT: 'Single product', CAPABILITY: 'Seller capability' };
const LEVEL_WORDS: Record<string, string> = { SELL: 'Open: sell now', APPLY: 'Apply to sell', NOT_AVAILABLE: 'Not available' };

/* ---- the case ---- */

const CASES: [string, string, string, string, string][] = [
  ['SA-50001', 'Dietary supplements', 'Kente Naturals', 'PENDING_REVIEW', 'CATEGORY'],
  ['SA-50002', 'Gucci', 'Ama’s Braiding Studio', 'AWAITING_FINAL_APPROVAL', 'BRAND'],
  ['SA-50003', 'Food service (cooks, bakers, caterers)', 'Jollof Republic Catering', 'PENDING_REVIEW', 'CAPABILITY'],
  ['SA-50004', 'Apple in Phones', 'Lagos Tech Hub', 'INFO_REQUESTED', 'BRAND_IN_CATEGORY']
];

export function seedSelling(now: Date): Record<'selling-approvals' | 'seller-approvals' | 'selling-rules', MockRecord[]> {
  const ago = (hours: number) => new Date(now.getTime() - hours * 3_600_000).toISOString();
  const cases: MockRecord[] = CASES.map(([reference, scopeLabel, orgName, status, scopeType], i) => ({
    id: `sap-${i + 1}`,
    fields: {
      reference, scopeLabel, orgName, orgId: `org-sell-${i + 1}`, scopeType, scopeKey: scopeLabel.toLowerCase().replace(/[^a-z0-9]+/g, '-'), kind: i === 2 ? 'From bakery flag' : 'New', status,
      reviewerId: status === 'AWAITING_FINAL_APPROVAL' ? 'stf-2' : null, reviewerName: status === 'AWAITING_FINAL_APPROVAL' ? 'Sara Tesfaye' : null,
      submittedAt: ago(30 - i * 6), applicantName: 'Seller owner', ...seedWorkflow(status, i),
      documents: i === 2 ? 'None on file: ask for the food permit and the insurance' : 'Permit or licence · permit.pdf · expires 2027-06-30; Liability insurance · policy.pdf · expires 2027-06-30'
    },
    activity: [{ at: ago(30 - i * 6), actor: 'Seller owner', text: 'Submitted' }]
  }));
  const approvals: MockRecord[] = [
    ['sell-a1', 'Kente Naturals', 'Dietary supplements', 'CATEGORY', 'APPROVED', 18, false],
    ['sell-a2', 'Lagos Tech Hub', 'Apple in Phones', 'BRAND_IN_CATEGORY', 'APPROVED', 120, false],
    ['sell-a3', 'Jollof Republic Catering', 'Food service (cooks, bakers, caterers)', 'CAPABILITY', 'EXPIRED', -16, true]
  ].map(([id, orgName, label, scopeType, status, days, paused]) => ({
    id: String(id),
    fields: { orgName: String(orgName), orgId: `org-${String(id)}`, label: String(label), scopeType: String(scopeType), scopeTypeWords: SCOPE_WORDS[String(scopeType)] ?? '', status: String(status),
      expiresAt: new Date(now.getTime() + Number(days) * 86_400_000).toISOString(), daysWords: Number(days) < 0 ? `Expired ${-Number(days)} days ago` : `${Number(days)} days`, pauseWords: paused ? 'Paused' : 'Live', paused: false, listingsPaused: Boolean(paused) },
    activity: [{ at: ago(24 * 40), actor: 'Sara Tesfaye', text: 'Approved' }]
  }));
  const rules: MockRecord[] = [
    ['dietary-supplements', 'CATEGORY', 'Dietary supplements', 'APPLY', 'Permit or licence (with expiry), Liability insurance naming AfroTransact (with expiry), Safety or lab certificate', 'SEED'],
    ['gift-baskets-bundles', 'CATEGORY', 'Gift baskets / bundles', 'NOT_AVAILABLE', 'None', 'SEED'],
    ['jewelry-accessories', 'CATEGORY', 'Jewelry & accessories', 'APPLY', 'Purchase invoice (within 180 days), Photos of all sides', 'SEED'],
    ['gucci', 'BRAND', 'Gucci', 'APPLY', 'Brand authorization, Purchase invoice (within 180 days), Photos of all sides', 'SEED'],
    ['apple|phones', 'BRAND_IN_CATEGORY', 'Apple in Phones', 'APPLY', 'Brand authorization, Purchase invoice (within 180 days), Photos of all sides', 'SEED'],
    ['packaged-snacks', 'CATEGORY', 'Packaged snacks', 'SELL', 'None', 'SEED']
  ].map(([key, scopeType, label, level, docs, source], i) => ({
    id: `rule-${i + 1}`,
    fields: { scopeKey: String(key), scopeType: String(scopeType), scopeTypeWords: SCOPE_WORDS[String(scopeType)] ?? '', label: String(label), level: String(level), levelWords: LEVEL_WORDS[String(level)] ?? '', docsWords: String(docs), activeWords: 'On',
      sourceWords: source === 'SEED' ? 'Seeded' : 'Staff', active: true, reapplyAfterDays: 30, updatedAt: ago(24 * 20), version: 0 },
    activity: [{ at: ago(24 * 20), actor: 'System', text: 'Seeded from the listing rules' }]
  }));
  return { 'selling-approvals': cases, 'seller-approvals': approvals, 'selling-rules': rules };
}

const noteForm = (title: string, submit: string, label: string, required: boolean, extra: FormField[] = []) => (): StaffForm =>
  form(title, submit, [...extra, field('note', label, 'textarea', required, { maxLength: 2000 })]);
const setStatus = (status: string, text: string) => (r: MockRecord, _v: Record<string, FieldValue>, ctx: ActionCtx) => {
  r.fields.status = status;
  r.fields.stage = '';
  r.fields.with = null;
  log(r, ctx, text);
};

export const sellingApprovalsDef: MockResource = {
  title: 'Selling approvals',
  columns: [
    { key: 'reference', label: 'Reference', kind: 'mono', width: 11 }, { key: 'scopeLabel', label: 'Approval for', kind: 'strong', width: 18 }, { key: 'orgName', label: 'Seller', kind: 'text', width: 16 }, { key: 'kindWords', label: 'Type', kind: 'text', width: 9 },
    { key: 'with', label: 'With', kind: 'text', width: 12 }, { key: 'waiting', label: 'Waiting', kind: 'duration', width: 8 }, { key: 'sla', label: 'SLA', kind: 'text', width: 8 }, { key: 'escalation', label: 'Escalation', kind: 'text', width: 9 },
    { key: 'submittedAt', label: 'Submitted', kind: 'date', width: 10 }, { key: 'status', label: 'Status', kind: 'status', width: 12 }
  ],
  statusKey: 'status',
  facets: APPLICATION_QUEUES,
  queue: inQueue,
  exportable: true,
  titleOf: (r) => `${str(r.fields.scopeLabel)} · ${str(r.fields.orgName)}`,
  subtitleOf: (r) => `${str(r.fields.reference)} · Selling approval · ${stageLine(r)}`,
  ids: { reviewerId: 'Reviewer', orgId: 'Organisation' },
  sections: [
    ['Who has it', ['with', 'sla', 'escalation']],
    ['Application', ['scopeLabel', 'scopeType', 'orgName', 'applicantName', 'kind', 'submittedAt']],
    ['Documents', ['documents']],
    ['Review', ['status', 'reviewerName']]
  ],
  actions: [
    ...ASSIGNMENT_ACTIONS,
    { key: 'review-approve', label: 'Approve (step 1)', tone: 'primary', permission: 'applications:review', level: 'APPROVE', form: noteForm('Approve for final review (step 1)', 'Approve', 'Note (internal)', false), when: (r) => stateReason(r, 'PENDING_REVIEW'), already: decided,
      apply: (r, _v, ctx) => { r.fields.status = 'AWAITING_FINAL_APPROVAL'; r.fields.reviewerId = ctx.actorId; r.fields.reviewerName = ctx.actor; r.fields.stage = 'APPROVAL'; r.fields.with = 'Any approver'; log(r, ctx, 'Step 1 approved'); } },
    { key: 'review-request-info', label: 'Ask the seller for more', tone: 'neutral', permission: 'applications:review', level: 'APPROVE', form: noteForm('Ask the seller for more', 'Return to seller', 'Message to the seller', true, [field('requestedItems', 'What is missing (one per line)', 'textarea', true)]),
      when: (r) => stateReason(r, 'PENDING_REVIEW', 'FLAGGED'), already: decided, apply: setStatus('INFO_REQUESTED', 'Asked the seller for more') },
    { key: 'final-approve', label: 'Final approval', tone: 'primary', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Give final approval (step 2)', 'Approve', 'Note (internal)', false), when: finalStep, already: decided, apply: setStatus('APPROVED', 'Final approval: the seller is approved for this scope') },
    { key: 'send-back', label: 'Send back', tone: 'neutral', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Send back to step 1', 'Send back', 'Note', true), when: finalStep, already: decided, apply: setStatus('PENDING_REVIEW', 'Sent back to step 1') },
    { key: 'reject', label: 'Reject', tone: 'danger', permission: 'applications:approve', level: 'APPROVE', form: noteForm('Reject the application', 'Reject', 'Message to the seller', true), when: finalStep, already: decided, apply: setStatus('REJECTED', 'Rejected') },
    { key: 'disqualify', label: 'Cannot apply (not appealable)', tone: 'danger', permission: 'applications:approve', level: 'APPROVE',
      form: noteForm('Cannot apply (not appealable)', 'Disqualify', 'Message to the seller', true, [field('reapplyAfter', 'They may apply again from', 'date', true)]), when: finalStep, already: decided,
      apply: (r, v, ctx) => {
        if (!str(v.reapplyAfter)) throw new Error('Set the day the seller may apply again');
        setStatus('REJECTED', `Cannot apply: not appealable, may apply again from ${str(v.reapplyAfter)}`)(r, v, ctx);
      } }
  ]
};

/* ---- approvals sellers hold ---- */

const staffAction = (key: string, label: string, tone: MockAction['tone'], when: MockAction['when'], intro: string, withDate: boolean, apply: MockAction['apply']): MockAction => ({
  key, label, tone, permission: 'applications:approve', level: 'APPROVE', when, apply,
  form: () => form(label, label.split(' ')[0] ?? label, [reasonField(), ...(withDate ? [field('reapplyAfter', 'They may apply again from', 'date', true)] : [])], intro)
});

export const sellerApprovalsDef: MockResource = {
  title: 'Seller approvals',
  columns: [
    { key: 'orgName', label: 'Seller', kind: 'strong', width: 18 }, { key: 'label', label: 'Approved for', kind: 'text', width: 22 }, { key: 'scopeTypeWords', label: 'Kind', kind: 'text', width: 12 }, { key: 'expiresAt', label: 'Runs until', kind: 'date', width: 11 },
    { key: 'daysWords', label: 'Expires in', kind: 'text', width: 12 }, { key: 'pauseWords', label: 'Listings', kind: 'text', width: 14 }, { key: 'status', label: 'Status', kind: 'status', width: 12 }
  ],
  statusKey: 'status',
  facets: ['APPROVED', 'APPLIED', 'NEEDS_ATTENTION', 'EXPIRED', 'NOT_AVAILABLE'].map((v) => ({ value: v, label: humanize(v) })),
  search: ['orgName', 'label'],
  exportable: true,
  titleOf: (r) => `${str(r.fields.label)} · ${str(r.fields.orgName)}`,
  subtitleOf: (r) => `${humanize(str(r.fields.status))} · ${str(r.fields.scopeTypeWords)}`,
  ids: { orgId: 'Organisation' },
  sections: [['Approval', ['orgName', 'label', 'status', 'expiresAt', 'reason']]],
  actions: [
    staffAction('pause', 'Pause listings', 'danger', (r) => stateReason(r, 'APPROVED', 'EXPIRED'), 'Use it for a performance problem. The seller’s listings in this scope go to Paused (never deleted) and the seller is told why.', false,
      (r, v, ctx) => { requireReason(v); r.fields.paused = true; r.fields.listingsPaused = true; r.fields.pauseWords = 'Paused'; log(r, ctx, `Paused: ${str(v.reason)}`); }),
    staffAction('restore', 'Restore listings', 'primary', (r) => (r.fields.paused === true ? null : 'The listings were not paused by staff.'), 'The seller’s paused listings in this scope go live again.', false,
      (r, v, ctx) => { requireReason(v); r.fields.paused = false; r.fields.listingsPaused = false; r.fields.pauseWords = 'Live'; log(r, ctx, `Restored: ${str(v.reason)}`); }),
    staffAction('revoke', 'Withdraw (cannot apply)', 'danger', () => null, 'The seller loses this approval and cannot apply again before the date you set. This cannot be appealed.', true,
      (r, v, ctx) => { requireReason(v); r.fields.status = 'NOT_AVAILABLE'; r.fields.listingsPaused = true; r.fields.pauseWords = 'Paused'; log(r, ctx, `Withdrawn: ${str(v.reason)}`); })
  ]
};

/* ---- the rules ---- */

const rulesForm = (r: MockRecord): StaffForm =>
  form(`Edit ${str(r.fields.label)}`, 'Save', [
    field('label', 'Name shown to sellers', 'text', true, { maxLength: 200, value: str(r.fields.label) }),
    field('level', 'What sellers need', 'select', true, { options: Object.entries(LEVEL_WORDS).map(([value, label]) => ({ value, label })), value: str(r.fields.level) }),
    field('required', 'Documents required', 'multiselect', false, { options: DOCUMENT_KINDS }),
    field('sellerReason', 'Reason shown to sellers (Not available)', 'textarea', false, { maxLength: 500 }),
    field('reapplyAfterDays', 'Days before a disqualified seller may apply again (suggested)', 'number', true, { min: 1, max: 730, value: num(r.fields.reapplyAfterDays) }),
    field('reason', 'Reason for this change (kept in the audit trail)', 'textarea', true, { maxLength: 500, pattern: '^[\\s\\S]{10,}$', help: 'Give a reason of at least 10 characters' })
  ], 'A change that makes a scope stricter pauses the listings of sellers who do not qualify (never deletes them); a relaxed rule brings them back.');

export const sellingRulesDef: MockResource = {
  title: 'Selling rules',
  columns: [
    { key: 'label', label: 'Rule', kind: 'strong', width: 26 }, { key: 'scopeTypeWords', label: 'Applies to', kind: 'text', width: 16 }, { key: 'levelWords', label: 'Level', kind: 'text', width: 18 }, { key: 'docsWords', label: 'Documents', kind: 'text', width: 30 },
    { key: 'activeWords', label: 'State', kind: 'text', width: 8 }, { key: 'sourceWords', label: 'Source', kind: 'text', width: 8 }, { key: 'updatedAt', label: 'Changed', kind: 'date', width: 10 }
  ],
  statusKey: 'level',
  facets: [{ value: '', label: 'All' }, { value: 'APPLY', label: 'Apply to sell' }, { value: 'NOT_AVAILABLE', label: 'Not available' }, { value: 'SELL', label: 'Open' }],
  search: ['label', 'scopeKey'],
  searchPlaceholder: 'Search a category, brand or rule',
  exportable: false,
  titleOf: (r) => str(r.fields.label),
  subtitleOf: (r) => `${str(r.fields.scopeTypeWords)} · ${str(r.fields.levelWords)}`,
  sections: [['Rule', ['scopeTypeWords', 'scopeKey', 'levelWords']], ['Documents the seller uploads', ['docsWords']]],
  actions: [],
  edit: {
    permission: 'selling-rules:manage',
    form: rulesForm,
    apply: (r, v, ctx) => {
      requireReason(v);
      r.fields.label = str(v.label);
      r.fields.level = str(v.level);
      r.fields.levelWords = LEVEL_WORDS[str(v.level)] ?? '';
      r.fields.reapplyAfterDays = num(v.reapplyAfterDays);
      r.fields.version = num(r.fields.version) + 1;
      if (ctx) r.activity.unshift({ at: ctx.now.toISOString(), actor: ctx.actor, text: `Update: ${str(v.reason)}` });
    }
  },
  create: {
    permission: 'selling-rules:manage',
    form: form('New selling rule', 'Create rule', [
      field('scopeType', 'What the rule is about', 'select', true, { options: Object.entries(SCOPE_WORDS).filter(([k]) => k !== 'CAPABILITY').map(([value, label]) => ({ value, label })), value: 'BRAND' }),
      field('key', 'Brand name, code or barcode', 'text', true, { maxLength: 200 }),
      field('level', 'What sellers need', 'select', true, { options: Object.entries(LEVEL_WORDS).map(([value, label]) => ({ value, label })), value: 'APPLY' }),
      field('required', 'Documents required', 'multiselect', false, { options: DOCUMENT_KINDS, value: ['BRAND_AUTHORIZATION', 'PURCHASE_INVOICE', 'PHOTOS_ALL_SIDES'] }),
      field('reason', 'Reason (kept in the audit trail)', 'textarea', true, { maxLength: 500, pattern: '^[\\s\\S]{10,}$', help: 'Give a reason of at least 10 characters' })
    ]),
    build: (v, id, ctx) => {
      requireReason(v);
      const type = str(v.scopeType) || 'BRAND';
      const level = str(v.level) || 'APPLY';
      return { id, fields: { scopeKey: str(v.key).toLowerCase().replace(/[^a-z0-9]+/g, ''), scopeType: type, scopeTypeWords: SCOPE_WORDS[type] ?? '', label: str(v.key), level, levelWords: LEVEL_WORDS[level] ?? '', docsWords: Array.isArray(v.required) ? v.required.join(', ') : 'None',
        activeWords: 'On', sourceWords: 'Staff', active: true, reapplyAfterDays: 30, updatedAt: ctx.now.toISOString(), version: 0 }, activity: [{ at: ctx.now.toISOString(), actor: ctx.actor, text: `Create: ${str(v.reason)}` }] };
    }
  }
};
