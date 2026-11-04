'use client';

import { type ProductTypeSchema, type SizeChart, type SizeChartUnit } from '@afrotransact/api-client';
import { Button, Select, TextField } from '@afrotransact/ui';
import { useMemo, useState } from 'react';

import { SizeTable, UnitSwitch } from '@/components/size-charts/SizeTable';
import { type MessageKey, useT } from '@/i18n/t';

import { fieldId, FIT_NOTES, type FormState } from '../form-model';
import { GRID } from './FormSection';

interface Props {
  s: FormState;
  type: ProductTypeSchema;
  patch: (p: Partial<FormState>) => void;
  /** The organisation's charts for this product type (retired ones are not offered). */
  charts: SizeChart[];
  /** The chosen chart, once loaded. */
  chart: SizeChart | undefined;
  chartState: 'none' | 'loading' | 'ready' | 'error';
  error?: string;
  /** Whether the member may write charts (the buttons are left out otherwise). */
  editable: boolean;
  onCreate: () => void;
  onEdit: () => void;
  onManage: () => void;
}

/** Charts listed before a search box is worth showing. */
const SEARCH_FROM = 6;

/**
 * The "Size guide" step of a sized product type. The seller's saved charts are a library: pick one (a chart serves many products), create a new one
 * for this category, or manage them all. "No size chart" is offered only where the category does not require one. Below, how the sizes fit and the
 * table the shopper gets.
 */
export function SizeGuideSection({ s, type, patch, charts, chart, chartState, error, editable, onCreate, onEdit, onManage }: Props) {
  const t = useT();
  const sizing = type.traits.sizing;
  const [unit, setUnit] = useState<SizeChartUnit | null>(null);
  const [q, setQ] = useState('');
  const shown = useMemo(() => {
    const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return charts.filter((c) => {
      const hay = `${c.name} ${c.rows.map((r) => r.size).join(' ')} ${c.columns.map((m) => m.label).join(' ')}`.toLowerCase();
      return words.every((w) => hay.includes(w));
    });
  }, [charts, q]);
  if (!sizing) return null;
  const required = sizing.sizeChartRequired;
  const shownUnit = unit ?? chart?.unitSystem ?? 'CM';
  // A chart the product already uses stays selectable after it is retired (the form then asks to choose another).
  const orphan = s.sizeChartId && !charts.some((c) => c.id === s.sizeChartId) ? s.sizeChartId : '';
  const row = (id: string, name: string, detail: string) => (
    <li key={id || 'none'}>
      <label className="flex min-h-11 cursor-pointer items-start gap-3 border-b border-border py-2 sm:min-h-0">
        <input type="radio" name="size-chart" checked={s.sizeChartId === id} onChange={() => patch({ sizeChartId: id })} className="mt-1 size-4 shrink-0" aria-label={name} />
        <span className="flex min-w-0 flex-col">
          <span className={s.sizeChartId === id ? 'text-sm font-semibold' : 'text-sm'}>{name}</span>
          {detail ? <span className="text-xs text-muted">{detail}</span> : null}
        </span>
      </label>
    </li>
  );
  const detailOf = (c: SizeChart) =>
    [
      c.rows.map((r) => r.size).join(', '),
      c.columns.map((m) => m.label).join(', '),
      c.unitSystem === 'CM' ? 'cm' : 'in',
      t('sc.pf.sizeChart.products', { count: c.productCount })
    ].join(' · ');
  return (
    <>
      <div className="flex flex-col gap-2">
        <span id={`${fieldId.sizeChart}-label`} className="text-sm font-semibold">{t('sc.pf.field.sizeChart')}{required ? <span aria-hidden> *</span> : null}</span>
        <p className="m-0 text-xs text-muted">{required ? t('sc.pf.sizeChart.requiredHelp') : t('sc.pf.sizeChart.optionalHelp')}</p>
        {charts.length >= SEARCH_FROM ? <TextField label={t('sc.pf.sizeChart.search')} type="search" value={q} onChange={(e) => setQ(e.target.value)} /> : null}
        <ul id={fieldId.sizeChart} tabIndex={-1} role="radiogroup" aria-labelledby={`${fieldId.sizeChart}-label`} aria-invalid={error ? true : undefined}
          className="m-0 flex max-h-72 list-none flex-col overflow-y-auto border-t border-border p-0">
          {required ? null : row('', t('sc.pf.sizeChart.none'), '')}
          {orphan ? row(orphan, chart ? t('sc.pf.sizeChart.retiredOption', { name: chart.name }) : orphan, '') : null}
          {shown.map((c) => row(c.id, c.name, detailOf(c)))}
        </ul>
        {q.trim() && !shown.length ? <p className="m-0 text-sm text-muted">{t('sc.pf.sizeChart.noMatch', { q: q.trim() })}</p> : null}
        {!charts.length && !s.sizeChartId ? <p className="m-0 text-sm text-muted">{t('sc.pf.sizeChart.empty', { type: type.label })}</p> : null}
        {error ? <p role="alert" className="m-0 text-sm text-danger">{error}</p> : null}
        <div className="flex flex-wrap items-center gap-3 text-sm">
          {editable ? (
            <>
              <Button size="sm" variant="neutral" onClick={onCreate}>{t('sc.pf.sizeChart.create')}</Button>
              {chart && !chart.retired ? <Button size="sm" variant="neutral" onClick={onEdit}>{t('sc.pf.sizeChart.edit')}</Button> : null}
            </>
          ) : null}
          <button type="button" onClick={onManage} className="inline-flex min-h-11 cursor-pointer items-center border-0 bg-transparent p-0 text-sm underline sm:min-h-8">{t('sc.pf.sizeChart.manage')}</button>
        </div>
      </div>
      <div className={GRID}>
        <Select id={fieldId.fitNote} label={t('sc.pf.field.fitNote')} value={s.fitNote}
          options={[{ value: '', label: t('sc.pf.fit.same') }, ...FIT_NOTES.map((f) => ({ value: f, label: t(`sc.pf.fit.${f}` as MessageKey) }))]}
          onChange={(e) => patch({ fitNote: FIT_NOTES.find((f) => f === e.target.value) ?? '' })} />
      </div>
      {chartState === 'loading' ? <p role="status" className="m-0 text-sm text-muted">{t('sc.pf.sizeChart.loading')}</p> : null}
      {chartState === 'error' ? <p role="alert" className="m-0 text-sm text-danger">{t('sc.pf.sizeChart.loadFailed')}</p> : null}
      {chart ? (
        <div className="flex flex-col gap-2" data-testid="size-preview">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="m-0 text-sm font-semibold">{t('sc.pf.sizeChart.preview')}</h3>
            <UnitSwitch value={shownUnit} onChange={setUnit} />
          </div>
          <SizeTable chart={chart} name={chart.name} unit={shownUnit} />
          {chart.howToMeasure ? <p className="m-0 text-xs text-muted">{chart.howToMeasure}</p> : null}
        </div>
      ) : null}
    </>
  );
}
