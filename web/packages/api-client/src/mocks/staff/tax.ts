/* Mock of Fees & commissions → Sales tax (/v1/staff/tax/**, the BFF's category tax tools over tax-returns): the real 73
   categories and 105 decision rows from tools/taxonomy/category_tax_map.json (see scripts/sync-tax-fixture.mjs), a review
   queue of variants with evidence, provider code maps for Stripe and Avalara, the tax method with its SHADOW provider and a
   shadow report. Validation, plain labels and permissions follow the BFF (staff_tax*.go). */
import { http } from 'msw';
import { z } from 'zod';

import {
  TAX_AUDIT_SAMPLE,
  type TaxCodeEntry,
  type TaxCodeMap,
  type TaxCodeMapImportResult,
  type TaxMethod,
  type TaxProfile,
  type TaxProfilePage,
  type TaxProfileRow,
  type TaxReview,
  type TaxReviewPage,
  type TaxReviewRow,
  type TaxReviewSettings,
  type TaxShadow} from '../../staff-tax';
import fixture from './fixtures/category-tax-map.json';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';
import {
  AUDIT_ACTION,
  describeRow,
  GATE_ORDER,
  GATES,
  humanizeCode,
  OUTCOME,
  outcomeName,
  OWNER,
  PROVIDER,
  RECORD_STATUS,
  requiredText,
  RESOLVE_STATUS,
  type TaxFactDef
} from './tax-text';

/* ── Dataset ─────────────────────────────────────────── */

interface Row {
  id: string;
  when: unknown;
  condition: string;
  outcome: string;
  internalTaxCode: string;
  stripeCode: string;
  rateDisplay: string;
  note: string | null;
}
interface Category {
  code: string;
  department: string;
  name: string;
  stripeCodes: string[];
  conflictingCodes: string[];
  defaultOutcome: string;
  status: string;
  statusReasons: { status: string; decision: number; text: string }[];
  facts: { fact: string; required: boolean; requiredWhen: unknown }[];
  decisionTable: Row[];
  note: string | null;
}

const DATA = fixture as unknown as {
  ruleVersion: string;
  departments: { code: string; name: string }[];
  facts: TaxFactDef[];
  categories: Category[];
};
export const TAX_RULE_VERSION = DATA.ruleVersion;
const FACT_DEFS = new Map(DATA.facts.map((f) => [f.id, f]));
const DEPARTMENT = new Map(DATA.departments.map((d) => [d.code, d.name]));

/* ── State ───────────────────────────────────────────── */

interface Change {
  at: string;
  changedByName: string;
  status: string;
  reason: string;
  approvedBy?: string;
  decisionRef?: string;
}
interface MockVariant {
  atin: string;
  gtin: string;
  title: string;
  sellers: string[];
  categoryCode: string;
  status: keyof typeof RECORD_STATUS;
  facts: Record<string, unknown>;
  behaviour: Record<string, unknown>;
  evidence: { factId: string; fileName: string; url: string; uploadedByName: string; uploadedAt: string }[];
  rowId: string;
  resolveStatus: string;
  refined: Record<string, string>;
  classifiedByName: string;
  classifiedAt: string;
  reviewedByName: string;
  reviewedAt: string;
  ordersAffected?: number;
  /** Approved by the system under the risk-based rule and waiting in the Audit sample. */
  auditSample?: boolean;
  audit: { at: string; actorName: string; action: string; reason: string }[];
}
interface MockCode {
  providerCode: string;
  verified: boolean;
  note: string;
  version: number;
  changedByName: string;
  changedAt: string;
}
export interface MockTaxState {
  profiles: Record<string, { status: string; changedByName: string; changedAt: string; history: Change[] }>;
  variants: MockVariant[];
  codes: Record<'STRIPE' | 'AVALARA', Record<string, MockCode>>;
  method: { method: 'RULES' | 'STRIPE_TAX' | 'AVALARA'; shadowProvider: '' | 'STRIPE' | 'AVALARA'; stripeAvailable: boolean; avalaraAvailable: boolean; changedByName: string; changedAt: string };
  history: { at: string; changedByName: string; fromLabel: string; toLabel: string; reason: string }[];
  /** How tax review works (docs/engineering/listing-rules.md D7): ALL until staff change it. */
  review: { mode: 'ALL' | 'RISK_BASED'; alwaysReview: string[]; auditPercent: number; changedByName: string; changedAt: string; history: TaxReviewSettings['history'] };
  /** When false the mock answers request-information like a tax-returns that has no such route yet (503). */
  requestInformation: boolean;
}

const DEFAULT_ALWAYS_REVIEW = ['baby-food-formula', 'dietary-supplements', 'gift-cards', 'medical-devices', 'medicated-skincare', 'otc-medicines'];
const AUTO_APPROVAL = 'Automatic approval (risk-based rule)';

const ago = (days: number, hours = 0) => new Date(Date.now() - days * 86_400_000 - hours * 3_600_000).toISOString();
const rows = (c: Category) => c.decisionTable;
const categoryOf = (code: string) => DATA.categories.find((c) => c.code === code);
const ALL_ROWS = DATA.categories.flatMap((c) => rows(c).map((r) => ({ category: c, row: r })));

