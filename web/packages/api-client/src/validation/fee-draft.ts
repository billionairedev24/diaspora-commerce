import { formatBps } from '../fees';
import { type FeeLimits, type FeeScheduleDraft } from '../schemas/staff';

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
  return errs;
}
