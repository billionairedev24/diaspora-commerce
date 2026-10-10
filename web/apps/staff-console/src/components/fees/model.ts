import {
  ALL_CATEGORIES,
  applyBps,
  bpsToPercentText,
  type FeeCategory,
  type FeeScheduleDraft,
  type FeeScheduleView,
  type FeesOverview,
  formatBps,
  normalizeFeeCategory,
  parsePercent,
  validateFeeDraft
} from '@afrotransact/api-client';

/* The fee-schedule editor's model: the form keeps what people type (percentages with two decimals, dollars), converts to
   basis points / minor units for the BFF, validates with the BFF's rules and compares the draft with the current
   version (diff, $100 impact preview, sellers affected). Pure functions, tested in test/fees.test.ts. */

export interface CategoryRow {
  rowId: string;
  category: string;
  rate: string;
}

export interface OverrideRow {
  rowId: string;
  orgId: string;
  orgName: string;
  /** "" = every category. */
  category: string;
  rate: string;
}

export interface FeeForm {
  effectiveAt: string;
  immediate: boolean;
  defaultCommission: string;
  categories: CategoryRow[];
  overrides: OverrideRow[];
  cardPct: string;
  cardFixed: string;
  fbaPerUnit: string;
  reverseOnRefund: boolean;
  note: string;
}

let seq = 0;
export const rowId = (): string => `r${(seq += 1)}`;