function seedVariants(): MockVariant[] {
  const v = (x: Partial<MockVariant> & Pick<MockVariant, 'atin' | 'title' | 'sellers' | 'categoryCode' | 'status'>): MockVariant => ({
    gtin: '',
    facts: {},
    behaviour: {},
    evidence: [],
    rowId: '',
    resolveStatus: 'RESOLVED',
    refined: {},
    classifiedByName: x.sellers[0] ?? 'Seller',
    classifiedAt: ago(1, 3),
    reviewedByName: '',
    reviewedAt: '',
    audit: [{ at: ago(1, 3), actorName: x.sellers[0] ?? 'Seller', action: 'SUBMITTED', reason: '' }],
    ...x
  });
  return [
    v({
      atin: 'AT20000101',
      gtin: '00812345678905',
      title: 'Roasted cashews, 6 oz bag',
      sellers: ['Mama Nkechi’s Kitchen'],
      categoryCode: 'nuts-seeds',
      status: 'PENDING_REVIEW',
      facts: { is_candy: false, candy_coated: false, net_weight_oz: 6, servings_per_package: 4, individual_serving: false },
      rowId: 'S007',
      evidence: [
        { factId: 'label_evidence', fileName: 'cashews-label.png', url: 'https://placehold.co/320x200/png?text=Nutrition+label', uploadedByName: 'Nkechi Obi', uploadedAt: ago(1, 3) },
        { factId: 'label_evidence', fileName: 'supplier-spec.pdf', url: 'https://example.com/supplier-spec.pdf', uploadedByName: 'Nkechi Obi', uploadedAt: ago(1, 3) }
      ]
    }),
    v({
      atin: 'AT20000102',
      title: 'Yoruba for beginners (e-book)',
      sellers: ['Lagos Books Ltd'],
      categoryCode: 'downloadable-ebooks',
      status: 'PENDING_REVIEW',
      facts: { delivery_method: 'DOWNLOAD' },
      resolveStatus: 'NEEDS_APPROVAL',
      ordersAffected: 0
    }),
    v({
      atin: 'AT20000103',
      title: 'Hand-woven Kente table runner',
      sellers: ['Accra Crafts Collective'],
      categoryCode: 'handmade-products',
      status: 'PENDING_REVIEW',
      facts: {},
      rowId: rows(categoryOf('handmade-products') ?? DATA.categories[0]!)[0]?.id ?? '',
      resolveStatus: 'REFINE_CODE',
      refined: {},
      ordersAffected: 3
    }),
    v({
      atin: 'AT20000104',
      title: 'Plantain chips, 2 oz single serve',
      sellers: ['Naija Pantry Foods', 'Mama Nkechi’s Kitchen'],
      categoryCode: 'packaged-snacks',
      status: 'PENDING_REVIEW',
      facts: { is_candy: false, net_weight_oz: 2, servings_per_package: 1, individual_serving: true },
      rowId: rows(categoryOf('packaged-snacks') ?? DATA.categories[0]!)[1]?.id ?? '',
      audit: [
        { at: ago(4, 0), actorName: 'Naija Pantry Foods', action: 'SUBMITTED', reason: '' },
        { at: ago(0, 6), actorName: 'Ada Okafor', action: 'RECLASSIFIED', reason: 'Moved from nuts and seeds: it is a fried snack, not a nut product.' }
      ]
    }),
    v({
      atin: 'AT20000105',
      title: 'Sparkling hibiscus water, 12 oz',
      sellers: ['Zobo Brothers'],
      categoryCode: 'flavored-water-soda',
      status: 'PENDING_REVIEW',
      facts: {},
      resolveStatus: 'CONFLICT_OPEN'
    }),
    v({
      atin: 'AT20000106',
      title: 'Plantain flour, 2 lb bag',
      sellers: ['Accra Crafts Collective'],
      categoryCode: 'baby-food-formula',
      status: 'STALE',
      facts: {},
      rowId: rows(categoryOf('baby-food-formula') ?? DATA.categories[0]!)[0]?.id ?? '',
      reviewedByName: 'Ada Okafor',
      reviewedAt: ago(9, 2),
      audit: [
        { at: ago(12, 0), actorName: 'Accra Crafts Collective', action: 'SUBMITTED', reason: '' },
        { at: ago(9, 2), actorName: 'Ada Okafor', action: 'APPROVED', reason: 'Label and weight match the listing.' },
        { at: ago(1, 0), actorName: 'System', action: 'MARKED_STALE', reason: 'The category changed after approval.' }
      ]
    }),
    v({
      atin: 'AT20000107',
      title: 'Jollof seasoning blend, 4 oz',
      sellers: ['Naija Pantry Foods'],
      categoryCode: 'packaged-snacks',
      status: 'APPROVED',
      facts: { is_candy: false, net_weight_oz: 4, servings_per_package: 8, individual_serving: false },
      rowId: rows(categoryOf('packaged-snacks') ?? DATA.categories[0]!)[0]?.id ?? '',
      reviewedByName: 'Ada Okafor',
      reviewedAt: ago(2, 0),
      audit: [
        { at: ago(3, 0), actorName: 'Naija Pantry Foods', action: 'SUBMITTED', reason: '' },
        { at: ago(2, 0), actorName: 'Ada Okafor', action: 'APPROVED', reason: 'Servings and weight match the label photo.' }
      ]
    }),
    v({
      atin: 'AT20000109',
      title: 'Shea butter body cream, 8 oz',
      sellers: ['Accra Crafts Collective'],
      categoryCode: 'skincare-cosmetics',
      status: 'APPROVED',
      auditSample: true,
      rowId: rows(categoryOf('skincare-cosmetics') ?? DATA.categories[0]!)[0]?.id ?? '',
      classifiedByName: AUTO_APPROVAL,
      reviewedByName: AUTO_APPROVAL,
      reviewedAt: ago(1, 0),
      audit: [
        { at: ago(1, 1), actorName: 'Accra Crafts Collective', action: 'SUBMITTED', reason: '' },
        { at: ago(1, 0), actorName: AUTO_APPROVAL, action: 'APPROVED', reason: 'Risk-based review: the category Skincare & cosmetics is ACTIVE, the rules resolved cleanly (TAXABLE), no required evidence is missing and the category is not on the always-review list.' },
        { at: ago(1, 0), actorName: AUTO_APPROVAL, action: 'AUDIT_SAMPLED', reason: 'Placed in the audit sample (5% of auto-approved items, picked by a fixed hash of the variant ATIN). It stays live until a reviewer confirms or reclassifies it.' }
      ]
    }),
    v({
      atin: 'AT20000108',
      title: 'Ankara print tote bag',
      sellers: ['Accra Crafts Collective'],
      categoryCode: 'handmade-products',
      status: 'REJECTED',
      facts: {},
      resolveStatus: 'REFINE_CODE',
      audit: [
        { at: ago(6, 0), actorName: 'Accra Crafts Collective', action: 'SUBMITTED', reason: '' },
        { at: ago(5, 0), actorName: 'Ada Okafor', action: 'REJECTED', reason: 'Please add what the bag is made of so we can pick the right code.' }
      ]
    })
  ];
}

export function seedTax(): MockTaxState {
  const profiles: MockTaxState['profiles'] = {};
  DATA.categories.forEach((c) => (profiles[c.code] = { status: c.status, changedByName: 'Platform Admin', changedAt: ago(8), history: [] }));
  const codes: MockTaxState['codes'] = { STRIPE: {}, AVALARA: {} };
  ALL_ROWS.forEach(({ row }, i) => {
    const stripe = row.outcome === 'BLOCKED' ? '' : row.stripeCode;
    codes.STRIPE[row.internalTaxCode] = { providerCode: stripe, verified: !!stripe && i % 5 !== 0 && row.stripeCode !== 'txcd_99999999', note: '', version: 1, changedByName: 'Ada Okafor', changedAt: ago(5) };
    codes.AVALARA[row.internalTaxCode] = { providerCode: '', verified: false, note: '', version: 0, changedByName: '', changedAt: '' };
  });
  return {
    profiles,
    variants: seedVariants(),
    codes,
    method: { method: 'RULES', shadowProvider: 'STRIPE', stripeAvailable: true, avalaraAvailable: false, changedByName: 'Ada Okafor', changedAt: ago(3) },
    review: { mode: 'ALL', alwaysReview: [...DEFAULT_ALWAYS_REVIEW], auditPercent: 5, changedByName: '', changedAt: '', history: [] },
    history: [
      { at: ago(3), changedByName: 'Ada Okafor', fromLabel: 'Stripe Tax', toLabel: 'Our own rules', reason: 'Back to our own rules while the provider codes are mapped.' },
      { at: ago(20), changedByName: 'Platform Admin', fromLabel: 'Our own rules', toLabel: 'Stripe Tax', reason: 'Trying Stripe Tax in the test environment.' }
    ],
    requestInformation: true
  };
}

/* ── Views ───────────────────────────────────────────── */

const profileRow = (state: MockTaxState, c: Category): TaxProfileRow => {
  const status = state.profiles[c.code]?.status ?? c.status;
  const decision = c.statusReasons.find((r) => r.status === status)?.decision;
  return {
    categoryCode: c.code,
    name: c.name,
    department: c.department,
    departmentName: DEPARTMENT.get(c.department) ?? humanizeCode(c.department),
    status: status as TaxProfileRow['status'],
    statusLabel: GATES[status]?.label ?? humanizeCode(status),
    statusExplain: GATES[status]?.explain ?? '',
    stripeCodes: c.stripeCodes,
    stripeCodeList: c.stripeCodes.join(', '),
    defaultOutcome: c.defaultOutcome,
    defaultOutcomeLabel: c.defaultOutcome === 'CONDITIONAL' ? 'Depends on the answers' : (OUTCOME[c.defaultOutcome] ?? ''),
    scenarioCount: rows(c).length,
    ruleVersion: DATA.ruleVersion,
    openDecision: decision ?? null
  };
};

const factMap = (c: Category): Map<string, TaxFactDef> => {
  const m = new Map<string, TaxFactDef>();
  c.facts.forEach((f) => {
    const def = FACT_DEFS.get(f.fact);
    if (def) m.set(def.id, def);
  });
  return m;
};

function actionsOf(status: string, can: boolean): TaxProfile['actions'] {
  const mk = (key: 'approve' | 'reopen', label: string, to: 'ACTIVE' | 'NEEDS_APPROVAL', signOff: boolean, explain: string, ok: boolean, why: string): TaxProfile['actions'][number] => ({
    key,
    label,
    status: to,
    needsSignOff: signOff,
    explain,
    available: ok && can,
    why: !can ? 'Needs the tax:classify-admin permission.' : ok ? '' : why
  });
  const approveText = 'Lifts the block after the Texas tax professional’s sign-off. Records who approved and the reference, and applies from the next rule version.';
  const reopenText = 'Takes the category back to needing sign-off. Products already live in it leave checkout until they are reviewed again.';
  if (['NEEDS_APPROVAL', 'CONFLICT_OPEN', 'BLOCKED_BUNDLE'].includes(status))
    return [mk('approve', 'Mark approved', 'ACTIVE', true, approveText, true, ''), mk('reopen', 'Reopen for approval', 'NEEDS_APPROVAL', false, reopenText, false, 'Already waiting for sign-off.')];
  if (status === 'ACTIVE')
    return [mk('approve', 'Mark approved', 'ACTIVE', true, approveText, false, 'Already approved.'), mk('reopen', 'Reopen for approval', 'NEEDS_APPROVAL', false, reopenText, true, '')];
  return [
    mk('approve', 'Mark approved', 'ACTIVE', true, approveText, false, 'The tax engine owns this gate.'),
    mk('reopen', 'Reopen for approval', 'NEEDS_APPROVAL', false, reopenText, false, 'The tax engine owns this gate.')
  ];
}

