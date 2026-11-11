/* Selling approvals in the mock seller BFF (docs/runbooks/selling-approvals.md): sell, apply or not available for a listing's
   facts, the Selling approvals page, applying with documents and the food service capability. Mirrors seller-service's
   evaluation for a handful of rules (supplements, bundles, jewelry, gated brands, food service); state is per organisation. */
import {
  type ApplyToSell,
  type ResubmitSelling,
  type SellerSellingStatus,
  type SellingApplication,
  type SellingApprovalRow,
  type SellingApprovals,
  type SellingCapabilities,
  type SellingDocumentSlot,
  type SellingFacts,
  type SellingMissing
} from '../../schemas/seller';

const SLOT: Record<string, SellingDocumentSlot> = {
  PERMIT_LICENCE: { kind: 'PERMIT_LICENCE', label: 'Permit or licence (with its expiry date)', optional: false, expiryRequired: true, dated: false, namesAfroTransact: false },
  LIABILITY_INSURANCE: { kind: 'LIABILITY_INSURANCE', label: 'Liability insurance naming AfroTransact as additional insured (with its expiry date)', optional: false, expiryRequired: true, dated: false, namesAfroTransact: true },
  SAFETY_CERTIFICATE: { kind: 'SAFETY_CERTIFICATE', label: 'Safety or lab certificate', optional: false, expiryRequired: false, dated: false, namesAfroTransact: false },
  PURCHASE_INVOICE: { kind: 'PURCHASE_INVOICE', label: 'Purchase invoice dated within the last 180 days', optional: false, expiryRequired: false, dated: true, namesAfroTransact: false },
  PHOTOS_ALL_SIDES: { kind: 'PHOTOS_ALL_SIDES', label: 'Photos of the product from all sides', optional: false, expiryRequired: false, dated: false, namesAfroTransact: false },
  BRAND_AUTHORIZATION: { kind: 'BRAND_AUTHORIZATION', label: 'Brand authorization letter', optional: false, expiryRequired: false, dated: false, namesAfroTransact: false }
};
const BRAND_DOCS = ['BRAND_AUTHORIZATION', 'PURCHASE_INVOICE', 'PHOTOS_ALL_SIDES'];

interface MockRule {
  scopeType: SellingApprovalRow['scopeType'];
  scopeKey: string;
  label: string;
  level: 'SELL' | 'APPLY' | 'NOT_AVAILABLE';
  docs: string[];
  reason?: string;
  capability?: string;
  /** Jewelry: applies when the material is one of these, or a watch. */
  materials?: string[];
}

const RULES: MockRule[] = [
  { scopeType: 'CATEGORY', scopeKey: 'dietary-supplements', label: 'Dietary supplements', level: 'APPLY', docs: ['PERMIT_LICENCE', 'SAFETY_CERTIFICATE', 'LIABILITY_INSURANCE'] },
  { scopeType: 'CATEGORY', scopeKey: 'gift-baskets-bundles', label: 'Gift baskets / bundles', level: 'NOT_AVAILABLE', docs: [], reason: 'Not available yet: blocked until bundles can be itemised for tax.' },
  { scopeType: 'CATEGORY', scopeKey: 'jewelry-accessories', label: 'Jewelry & accessories', level: 'APPLY', docs: ['PURCHASE_INVOICE', 'PHOTOS_ALL_SIDES'], materials: ['gold', 'silver', 'gemstone'] },
  { scopeType: 'CATEGORY', scopeKey: 'bakery-products', label: 'Bakery products', level: 'APPLY', docs: [], capability: 'FOOD_SERVICE' },
  { scopeType: 'CATEGORY', scopeKey: 'prepared-meals', label: 'Prepared meals', level: 'APPLY', docs: [], capability: 'FOOD_SERVICE' },
  { scopeType: 'CAPABILITY', scopeKey: 'FOOD_SERVICE', label: 'Food service (cooks, bakers, caterers)', level: 'APPLY', docs: ['PERMIT_LICENCE', 'LIABILITY_INSURANCE'] },
  { scopeType: 'BRAND', scopeKey: 'gucci', label: 'Gucci', level: 'APPLY', docs: BRAND_DOCS },
  { scopeType: 'BRAND_IN_CATEGORY', scopeKey: 'nike|adult-clothing-footwear', label: 'Nike in Adult clothing & footwear', level: 'APPLY', docs: BRAND_DOCS },
  { scopeType: 'BRAND_IN_CATEGORY', scopeKey: 'apple|phones', label: 'Apple in Phones', level: 'APPLY', docs: BRAND_DOCS }
];

interface MockApproval {
  state: 'APPLIED' | 'NEEDS_ATTENTION' | 'APPROVED' | 'EXPIRED' | 'NOT_AVAILABLE';
  applicationId: string;
  expiresAt?: string;
  reason?: string;
}

