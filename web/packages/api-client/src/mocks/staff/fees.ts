/* Mock of the fee-schedule editor routes (gateway-bff-staff.yaml, tag Fees) over an in-memory copy of payments' immutable
   fee-schedule versions. Statuses, the base of a new version, validation and the AfroTransact Retail rule follow the
   Go BFF (internal/app/staff_fees.go). */
import { quoteTariff } from '../../fees';
import {
  type DeliveryPricing,
  type DeliveryQuote,
  type DeliveryTariff,
  type FeeCategory,
  type FeeLimits,
  type FeeOrgHit,
  type FeeScheduleDraft,
  type FeeScheduleView,
  type FeesOverview
} from '../../schemas/staff';
import { ALL_CATEGORIES, normalizeFeeCategory, validateFeeDraft } from '../../validation/fee-draft';
import { type MockRecord } from './db';

export interface MockFeeSchedule {
  id: string;
  version: number;
  effectiveFrom: string;
  categoryBps: Record<string, number>;
  defaultCommissionBps: number;
  overrides: { orgId: string; category: string; commissionBps: number }[];
  cardPctBps: number;
  cardFixedMinor: number;
  fbaPerUnitMinor: number;
  reverseCommissionOnRefund: boolean;
  /** AfroTransact Delivery from seller stores (S255); null on versions before it. */
  deliveryPricing: MockDeliveryPricing | null;
  note: string | null;
  createdBy: string;
  createdAt: string;
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancelReason: string | null;
}

export interface MockDeliveryPricing {
  enabled: boolean;
  maxRadiusMiles: number;
  customerCharge: DeliveryTariff | null;
  sellerFee: DeliveryTariff | null;
  sellerOverrides: { orgId: string; sellerFee: DeliveryTariff }[];
}

const tariff = (t: Partial<DeliveryTariff> & Pick<DeliveryTariff, 'mode'>): DeliveryTariff => ({
  flatPerPackageMinor: null,
  baseMinor: null,
  perMileMinor: null,
  bands: [],
  minMinor: null,
  maxMinor: null,
  ...t
});

/** payments' seed (V9): customer $4.99 + $0.85/mi, $5.99–$24.99 within 25 miles; sellers $2.00 per package. */
export const LAUNCH_DELIVERY: MockDeliveryPricing = {
  enabled: true,
  maxRadiusMiles: 25,
  customerCharge: tariff({ mode: 'DISTANCE', baseMinor: 499, perMileMinor: 85, minMinor: 599, maxMinor: 2499 }),
  sellerFee: tariff({ mode: 'FLAT', flatPerPackageMinor: 200 }),
  sellerOverrides: [{ orgId: 'org-kente', sellerFee: tariff({ mode: 'FLAT', flatPerPackageMinor: 150 }) }]
};

export interface MockFeeOrg {
  id: string;
  name: string;
  kind: string;
  status: string;
}

export const MOCK_RETAIL_ORG_ID = 'org-afrotransact-retail';
export const RETAIL_NAME = 'AfroTransact Retail';
export const RETAIL_NOTE = 'No commission, no fees (merchant of record)';
export const FEE_LIMITS: FeeLimits = {
  commissionMaxBps: 5000, cardPctMaxBps: 1000, cardFixedMaxMinor: 1000, fbaPerUnitMaxMinor: 10_000, noteMinLength: 3, noteMaxLength: 500, maxCategoryRates: 100, maxOverrides: 500,
  deliveryMaxRadiusMiles: 200, deliveryAmountMaxMinor: 10_000, deliveryMaxBands: 20, maxDeliveryOverrides: 500
};

/** Catalog departments (seed taxonomy) as payments commission categories. */
const TAXONOMY = ['Fashion', 'Groceries', 'Beauty', 'Books', 'Home & Art', 'Health', 'Event & party'];