const READ_ONLY_REFINE =
  'This gate is lifted product by product: tax review verifies the specific provider code on each product, then approves it. It is not changed for the whole category.';

function profileView(state: MockTaxState, c: Category, can: boolean): TaxProfile {
  const facts = factMap(c);
  const row = profileRow(state, c);
  const notes: TaxProfile['notes'] = [];
  rows(c).forEach((r) => {
    if (r.note) notes.push({ kind: 'RULE', rowId: r.id, title: `Rule ${r.id}: ${r.condition}`, text: r.note });
  });
  c.statusReasons.forEach((r) => notes.push({ kind: 'GATE', title: GATES[r.status]?.label ?? r.status, decision: r.decision, text: r.text }));
  if (c.note) notes.push({ kind: 'CATEGORY', title: 'Note on this category', text: c.note });
  const p = state.profiles[c.code];
  return {
    ...row,
    conflictingCodes: c.conflictingCodes,
    facts: c.facts.map((ref) => {
      const def = FACT_DEFS.get(ref.fact)!;
      return {
        id: def.id,
        label: def.label,
        kind: def.kind,
        unit: def.unit ?? '',
        help: def.help ?? '',
        owner: def.owner,
        ownerLabel: OWNER[def.owner] ?? '',
        required: ref.required,
        needed: requiredText({ required: ref.required, requiredWhen: ref.requiredWhen as never }, facts),
        evidence: !!def.evidence
      };
    }),
    decisions: rows(c).map((r) => ({
      id: r.id,
      sentence: describeRow({ when: r.when as never, outcome: r.outcome }, facts),
      condition: r.condition,
      outcome: r.outcome,
      outcomeLabel: OUTCOME[r.outcome] ?? r.outcome,
      internalTaxCode: r.internalTaxCode,
      stripeCode: r.stripeCode,
      rateDisplay: r.rateDisplay
    })),
    notes,
    history: (p?.history ?? []).map((h) => ({ changedAt: h.at, changedByName: h.changedByName, status: GATES[h.status]?.label ?? h.status, reason: h.reason, approvedBy: h.approvedBy, decisionRef: h.decisionRef })).reverse(),
    changedByName: p?.changedByName ?? '',
    changedAt: p?.changedAt ?? '',
    canChange: can,
    actions: actionsOf(row.status, can),
    readOnlyReason: row.status === 'REFINE_CODE' ? READ_ONLY_REFINE : !can ? 'You can read this profile. Changing it needs the tax:classify-admin permission.' : ''
  };
}

const proposedOf = (v: MockVariant) => {
  const c = categoryOf(v.categoryCode);
  const r = c ? rows(c).find((x) => x.id === v.rowId) : undefined;
  return {
    status: v.resolveStatus,
    statusLabel: RESOLVE_STATUS[v.resolveStatus] ?? '',
    outcome: r?.outcome ?? '',
    outcomeLabel: r ? (OUTCOME[r.outcome] ?? '') : '',
    internalTaxCode: r?.internalTaxCode ?? '',
    rateDisplay: r?.rateDisplay ?? '',
    rowId: r?.id ?? '',
    reason: '',
    ruleVersion: DATA.ruleVersion,
    because: ''
  };
};

function whyOf(v: MockVariant): string {
  if (v.auditSample && v.status === 'APPROVED') return 'The system approved this under the risk-based rule and it is live. Check the category and the answers, then confirm it or move it to the right category.';
  switch (v.status) {
    case 'STALE':
      return 'The product’s facts or category changed after it was approved, so the old decision no longer holds.';
    case 'REJECTED':
      return 'Sent back to the seller. It returns here when they change it.';
    case 'MISSING_FACTS':
    case 'DRAFT':
      return 'The seller still has to answer the tax questions.';
    case 'APPROVED':
      return 'Approved: the stored decision is used at checkout.';
    default:
  }
  switch (v.resolveStatus) {
    case 'NEEDS_APPROVAL':
      return 'The category needs the Texas tax professional’s sign-off before anything in it can go live.';
    case 'REFINE_CODE':
      return 'The category uses a generic provider code. Verify the specific code for this product.';
    case 'CONFLICT_OPEN':
      return 'The category waits on an open decision about which code applies.';
    case 'BLOCKED':
      return 'As described this can’t be sold: a mixed bundle must be itemised.';
    default:
  }
  if (v.evidence.length > 0) return 'The seller claims a tax exemption and attached proof. Check the proof matches the product.';
  if (v.audit.at(-1)?.action === 'RECLASSIFIED') return 'Moved to this category. Check that the answers still fit.';
  return 'New or changed listing: check the category, the answers and the tax decision.';
}

const reviewRow = (v: MockVariant): TaxReviewRow => ({
  atin: v.atin,
  gtin: v.gtin,
  title: v.title,
  sellers: v.sellers,
  categoryCode: v.categoryCode,
  categoryName: categoryOf(v.categoryCode)?.name ?? humanizeCode(v.categoryCode),
  status: v.status,
  statusLabel: RECORD_STATUS[v.status] ?? v.status,
  why: whyOf(v),
  proposed: proposedOf(v),
  classifiedAt: v.classifiedAt,
  classifiedByName: v.classifiedByName,
  reviewedAt: v.reviewedAt,
  reviewedByName: v.reviewedByName,
  ruleVersion: DATA.ruleVersion,
  auditSample: !!v.auditSample && v.status === 'APPROVED',
  autoApproved: v.status === 'APPROVED' && v.reviewedByName === AUTO_APPROVAL
});

const IMAGE = /\.(png|jpe?g|webp|gif)$/i;

function reviewView(state: MockTaxState, v: MockVariant, can: boolean): TaxReview {
  const c = categoryOf(v.categoryCode);
  const facts = c ? factMap(c) : new Map<string, TaxFactDef>();
  const base = reviewRow(v);
  const row = c ? rows(c).find((r) => r.id === v.rowId) : undefined;
  const status = state.profiles[v.categoryCode]?.status ?? '';
  const factRows = (c?.facts ?? []).flatMap((ref) => {
    const val = v.facts[ref.fact];
    const def = FACT_DEFS.get(ref.fact);
    if (val === undefined || !def) return [];
    const text = typeof val === 'boolean' ? (val ? 'yes' : 'no') : typeof val === 'number' ? `${val}${def.unit ? (def.unit === '%' ? '%' : ` ${def.unit}`) : ''}` : (def.choices?.find((x) => x.value === val)?.label ?? String(val));
    return [{ id: def.id, label: def.label, value: text, owner: OWNER[def.owner] ?? '' }];
  });
  return {
    ...base,
    departmentName: c ? (DEPARTMENT.get(c.department) ?? '') : '',
    productTypeCode: v.categoryCode,
    profileStatus: status,
    profileStatusLabel: GATES[status]?.label ?? '',
    proposed: { ...base.proposed, because: row ? describeRow({ when: row.when as never, outcome: row.outcome }, facts) : '' },
    facts: factRows,
    sellerListings: v.sellers.map((s) => ({ sellerName: s, answers: [], outcomeLabel: base.proposed.outcomeLabel, statusLabel: base.proposed.statusLabel })),
    evidence: v.evidence.map((e) => ({ factId: e.factId, factLabel: FACT_DEFS.get(e.factId)?.label ?? humanizeCode(e.factId), fileName: e.fileName, url: e.url, isImage: IMAGE.test(e.fileName), uploadedByName: e.uploadedByName, uploadedAt: e.uploadedAt })),
    decision: { reviewedByName: v.reviewedByName, reviewedAt: v.reviewedAt, ruleVersion: DATA.ruleVersion },
    audit: [...v.audit].reverse().map((a) => ({ at: a.at, actorName: a.actorName, action: a.action, actionLabel: AUDIT_ACTION[a.action] ?? humanizeCode(a.action), reason: a.reason, ruleVersion: DATA.ruleVersion })),
    canDecide: can,
    canApprove: can && (v.status === 'PENDING_REVIEW' || v.status === 'STALE'),
    canConfirm: can && v.status === 'APPROVED' && !!v.auditSample,
    approve: {
      staffFacts: (c?.facts ?? []).flatMap((ref) => {
        const def = FACT_DEFS.get(ref.fact);
        return def?.owner === 'STAFF' ? [{ id: def.id, label: def.label, kind: def.kind, help: def.help ?? '', value: v.facts[def.id] }] : [];
      }),
      needsRefinedCode: status === 'REFINE_CODE',
      refinedProviders: status === 'REFINE_CODE' ? (['STRIPE', 'AVALARA'] as const).map((p) => ({ provider: p, label: PROVIDER[p] ?? p, value: v.refined[p] ?? '', required: p === 'STRIPE' })) : []
    },
    refinedProviderCodes: v.refined,
    ordersAffected: v.ordersAffected
  };
}