interface OrgState {
  approvals: Map<string, MockApproval>;
  applications: SellingApplication[];
  seq: number;
}

const orgs = new Map<string, OrgState>();
const stateOf = (orgId: string): OrgState => {
  let s = orgs.get(orgId);
  if (!s) {
    s = { approvals: new Map(), applications: [], seq: 50000 };
    orgs.set(orgId, s);
  }
  return s;
};
const keyOf = (type: string, key: string) => `${type}:${key}`;
export const resetSellingMocks = () => orgs.clear();

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '');
const slots = (r: MockRule) => r.docs.map((k) => SLOT[k]).filter((x): x is SellingDocumentSlot => x !== undefined);

function appliesTo(r: MockRule, f: SellingFacts): 'yes' | 'no' | 'unknown' {
  if (!r.materials) return 'yes';
  const m = (f.material ?? '').toLowerCase();
  const watch = (f.productTypeCode ?? '').toLowerCase().includes('watch') || m === 'watch';
  if (watch) return 'yes';
  if (!m) return 'unknown';
  return r.materials.some((x) => m.includes(x)) ? 'yes' : 'no';
}

function describe(r: MockRule, a: MockApproval | undefined, orgId: string): SellingMissing | null {
  const app = a ? stateOf(orgId).applications.find((x) => x.id === a.applicationId) : undefined;
  if (a?.state === 'APPROVED') return null;
  const base = { scope: { type: r.scopeType, key: r.scopeKey }, label: r.label, documents: r.docs.map((kind) => ({ kind, optional: false })), applicationId: app?.id ?? null, applicationStatus: app?.status ?? null };
  if (a?.state === 'APPLIED') return { ...base, level: 'APPLY', state: 'APPLIED', message: `Your application for ${r.label} is with our team. We aim to answer within 2 business days.`, canApply: false };
  if (a?.state === 'NEEDS_ATTENTION') return { ...base, level: 'APPLY', state: 'NEEDS_ATTENTION', message: `We need more from you for ${r.label}: ${a.reason ?? 'see your application'}.`, canApply: true };
  if (a?.state === 'NOT_AVAILABLE') return { ...base, level: 'NOT_AVAILABLE', state: 'NOT_AVAILABLE', message: `${r.label} is not available to you: ${a.reason ?? 'your account does not qualify'}. This cannot be appealed.`, canApply: false };
  return { ...base, level: 'APPLY', state: a?.state === 'EXPIRED' ? 'EXPIRED' : 'NOT_APPLIED',
    message: a?.state === 'EXPIRED' ? `Your approval for ${r.label} expired. Upload the renewed document to sell here again.` : `You need approval to sell in ${r.label}. Apply once; after that every listing here goes live.`, canApply: true };
}

export function sellingStatus(orgId: string, facts: SellingFacts): SellerSellingStatus {
  const cats = (facts.categoryCode ?? '').split(',').map((c) => c.trim()).filter(Boolean);
  const brand = norm(facts.brand ?? '');
  const matches = RULES.filter((r) => r.level !== 'SELL' && r.scopeType !== 'CAPABILITY').filter((r) => {
    switch (r.scopeType) {
      case 'CATEGORY': return cats.includes(r.scopeKey);
      case 'BRAND': return brand !== '' && r.scopeKey === brand;
      case 'BRAND_IN_CATEGORY': { const [b, c] = r.scopeKey.split('|'); return brand === b && cats.includes(c ?? ''); }
      case 'PRODUCT_TYPE': return r.scopeKey === facts.productTypeCode;
      default: return false;
    }
  });
  const missing: SellingMissing[] = [];
  const pending: string[] = [];
  const seen = new Set<string>();
  for (const r of matches) {
    const applies = appliesTo(r, facts);
    if (applies === 'unknown') { pending.push('material'); continue; }
    if (applies === 'no') continue;
    if (r.level === 'NOT_AVAILABLE') {
      missing.push({ scope: { type: r.scopeType, key: r.scopeKey }, label: r.label, level: 'NOT_AVAILABLE', state: 'NOT_AVAILABLE', message: r.reason ?? `${r.label} is not available on AfroTransact.`, canApply: false, documents: [] });
      continue;
    }
    const need = r.capability ? RULES.find((x) => x.scopeType === 'CAPABILITY' && x.scopeKey === r.capability) ?? r : r;
    if (seen.has(keyOf(need.scopeType, need.scopeKey))) continue;
    seen.add(keyOf(need.scopeType, need.scopeKey));
    const d = describe(need, stateOf(orgId).approvals.get(keyOf(need.scopeType, need.scopeKey)), orgId);
    if (d) missing.push(d);
  }
  const blocked = missing.find((m) => m.level === 'NOT_AVAILABLE');
  if (blocked) return { level: 'NOT_AVAILABLE', reason: blocked.message, missing, pendingFacts: pending };
  if (missing.length > 0) return { level: 'APPLY', reason: missing[0]?.message ?? null, missing, pendingFacts: pending };
  return { level: 'SELL', reason: null, missing: [], pendingFacts: pending };
}