const dollarsText = (minor: number): string => `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;

/** Dollars typed by people ("3.25", "$3.25", "3") → minor units, exactly; null when not an amount. */
export function parseDollars(text: string): number | null {
  const m = /^\$?\s*(\d{1,6})(?:\.(\d{0,2}))?$/.exec(text.trim());
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** datetime-local value (local time, minutes) for a Date. */
export function localInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Suggested start of a new version: the day after the earliest allowed time, at 00:00 local. */
export function suggestedStart(minEffectiveFrom: string, now: Date): Date {
  const min = new Date(Math.max(new Date(minEffectiveFrom).getTime(), now.getTime()));
  return new Date(min.getFullYear(), min.getMonth(), min.getDate() + 1, 0, 0, 0, 0);
}

/** "New version" starts as a copy of the base (latest scheduled, else active) version. */
export function formFrom(base: FeeScheduleView | null, overview: Pick<FeesOverview, 'minEffectiveFrom'>, now: Date): FeeForm {
  return {
    effectiveAt: localInput(suggestedStart(overview.minEffectiveFrom, now)),
    immediate: false,
    defaultCommission: bpsToPercentText(base?.defaultCommissionBps ?? 1000),
    categories: (base?.categoryRates ?? []).map((c) => ({ rowId: rowId(), category: c.category, rate: bpsToPercentText(c.commissionBps) })),
    overrides: (base?.overrides ?? []).map((o) => ({ rowId: rowId(), orgId: o.orgId, orgName: o.orgName ?? o.orgId, category: o.category ?? '', rate: bpsToPercentText(o.commissionBps) })),
    cardPct: bpsToPercentText(base?.cardPctBps ?? 290),
    cardFixed: dollarsText(base?.cardFixedMinor ?? 30),
    fbaPerUnit: dollarsText(base?.fbaPerUnitMinor ?? 325),
    reverseOnRefund: base?.reverseCommissionOnRefund ?? true,
    note: ''
  };
}

export interface DraftResult {
  draft: FeeScheduleDraft | null;
  /** Keyed like the BFF's field errors (categoryRates.2.commissionBps…). */
  errors: Record<string, string>;
}

/** Converts the form to the BFF draft (percent → bps, dollars → minor units) and validates it with the BFF's rules. */
export function toDraft(form: FeeForm, overview: Pick<FeesOverview, 'minEffectiveFrom' | 'immediateAllowed' | 'retail' | 'limits'>, now: Date): DraftResult {
  const errors: Record<string, string> = {};
  const pct = (key: string, text: string): number => {
    const v = parsePercent(text);
    if (v === null) {
      errors[key] = 'Enter a percentage with up to two decimals, e.g. 12.50';
      return 0;
    }
    return v;
  };
  const usd = (key: string, text: string): number => {
    const v = parseDollars(text);
    if (v === null) {
      errors[key] = 'Enter an amount in dollars, e.g. 3.25';
      return 0;
    }
    return v;
  };
  let effectiveFrom: string | null = null;
  if (!form.immediate) {
    const d = form.effectiveAt ? new Date(form.effectiveAt) : null;
    if (d && !Number.isNaN(d.getTime())) effectiveFrom = d.toISOString();
  }
  const draft: FeeScheduleDraft = {
    effectiveFrom,
    immediate: form.immediate,
    defaultCommissionBps: pct('defaultCommissionBps', form.defaultCommission),
    categoryRates: form.categories.map((c, i) => ({ category: c.category, commissionBps: pct(`categoryRates.${i}.commissionBps`, c.rate) })),
    overrides: form.overrides.map((o, i) => ({ orgId: o.orgId, category: o.category ? o.category : null, commissionBps: pct(`overrides.${i}.commissionBps`, o.rate) })),
    cardPctBps: pct('cardPctBps', form.cardPct),
    cardFixedMinor: usd('cardFixedMinor', form.cardFixed),
    fbaPerUnitMinor: usd('fbaPerUnitMinor', form.fbaPerUnit),
    reverseCommissionOnRefund: form.reverseOnRefund,
    note: form.note
  };
  const rules = validateFeeDraft(draft, {
    now,
    minEffectiveFrom: new Date(overview.minEffectiveFrom),
    immediateAllowed: overview.immediateAllowed,
    retailOrgId: overview.retail.orgId,
    limits: overview.limits
  });
  // Typing errors win over range errors for the same field.
  const all = { ...rules, ...errors };
  return { draft: Object.keys(all).length ? null : draft, errors: all };
}

/* ── review ── */

export interface DiffRow {
  key: string;
  label: string;
  kind: 'added' | 'removed' | 'changed';
  from: string;
  to: string;
}

const yesNo = (b: boolean) => (b ? 'Returned to the seller' : 'Kept by AfroTransact');
const money = (minor: number) => `$${dollarsText(minor)}`;

export function categoryLabel(key: string, categories: FeeCategory[]): string {
  if (!key || key === ALL_CATEGORIES) return 'All categories';
  return categories.find((c) => c.key === normalizeFeeCategory(key))?.label ?? key.charAt(0).toUpperCase() + key.slice(1);
}

/** What the draft changes compared with the base version (every rate, fee and override). */
export function diffDraft(base: FeeScheduleView | null, d: FeeScheduleDraft, categories: FeeCategory[], orgName: (id: string) => string): DiffRow[] {
  const rows: DiffRow[] = [];
  const cmp = (key: string, label: string, from: string | null, to: string | null) => {
    if (from === to) return;
    rows.push({ key, label, kind: from === null ? 'added' : to === null ? 'removed' : 'changed', from: from ?? '—', to: to ?? '—' });
  };
  cmp('defaultCommissionBps', 'Default commission', base ? formatBps(base.defaultCommissionBps) : null, formatBps(d.defaultCommissionBps));
  const before = new Map((base?.categoryRates ?? []).map((c) => [normalizeFeeCategory(c.category), c.commissionBps]));
  const after = new Map(d.categoryRates.map((c) => [normalizeFeeCategory(c.category), c.commissionBps]));
  [...new Set([...before.keys(), ...after.keys()])].sort().forEach((k) => {
    const f = before.get(k);
    const t = after.get(k);
    cmp(`category:${k}`, `${categoryLabel(k, categories)} commission`, f === undefined ? null : formatBps(f), t === undefined ? null : formatBps(t));
  });
  const okey = (orgId: string, cat: string | null) => `${orgId}|${cat && normalizeFeeCategory(cat) ? normalizeFeeCategory(cat) : ALL_CATEGORIES}`;
  const ob = new Map((base?.overrides ?? []).map((o) => [okey(o.orgId, o.category), o.commissionBps]));
  const oa = new Map(d.overrides.map((o) => [okey(o.orgId, o.category), o.commissionBps]));
  [...new Set([...ob.keys(), ...oa.keys()])].sort().forEach((k) => {
    const [org = '', cat = ALL_CATEGORIES] = k.split('|');
    const f = ob.get(k);
    const t = oa.get(k);
    cmp(`override:${k}`, `${orgName(org)} · ${categoryLabel(cat, categories)}`, f === undefined ? null : formatBps(f), t === undefined ? null : formatBps(t));
  });
  cmp('cardFee', 'Card fee estimate', base ? `${formatBps(base.cardPctBps)} + ${money(base.cardFixedMinor)}` : null, `${formatBps(d.cardPctBps)} + ${money(d.cardFixedMinor)}`);
  cmp('fbaPerUnitMinor', 'FBA fee per unit', base ? money(base.fbaPerUnitMinor) : null, money(d.fbaPerUnitMinor));
  cmp('reverseCommissionOnRefund', 'Commission on refunds', base ? yesNo(base.reverseCommissionOnRefund) : null, yesNo(d.reverseCommissionOnRefund));
  return rows;
}

export interface ImpactRow {
  category: string;
  label: string;
  beforeBps: number;
  afterBps: number;
  beforeMinor: number;
  afterMinor: number;
}

/** Commission on an example sale per category, under the current and the new version (category rate, else default). */
export function impactRows(current: FeeScheduleView | null, d: FeeScheduleDraft, categories: FeeCategory[], saleMinor: number): ImpactRow[] {
  const before = new Map((current?.categoryRates ?? []).map((c) => [normalizeFeeCategory(c.category), c.commissionBps]));
  const after = new Map(d.categoryRates.map((c) => [normalizeFeeCategory(c.category), c.commissionBps]));
  const keys = [...new Set([...before.keys(), ...after.keys()])];
  const rows = keys.map((k) => {
    const b = before.get(k) ?? current?.defaultCommissionBps ?? 0;
    const a = after.get(k) ?? d.defaultCommissionBps;
    return { category: k, label: categoryLabel(k, categories), beforeBps: b, afterBps: a, beforeMinor: applyBps(saleMinor, b), afterMinor: applyBps(saleMinor, a) };
  });
  rows.sort((x, y) => x.label.localeCompare(y.label));
  const b = current?.defaultCommissionBps ?? 0;
  rows.push({ category: '', label: 'Any other category', beforeBps: b, afterBps: d.defaultCommissionBps, beforeMinor: applyBps(saleMinor, b), afterMinor: applyBps(saleMinor, d.defaultCommissionBps) });
  return rows;
}

export interface AffectedSeller {
  orgId: string;
  orgName: string;
  changes: { category: string; from: number | null; to: number | null }[];
}

/** Sellers whose negotiated rates are added, changed or removed by the draft. */
export function affectedSellers(base: FeeScheduleView | null, d: FeeScheduleDraft, categories: FeeCategory[], orgName: (id: string) => string): AffectedSeller[] {
  const norm = (c: string | null) => (c && normalizeFeeCategory(c) ? normalizeFeeCategory(c) : ALL_CATEGORIES);
  const before = new Map((base?.overrides ?? []).map((o) => [`${o.orgId}|${norm(o.category)}`, o.commissionBps]));
  const after = new Map(d.overrides.map((o) => [`${o.orgId}|${norm(o.category)}`, o.commissionBps]));
  const byOrg = new Map<string, AffectedSeller>();
  [...new Set([...before.keys(), ...after.keys()])].forEach((k) => {
    const f = before.get(k) ?? null;
    const t = after.get(k) ?? null;
    if (f === t) return;
    const [org = '', cat = ALL_CATEGORIES] = k.split('|');
    const entry = byOrg.get(org) ?? { orgId: org, orgName: orgName(org), changes: [] };
    entry.changes.push({ category: categoryLabel(cat, categories), from: f, to: t });
    byOrg.set(org, entry);
  });
  return [...byOrg.values()].sort((a, b) => a.orgName.localeCompare(b.orgName));
}