/* ── Code map ────────────────────────────────────────── */

const STATE_LABEL: Record<string, string> = { NOT_NEEDED: 'Not needed (never sold)', UNMAPPED: 'No code yet', UNVERIFIED: 'Code set, not verified', VERIFIED: 'Verified' };
const stateOf = (outcome: string, c: MockCode): TaxCodeEntry['state'] => (outcome === 'BLOCKED' ? 'NOT_NEEDED' : !c.providerCode ? 'UNMAPPED' : c.verified ? 'VERIFIED' : 'UNVERIFIED');

function codeEntry(state: MockTaxState, provider: 'STRIPE' | 'AVALARA', internal: string): TaxCodeEntry | null {
  const hit = ALL_ROWS.find((x) => x.row.internalTaxCode === internal);
  const c = state.codes[provider][internal];
  if (!hit || !c) return null;
  const st = stateOf(hit.row.outcome, c);
  return {
    internalTaxCode: internal,
    categoryCode: hit.category.code,
    categoryName: hit.category.name,
    outcome: hit.row.outcome,
    outcomeLabel: OUTCOME[hit.row.outcome] ?? hit.row.outcome,
    outcomeName: outcomeName(internal),
    provider,
    providerCode: c.providerCode,
    verified: c.verified,
    state: st,
    stateLabel: STATE_LABEL[st] ?? st,
    note: c.note,
    mapVersion: c.version,
    changedByName: c.changedByName,
    changedAt: c.changedAt
  };
}

function completeness(state: MockTaxState, provider: 'STRIPE' | 'AVALARA'): TaxCodeMap['completeness'] {
  const entries = ALL_ROWS.map((x) => codeEntry(state, provider, x.row.internalTaxCode)!).filter((e) => e.state !== 'NOT_NEEDED');
  const verified = entries.filter((e) => e.state === 'VERIFIED').length;
  const mapped = entries.filter((e) => e.state !== 'UNMAPPED').length;
  return { needed: entries.length, mapped, verified, unmapped: entries.length - mapped, unverified: mapped - verified, percent: entries.length ? Math.floor((verified * 100) / entries.length) : 100, complete: entries.length > 0 && verified === entries.length };
}

const CODE_PATTERN = { STRIPE: /^txcd_[0-9]{8}$/, AVALARA: /^[A-Za-z0-9._-]{1,25}$/ } as const;
const CODE_HELP = { STRIPE: 'A Stripe Tax code looks like txcd_40040016.', AVALARA: 'An Avalara tax code is letters and digits, up to 25 characters, e.g. PF050099.' } as const;

/* ── Shadow report ───────────────────────────────────── */

const SHADOW_KINDS: Record<string, string> = {
  OUTCOME: 'Different outcome (exempt against taxable)',
  RATE: 'Different rate',
  BOTH: 'Different outcome and rate',
  PROVIDER_ERROR: 'The provider did not answer',
  NO_DECISION: 'We had no stored decision'
};

function shadowView(state: MockTaxState, provider: string, page: number, size: number, can: boolean, from: string, to: string): TaxShadow {
  if (!provider)
    return { provider: '', providerLabel: '', from, to, shadowOff: true, canConfigure: can, message: 'Shadow mode is off. Turn it on in Who calculates tax to compare a provider with our own rules on real orders.', linesCompared: 0, linesDiffering: 0, differingPct: 0, absoluteDifferenceText: '', netDifferenceText: '', byCode: [], byCategory: [], byOutcome: [], byState: [], kinds: [], items: [], total: 0, page, size, partial: false, runbookPath: 'docs/runbooks/tax-provider-cutover.md' };
  const sample: { code: string; lines: number; differing: number; kind: string; inH: string; pr: string; state: string; cents: number }[] = [
    { code: 'AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS', lines: 64, differing: 3, kind: 'OUTCOME', inH: 'Exempt (no tax)', pr: 'Taxable at 8.25%, tax $0.83', state: 'TX', cents: 83 },
    { code: 'AT.PACKAGED_SNACKS.STANDARD', lines: 88, differing: 2, kind: 'RATE', inH: 'Taxable at 8.25%, tax $0.75', pr: 'Taxable at 8%, tax $0.73', state: 'TX', cents: -2 },
    { code: 'AT.BABY_FOOD_FORMULA.STANDARD', lines: 41, differing: 0, kind: 'RATE', inH: '', pr: '', state: 'TX', cents: 0 },
    { code: 'AT.FLAVORED_WATER_SODA.STANDARD', lines: 12, differing: 1, kind: 'PROVIDER_ERROR', inH: 'Taxable at 8.25%, tax $0.50', pr: 'No answer: Stripe timed out', state: 'TX', cents: 0 }
  ];
  const known = sample.filter((s) => ALL_ROWS.some((x) => x.row.internalTaxCode === s.code));
  const byCode = known.map((s) => {
    const hit = ALL_ROWS.find((x) => x.row.internalTaxCode === s.code)!;
    return { internalTaxCode: s.code, providerCode: hit.row.stripeCode, categoryName: hit.category.name, outcomeLabel: OUTCOME[hit.row.outcome] ?? '', lines: s.lines, differing: s.differing, differingPct: Math.round((s.differing / s.lines) * 10_000) / 100 };
  });
  const diffs = known.filter((s) => s.differing > 0).flatMap((s, i) =>
    Array.from({ length: s.differing }, (_, j) => ({
      orderId: `ord-${i}-${j}`,
      orderNumber: `AT-00${2310 + i * 5 + j}`,
      lineId: `l-${i}-${j}`,
      atin: 'AT20000101',
      internalTaxCode: s.code,
      categoryName: ALL_ROWS.find((x) => x.row.internalTaxCode === s.code)!.category.name,
      providerCode: ALL_ROWS.find((x) => x.row.internalTaxCode === s.code)!.row.stripeCode,
      kind: s.kind,
      kindLabel: SHADOW_KINDS[s.kind] ?? s.kind,
      inHouseText: s.inH,
      providerText: s.pr,
      differenceMinor: s.cents,
      differenceText: `${s.cents < 0 ? '-' : ''}$${(Math.abs(s.cents) / 100).toFixed(2)}`,
      quotedAt: ago(i + 1)
    }))
  );
  const lines = known.reduce((n, s) => n + s.lines, 0);
  const differing = known.reduce((n, s) => n + s.differing, 0);
  const cat = (k: string) => known.filter((s) => s.code.split('.')[1] === k);
  const byCategory = [...new Set(known.map((s) => s.code.split('.')[1]!))].map((k) => {
    const l = cat(k).reduce((n, s) => n + s.lines, 0);
    const d = cat(k).reduce((n, s) => n + s.differing, 0);
    return { key: k.toLowerCase().replace(/_/g, '-'), label: humanizeCode(k), lines: l, differing: d, differingPct: Math.round((d / l) * 10_000) / 100 };
  });
  const kinds = ['OUTCOME', 'BOTH', 'RATE', 'NO_DECISION', 'PROVIDER_ERROR'].map((k) => ({ kind: k, label: SHADOW_KINDS[k] ?? k, count: diffs.filter((d) => d.kind === k).length }));
  const outcomeDiff = kinds.filter((k) => ['OUTCOME', 'BOTH', 'NO_DECISION'].includes(k.kind)).reduce((n, k) => n + k.count, 0);
  const comp = completeness(state, provider as 'STRIPE' | 'AVALARA');
  const days = Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1;
  const checks = [
    { key: 'window', title: 'Long enough', ok: days >= 30, detail: `Compared ${days} days; at least 30 needed (a full filing month).` },
    { key: 'volume', title: 'Enough real orders', ok: lines >= 200, detail: `${lines} order lines compared; at least 200 needed.` },
    { key: 'outcomes', title: 'Same exempt or taxable answer', ok: outcomeDiff === 0, detail: `${outcomeDiff} lines where the provider disagreed on exempt or taxable, or we had no decision. Each must be fixed (re-map, refine the code) or accepted in writing by the tax adviser.` },
    { key: 'errors', title: 'Provider answers reliably', ok: (diffs.filter((d) => d.kind === 'PROVIDER_ERROR').length / Math.max(lines, 1)) * 100 <= 1, detail: `${diffs.filter((d) => d.kind === 'PROVIDER_ERROR').length} lines the provider did not answer; at most 1% allowed.` },
    { key: 'perCode', title: 'Every outcome seen often enough', ok: byCode.every((b) => b.lines >= 5), detail: `${byCode.filter((b) => b.lines < 5).length} of ${byCode.length} outcomes had fewer than 5 lines.` },
    { key: 'codes', title: 'Every code verified', ok: comp.complete, detail: `${comp.unmapped} outcomes have no code and ${comp.unverified} are not verified.` }
  ];
  const failing = checks.filter((c) => !c.ok).length;
  return {
    provider,
    providerLabel: PROVIDER[provider] ?? provider,
    from,
    to,
    shadowOff: false,
    canConfigure: can,
    message: '',
    linesCompared: lines,
    linesDiffering: differing,
    differingPct: Math.round((differing / lines) * 10_000) / 100,
    absoluteDifferenceText: '$1.66',
    netDifferenceText: '$1.64',
    byCode,
    byCategory,
    byOutcome: [
      { key: 'EXEMPT', label: 'Exempt (no tax)', lines: 105, differing: 3, differingPct: 2.86 },
      { key: 'TAXABLE', label: 'Taxable', lines: 100, differing: 3, differingPct: 3 }
    ],
    byState: [{ key: 'TX', label: 'TX', lines, differing, differingPct: Math.round((differing / lines) * 10_000) / 100 }],
    kinds,
    items: diffs.slice(page * size, page * size + size),
    total: diffs.length,
    page,
    size,
    partial: false,
    runbookPath: 'docs/runbooks/tax-provider-cutover.md',
    readiness: {
      ready: failing === 0,
      headline: failing === 0 ? 'Ready to cut over: every check below passes. The tax professional still signs off the cutover.' : `Not ready to cut over: ${failing} of ${checks.length} checks need work.`,
      checks,
      thresholdSource: 'DEFAULT',
      thresholds: { minDays: 30, minLines: 200, minLinesPerCode: 5, maxOutcomeDisagreementPct: 0, maxProviderErrorPct: 1 },
      rateNote: 'Rate differences are expected while local rates come from the ZIP table. Each one still has to be explained in the tax adviser’s notes.'
    }
  };
}

