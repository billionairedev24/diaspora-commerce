import {
  ALL_CATEGORIES,
  bpsToPercentText,
  type FeeDraftPlanRate,
  type FeeKind,
  type FeePlan,
  type FeeScheduleDraft,
  type FeeScheduleView,
  type FeesOverview,
  normalizeFeeCategory,
  parsePercent,
  validateFeeDraft
} from '@afrotransact/api-client';

/* Rate matrices of Fees & commissions: product commission and service connection fees, each a category × (category /
   default rate, then one column per plan) grid. Saving creates a new fee-schedule version from the base version with
   only that section changed (the other one, the card / FBA fees and the delivery pricing are carried over). Pure
   functions, tested in test/fee-rates.test.tsx. */

/** Key of the "other categories" row: the default rate, and each plan's '*' rate. */
export const OTHER = '';

export interface MatrixRow {
  /** Category key, or OTHER. */
  key: string;
  /** Category / default rate (percent text). */
  base: string;
  /** Plan code → percent text ('' = no plan rate for this row: the plan's other rate, else the category rate applies). */
  plans: Record<string, string>;
}

export interface RateMatrix {
  kind: FeeKind;
  plans: FeePlan[];
  rows: MatrixRow[];
}

/** The plans whose rates a section holds: SELLER plans price commission, PROVIDER plans connection fees. */
export function plansOf(all: FeePlan[], kind: FeeKind): FeePlan[] {
  return all.filter((p) => (kind === 'SERVICE' ? p.kind === 'PROVIDER' : p.kind !== 'PROVIDER'));
}

export function matrixFrom(view: FeeScheduleView | null, allPlans: FeePlan[], kind: FeeKind): RateMatrix {
  const plans = plansOf(allPlans, kind);
  const section =
    kind === 'SERVICE'
      ? { defaultBps: view?.connectionFee?.defaultBps ?? 1000, categories: view?.connectionFee?.categoryRates.map((r) => ({ key: r.category, bps: r.bps })) ?? [], planRates: view?.connectionFee?.planRates ?? [] }
      : { defaultBps: view?.defaultCommissionBps ?? 1000, categories: view?.categoryRates.map((r) => ({ key: r.category, bps: r.commissionBps })) ?? [], planRates: view?.planRates ?? [] };
  const codes = new Set(plans.map((p) => p.code));
  // Plans the version prices but the catalogue no longer lists still show (their rates are kept on save).
  section.planRates.forEach((r) => {
    if (!codes.has(r.planCode)) {
      codes.add(r.planCode);
      plans.push({ code: r.planCode, kind: kind === 'SERVICE' ? 'PROVIDER' : 'SELLER', name: r.planName, status: 'UNLISTED', visibility: 'HIDDEN', featureText: null, advertised: null, configured: null, warning: null });
    }
  });
  const keys = new Set(section.categories.map((c) => c.key));
  section.planRates.forEach((r) => r.category !== ALL_CATEGORIES && keys.add(r.category));
  const planText = (code: string, key: string) => {
    const r = section.planRates.find((x) => x.planCode === code && x.category === (key === OTHER ? ALL_CATEGORIES : key));
    return r ? bpsToPercentText(r.bps) : '';
  };
  const row = (key: string, baseBps: number | undefined): MatrixRow => ({
    key,
    base: baseBps === undefined ? '' : bpsToPercentText(baseBps),
    plans: Object.fromEntries(plans.map((p) => [p.code, planText(p.code, key)]))
  });
  return {
    kind,
    plans,
    rows: [row(OTHER, section.defaultBps), ...[...keys].sort().map((k) => row(k, section.categories.find((c) => c.key === k)?.bps))]
  };
}

/** Adds a category row (no-op when it exists). */
export function addRow(m: RateMatrix, category: string): RateMatrix {
  const key = normalizeFeeCategory(category);
  if (!key || key === ALL_CATEGORIES || m.rows.some((r) => r.key === key)) return m;
  return { ...m, rows: [...m.rows, { key, base: '', plans: Object.fromEntries(m.plans.map((p) => [p.code, ''])) }] };
}

