import {
  type DeliveryPricing,
  type DeliveryPricingDraft,
  type DeliveryTariff,
  type FeeScheduleView,
  quoteTariff,
  tariffSummary
} from '@afrotransact/api-client';

import { type MessageKey, type Translator } from '@/i18n/t';

import { dollarsText, milesText, parseDollars, parseMiles, rowId } from './money-input';

/* AfroTransact Delivery from the seller's store (S255): the delivery pricing section of a new fee-schedule version.
   The form keeps dollars and miles as typed; toDeliveryDraft converts them to minor units with error keys like the
   BFF's (deliveryPricing.customerCharge.baseMinor…). Leaving the section out of a version carries the latest live
   version's section over (payments' rule). Pure functions, tested in test/delivery.test.ts. */

export type TariffMode = 'FLAT' | 'DISTANCE';

export interface BandRow {
  rowId: string;
  from: string;
  to: string;
  fee: string;
}

export interface TariffForm {
  mode: TariffMode;
  flat: string;
  base: string;
  perMile: string;
  min: string;
  max: string;
  bands: BandRow[];
}

export interface DeliveryOverrideRow {
  rowId: string;
  orgId: string;
  orgName: string;
  tariff: TariffForm;
}

export interface DeliveryForm {
  /** false = the section is left out and the latest live version's pricing carries over. */
  include: boolean;
  enabled: boolean;
  radius: string;
  customer: TariffForm;
  seller: TariffForm;
  overrides: DeliveryOverrideRow[];
}

/** payments' launch defaults (V9): customer $4.99 + $0.85/mi, $5.99–$24.99, 25 miles; sellers $2.00 per package. */
const LAUNCH: DeliveryPricing = {
  enabled: true,
  maxRadiusMiles: 25,
  customerCharge: { mode: 'DISTANCE', flatPerPackageMinor: null, baseMinor: 499, perMileMinor: 85, bands: [], minMinor: 599, maxMinor: 2499 },
  sellerFee: { mode: 'FLAT', flatPerPackageMinor: 200, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null },
  sellerOverrides: []
};

const amount = (minor: number | null) => (minor === null ? '' : dollarsText(minor));

export function tariffForm(t: DeliveryTariff | null): TariffForm {
  if (!t) return { mode: 'FLAT', flat: '0.00', base: '', perMile: '', min: '', max: '', bands: [] };
  return {
    mode: t.mode,
    flat: amount(t.flatPerPackageMinor),
    base: amount(t.baseMinor),
    perMile: amount(t.perMileMinor),
    min: amount(t.minMinor),
    max: amount(t.maxMinor),
    bands: t.bands.map((b) => ({ rowId: rowId(), from: milesText(b.fromMiles), to: milesText(b.toMiles), fee: dollarsText(b.feeMinor) }))
  };
}

/** The section starts as a copy of the base version's (launch defaults when it has none), left out until edited. */
export function deliveryFormFrom(base: FeeScheduleView | null): DeliveryForm {
  const p = base?.deliveryPricing ?? LAUNCH;
  return {
    include: false,
    enabled: p.enabled,
    radius: milesText(p.maxRadiusMiles),
    customer: tariffForm(p.customerCharge),
    seller: tariffForm(p.sellerFee),
    overrides: p.sellerOverrides.map((o) => ({ rowId: rowId(), orgId: o.orgId, orgName: o.orgName ?? o.orgId, tariff: tariffForm(o.sellerFee) }))
  };
}

/** Converts one tariff; errors keyed `${key}.flatPerPackageMinor` etc. Typing errors only (ranges are checked by the shared rules). */
function toTariff(f: TariffForm, key: string, errors: Record<string, string>, t: Translator<MessageKey>): DeliveryTariff {
  const money = (field: string, text: string, optional = false): number | null => {
    if (optional && !text.trim()) return null;
    const v = parseDollars(text);
    if (v === null) errors[`${key}.${field}`] = t('st.delivery.err.amount');
    return v;
  };
  if (f.mode === 'FLAT') {
    return { mode: 'FLAT', flatPerPackageMinor: money('flatPerPackageMinor', f.flat) ?? 0, baseMinor: null, perMileMinor: null, bands: [], minMinor: null, maxMinor: null };
  }
  const bands = f.bands.map((b, i) => {
    const from = parseMiles(b.from);
    const to = parseMiles(b.to);
    if (from === null) errors[`${key}.bands.${i}.fromMiles`] = t('st.delivery.err.miles');
    if (to === null) errors[`${key}.bands.${i}.toMiles`] = t('st.delivery.err.miles');
    return { fromMiles: from ?? 0, toMiles: to ?? 0, feeMinor: money(`bands.${i}.feeMinor`, b.fee) ?? 0 };
  });
  return {
    mode: 'DISTANCE',
    flatPerPackageMinor: null,
    baseMinor: money('baseMinor', f.base) ?? 0,
    perMileMinor: money('perMileMinor', f.perMile) ?? 0,
    bands,
    minMinor: money('minMinor', f.min, true),
    maxMinor: money('maxMinor', f.max, true)
  };
}