/* ── Method ──────────────────────────────────────────── */

const METHOD_TEXT: Record<string, { label: string; explain: string }> = {
  RULES: { label: 'Our own rules', explain: 'AfroTransact decides the tax itself. Each product’s stored tax decision says exempt or taxable, and our state and local rate tables give the rate. No provider is called and nothing costs per order. This is the default.' },
  STRIPE_TAX: { label: 'Stripe Tax', explain: 'Stripe Tax works out the tax amount. We still decide what each item is, and send Stripe the code you mapped to that outcome. A line without a verified code is blocked, never guessed.' },
  AVALARA: { label: 'Avalara', explain: 'Avalara works out the tax amount. We still decide what each item is, and send Avalara the code you mapped to that outcome. A line without a verified code is blocked, never guessed.' }
};

const configured = (state: MockTaxState, p: 'STRIPE' | 'AVALARA') => (p === 'STRIPE' ? state.method.stripeAvailable : state.method.avalaraAvailable);
const notSetUp = (p: 'STRIPE' | 'AVALARA') =>
  p === 'AVALARA'
    ? 'Avalara isn’t set up yet: its account details aren’t configured for this environment.'
    : 'Stripe Tax isn’t set up yet: its key isn’t configured, or the registrations aren’t done in the Stripe dashboard.';

function methodView(state: MockTaxState, can: boolean): TaxMethod {
  const m = state.method;
  const providers = (['STRIPE', 'AVALARA'] as const).map((p) => ({ provider: p, label: PROVIDER[p] ?? p, configured: configured(state, p), completeness: completeness(state, p) }));
  const options = (['RULES', 'STRIPE_TAX', 'AVALARA'] as const).map((k) => {
    const p = k === 'STRIPE_TAX' ? 'STRIPE' : k === 'AVALARA' ? 'AVALARA' : null;
    let available = true;
    let blockedReason = '';
    if (p) {
      const comp = providers.find((x) => x.provider === p)!.completeness!;
      if (!configured(state, p)) [available, blockedReason] = [false, notSetUp(p)];
      else if (!comp.complete) [available, blockedReason] = [false, `${comp.unmapped} outcomes have no ${PROVIDER[p]} code and ${comp.unverified} are not verified. Finish the Provider codes first.`];
    }
    return { method: k, label: METHOD_TEXT[k]!.label, explain: METHOD_TEXT[k]!.explain, available, blockedReason, current: k === m.method };
  });
  return {
    method: m.method,
    methodLabel: METHOD_TEXT[m.method]!.label,
    methodExplain: METHOD_TEXT[m.method]!.explain,
    shadowProvider: m.shadowProvider,
    shadowLabel: m.shadowProvider ? (PROVIDER[m.shadowProvider] ?? m.shadowProvider) : 'Off',
    activeProvider: m.method === 'STRIPE_TAX' ? 'STRIPE' : m.method === 'AVALARA' ? 'AVALARA' : m.shadowProvider,
    options,
    shadowOptions: [
      { value: '', label: 'Off', explain: 'No provider is asked in the background.', available: true, blockedReason: '' },
      ...(['STRIPE', 'AVALARA'] as const).map((p) => ({
        value: p,
        label: PROVIDER[p] ?? p,
        explain: `${PROVIDER[p]} is asked for the same lines on real orders. The answers are logged and compared. They never change what a customer pays.`,
        available: configured(state, p),
        blockedReason: configured(state, p) ? '' : notSetUp(p)
      }))
    ],
    providers,
    history: state.history,
    changedByName: m.changedByName,
    changedAt: m.changedAt,
    runbookPath: 'docs/runbooks/tax-provider-cutover.md',
    canConfigure: can,
    draftNotice: 'The tax rules are a draft. A Texas tax professional must approve them before they are used for real customers.'
  };
}

/* ── Handlers ────────────────────────────────────────── */

const Reason = (field: string, min = 10) => z.string().default('').refine((s) => [...s.trim()].length >= min, `${field === 'message' ? 'Tell the seller what you need' : 'Say why'} in at least ${min} characters.${field === 'message' ? '' : ' The reason is kept with your name.'}`);
const StatusBody = z.object({ status: z.enum(['ACTIVE', 'NEEDS_APPROVAL']), reason: Reason('reason'), approvedBy: z.string().optional(), decisionRef: z.string().optional() });
const ApproveBody = z.object({ reason: Reason('reason'), staffFacts: z.record(z.string(), z.unknown()).optional(), refinedProviderCodes: z.record(z.string(), z.string()).optional() });
const ReclassifyBody = z.object({ categoryCode: z.string().min(1, 'Choose the category it belongs in.'), productTypeCode: z.string().optional(), reason: Reason('reason') });
const InfoBody = z.object({ message: Reason('message') });
const CodeBody = z.object({ provider: z.enum(['STRIPE', 'AVALARA']), providerCode: z.string().nullish(), verified: z.boolean().default(false), reason: Reason('reason') });
const ImportBody = z.object({ provider: z.enum(['STRIPE', 'AVALARA']), csv: z.string().min(1, 'Choose a CSV file.'), dryRun: z.boolean().optional(), reason: z.string().optional() });
const ReviewSettingsBody = z.object({
  mode: z.enum(['ALL', 'RISK_BASED']),
  alwaysReview: z.array(z.string()).optional(),
  auditPercent: z.number().int().min(0, 'Use a whole number from 0 to 100.').max(100, 'Use a whole number from 0 to 100.').optional(),
  reason: Reason('reason')
});