export function sellingCapabilities(orgId: string): SellingCapabilities {
  const a = stateOf(orgId).approvals.get(keyOf('CAPABILITY', 'FOOD_SERVICE'));
  return { foodService: { approved: a?.state === 'APPROVED', state: a?.state ?? 'NOT_APPLIED', expiresAt: a?.expiresAt ?? null } };
}

export function sellingApprovals(orgId: string): SellingApprovals {
  const s = stateOf(orgId);
  const rows: SellingApprovalRow[] = [];
  for (const r of RULES.filter((x) => x.level !== 'SELL' && !(x.capability && x.scopeType === 'CATEGORY'))) {
    const a = s.approvals.get(keyOf(r.scopeType, r.scopeKey));
    const d = r.level === 'NOT_AVAILABLE' ? null : describe(r, a, orgId);
    const state = r.level === 'NOT_AVAILABLE' ? 'NOT_AVAILABLE' : (d?.state ?? 'APPROVED');
    const words: Record<string, string> = { NOT_APPLIED: 'Not applied', APPLIED: 'With our team', NEEDS_ATTENTION: 'Needs your attention', APPROVED: 'Approved', EXPIRED: 'Expired', NOT_AVAILABLE: 'Not available' };
    rows.push({ scopeType: r.scopeType, scopeKey: r.scopeKey, label: r.label, level: r.level === 'NOT_AVAILABLE' ? 'NOT_AVAILABLE' : 'APPLY', state, statusWords: words[state] ?? state,
      message: r.level === 'NOT_AVAILABLE' ? (r.reason ?? null) : (d?.message ?? 'You are approved.'), canApply: d?.canApply ?? false, approvedAt: null, expiresAt: a?.expiresAt ?? null, daysToExpiry: a?.expiresAt ? Math.round((Date.parse(a.expiresAt) - Date.now()) / 86_400_000) : null,
      reapplyAfter: null, documents: slots(r), onFile: [], applicationId: a?.applicationId ?? null, applicationStatus: s.applications.find((x) => x.id === a?.applicationId)?.status ?? null, note: null, listingsPaused: false });
  }
  const notices = rows.filter((r) => r.state === 'APPROVED' && r.daysToExpiry != null && r.daysToExpiry <= 30)
    .map((r) => ({ severity: (r.daysToExpiry ?? 99) <= 7 ? 'urgent' as const : 'warning' as const, scopeKey: r.scopeKey, scopeLabel: r.label, message: `A document for ${r.label} runs out in ${r.daysToExpiry} days. Upload the renewed one to keep selling.` }));
  return { approvals: rows, notices, capabilities: sellingCapabilities(orgId) };
}

export class SellingMockError extends Error {
  constructor(readonly status: number, readonly code: string, message: string, readonly errors?: Record<string, string>) {
    super(message);
  }
}

const today = () => new Date().toISOString().slice(0, 10);

function check(r: MockRule, docs: NonNullable<ApplyToSell['documents']>): void {
  const errors: Record<string, string> = {};
  for (const slot of slots(r)) {
    const d = docs.find((x) => x.kind === slot.kind);
    if (!d) { errors[`documents.${slot.kind}`] = `Upload: ${slot.label}`; continue; }
    if (slot.dated && (!d.issuedOn || d.issuedOn > today() || d.issuedOn < new Date(Date.now() - 180 * 86_400_000).toISOString().slice(0, 10))) errors[`documents.${slot.kind}`] = 'Must be dated within the last 180 days';
    else if (slot.expiryRequired && (!d.expiresOn || d.expiresOn <= today())) errors[`documents.${slot.kind}`] = d.expiresOn ? 'This document has already expired' : 'Enter the expiry date';
    else if (slot.namesAfroTransact && d.namesAfroTransact !== true) errors[`documents.${slot.kind}`] = 'The policy must name AfroTransact as additional insured';
  }
  if (Object.keys(errors).length > 0) throw new SellingMockError(422, 'seller.selling-documents-invalid', 'Some documents are missing or need fixing', errors);
}

const view = (a: SellingApplication): SellingApplication => a;

