'use client';

import { type ProductTypeSchema, type SizeChart, type SizeChartUnit } from '@afrotransact/api-client';
import { Select } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode, useState } from 'react';

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
  /** Extra actions beside the chart choice (create / edit a chart). */
  actions?: ReactNode;
}

/** The "Size guide" section of a sized product type: pick the size chart, say how the sizes fit, and see the table the shopper gets. */
export function SizeGuideSection({ s, type, patch, charts, chart, chartState, error, actions }: Props) {
  const t = useT();
  const sizing = type.traits.sizing;
  const [unit, setUnit] = useState<SizeChartUnit | null>(null);
  if (!sizing) return null;
  const shownUnit = unit ?? chart?.unitSystem ?? 'CM';
  // A chart the product already uses stays selectable after it is retired (the form then asks to choose another).
  const options = [...charts.map((c) => ({ value: c.id, label: c.name }))];
  if (s.sizeChartId && !options.some((o) => o.value === s.sizeChartId)) {
    options.push({ value: s.sizeChartId, label: chart ? t('sc.pf.sizeChart.retiredOption', { name: chart.name }) : s.sizeChartId });
  }
  return (
    <>
      <div className={GRID}>
        <Select id={fieldId.sizeChart} label={t('sc.pf.field.sizeChart')} value={s.sizeChartId} error={error}
          placeholder={sizing.sizeChartRequired ? t('sc.pf.sizeChart.choose') : t('sc.pf.sizeChart.none')}
          help={sizing.sizeChartRequired ? t('sc.pf.sizeChart.requiredHelp') : t('sc.pf.sizeChart.optionalHelp')}
          options={options} onChange={(e) => patch({ sizeChartId: e.target.value })} />
        <Select id={fieldId.fitNote} label={t('sc.pf.field.fitNote')} value={s.fitNote}
          options={[{ value: '', label: t('sc.pf.fit.same') }, ...FIT_NOTES.map((f) => ({ value: f, label: t(`sc.pf.fit.${f}` as MessageKey) }))]}
          onChange={(e) => patch({ fitNote: FIT_NOTES.find((f) => f === e.target.value) ?? '' })} />
      </div>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        {actions}
        <Link href="/size-charts" className="inline-flex min-h-11 items-center text-sm sm:min-h-8">{t('sc.pf.sizeChart.manage')}</Link>
      </div>
      {!charts.length && !s.sizeChartId ? <p className="m-0 text-sm text-muted">{t('sc.pf.sizeChart.empty', { type: type.label })}</p> : null}
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