const REVIEW_MODE_TEXT: Record<'ALL' | 'RISK_BASED', { label: string; explain: string }> = {
  ALL: { label: 'Review every item', explain: 'Every product variant waits for a tax reviewer before it can go live. This is the default and how tax review works today.' },
  RISK_BASED: {
    label: 'Review by risk',
    explain:
      'The system approves an item by itself only when its category is active, the tax rules give a clear answer, no required proof is missing and the category is not on the always-review list. Everything else waits for a tax reviewer. A share of the approved items is also put in the Audit sample to be checked by a person.'
  }
};
const REVIEW_WARNING =
  'Only switch to Review by risk after the Texas tax professional has agreed. It lets the system approve items without a person looking at them, and the tax rules are still a draft. Items in a category that needs sign-off, needs a specific code, waits on a decision or is a blocked bundle, items in the always-review list, and anything the rules can’t decide still wait for a tax reviewer. Checkout still blocks any item that has no clear stored tax decision.';

function reviewSummary(mode: 'ALL' | 'RISK_BASED', always: string[], percent: number): string {
  if (mode === 'ALL') return REVIEW_MODE_TEXT.ALL.label;
  const names = always.map((c) => categoryOf(c)?.name ?? humanizeCode(c)).sort();
  return `${REVIEW_MODE_TEXT.RISK_BASED.label}, ${percent}% audit sample${names.length ? `, always review: ${names.join(', ')}` : ', no always-review categories'}`;
}

function reviewSettingsView(state: MockTaxState, can: boolean): TaxReviewSettings {
  const r = state.review;
  return {
    mode: r.mode,
    modeLabel: REVIEW_MODE_TEXT[r.mode].label,
    modeExplain: REVIEW_MODE_TEXT[r.mode].explain,
    options: (['ALL', 'RISK_BASED'] as const).map((m) => ({ mode: m, label: REVIEW_MODE_TEXT[m].label, explain: REVIEW_MODE_TEXT[m].explain, current: m === r.mode })),
    categories: DATA.categories.map((c) => {
      const status = state.profiles[c.code]?.status ?? c.status;
      return { code: c.code, name: c.name, departmentName: DEPARTMENT.get(c.department) ?? '', alwaysReview: r.alwaysReview.includes(c.code), gated: status !== 'ACTIVE', gateLabel: GATES[status]?.label ?? '' };
    }),
    alwaysReviewNames: DATA.categories.filter((c) => r.alwaysReview.includes(c.code)).map((c) => c.name).sort(),
    alwaysReviewStatuses: ['NEEDS_APPROVAL', 'REFINE_CODE', 'CONFLICT_OPEN', 'BLOCKED_BUNDLE'].map((s) => ({ status: s, label: GATES[s]?.label ?? s })),
    auditPercent: r.auditPercent,
    auditExplain: `About ${r.auditPercent} in every 100 items the system approves are also put in the Audit sample. They stay live. A reviewer confirms each one or moves it to the right category, which closes it until it is reviewed again.`,
    history: r.history,
    changedByName: r.changedByName,
    changedAt: r.changedAt,
    canChange: can,
    warning: REVIEW_WARNING
  };
}

const MethodBody = z.object({ method: z.enum(['RULES', 'STRIPE_TAX', 'AVALARA']), shadowProvider: z.enum(['STRIPE', 'AVALARA', '']).nullish(), reason: Reason('reason') });

function paged<T>(all: T[], q: URLSearchParams, def = 25): { items: T[]; page: number; size: number } {
  const page = Math.max(0, Number(q.get('page') ?? 0) || 0);
  const size = Math.min(200, Math.max(1, Number(q.get('size') ?? def) || def));
  return { items: all.slice(page * size, page * size + size), page, size };
}

function sorted<T extends object>(all: T[], q: URLSearchParams, known: string[]): T[] {
  const keys = q.getAll('sort').flatMap((s) => s.split(';')).map((s) => {
    const [k = '', d = 'asc'] = s.split(',');
    return { k: k.trim(), desc: d.trim() === 'desc' };
  });
  if (keys.some((x) => !known.includes(x.k))) return all;
  return [...all].sort((a, b) => {
    for (const { k, desc } of keys) {
      const va = (a as Record<string, unknown>)[k];
      const vb = (b as Record<string, unknown>)[k];
      const c = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va ?? '').localeCompare(String(vb ?? ''));
      if (c !== 0) return desc ? -c : c;
    }
    return 0;
  });
}

