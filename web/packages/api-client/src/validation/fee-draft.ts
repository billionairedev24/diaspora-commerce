import { formatBps } from '../fees';
import { type DeliveryPricingDraft, type DeliveryTariff, type FeeLimits, type FeeScheduleDraft } from '../schemas/staff';

/* The rules the gateway BFF applies to a new fee-schedule version (internal/domain/fees.go ValidateFeeDraft), shared by
   the staff console (client-side, before review) and the MSW mocks. Errors are keyed like the BFF's:
   effectiveFrom, defaultCommissionBps, categoryRates.2.commissionBps, overrides.0.orgId… */

export interface FeeDraftRules {
  now: Date;
  /** Earliest effective time: now, or the latest scheduled version's effective date. */
  minEffectiveFrom: Date;
  /** false while a version is scheduled (a new one can't take effect before it). */
  immediateAllowed: boolean;
  retailOrgId: string;
  limits: FeeLimits;
}

export const ALL_CATEGORIES = '*';

/** payments' category key: trimmed and lower-case. */
export const normalizeFeeCategory = (s: string): string => s.trim().toLowerCase();

const money = (minor: number) => `$${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;

export function validateFeeDraft(d: FeeScheduleDraft, r: FeeDraftRules): Record<string, string> {
  const errs: Record<string, string> = {};
  const l = r.limits;
  const bps = (key: string, v: number | null | undefined, hi: number) => {
    if (v === null || v === undefined || !Number.isInteger(v)) errs[key] = 'Enter a rate';
    else if (v < 0 || v > hi) errs[key] = `Enter a rate between 0.00% and ${formatBps(hi)}`;
  };
  const minor = (key: string, v: number | null | undefined, hi: number) => {
    if (v === null || v === undefined || !Number.isInteger(v)) errs[key] = 'Enter an amount';
    else if (v < 0 || v > hi) errs[key] = `Enter an amount between $0.00 and ${money(hi)}`;
  };
  const at = d.effectiveFrom ? new Date(d.effectiveFrom) : null;
  if (d.immediate && d.effectiveFrom) errs.effectiveFrom = 'Choose a date or immediate, not both';
  else if (d.immediate && !r.immediateAllowed) errs.immediate = 'A version is already scheduled: schedule this one after it, or cancel it first';
  else if (!d.immediate && (!at || Number.isNaN(at.getTime()))) errs.effectiveFrom = 'Choose when the new version takes effect';
  else if (!d.immediate && at && at.getTime() <= r.now.getTime()) errs.effectiveFrom = 'Choose a time in the future (or take effect immediately)';
  else if (!d.immediate && at && at.getTime() < r.minEffectiveFrom.getTime())
    errs.effectiveFrom = `Must not be before the latest scheduled version (${r.minEffectiveFrom.toISOString().slice(0, 16).replace('T', ' ')} UTC)`;
  bps('defaultCommissionBps', d.defaultCommissionBps, l.commissionMaxBps);
  bps('cardPctBps', d.cardPctBps, l.cardPctMaxBps);
  minor('cardFixedMinor', d.cardFixedMinor, l.cardFixedMaxMinor);
  minor('fbaPerUnitMinor', d.fbaPerUnitMinor, l.fbaPerUnitMaxMinor);
  const note = d.note.trim().length;
  if (note < l.noteMinLength || note > l.noteMaxLength) errs.note = `Explain the change (${l.noteMinLength} to ${l.noteMaxLength} characters)`;
  if (d.categoryRates.length > l.maxCategoryRates) errs.categoryRates = `At most ${l.maxCategoryRates} categories`;
  const seen = new Set<string>();
  d.categoryRates.forEach((c, i) => {
    const key = normalizeFeeCategory(c.category);
    const f = `categoryRates.${i}`;
    if (!key || key === ALL_CATEGORIES) errs[`${f}.category`] = 'Name the category';
    else if (key.length > 80) errs[`${f}.category`] = 'Keep the category under 80 characters';
    else if (seen.has(key)) errs[`${f}.category`] = 'This category is listed twice';
    seen.add(key);
    bps(`${f}.commissionBps`, c.commissionBps, l.commissionMaxBps);
  });
  if (d.overrides.length > l.maxOverrides) errs.overrides = `At most ${l.maxOverrides} seller overrides`;
  const pairs = new Set<string>();
  d.overrides.forEach((o, i) => {
    const f = `overrides.${i}`;
    const org = o.orgId.trim();
    const cat = o.category && normalizeFeeCategory(o.category) ? normalizeFeeCategory(o.category) : ALL_CATEGORIES;
    if (!org || org.length > 80) errs[`${f}.orgId`] = 'Choose a seller organisation';
    else if (r.retailOrgId && org === r.retailOrgId) errs[`${f}.orgId`] = 'AfroTransact Retail pays no commission and no fees (merchant of record); it can’t have an override';
    else if (pairs.has(`${org}|${cat}`)) errs[`${f}.category`] = 'This seller already has an override for this category';
    if (cat.length > 80) errs[`${f}.category`] = 'Keep the category under 80 characters';
    pairs.add(`${org}|${cat}`);
    bps(`${f}.commissionBps`, o.commissionBps, l.commissionMaxBps);
  });
  Object.assign(errs, validateDeliveryDraft(d.deliveryPricing ?? null, r.retailOrgId, l));
  return errs;
}

/* AfroTransact Delivery pricing (S255), the BFF's ValidateDeliveryDraft: keys deliveryPricing.<property>. */

function validateTariff(errs: Record<string, string>, key: string, t: DeliveryTariff | null, l: FeeLimits) {
  if (!t) return;
  const amount = (field: string, v: number | null, required: boolean) => {
    if (v === null || v === undefined) {
      if (required) errs[`${key}.${field}`] = 'Enter an amount';
    } else if (!Number.isInteger(v) || v < 0 || v > l.deliveryAmountMaxMinor) errs[`${key}.${field}`] = `Enter an amount between $0.00 and ${money(l.deliveryAmountMaxMinor)}`;
  };
  if (t.mode === 'FLAT') {
    amount('flatPerPackageMinor', t.flatPerPackageMinor, true);
    return;
  }
  if (t.mode !== 'DISTANCE') {
    errs[`${key}.mode`] = 'Choose flat or by distance';
    return;
  }
  amount('baseMinor', t.baseMinor, true);
  amount('perMileMinor', t.perMileMinor, true);
  amount('minMinor', t.minMinor, false);
  amount('maxMinor', t.maxMinor, false);
  if (t.minMinor !== null && t.maxMinor !== null && t.minMinor > t.maxMinor && !errs[`${key}.maxMinor`]) errs[`${key}.maxMinor`] = 'The maximum must not be below the minimum';
  if (t.bands.length > l.deliveryMaxBands) errs[`${key}.bands`] = `At most ${l.deliveryMaxBands} distance bands`;
  const order = t.bands.map((_, i) => i).sort((a, b) => (t.bands[a]?.fromMiles ?? 0) - (t.bands[b]?.fromMiles ?? 0));
  order.forEach((i, n) => {
    const b = t.bands[i];
    if (!b) return;
    const bk = `${key}.bands.${i}`;
    const prev = n > 0 ? t.bands[order[n - 1] ?? 0] : undefined;
    if (!Number.isFinite(b.fromMiles) || b.fromMiles < 0) errs[`${bk}.fromMiles`] = 'Start at 0 miles or more';
    else if (!Number.isFinite(b.toMiles) || b.toMiles <= b.fromMiles) errs[`${bk}.toMiles`] = 'Must be more than the start of the band';
    else if (prev && b.fromMiles < prev.toMiles) errs[`${bk}.fromMiles`] = 'Bands must not overlap';
    if (!Number.isInteger(b.feeMinor) || b.feeMinor < 0 || b.feeMinor > l.deliveryAmountMaxMinor) errs[`${bk}.feeMinor`] = `Enter an amount between $0.00 and ${money(l.deliveryAmountMaxMinor)}`;
  });
}

export function validateDeliveryDraft(d: DeliveryPricingDraft | null, retailOrgId: string, l: FeeLimits): Record<string, string> {
  const errs: Record<string, string> = {};
  if (!d) return errs;
  const p = 'deliveryPricing.';
  if (d.maxRadiusMiles === null || !Number.isFinite(d.maxRadiusMiles)) errs[`${p}maxRadiusMiles`] = 'Enter the maximum radius in miles';
  else if (d.maxRadiusMiles <= 0 || d.maxRadiusMiles > l.deliveryMaxRadiusMiles) errs[`${p}maxRadiusMiles`] = `Enter more than 0 and at most ${l.deliveryMaxRadiusMiles} miles`;
  validateTariff(errs, `${p}customerCharge`, d.customerCharge, l);
  validateTariff(errs, `${p}sellerFee`, d.sellerFee, l);
  if (d.sellerOverrides.length > l.maxDeliveryOverrides) errs[`${p}sellerOverrides`] = `At most ${l.maxDeliveryOverrides} seller overrides`;
  const seen = new Set<string>();
  d.sellerOverrides.forEach((o, i) => {
    const key = `${p}sellerOverrides.${i}`;
    const org = o.orgId.trim();
    if (!org || org.length > 80) errs[`${key}.orgId`] = 'Choose a seller organisation';
    else if (retailOrgId && org === retailOrgId) errs[`${key}.orgId`] = 'AfroTransact Retail never pays a delivery fee; it can’t have an override';
    else if (seen.has(org)) errs[`${key}.orgId`] = 'This seller already has a delivery fee override';
    seen.add(org);
    validateTariff(errs, `${key}.sellerFee`, o.sellerFee, l);
  });
  return errs;
}
