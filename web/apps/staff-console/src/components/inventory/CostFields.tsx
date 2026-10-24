'use client';

import { type CostComponents, type CostCurrency, inventoryHubQueries, minorDigits, type ReceiveCostInput, type SuggestedRate, usdMinorOf } from '@afrotransact/api-client';
import { Checkbox, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';

import { useT } from '@/i18n/t';
import { money, shortDate, toMinorIn } from '@/lib/format';

import { useHub } from './shared';

/** What the receiver typed for the cost of one counted line (text, as typed). */
export interface CostDraft {
  currency: string;
  unit: string;
  /** The rate the receiver typed; empty and untouched means "use the rates table's". */
  rate: string;
  rateTouched: boolean;
  breakdown: boolean;
  goods: string;
  freight: string;
  duty: string;
  other: string;
}

export const emptyCost = (): CostDraft => ({ currency: 'USD', unit: '', rate: '', rateTouched: false, breakdown: false, goods: '', freight: '', duty: '', other: '' });

/** The cost as the BFF takes it, the errors to show by field, and the unit cost in US cents when it can be valued. */
export interface CostCheck {
  /** Undefined when no cost was entered (the units are received at $0). */
  input?: ReceiveCostInput;
  errors: Record<string, string>;
  tableRate?: SuggestedRate;
  rate: string;
  unitMinor: number | null;
  usdUnit: number | null;
}

type Translate = (key: 'st.cost.rateMissing' | 'st.cost.rateInvalid' | 'st.cost.costInvalid', vars?: Record<string, string>) => string;

/** Validates what was typed, with the same rules inventory-service applies, so the form shows the USD value the lot will get. */
export function checkCost(d: CostDraft, rates: SuggestedRate[], t: Translate): CostCheck {
  const errors: Record<string, string> = {};
  const parts = d.breakdown ? [d.goods, d.freight, d.duty, d.other].map((p) => (p.trim() === '' ? 0 : toMinorIn(p, d.currency))) : null;
  const entered = d.breakdown ? [d.goods, d.freight, d.duty, d.other].some((p) => p.trim() !== '') : d.unit.trim() !== '';
  const tableRate = d.currency === 'USD' ? undefined : rates.find((r) => r.currency === d.currency);
  const rate = d.currency === 'USD' ? '1' : d.rateTouched ? d.rate.trim() : tableRate ? String(tableRate.rateToUsd) : '';
  if (!entered) return { errors, tableRate, rate, unitMinor: null, usdUnit: null };
  let unitMinor: number | null = null;
  if (parts) {
    if (parts.some((p) => !Number.isFinite(p))) errors.unit = t('st.cost.costInvalid');
    else unitMinor = parts.reduce((a, p) => a + p, 0);
  } else {
    const v = toMinorIn(d.unit, d.currency);
    if (Number.isFinite(v)) unitMinor = v;
    else errors.unit = t('st.cost.costInvalid');
  }
  let usdUnit: number | null = null;
  if (d.currency !== 'USD') {
    if (rate === '') errors.rate = t('st.cost.rateMissing', { currency: d.currency });
    else if (usdMinorOf(100, d.currency, rate) === null) errors.rate = t('st.cost.rateInvalid');
  }
  if (unitMinor !== null && !errors.rate) usdUnit = usdMinorOf(unitMinor, d.currency, rate);
  if (Object.keys(errors).length || unitMinor === null) return { errors, tableRate, rate, unitMinor, usdUnit };
  const components: CostComponents | undefined = parts ? { goodsMinor: parts[0] ?? 0, freightMinor: parts[1] ?? 0, dutyMinor: parts[2] ?? 0, otherMinor: parts[3] ?? 0 } : undefined;
  const input: ReceiveCostInput = {
    unitLandedCostMinor: unitMinor,
    currency: d.currency,
    ...(d.currency !== 'USD' && d.rateTouched ? { fxRateToUsd: Number(rate) } : {}),
    ...(components ? { costComponents: components } : {})
  };
  return { input, errors, tableRate, rate, unitMinor, usdUnit };
}

/**
 * The cost of one received line: the currency actually paid, the unit cost (or goods, freight, duty and other), the rate to
 * US dollars (prefilled from the staff-managed rates, editable) and the USD total the lots will be valued at.
 */
export function CostFields({ draft, onChange, check, units, lineLabel }: { draft: CostDraft; onChange: (patch: Partial<CostDraft>) => void; check: CostCheck; units: number; lineLabel: string }) {
  const t = useT();
  const api = useHub();
  const currencies = useQuery(inventoryHubQueries.currencies(api));
  const options = (currencies.data ?? [{ code: 'USD', name: 'US Dollar', minorUnits: 2, base: true } satisfies CostCurrency]).map((c) => ({ value: c.code, label: `${c.code} · ${c.name}` }));
  const foreign = draft.currency !== 'USD';
  const rateShown = draft.rateTouched ? draft.rate : check.tableRate ? String(check.tableRate.rateToUsd) : '';
  const sum = check.unitMinor;
  return (
    <fieldset className="m-0 flex flex-col gap-2 rounded-md border border-dashed border-border p-3" aria-label={t('st.cost.legend', { line: lineLabel })} data-testid="cost-fields">
      <legend className="px-1 text-sm font-semibold">{t('st.cost.title')}</legend>
      <span className="text-xs text-muted">{t('st.cost.hint')}</span>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,150px),1fr))] gap-2">
        <Select label={t('st.cost.currency')} options={options} value={draft.currency} onChange={(e) => onChange({ currency: e.currentTarget.value, rate: '', rateTouched: false })} />
        {draft.breakdown ? (
          <>
            <TextField label={t('st.cost.goods')} inputMode="decimal" value={draft.goods} onChange={(e) => onChange({ goods: e.target.value })} error={check.errors.unit} />
            <TextField label={t('st.cost.freight')} inputMode="decimal" value={draft.freight} onChange={(e) => onChange({ freight: e.target.value })} />
            <TextField label={t('st.cost.duty')} inputMode="decimal" value={draft.duty} onChange={(e) => onChange({ duty: e.target.value })} />
            <TextField label={t('st.cost.other')} inputMode="decimal" value={draft.other} onChange={(e) => onChange({ other: e.target.value })} />
          </>
        ) : (
          <TextField label={t('st.cost.unitCost', { currency: draft.currency })} inputMode="decimal" value={draft.unit} onChange={(e) => onChange({ unit: e.target.value })} error={check.errors.unit} placeholder={minorDigits(draft.currency) === 0 ? '0' : '0.00'} />
        )}
        {foreign ? (
          <TextField
            label={t('st.cost.rate', { currency: draft.currency })}
            inputMode="decimal"
            value={rateShown}
            onChange={(e) => onChange({ rate: e.target.value, rateTouched: true })}
            error={check.errors.rate}
            help={!draft.rateTouched && check.tableRate ? t('st.cost.rateTable', { date: shortDate(check.tableRate.rateDate) }) : undefined}
          />
        ) : null}
      </div>
      <Checkbox label={t('st.cost.breakdown')} checked={draft.breakdown} onChange={(e) => onChange({ breakdown: e.target.checked })} />
      {check.usdUnit !== null && sum !== null ? (
        <span className="text-sm font-semibold" role="status" data-testid="usd-total">
          {t('st.cost.usdTotal', { paid: money(sum, draft.currency), unit: money(check.usdUnit), total: money(check.usdUnit * units), units: String(units) })}
        </span>
      ) : null}
    </fieldset>
  );
}