export function taxHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const tax = (ctx: PreorderCtx) => ctx.db.tax;
  const read = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'fees')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'Your roles can’t open fees, commissions & tax.');
    if (!ctx.permissions.includes('tax:read')) throw problem(403, 'FORBIDDEN', 'Forbidden', 'This needs the tax:read permission.');
  };
  const needs = (ctx: PreorderCtx, perm: string) => {
    read(ctx);
    if (!ctx.permissions.includes(perm)) throw problem(403, 'FORBIDDEN', 'Forbidden', `This needs the ${perm} permission.`);
  };
  const canAdmin = (ctx: PreorderCtx) => ctx.permissions.includes('tax:classify-admin');
  const actor = (ctx: PreorderCtx) => ctx.action.actor;
  const variantOf = (ctx: PreorderCtx, atin: string | undefined) => {
    const v = tax(ctx).variants.find((x) => x.atin === atin);
    if (!v) throw problem(404, 'NOT_FOUND', 'Not found', 'No such product variant in tax review.');
    return v;
  };
  const categoryNamed = (code: string | undefined) => {
    const c = categoryOf(String(code ?? ''));
    if (!c) throw problem(404, 'NOT_FOUND', 'Not found', 'No such tax category.');
    return c;
  };
  const providerOf = (raw: string | null, fallback: string): 'STRIPE' | 'AVALARA' => {
    const p = (raw ?? fallback).toUpperCase();
    if (p !== 'STRIPE' && p !== 'AVALARA') throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { provider: 'Choose Stripe or Avalara' });
    return p;
  };
  const validCode = (provider: 'STRIPE' | 'AVALARA', code: string, verified: boolean) => {
    if (!code && verified) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { verified: 'A code that is not set can’t be verified.' });
    if (code && !CODE_PATTERN[provider].test(code)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { providerCode: CODE_HELP[provider] });
  };
  const setCode = (ctx: PreorderCtx, provider: 'STRIPE' | 'AVALARA', internal: string, code: string, verified: boolean, reason: string) => {
    const cur = tax(ctx).codes[provider][internal];
    if (!cur) throw problem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that.');
    Object.assign(cur, { providerCode: code, verified, note: reason, version: cur.version + 1, changedByName: actor(ctx), changedAt: ctx.now.toISOString() });
  };
  const logAudit = (v: MockVariant, ctx: PreorderCtx, action: string, reason: string) => v.audit.push({ at: ctx.now.toISOString(), actorName: actor(ctx), action, reason });

  return [
    http.get(
      `${W}/tax/profiles`,
      route((ctx) => {
        read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const all = DATA.categories.map((c) => profileRow(tax(ctx), c));
        const dept = q.get('department') ?? '';
        const status = (q.get('status') ?? '').toUpperCase();
        if (status && !GATES[status]) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { status: 'Choose one of the tax statuses' });
        const terms = (q.get('q') ?? '').toLowerCase().split(/\s+/).filter(Boolean);
        const filtered = all.filter((r) => (!dept || r.department === dept) && (!status || r.status === status) && terms.every((t) => `${r.name} ${r.departmentName} ${r.stripeCodeList} ${r.categoryCode}`.toLowerCase().includes(t)));
        const { items, page, size } = paged(sorted(filtered, q, ['name', 'departmentName', 'statusLabel', 'scenarioCount', 'ruleVersion']), q);
        const departments = DATA.departments.map((d) => ({ code: d.code, name: d.name, count: all.filter((r) => r.department === d.code).length }));
        return {
          items,
          total: filtered.length,
          page,
          size,
          departments,
          statuses: GATE_ORDER.map((s) => ({ value: s, label: GATES[s]!.label, count: all.filter((r) => r.status === s).length })),
          ruleVersion: DATA.ruleVersion,
          canChange: canAdmin(ctx)
        } satisfies TaxProfilePage;
      })
    ),
    http.get(
      `${W}/tax/profiles/:code`,
      route((ctx, p) => {
        read(ctx);
        return profileView(tax(ctx), categoryNamed(p.code), canAdmin(ctx));
      })
    ),
    http.post(
      `${W}/tax/profiles/:code/status`,
      route(async (ctx, p) => {
        needs(ctx, 'tax:classify-admin');
        const c = categoryNamed(p.code);
        const b = await json(ctx.request, StatusBody);
        const cur = tax(ctx).profiles[c.code]!;
        if (cur.status === 'REFINE_CODE') throw problem(409, 'CONFLICT', 'Not changed here', READ_ONLY_REFINE);
        if (b.status === 'ACTIVE' && cur.status !== 'ACTIVE') {
          const errors: Record<string, string> = {};
          if (!b.approvedBy?.trim()) errors.approvedBy = 'Name the tax professional or firm who signed this off.';
          if (!b.decisionRef?.trim()) errors.decisionRef = 'Give the reference of the signed-off decision or opinion letter.';
          if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        }
        cur.history.push({ at: ctx.now.toISOString(), changedByName: actor(ctx), status: b.status, reason: b.reason.trim(), approvedBy: b.approvedBy?.trim(), decisionRef: b.decisionRef?.trim() });
        Object.assign(cur, { status: b.status, changedByName: actor(ctx), changedAt: ctx.now.toISOString() });
        return profileView(tax(ctx), c, true);
      })
    ),
    http.get(
      `${W}/tax/reviews`,
      route((ctx) => {
        read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const status = (q.get('status') ?? 'PENDING_REVIEW').toUpperCase();
        if (status !== TAX_AUDIT_SAMPLE && !(status in RECORD_STATUS)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { status: 'Choose a review status' });
        const terms = (q.get('q') ?? '').toLowerCase();
        const all = tax(ctx).variants.filter((v) => (status === TAX_AUDIT_SAMPLE ? !!v.auditSample && v.status === 'APPROVED' : v.status === status) && (!q.get('categoryCode') || v.categoryCode === q.get('categoryCode')) && (!terms || `${v.title} ${v.atin} ${v.gtin}`.toLowerCase().includes(terms)));
        const { items, page, size } = paged(all.map(reviewRow), q);
        return { items, total: all.length, page, size, status, statuses: ['PENDING_REVIEW', TAX_AUDIT_SAMPLE, 'APPROVED', 'REJECTED', 'STALE'].map((s) => ({ value: s, label: s === TAX_AUDIT_SAMPLE ? 'Audit sample' : RECORD_STATUS[s]! })), canDecide: canAdmin(ctx) } satisfies TaxReviewPage;
      })
    ),
    http.get(
      `${W}/tax/reviews/:atin`,
      route((ctx, p) => {
        read(ctx);
        return reviewView(tax(ctx), variantOf(ctx, p.atin), canAdmin(ctx));
      })
    ),
    http.post(
      `${W}/tax/reviews/:atin/approve`,
      route(async (ctx, p) => {
        needs(ctx, 'tax:classify-admin');
        const v = variantOf(ctx, p.atin);
        const b = await json(ctx.request, ApproveBody);
        if (v.status === 'APPROVED' && v.auditSample) {
          Object.assign(v, { auditSample: false, reviewedByName: actor(ctx), reviewedAt: ctx.now.toISOString() });
          logAudit(v, ctx, 'AUDIT_CONFIRMED', b.reason.trim());
          return reviewView(tax(ctx), v, true);
        }
        if (v.resolveStatus !== 'RESOLVED')
          throw problem(422, 'VALIDATION_FAILED', 'Can’t do that', `This can’t be approved yet: the answers don’t give a definite tax decision, or the category isn’t cleared for sale. ${RESOLVE_STATUS[v.resolveStatus] ?? ''}`.trim());
        Object.entries(b.refinedProviderCodes ?? {}).forEach(([provider, code]) => {
          const pr = provider as 'STRIPE' | 'AVALARA';
          if (code.trim() && CODE_PATTERN[pr] && !CODE_PATTERN[pr].test(code.trim()))
            throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { refinedProviderCodes: CODE_HELP[pr] });
          if (code.trim()) v.refined[provider] = code.trim();
        });
        Object.assign(v.facts, b.staffFacts ?? {});
        Object.assign(v, { status: 'APPROVED', reviewedByName: actor(ctx), reviewedAt: ctx.now.toISOString() });
        logAudit(v, ctx, 'APPROVED', b.reason.trim());
        return reviewView(tax(ctx), v, true);
      })
    ),
    http.post(
      `${W}/tax/reviews/:atin/reclassify`,
      route(async (ctx, p) => {
        needs(ctx, 'tax:classify-admin');
        const v = variantOf(ctx, p.atin);
        const b = await json(ctx.request, ReclassifyBody);
        categoryNamed(b.categoryCode);
        v.categoryCode = b.categoryCode;
        Object.assign(v, { status: 'PENDING_REVIEW', rowId: '', resolveStatus: 'MISSING_FACTS', auditSample: false });
        logAudit(v, ctx, 'RECLASSIFIED', b.reason.trim());
        return reviewView(tax(ctx), v, true);
      })
    ),
    http.post(
      `${W}/tax/reviews/:atin/request-information`,
      route(async (ctx, p) => {
        needs(ctx, 'tax:classify-admin');
        const v = variantOf(ctx, p.atin);
        const b = await json(ctx.request, InfoBody);
        if (!tax(ctx).requestInformation) throw problem(503, 'UPSTREAM_UNAVAILABLE', 'Service unavailable', 'This part of AfroTransact is temporarily unavailable. Please try again shortly.');
        v.status = 'REJECTED';
        logAudit(v, ctx, 'REJECTED', b.message.trim());
        return reviewView(tax(ctx), v, true);
      })
    ),
    http.get(
      `${W}/tax/code-map`,
      route((ctx) => {
        read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const st = tax(ctx);
        const active = st.method.method === 'STRIPE_TAX' ? 'STRIPE' : st.method.method === 'AVALARA' ? 'AVALARA' : st.method.shadowProvider;
        const provider = providerOf(q.get('provider'), active || 'STRIPE');
        const all = ALL_ROWS.map((x) => codeEntry(st, provider, x.row.internalTaxCode)!);
        const state = q.get('unmappedOnly') === 'true' ? 'UNMAPPED' : (q.get('state') ?? '').toUpperCase();
        const terms = (q.get('q') ?? '').toLowerCase().split(/\s+/).filter(Boolean);
        const filtered = all.filter((e) => (!state || e.state === state) && (!q.get('categoryCode') || e.categoryCode === q.get('categoryCode')) && terms.every((t) => `${e.internalTaxCode} ${e.providerCode} ${e.categoryName} ${e.outcomeName}`.toLowerCase().includes(t)));
        const { items, page, size } = paged(sorted(filtered, q, ['internalTaxCode', 'categoryName', 'outcomeLabel', 'providerCode', 'stateLabel']), q, 50);
        return {
          provider,
          providerLabel: PROVIDER[provider] ?? provider,
          activeProvider: active,
          items,
          total: filtered.length,
          page,
          size,
          completeness: completeness(st, provider),
          canEdit: canAdmin(ctx),
          states: Object.entries(STATE_LABEL).map(([value, label]) => ({ value: value === 'NOT_NEEDED' ? 'NOT_NEEDED' : value, label }))
        } satisfies TaxCodeMap;
      })
    ),
    http.get(
      `${W}/tax/code-map/export`,
      route((ctx) => {
        read(ctx);
        const provider = providerOf(new URL(ctx.request.url).searchParams.get('provider'), '');
        const safe = (s: string) => (/^[=+\-@\t\r]/.test(s) ? `'${s}` : s);
        const lines = ['internal_tax_code,category,outcome,provider,provider_code,verified'].concat(
          ALL_ROWS.map((x) => {
            const e = codeEntry(tax(ctx), provider, x.row.internalTaxCode)!;
            return [safe(e.internalTaxCode), `"${safe(e.categoryName).replace(/"/g, '""')}"`, e.outcome, provider, safe(e.providerCode), String(e.verified)].join(',');
          })
        );
        return { filename: `tax-code-map-${provider.toLowerCase()}.csv`, csv: `${lines.join('\n')}\n`, rows: ALL_ROWS.length };
      })
    ),
    http.post(
      `${W}/tax/code-map/import`,
      route(async (ctx) => {
        needs(ctx, 'tax:classify-admin');
        const b = await json(ctx.request, ImportBody);
        if (!b.dryRun && [...(b.reason ?? '').trim()].length < 10) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { reason: 'Say why in at least 10 characters. The reason is kept with your name.' });
        const recs = b.csv.trim().split(/\r?\n/).map((l) => l.split(',').map((c) => c.trim().replace(/^'/, '')));
        const header = (recs[0] ?? []).map((h) => h.toLowerCase());
        const ci = header.indexOf('internal_tax_code');
        const pi = header.indexOf('provider_code');
        const vi = header.indexOf('verified');
        if (recs.length < 2 || ci < 0 || pi < 0) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { csv: 'The header needs the columns internal_tax_code and provider_code (verified is optional).' });
        const problems: TaxCodeMapImportResult['problems'] = [];
        const preview: TaxCodeMapImportResult['preview'] = [];
        const changes: { internal: string; code: string; verified: boolean }[] = [];
        let unchanged = 0;
        recs.slice(1).forEach((r, i) => {
          const line = i + 2;
          const internal = r[ci] ?? '';
          const code = r[pi] ?? '';
          const verified = vi >= 0 && ['true', 'yes', '1', 'y'].includes((r[vi] ?? '').toLowerCase());
          const cur = tax(ctx).codes[b.provider][internal];
          if (!cur) return void problems.push({ line, internalTaxCode: internal, message: 'No such internal tax code.' });
          if (code && !CODE_PATTERN[b.provider].test(code)) return void problems.push({ line, internalTaxCode: internal, message: CODE_HELP[b.provider] });
          if (!code && verified) return void problems.push({ line, internalTaxCode: internal, message: 'A code that is not set can’t be verified.' });
          if (cur.providerCode === code && cur.verified === verified) return void (unchanged += 1);
          changes.push({ internal, code, verified });
          preview.push({ line, internalTaxCode: internal, from: cur.providerCode, to: code, verified, fromVerified: cur.verified });
        });
        const result = { provider: b.provider, rows: recs.length - 1, changed: changes.length, unchanged, problems, preview: preview.slice(0, 100), applied: 0, dryRun: !!b.dryRun, blocked: !b.dryRun && problems.length > 0 };
        if (b.dryRun || problems.length) return result;
        changes.forEach((c) => setCode(ctx, b.provider, c.internal, c.code, c.verified, (b.reason ?? '').trim()));
        return { ...result, applied: changes.length };
      })
    ),
    http.put(
      `${W}/tax/code-map/:internal`,
      route(async (ctx, p) => {
        needs(ctx, 'tax:classify-admin');
        const b = await json(ctx.request, CodeBody);
        const code = (b.providerCode ?? '').trim();
        validCode(b.provider, code, b.verified);
        setCode(ctx, b.provider, String(p.internal), code, b.verified, b.reason.trim());
        return codeEntry(tax(ctx), b.provider, String(p.internal));
      })
    ),
    http.get(
      `${W}/tax/method`,
      route((ctx) => {
        read(ctx);
        return methodView(tax(ctx), ctx.permissions.includes('tax:configure'));
      })
    ),
    http.put(
      `${W}/tax/method`,
      route(async (ctx) => {
        needs(ctx, 'tax:configure');
        const b = await json(ctx.request, MethodBody);
        const st = tax(ctx);
        const shadow = (b.shadowProvider ?? '') as '' | 'STRIPE' | 'AVALARA';
        const field = (name: string, message: string) => problem(422, 'VALIDATION_FAILED', 'Can’t switch', message, { [name]: message });
        const provider = b.method === 'STRIPE_TAX' ? 'STRIPE' : b.method === 'AVALARA' ? 'AVALARA' : null;
        if (shadow && shadow === provider) throw field('shadowProvider', `${PROVIDER[shadow]} is already the one charging. Shadow mode compares another provider with it.`);
        if (provider) {
          if (!configured(st, provider)) throw field('method', notSetUp(provider));
          if (st.method.method !== b.method) {
            const comp = completeness(st, provider);
            if (!comp.complete) throw field('method', `${comp.unmapped} outcomes have no ${PROVIDER[provider]} code and ${comp.unverified} are not verified. Lines without a verified code would be blocked at checkout. Finish the Provider codes first.`);
          }
        }
        if (shadow && !configured(st, shadow)) throw field('shadowProvider', notSetUp(shadow));
        st.history.unshift({ at: ctx.now.toISOString(), changedByName: actor(ctx), fromLabel: METHOD_TEXT[st.method.method]!.label, toLabel: METHOD_TEXT[b.method]!.label, reason: b.reason.trim() });
        Object.assign(st.method, { method: b.method, shadowProvider: shadow, changedByName: actor(ctx), changedAt: ctx.now.toISOString() });
        return methodView(st, true);
      })
    ),
    http.get(
      `${W}/tax/review-settings`,
      route((ctx) => {
        read(ctx);
        return reviewSettingsView(tax(ctx), canAdmin(ctx));
      })
    ),
    http.put(
      `${W}/tax/review-settings`,
      route(async (ctx) => {
        needs(ctx, 'tax:classify-admin');
        const b = await json(ctx.request, ReviewSettingsBody);
        const st = tax(ctx);
        const always = b.alwaysReview ? [...new Set(b.alwaysReview.map((c) => c.trim()).filter(Boolean))] : st.review.alwaysReview;
        const unknown = always.filter((c) => !categoryOf(c));
        if (unknown.length) throw problem(422, 'VALIDATION_FAILED', 'Can’t do that', 'One of the categories isn’t a known tax category. Choose categories from the list.', { alwaysReview: 'Choose categories from the list.' });
        const percent = b.auditPercent ?? st.review.auditPercent;
        const entry = {
          at: ctx.now.toISOString(),
          changedByName: actor(ctx),
          fromLabel: reviewSummary(st.review.mode, st.review.alwaysReview, st.review.auditPercent),
          toLabel: reviewSummary(b.mode, always, percent),
          summary: reviewSummary(b.mode, always, percent),
          reason: b.reason.trim()
        };
        if (b.mode !== st.review.mode || percent !== st.review.auditPercent || always.join() !== [...st.review.alwaysReview].join()) st.review.history.unshift(entry);
        Object.assign(st.review, { mode: b.mode, alwaysReview: always, auditPercent: percent, changedByName: actor(ctx), changedAt: ctx.now.toISOString() });
        return reviewSettingsView(st, true);
      })
    ),
    http.get(
      `${W}/tax/shadow`,
      route((ctx) => {
        read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const st = tax(ctx);
        const provider = (q.get('provider') ?? '').toUpperCase() || st.method.shadowProvider || (st.method.method === 'STRIPE_TAX' ? 'STRIPE' : st.method.method === 'AVALARA' ? 'AVALARA' : '');
        const to = q.get('to') ?? ctx.now.toISOString().slice(0, 10);
        const from = q.get('from') ?? new Date(Date.parse(to) - 30 * 86_400_000).toISOString().slice(0, 10);
        if (Number.isNaN(Date.parse(from)) || Number.isNaN(Date.parse(to))) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { from: 'Use a date like 2026-11-09' });
        if (Date.parse(to) < Date.parse(from)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { to: 'The end date must be after the start, within a year' });
        const { page, size } = paged([], q);
        return shadowView(st, provider, page, size, ctx.permissions.includes('tax:configure'), from, to);
      })
    )
  ];
}