export const FEE_ORGS: MockFeeOrg[] = [
  { id: 'org-ade', name: "Ade's Kitchen", kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-kente', name: 'Kente Naturals', kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-mamatee', name: "Mama Tee's Pantry", kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-lagos', name: 'Lagos Threads', kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-accra', name: 'Accra Spice Co.', kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-nile', name: 'Nile Beauty', kind: 'SELLER', status: 'APPROVED' },
  { id: 'org-braids', name: 'Braids by Ama', kind: 'PROVIDER', status: 'APPROVED' },
  { id: MOCK_RETAIL_ORG_ID, name: RETAIL_NAME, kind: 'SELLER', status: 'APPROVED' }
];

const LAUNCH: Record<string, number> = { groceries: 800, fashion: 1200, beauty: 1000, 'home & art': 1200, books: 1000, health: 1000, party: 1000, services: 1000 };

export function seedFeeSchedules(now: Date): MockFeeSchedule[] {
  const ago = (days: number) => new Date(now.getTime() - days * 86_400_000).toISOString();
  const ahead = (days: number) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + days, 5, 0, 0)).toISOString();
  const base = { cardPctBps: 290, cardFixedMinor: 30, reverseCommissionOnRefund: true, cancelledAt: null, cancelledBy: null, cancelReason: null };
  return [
    { ...base, id: 'fee-1', version: 1, effectiveFrom: '2020-01-01T00:00:00Z', categoryBps: { ...LAUNCH }, defaultCommissionBps: 1000, overrides: [], fbaPerUnitMinor: 325,
      deliveryPricing: null, note: 'Launch defaults', createdBy: 'system:seed', createdAt: '2020-01-01T00:00:00Z' },
    { ...base, id: 'fee-2', version: 2, effectiveFrom: ago(60), categoryBps: { ...LAUNCH, fashion: 1150 }, defaultCommissionBps: 1000, fbaPerUnitMinor: 325,
      deliveryPricing: structuredClone(LAUNCH_DELIVERY),
      overrides: [{ orgId: 'org-kente', category: 'beauty', commissionBps: 800 }, { orgId: 'org-ade', category: ALL_CATEGORIES, commissionBps: 900 }],
      note: 'Q3 review: fashion down, launch partners', createdBy: 'Musa Bello', createdAt: ago(62) },
    { ...base, id: 'fee-3', version: 3, effectiveFrom: ahead(7), categoryBps: { ...LAUNCH }, defaultCommissionBps: 1100, overrides: [], fbaPerUnitMinor: 325,
      deliveryPricing: structuredClone(LAUNCH_DELIVERY),
      note: 'Default to 11 % (typo)', createdBy: 'Musa Bello', createdAt: ago(3), cancelledAt: ago(2), cancelledBy: 'Musa Bello', cancelReason: 'Wrong default rate' },
    { ...base, id: 'fee-4', version: 4, effectiveFrom: ahead(21), categoryBps: { ...LAUNCH, fashion: 1300 }, defaultCommissionBps: 1000, fbaPerUnitMinor: 350,
      deliveryPricing: structuredClone(LAUNCH_DELIVERY),
      overrides: [{ orgId: 'org-kente', category: 'beauty', commissionBps: 800 }, { orgId: 'org-ade', category: ALL_CATEGORIES, commissionBps: 900 }],
      note: 'Holiday season: fashion up, FBA $3.50', createdBy: 'Musa Bello', createdAt: ago(1) }
  ];
}

const label = (key: string): string => {
  const t = TAXONOMY.find((n) => normalizeFeeCategory(n) === key);
  if (t) return t;
  if (key === 'services') return 'Services';
  return key ? key.charAt(0).toUpperCase() + key.slice(1) : 'All categories';
};

interface Timeline {
  active: MockFeeSchedule | null;
  latest: MockFeeSchedule | null;
}

function timeline(all: MockFeeSchedule[], now: Date): Timeline {
  const live = all.filter((s) => !s.cancelledAt);
  const inForce = live.filter((s) => new Date(s.effectiveFrom).getTime() <= now.getTime());
  const active = inForce.sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom) || b.version - a.version)[0] ?? null;
  const latest = [...live].sort((a, b) => b.version - a.version)[0] ?? null;
  return { active, latest };
}

const nameOf = (orgId: string) => FEE_ORGS.find((o) => o.id === orgId)?.name ?? null;

export function feeView(s: MockFeeSchedule, all: MockFeeSchedule[], now: Date, canConfigure: boolean): FeeScheduleView {
  const t = timeline(all, now);
  const future = new Date(s.effectiveFrom).getTime() > now.getTime();
  const status = s.cancelledAt ? 'CANCELLED' : future ? 'SCHEDULED' : t.active?.id === s.id ? 'ACTIVE' : 'SUPERSEDED';
  return {
    id: s.id,
    version: s.version,
    status,
    effectiveFrom: s.effectiveFrom,
    currency: 'USD',
    defaultCommissionBps: s.defaultCommissionBps,
    categoryRates: Object.keys(s.categoryBps)
      .sort()
      .map((k) => ({ category: k, label: label(k), commissionBps: s.categoryBps[k] ?? 0 })),
    overrides: s.overrides
      .map((o) => ({
        orgId: o.orgId,
        orgName: nameOf(o.orgId),
        category: o.category === ALL_CATEGORIES ? null : o.category,
        categoryLabel: o.category === ALL_CATEGORIES ? 'All categories' : label(o.category),
        commissionBps: o.commissionBps
      }))
      .sort((a, b) => (a.orgName ?? a.orgId).localeCompare(b.orgName ?? b.orgId) || (a.category ?? '').localeCompare(b.category ?? '')),
    cardPctBps: s.cardPctBps,
    cardFixedMinor: s.cardFixedMinor,
    fbaPerUnitMinor: s.fbaPerUnitMinor,
    reverseCommissionOnRefund: s.reverseCommissionOnRefund,
    deliveryPricing: deliveryView(s.deliveryPricing),
    note: s.note,
    createdBy: s.createdBy,
    createdAt: s.createdAt,
    cancelledAt: s.cancelledAt,
    cancelledBy: s.cancelledBy,
    cancelReason: s.cancelReason,
    cancellable: canConfigure && status === 'SCHEDULED'
  };
}