export function setCell(m: RateMatrix, key: string, column: string | null, text: string): RateMatrix {
  return {
    ...m,
    rows: m.rows.map((r) => (r.key !== key ? r : column === null ? { ...r, base: text } : { ...r, plans: { ...r.plans, [column]: text } }))
  };
}

export interface MatrixDraft {
  draft: FeeScheduleDraft | null;
  errors: Record<string, string>;
}

const cellKey = (key: string, column: string | null) => `${key || 'other'}:${column ?? 'base'}`;
export { cellKey };

/**
 * A new version with this section replaced, copied from the base version otherwise. Errors are keyed by cell
 * (`<category or other>:<plan or base>`), plus `effectiveFrom` / `note` from the BFF's draft rules.
 */
export function draftFromMatrix(
  m: RateMatrix,
  base: FeeScheduleView,
  overview: Pick<FeesOverview, 'minEffectiveFrom' | 'immediateAllowed' | 'retail' | 'limits'>,
  when: { immediate: boolean; effectiveFrom: string | null },
  note: string,
  now: Date
): MatrixDraft {
  const errors: Record<string, string> = {};
  const pct = (key: string, column: string | null, text: string, required: boolean): number | null => {
    if (!text.trim()) {
      if (required) errors[cellKey(key, column)] = 'Enter a rate';
      return null;
    }
    const v = parsePercent(text);
    if (v === null || v > overview.limits.commissionMaxBps) {
      errors[cellKey(key, column)] = `Enter 0.00 to ${bpsToPercentText(overview.limits.commissionMaxBps)}`;
      return null;
    }
    return v;
  };
  let defaultBps = 0;
  const categoryRates: { category: string; bps: number }[] = [];
  const planRates: FeeDraftPlanRate[] = [];
  m.rows.forEach((r) => {
    const b = pct(r.key, null, r.base, r.key === OTHER);
    if (r.key === OTHER) defaultBps = b ?? 0;
    else if (b !== null) categoryRates.push({ category: r.key, bps: b });
    m.plans.forEach((p) => {
      const v = pct(r.key, p.code, r.plans[p.code] ?? '', false);
      if (v !== null) planRates.push({ planCode: p.code, category: r.key === OTHER ? ALL_CATEGORIES : r.key, bps: v });
    });
  });
  const draft: FeeScheduleDraft = {
    effectiveFrom: when.immediate ? null : when.effectiveFrom,
    immediate: when.immediate,
    defaultCommissionBps: base.defaultCommissionBps,
    categoryRates: base.categoryRates.map((c) => ({ category: c.category, commissionBps: c.commissionBps })),
    overrides: base.overrides.map((o) => ({ orgId: o.orgId, category: o.category, commissionBps: o.commissionBps })),
    cardPctBps: base.cardPctBps,
    cardFixedMinor: base.cardFixedMinor,
    fbaPerUnitMinor: base.fbaPerUnitMinor,
    reverseCommissionOnRefund: base.reverseCommissionOnRefund,
    note
  };
  if (m.kind === 'SERVICE') {
    draft.connectionFee = { defaultBps, categoryRates, planRates };
  } else {
    draft.defaultCommissionBps = defaultBps;
    draft.categoryRates = categoryRates.map((c) => ({ category: c.category, commissionBps: c.bps }));
    draft.planRates = planRates;
  }
  const rules = validateFeeDraft(draft, {
    now,
    minEffectiveFrom: new Date(overview.minEffectiveFrom),
    immediateAllowed: overview.immediateAllowed,
    retailOrgId: overview.retail.orgId,
    limits: overview.limits
  });
  for (const k of ['effectiveFrom', 'immediate', 'note']) if (rules[k]) errors[k] = rules[k];
  return { draft: Object.keys(errors).length ? null : draft, errors };
}

/** The range a plan column charges ("8–12%"), for the header. */
export function columnRange(m: RateMatrix, code: string): string | null {
  const vals = m.rows.map((r) => parsePercent(r.plans[code] ?? '')).filter((v): v is number => v !== null);
  if (!vals.length) return null;
  const lo = Math.min(...vals);
  const hi = Math.max(...vals);
  const p = (b: number) => (b % 100 === 0 ? String(b / 100) : bpsToPercentText(b));
  return lo === hi ? `${p(lo)}%` : `${p(lo)}–${p(hi)}%`;
}
