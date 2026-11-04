import { type SizeChartImportChart, type SizeChartImportReport, type SizeChartImportRow } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

/* Pure rules of the size chart upload screen: what a dry run says the file would do, which rows need the seller's
   attention, how a message names its column, and what is left to import. The catalog service decides on import; these
   only read its report. */

export type ImportStage = 'choose' | 'checked' | 'imported';

export interface ImportSummary {
  /** Charts the file would create or update (the ones that can be saved). */
  importable: number;
  created: number;
  updated: number;
  unchanged: number;
  failed: number;
  /** Charts in the file. */
  total: number;
  errorRows: number;
  warningRows: number;
}

export function summaryOf(r: SizeChartImportReport): ImportSummary {
  return {
    importable: r.created + r.updated, created: r.created, updated: r.updated, unchanged: r.unchanged, failed: r.failed, total: r.chartCount, errorRows: r.errorRows, warningRows: r.warningRows
  };
}

/** The stage after a report: a dry run is only checked, an applied one is done. */
export const stageOf = (r: SizeChartImportReport | null): ImportStage => (r === null ? 'choose' : r.applied ? 'imported' : 'checked');

/** Rows with an error or a warning first (by row number), then the rest. */
export function rowsToShow(r: SizeChartImportReport, onlyProblems: boolean): SizeChartImportRow[] {
  const rows = onlyProblems ? r.rows.filter((x) => x.status !== 'VALID') : r.rows;
  return [...rows].sort((a, b) => a.rowNumber - b.rowNumber);
}

/** "m:foot_length" → "Foot length"; "chart_name" → "Chart name"; null (the whole row) → null. */
export function columnName(column: string | null): string | null {
  if (!column) return null;
  const words = column.replace(/^m:/, '').replace(/_/g, ' ').trim();
  return words === '' ? null : words.charAt(0).toUpperCase() + words.slice(1);
}

const ACTION_KEYS: Record<SizeChartImportChart['action'], MessageKey> = {
  CREATE: 'sc.szi.action.CREATE', UPDATE: 'sc.szi.action.UPDATE', UNCHANGED: 'sc.szi.action.UNCHANGED', FAILED: 'sc.szi.action.FAILED'
};
export const actionKey = (a: SizeChartImportChart['action']): MessageKey => ACTION_KEYS[a];

export const actionTone = (a: SizeChartImportChart['action']): 'success' | 'neutral' | 'warning' | 'danger' =>
  a === 'CREATE' ? 'success' : a === 'UPDATE' ? 'warning' : a === 'FAILED' ? 'danger' : 'neutral';

/** The charts that failed, with the first row to fix, so the seller starts with the cause. */
export const failedCharts = (r: SizeChartImportReport): SizeChartImportChart[] => r.charts.filter((c) => c.action === 'FAILED');
