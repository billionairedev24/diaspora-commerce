import { type ExportCell, type ExportColumnKind } from '@afrotransact/api-client';
import { type RowData } from '@tanstack/react-table';

/** How a column's values read, sort, search and export (money in minor units, dates as ISO strings). */
export type ColumnKind = ExportColumnKind;

declare module '@tanstack/react-table' {
  // Type parameters must match TanStack's declaration to merge.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData extends RowData, TValue> {
    /** Plain-text name for the Columns menu and exports (defaults to a string header or the id). */
    label?: string;
    /** Value kind: formats exports, drives the one search (money as $1,234.50, dates as "Oct 11, 2026") and sorting. */
    kind?: ColumnKind;
    /** Right-align numbers and amounts. */
    align?: 'start' | 'end';
    /** The exported cell (defaults to the column's accessor value). */
    exportValue?: (row: TData) => ExportCell;
    /** Leave the column out of exports (actions, thumbnails). */
    export?: boolean;
  }
}

/** The column id of the selection checkboxes. */
export const SELECT_COLUMN = '__select';