export function applyToSell(orgId: string, input: ApplyToSell): SellingApplication {
  const s = stateOf(orgId);
  const r = RULES.find((x) => x.scopeType === input.scopeType && x.scopeKey === input.scopeKey && x.level === 'APPLY');
  if (!r) throw new SellingMockError(422, 'seller.selling-not-needed', 'You can sell here without approval');
  const open = s.applications.find((a) => a.scopeKey === r.scopeKey && ['PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED'].includes(a.status));
  if (open) return view(open);
  const current = s.approvals.get(keyOf(r.scopeType, r.scopeKey));
  if (current?.state === 'NOT_AVAILABLE') throw new SellingMockError(409, 'seller.selling-not-available', `${r.label} is not available to you. This cannot be appealed.`);
  check(r, input.documents ?? []);
  const now = new Date().toISOString();
  const app: SellingApplication = { id: `sap_mock${++s.seq}`, reference: `SA-${s.seq}`, scopeType: r.scopeType, scopeKey: r.scopeKey, scopeLabel: r.label, kind: current?.state === 'APPROVED' ? 'RENEWAL' : 'NEW',
    status: 'PENDING_REVIEW', statusWords: 'With our team', message: 'We aim to answer within 2 business days.', requestedItems: [], disqualified: false, reapplyAfter: null,
    documents: (input.documents ?? []).map((d, i) => ({ id: `doc${i}`, kind: d.kind, label: SLOT[d.kind]?.label ?? d.kind, assetId: d.assetId, fileName: d.fileName ?? null, issuedOn: d.issuedOn ?? null, expiresOn: d.expiresOn ?? null, namesAfroTransact: d.namesAfroTransact ?? false, note: null })),
    required: slots(r), submittedAt: now, updatedAt: now };
  s.applications.unshift(app);
  if (current?.state !== 'APPROVED') s.approvals.set(keyOf(r.scopeType, r.scopeKey), { state: 'APPLIED', applicationId: app.id });
  return view(app);
}

export function resubmitSelling(orgId: string, id: string, input: ResubmitSelling): SellingApplication {
  const app = stateOf(orgId).applications.find((a) => a.id === id);
  if (!app) throw new SellingMockError(404, 'seller.selling-application-not-found', 'Application not found');
  if (app.status !== 'INFO_REQUESTED') throw new SellingMockError(409, 'seller.selling-application-locked', `Application ${app.reference} is ${app.status} and can't be changed`);
  app.status = 'PENDING_REVIEW';
  app.statusWords = 'With our team';
  app.message = 'We aim to answer within 2 business days.';
  app.requestedItems = [];
  if (input.documents?.length) app.documents = input.documents.map((d, i) => ({ id: `doc${i}`, kind: d.kind, label: SLOT[d.kind]?.label ?? d.kind, assetId: d.assetId, fileName: d.fileName ?? null, issuedOn: d.issuedOn ?? null, expiresOn: d.expiresOn ?? null, namesAfroTransact: d.namesAfroTransact ?? false, note: null }));
  const a = stateOf(orgId).approvals.get(keyOf(app.scopeType, app.scopeKey));
  if (a) a.state = 'APPLIED';
  return view(app);
}

export const sellingApplications = (orgId: string): SellingApplication[] => stateOf(orgId).applications;
export const sellingApplication = (orgId: string, id: string): SellingApplication => {
  const a = stateOf(orgId).applications.find((x) => x.id === id);
  if (!a) throw new SellingMockError(404, 'seller.selling-application-not-found', 'Application not found');
  return a;
};

/** Test and demo helpers: staff decide a mock application. */
export function decideSellingMock(orgId: string, id: string, outcome: 'APPROVED' | 'INFO_REQUESTED' | 'DISQUALIFIED', expiresInDays = 180): void {
  const s = stateOf(orgId);
  const app = sellingApplication(orgId, id);
  const key = keyOf(app.scopeType, app.scopeKey);
  if (outcome === 'APPROVED') {
    app.status = 'APPROVED'; app.statusWords = 'Approved'; app.message = `You are approved. Listings in ${app.scopeLabel} go live after the usual checks.`;
    s.approvals.set(key, { state: 'APPROVED', applicationId: id, expiresAt: new Date(Date.now() + expiresInDays * 86_400_000).toISOString() });
  } else if (outcome === 'INFO_REQUESTED') {
    app.status = 'INFO_REQUESTED'; app.statusWords = 'Needs your attention'; app.requestedItems = ['A full scan of the permit']; app.message = 'We need more from you: A full scan of the permit';
    s.approvals.set(key, { state: 'NEEDS_ATTENTION', applicationId: id, reason: 'A full scan of the permit' });
  } else {
    app.status = 'REJECTED'; app.statusWords = 'Not available'; app.disqualified = true; app.message = 'This cannot be appealed';
    s.approvals.set(key, { state: 'NOT_AVAILABLE', applicationId: id, reason: 'Your account does not qualify' });
  }
}
