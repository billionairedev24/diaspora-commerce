import { type ExportCell, type ExportColumn, type ExportDataset, type ExportFormat, type ExportJob } from '@afrotransact/api-client';
import { type Column, type Row, type Table } from '@tanstack/react-table';

import { SELECT_COLUMN } from './types';

/** What the export menu asks the page for. Server tables send `columns`, the search, the sort and `ids`; client-side
    tables send `dataset` (the rows as shown: the whole filtered and sorted list, or only the selected rows). */
export interface TableExportRequest {
  format: ExportFormat;
  /** The visible, exportable columns in order (their ids, which are the API's column keys). */
  columns: string[];
  /** Only these rows (the selection); empty for every row matching the search. */
  ids: string[];
  q: string;
  sort: string[];
  /** The rows as the table holds them (client-side tables). */
  dataset: ExportDataset;
}

export const exportable = <T,>(c: Column<T, unknown>) => c.id !== SELECT_COLUMN && c.columnDef.meta?.export !== false && c.getIsVisible();

export function columnLabel<T>(c: Column<T, unknown>): string {
  const meta = c.columnDef.meta;
  if (meta?.label) return meta.label;
  return typeof c.columnDef.header === 'string' ? c.columnDef.header : c.id;
}

function cellOf(v: unknown): ExportCell {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v;
  if (v instanceof Date) return v.toISOString();
  if (Array.isArray(v)) return v.map((x) => String(x)).join(', ');
  return String(v);
}

/** The rows as an export dataset: visible columns, money in currency units, dates as ISO strings. */
export function datasetOf<T>(table: Table<T>, rows: Row<T>[], meta: { table: string; title: string; scope?: string }): ExportDataset {
  const cols = table.getVisibleLeafColumns().filter(exportable);
  const columns: ExportColumn[] = cols.map((c) => ({ key: c.id, label: columnLabel(c), kind: c.columnDef.meta?.kind ?? 'text' }));
  return {
    table: meta.table,
    title: meta.title,
    scope: meta.scope,
    columns,
    rows: rows.map((r) =>
      cols.map((c) => {
        const raw = c.columnDef.meta?.exportValue ? c.columnDef.meta.exportValue(r.original) : r.getValue(c.id);
        const v = cellOf(raw);
        return c.columnDef.meta?.kind === 'money' && typeof v === 'number' && !c.columnDef.meta.exportValue ? v / 100 : v;
      })
    )
  };
}

/** The outcome the table reports: ready files download at once; queued ones are polled when the page can. */
export type ExportOutcome = Pick<ExportJob, 'id' | 'status' | 'rows' | 'fileName' | 'downloadUrl' | 'error'>;

/** A table key from an id ("staff.inventory" → "staff-inventory"). */
export const tableKey = (id: string) =>
  id
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60) || 'table';
