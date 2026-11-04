'use client';

import { type SizeChartUnit, sizeTable, type SizeTableChart, unitLabel } from '@afrotransact/api-client';
import { ChipGroup } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

/** The size table as the shopper reads it (one row per size, ranges written "86–90"), in the unit chosen. The same
    function builds the storefront's size guide, so the seller sees what the shopper will. */
export function SizeTable({ chart, name, unit }: { chart: SizeTableChart; name: string; unit: SizeChartUnit }) {
  const t = useT();
  const table = sizeTable(chart, unit);
  return (
    <div className="overflow-x-auto rounded-sm border border-border">
      <table aria-label={t('sc.pf.sizeChart.table', { name })} className="w-full border-collapse text-sm" data-testid="size-table">
        <thead>
          <tr className="bg-surface-muted text-left">
            <th scope="col" className="px-3 py-2 text-xs font-semibold">{t('sc.pf.sizeChart.size')}</th>
            {table.headers.map((h) => (
              <th key={h.key} scope="col" className="px-3 py-2 text-xs font-semibold whitespace-nowrap">{h.label} ({unitLabel(h.unit)})</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((r) => (
            <tr key={r.size} className="border-t border-border">
              <th scope="row" className="px-3 py-1.5 text-left font-semibold whitespace-nowrap">{r.size}</th>
              {r.cells.map((c, i) => <td key={table.headers[i]?.key ?? i} className="px-3 py-1.5 whitespace-nowrap tabular-nums">{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const UNIT_OPTIONS: { value: SizeChartUnit; label: string }[] = [{ value: 'CM', label: 'cm' }, { value: 'IN', label: 'in' }];

/** cm / in switch for a size table. */
export function UnitSwitch({ value, onChange }: { value: SizeChartUnit; onChange: (u: SizeChartUnit) => void }) {
  const t = useT();
  return <ChipGroup<SizeChartUnit> label={t('sc.pf.sizeChart.unit')} options={UNIT_OPTIONS} value={value} onChange={onChange} />;
}
