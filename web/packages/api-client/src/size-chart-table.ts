/* The size table as the shopper reads it: one header row and one row per size, in the unit the shopper picked, a range
   written "86–90". Seller Center's chart preview and the product page's size guide can use the same function so the
   seller sees what the shopper will. Values come from the chart (its own unit) or from `alternateValues` when the unit is
   switched; a chart without alternates (an unsaved draft) is converted on the spot, one decimal, as the server does. */
import { type SizeChart, type SizeChartUnit, type SizeChartValue } from './schemas/seller';
import { convertMeasurement, formatMeasurement } from './size-charts';

export interface SizeTableModel {
  unit: SizeChartUnit;
  /** Column headers: the measurement labels with the unit shown. */
  headers: Array<{ key: string; label: string; unit: SizeChartUnit }>;
  rows: Array<{ size: string; cells: string[] }>;
}

interface TableRow {
  size: string;
  values: Partial<Record<string, SizeChartValue>>;
  alternateValues?: Partial<Record<string, SizeChartValue>>;
}

export interface SizeTableChart {
  unitSystem: SizeChartUnit;
  columns: ReadonlyArray<Pick<SizeChart['columns'][number], 'key' | 'label'>>;
  rows: readonly TableRow[];
}

function valueIn(chart: SizeTableChart, row: TableRow, key: string, unit: SizeChartUnit): SizeChartValue | undefined {
  const own = row.values[key];
  if (!own || unit === chart.unitSystem) return own;
  const alt = row.alternateValues?.[key];
  if (alt) return alt;
  return { min: convertMeasurement(own.min, chart.unitSystem, unit), max: convertMeasurement(own.max, chart.unitSystem, unit) };
}

/** The table of a chart in `unit` (the chart's own unit by default); a missing value shows as "–". */
export function sizeTable(chart: SizeTableChart, unit: SizeChartUnit = chart.unitSystem): SizeTableModel {
  return {
    unit,
    headers: chart.columns.map((c) => ({ key: c.key, label: c.label, unit })),
    rows: chart.rows.map((r) => ({
      size: r.size,
      cells: chart.columns.map((c) => {
        const v = valueIn(chart, r, c.key, unit);
        return v ? formatMeasurement(v) : '–';
      })
    }))
  };
}

export const unitLabel = (unit: SizeChartUnit): string => (unit === 'CM' ? 'cm' : 'in');

/** A column key from its label, as catalog derives it ("Foot length" becomes foot_length; one starting with a digit gets an m_ prefix). */
export function measurementKey(label: string): string {
  const key = label.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  if (key === '') return '';
  return (/^[a-z]/.test(key) ? key : `m_${key}`).slice(0, 40);
}