function deliveryView(p: MockDeliveryPricing | null): DeliveryPricing | null {
  if (!p) return null;
  return {
    enabled: p.enabled,
    maxRadiusMiles: p.maxRadiusMiles,
    customerCharge: p.customerCharge ? structuredClone(p.customerCharge) : null,
    sellerFee: p.sellerFee ? structuredClone(p.sellerFee) : null,
    sellerOverrides: p.sellerOverrides
      .map((o) => ({ orgId: o.orgId, orgName: nameOf(o.orgId), sellerFee: structuredClone(o.sellerFee) }))
      .sort((a, b) => (a.orgName ?? a.orgId).localeCompare(b.orgName ?? b.orgId))
  };
}

/** payments POST /v1/delivery-pricing/quote under the version in force now. */
export function quoteDelivery(all: MockFeeSchedule[], now: Date, orgId: string, miles: number, packages: number): DeliveryQuote {
  const { active } = timeline(all, now);
  const p = active?.deliveryPricing ?? null;
  const base = { customerChargeMinor: 0, sellerFeeMinor: 0, sellerFeeSource: null, scheduleVersion: active?.version ?? null, maxRadiusMiles: p?.maxRadiusMiles ?? null, currency: 'USD' };
  if (!p || !p.enabled) return { ...base, available: false, unavailableReason: 'NOT_CONFIGURED' };
  if (miles > p.maxRadiusMiles) return { ...base, available: false, unavailableReason: 'BEYOND_MAX_RADIUS' };
  const override = p.sellerOverrides.find((o) => o.orgId === orgId);
  const retail = orgId === MOCK_RETAIL_ORG_ID;
  return {
    ...base,
    available: true,
    unavailableReason: null,
    customerChargeMinor: quoteTariff(p.customerCharge, miles, packages),
    sellerFeeMinor: retail ? 0 : quoteTariff(override ? override.sellerFee : p.sellerFee, miles, packages),
    sellerFeeSource: retail ? 'RETAIL' : override ? 'OVERRIDE' : 'DEFAULT'
  };
}

export function feeCategories(all: MockFeeSchedule[]): FeeCategory[] {
  const out: FeeCategory[] = [];
  const seen = new Set<string>();
  const add = (c: FeeCategory) => {
    if (!c.key || c.key === ALL_CATEGORIES || seen.has(c.key)) return;
    seen.add(c.key);
    out.push(c);
  };
  TAXONOMY.forEach((n) => add({ key: normalizeFeeCategory(n), label: n, source: 'TAXONOMY' }));
  add({ key: 'services', label: 'Services', source: 'PLATFORM' });
  all.forEach((s) => {
    Object.keys(s.categoryBps).forEach((k) => add({ key: k, label: label(k), source: 'SCHEDULE' }));
    s.overrides.forEach((o) => add({ key: o.category, label: label(o.category), source: 'SCHEDULE' }));
  });
  return out.sort((a, b) => a.label.toLowerCase().localeCompare(b.label.toLowerCase()));
}

export function feesOverview(all: MockFeeSchedule[], now: Date, canConfigure: boolean): FeesOverview {
  const t = timeline(all, now);
  const views = [...all].sort((a, b) => b.version - a.version).map((s) => feeView(s, all, now, canConfigure));
  const scheduled = t.latest && new Date(t.latest.effectiveFrom).getTime() > now.getTime();
  return {
    generatedAt: now.toISOString(),
    currency: 'USD',
    canConfigure,
    current: t.active ? feeView(t.active, all, now, canConfigure) : null,
    base: t.latest ? feeView(t.latest, all, now, canConfigure) : null,
    minEffectiveFrom: scheduled && t.latest ? t.latest.effectiveFrom : now.toISOString(),
    immediateAllowed: !scheduled,
    schedules: views,
    categories: feeCategories(all),
    taxonomyAvailable: true,
    orgNamesAvailable: true,
    retail: { orgId: MOCK_RETAIL_ORG_ID, name: RETAIL_NAME, note: RETAIL_NOTE },
    limits: FEE_LIMITS,
    exampleSaleMinor: 10_000,
    reverseOnRefundHelp: 'When on, a refund gives the seller back the commission on the refunded amount.'
  };
}

