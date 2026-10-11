/**
 * @afrotransact/data-table — the one table of the staff console and Seller Center.
 *
 * TanStack Table (headless) with: a checkbox column (select all on the page, indeterminate, shift-click ranges) and a
 * selection bar with per-table bulk actions; exactly one filter — a search over every column (server-driven with
 * `state.q`, or TanStack's global filter for client-side lists); sorting by any column (aria-sort, shift-click adds
 * columns); server or client paging with a page size selector; a Columns menu (visibility, density, reset) persisted per
 * table; resizable columns; the checkbox and first column pinned; a sticky header; skeleton, empty and error states;
 * and exports (Excel with charts, PDF with charts, CSV) of the search and sort, or of the selected rows only.
 *
 * Kept out of @afrotransact/ui on purpose: ui is the dependency-light design system the storefront also ships; the
 * table brings TanStack Table and the export contract (api-client types) that only the back offices need.
 */
export { ColumnsMenu, type Density } from './ColumnsMenu';
export { DataTable, type DataTableProps } from './DataTable';
export { columnLabel, datasetOf, type ExportOutcome, type TableExportRequest, tableKey } from './export';
export { everyColumnFilter, kindSortingFn, searchTerms, searchTexts } from './search';
export { readPrefs, type TablePrefs, writePrefs } from './storage';
export { type ColumnKind, SELECT_COLUMN } from './types';
export { PAGE_SIZES, paramsToSort, type SearchParamsLike, sortToParams, type TableState, type TableStateOptions, useTableState } from './useTableState';