/** The draft's section (undefined when left out = carried over); typing errors go into `errors`. */
export function toDeliveryDraft(f: DeliveryForm, errors: Record<string, string>, t: Translator<MessageKey>): DeliveryPricingDraft | undefined {
  if (!f.include) return undefined;
  const radius = parseMiles(f.radius);
  if (radius === null) errors['deliveryPricing.maxRadiusMiles'] = t('st.delivery.err.radius');
  return {
    enabled: f.enabled,
    maxRadiusMiles: radius,
    customerCharge: toTariff(f.customer, 'deliveryPricing.customerCharge', errors, t),
    sellerFee: toTariff(f.seller, 'deliveryPricing.sellerFee', errors, t),
    sellerOverrides: f.overrides.map((o, i) => ({ orgId: o.orgId, sellerFee: toTariff(o.tariff, `deliveryPricing.sellerOverrides.${i}.sellerFee`, errors, t) }))
  };
}

/* ── review ── */

export interface DeliveryDiffRow {
  key: string;
  label: string;
  kind: 'added' | 'removed' | 'changed';
  from: string;
  to: string;
}

/** What the section changes compared with the base version (none when it is carried over). */
export function diffDelivery(base: DeliveryPricing | null, d: DeliveryPricingDraft | undefined, orgName: (id: string) => string, t: Translator<MessageKey>): DeliveryDiffRow[] {
  if (!d) return [];
  const rows: DeliveryDiffRow[] = [];
  const cmp = (key: string, label: string, from: string | null, to: string | null) => {
    if (from === to) return;
    rows.push({ key, label, kind: from === null ? 'added' : to === null ? 'removed' : 'changed', from: from ?? '—', to: to ?? '—' });
  };
  const offered = (on: boolean) => (on ? t('st.delivery.offered') : t('st.delivery.notOffered'));
  cmp('delivery:enabled', t('st.delivery.diff.enabled'), base ? offered(base.enabled) : null, offered(d.enabled));
  cmp('delivery:radius', t('st.delivery.diff.radius'), base ? t('st.delivery.miles', { n: base.maxRadiusMiles }) : null, d.maxRadiusMiles === null ? null : t('st.delivery.miles', { n: d.maxRadiusMiles }));
  cmp('delivery:customer', t('st.delivery.diff.customer'), base ? tariffSummary(base.customerCharge) : null, tariffSummary(d.customerCharge));
  cmp('delivery:seller', t('st.delivery.diff.seller'), base ? tariffSummary(base.sellerFee) : null, tariffSummary(d.sellerFee));
  const before = new Map((base?.sellerOverrides ?? []).map((o) => [o.orgId, tariffSummary(o.sellerFee)]));
  const after = new Map(d.sellerOverrides.map((o) => [o.orgId, tariffSummary(o.sellerFee)]));
  [...new Set([...before.keys(), ...after.keys()])].sort().forEach((org) =>
    cmp(`delivery:override:${org}`, t('st.delivery.diff.override', { seller: orgName(org) }), before.get(org) ?? null, after.get(org) ?? null)
  );
  return rows;
}

export interface QuotePreview {
  available: boolean;
  reason: 'NOT_CONFIGURED' | 'BEYOND_MAX_RADIUS' | null;
  customerMinor: number;
  sellerMinor: number;
  source: 'DEFAULT' | 'OVERRIDE' | 'RETAIL';
}

type PricingLike = Pick<DeliveryPricing, 'enabled' | 'customerCharge' | 'sellerFee'> & { maxRadiusMiles: number | null; sellerOverrides: { orgId: string; sellerFee: DeliveryTariff }[] };

/** payments' quote for a section (the live version's, or a draft's): radius, then customer charge and seller fee. */
export function previewQuote(p: PricingLike | null, orgId: string, miles: number, packages: number, retailOrgId: string): QuotePreview {
  const none = { customerMinor: 0, sellerMinor: 0, source: 'DEFAULT' as const };
  if (!p || !p.enabled) return { ...none, available: false, reason: 'NOT_CONFIGURED' };
  if (p.maxRadiusMiles !== null && miles > p.maxRadiusMiles) return { ...none, available: false, reason: 'BEYOND_MAX_RADIUS' };
  const retail = !!orgId && orgId === retailOrgId;
  const override = p.sellerOverrides.find((o) => o.orgId === orgId);
  return {
    available: true,
    reason: null,
    customerMinor: quoteTariff(p.customerCharge, miles, packages),
    sellerMinor: retail ? 0 : quoteTariff(override ? override.sellerFee : p.sellerFee, miles, packages),
    source: retail ? 'RETAIL' : override ? 'OVERRIDE' : 'DEFAULT'
  };
}

/** Example trips shown on review: what a shopper and a seller pay now and under the new version. */
export const EXAMPLE_MILES = [3, 10, 20] as const;