export function searchFeeOrgs(q: string): FeeOrgHit[] {
  const needle = q.trim().toLowerCase();
  return FEE_ORGS.filter((o) => o.name.toLowerCase().includes(needle) || o.id === q.trim())
    .slice(0, 20)
    .map((o) =>
      o.id === MOCK_RETAIL_ORG_ID
        ? { orgId: o.id, name: o.name, kind: 'RETAIL', status: 'ACTIVE', retail: true, selectable: false, reason: RETAIL_NOTE }
        : { orgId: o.id, name: o.name, kind: o.kind, status: o.status, retail: false, selectable: true, reason: null }
    );
}

/** Validates and appends a version (field errors, or the created version). */
export function createFeeSchedule(all: MockFeeSchedule[], d: FeeScheduleDraft, now: Date, actor: string): { errors: Record<string, string> } | { created: MockFeeSchedule } {
  const t = timeline(all, now);
  const scheduled = t.latest && new Date(t.latest.effectiveFrom).getTime() > now.getTime();
  const errors = validateFeeDraft(d, {
    now,
    minEffectiveFrom: scheduled && t.latest ? new Date(t.latest.effectiveFrom) : now,
    immediateAllowed: !scheduled,
    retailOrgId: MOCK_RETAIL_ORG_ID,
    limits: FEE_LIMITS
  });
  if (Object.keys(errors).length) return { errors };
  const created: MockFeeSchedule = {
    id: `fee-${all.length + 1}-${now.getTime().toString(36)}`,
    version: Math.max(0, ...all.map((s) => s.version)) + 1,
    effectiveFrom: d.immediate || !d.effectiveFrom ? now.toISOString() : new Date(d.effectiveFrom).toISOString(),
    categoryBps: Object.fromEntries(d.categoryRates.map((c) => [normalizeFeeCategory(c.category), c.commissionBps])),
    defaultCommissionBps: d.defaultCommissionBps,
    overrides: d.overrides.map((o) => ({ orgId: o.orgId.trim(), category: o.category && normalizeFeeCategory(o.category) ? normalizeFeeCategory(o.category) : ALL_CATEGORIES, commissionBps: o.commissionBps })),
    cardPctBps: d.cardPctBps,
    cardFixedMinor: d.cardFixedMinor,
    fbaPerUnitMinor: d.fbaPerUnitMinor,
    reverseCommissionOnRefund: d.reverseCommissionOnRefund,
    // Omitted = carried over from the latest live version (payments' rule).
    deliveryPricing: d.deliveryPricing
      ? {
          enabled: d.deliveryPricing.enabled,
          maxRadiusMiles: d.deliveryPricing.maxRadiusMiles ?? 0,
          customerCharge: d.deliveryPricing.customerCharge,
          sellerFee: d.deliveryPricing.sellerFee,
          sellerOverrides: d.deliveryPricing.sellerOverrides.map((o) => ({ orgId: o.orgId.trim(), sellerFee: o.sellerFee }))
        }
      : t.latest?.deliveryPricing
        ? structuredClone(t.latest.deliveryPricing)
        : null,
    note: d.note.trim(),
    createdBy: actor,
    createdAt: now.toISOString(),
    cancelledAt: null,
    cancelledBy: null,
    cancelReason: null
  };
  all.push(created);
  return { created };
}

/** The read-only "Fee schedules" table rows (resource fee-schedules) derived from the versions. */
export function feeRecords(all: MockFeeSchedule[], now: Date): MockRecord[] {
  return [...all]
    .sort((a, b) => b.version - a.version)
    .map((s) => {
      const v = feeView(s, all, now, false);
      return {
        id: s.id,
        fields: {
          version: s.version,
          effectiveFrom: s.effectiveFrom,
          status: v.status,
          defaultCommissionBps: s.defaultCommissionBps / 10_000,
          cardPctBps: s.cardPctBps / 10_000,
          fbaPerUnitMinor: s.fbaPerUnitMinor,
          reverseCommissionOnRefund: s.reverseCommissionOnRefund,
          note: s.note,
          createdBy: s.createdBy,
          cancelReason: s.cancelReason
        },
        activity: [
          { at: s.createdAt, actor: s.createdBy, text: `Version ${s.version} created` },
          ...(s.cancelledAt ? [{ at: s.cancelledAt, actor: s.cancelledBy ?? '', text: `Cancelled — ${s.cancelReason ?? ''}` }] : [])
        ]
      };
    });
}
