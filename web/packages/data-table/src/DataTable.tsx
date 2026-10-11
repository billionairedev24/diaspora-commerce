'use client';

import { Button, cx, Menu, MenuItem, Skeleton, useToast } from '@afrotransact/ui';
import { ArrowDown, ArrowsDownUp, ArrowUp, CaretDown, CaretLeft, CaretRight, DownloadSimple, FileCsv, FilePdf, MagnifyingGlass, MicrosoftExcelLogo, X } from '@phosphor-icons/react/ssr';
import {
  type Cell,
  type Column,
  type ColumnDef,
  type ColumnPinningState,
  type ColumnSizingState,
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  type Header,
  type Row,
  type RowSelectionState,
  type Table,
  useReactTable,
  type VisibilityState
} from '@tanstack/react-table';
import { type CSSProperties, type KeyboardEvent, type MouseEvent, type ReactNode, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';

import { ColumnsMenu, type Density } from './ColumnsMenu';
import { columnLabel, datasetOf, exportable, type ExportOutcome, type TableExportRequest, tableKey } from './export';
import { everyColumnFilter, kindSortingFn } from './search';
import { readDefaultDensity, readPrefs, writePrefs } from './storage';
import { SELECT_COLUMN } from './types';
import { PAGE_SIZES, type TableState } from './useTableState';

export interface DataTableProps<T> {
  /** Stable id: persists visible columns, widths and density per table ("staff.inventory"). */
  id: string;
  /** Accessible name of the table (and the export's title). */
  label: string;
  columns: ColumnDef<T>[];
  data: T[];
  getRowId: (row: T) => string;
  /** Search, sort and paging (useTableState). */
  state: TableState;
  /** server: the API searches, sorts and pages (send state.q, state.sort, the page); client: the table does it over `data`. */
  mode?: 'server' | 'client';
  /** Server paging: page numbers (with `total`) or cursors (with `nextCursor`). */
  paging?: 'pages' | 'cursor';
  /** Rows matching the search on the server (null when unknown). */
  total?: number | null;
  nextCursor?: string | null;
  /** First load (shows skeleton rows). */
  loading?: boolean;
  /** A refetch is running (keeps rows, dims them). */
  fetching?: boolean;
  /** Rendered instead of the rows when the list failed (e.g. an error state with a retry button). */
  error?: ReactNode;
  /** The server searched or sorted only the first rows of a bigger table. */
  partial?: boolean;
  /** Opens the row's detail sheet; clicks on buttons, links and the checkbox never trigger it. */
  onRowClick?: (row: T) => void;
  activeRowId?: string | null;
  /** Name of a row for its checkbox ("Select AT-000123"). */
  rowLabel?: (row: T) => string;
  /** Bulk actions for the selected rows (shown in the selection bar). */
  bulkActions?: (rows: T[], clear: () => void) => ReactNode;
  /** Exports (CSV, Excel with charts, PDF with charts); omit to hide the menu. */
  onExport?: (req: TableExportRequest) => Promise<ExportOutcome>;
  /** Polls a queued export until it is ready (pages without an exports list). */
  pollExport?: (id: string) => Promise<ExportOutcome>;
  /** Where queued exports are listed (staff console). */
  exportsHref?: string;
  /** Extra toolbar content (e.g. a New button). */
  toolbar?: ReactNode;
  searchPlaceholder?: string;
  empty?: ReactNode;
  noMatches?: ReactNode;
  enableSelection?: boolean;
  pageSizes?: number[];
  /** Below the pagination (notes). */
  footer?: ReactNode;
  /** data-testid of every body row (end-to-end tests). */
  rowTestId?: string;
}

const INTERACTIVE = 'button,a,input,label,select,textarea,summary,[role="menuitem"],[role="checkbox"],[data-no-row-click]';
const DEFAULT_SIZE = 160;

function sortIcon<T>(c: Column<T, unknown>) {
  const s = c.getIsSorted();
  if (s === 'asc') return <ArrowUp size={12} aria-hidden />;
  if (s === 'desc') return <ArrowDown size={12} aria-hidden />;
  return <ArrowsDownUp size={12} aria-hidden className="opacity-50" />;
}

/** Sticky left offset of the pinned checkbox and first column (widths come from the <colgroup>). */
function pinStyle<T>(c: Column<T, unknown>, header = false): CSSProperties | undefined {
  if (c.getIsPinned() !== 'left') return undefined;
  return { position: 'sticky', left: c.getStart('left'), zIndex: header ? 3 : 1 };
}

const fmt = (n: number) => n.toLocaleString('en-US');

/** Page numbers to show (0-based), with null for a gap: 1 … 4 5 6 … 20. */
export function pageNumbers(current: number, count: number): (number | null)[] {
  if (count <= 7) return Array.from({ length: count }, (_, i) => i);
  const set = new Set([0, count - 1, current - 1, current, current + 1].filter((n) => n >= 0 && n < count));
  if (current <= 2) [1, 2, 3].forEach((n) => set.add(n));
  if (current >= count - 3) [count - 4, count - 3, count - 2].forEach((n) => set.add(n));
  const sorted = [...set].sort((a, b) => a - b);
  const out: (number | null)[] = [];
  sorted.forEach((n, i) => {
    if (i > 0 && n - (sorted[i - 1] ?? n) > 1) out.push(null);
    out.push(n);
  });
  return out;
}

/** The data table of every back-office list (see the package README in index.ts). */
export function DataTable<T>(props: DataTableProps<T>) {
  const {
    id,
    label,
    columns,
    data,
    getRowId,
    state,
    mode = 'server',
    paging = 'pages',
    total,
    nextCursor,
    loading,
    fetching,
    error,
    partial,
    onRowClick,
    activeRowId,
    rowLabel,
    bulkActions,
    onExport,
    pollExport,
    exportsHref,
    toolbar,
    searchPlaceholder,
    empty,
    noMatches,
    enableSelection = true,
    pageSizes = PAGE_SIZES,
    footer,
    rowTestId
  } = props;
  const toast = useToast();
  const searchId = useId();
  const client = mode === 'client';

  // Per-viewer preferences: read after mount (storage is client-only), written on change.
  const [visibility, setVisibility] = useState<VisibilityState>({});
  const [sizing, setSizing] = useState<ColumnSizingState>({});
  const [density, setDensity] = useState<Density>('comfortable');
  const [prefsLoaded, setPrefsLoaded] = useState(false);
  useEffect(() => {
    const p = readPrefs(id);
    setVisibility(p.visibility ?? {});
    setSizing(p.sizing ?? {});
    setDensity(p.density ?? readDefaultDensity() ?? 'comfortable');
    // Rows per page are remembered per table (a size in the URL, e.g. a shared link, wins).
    if (p.pageSize && pageSizes.includes(p.pageSize) && !state.sizeFromUrl && p.pageSize !== state.pagination.pageSize) {
      state.setPagination({ pageIndex: 0, pageSize: p.pageSize });
    }
    setPrefsLoaded(true);
    // Read once per table id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);
  const pageSizeNow = state.pagination.pageSize;
  useEffect(() => {
    if (prefsLoaded) writePrefs(id, { visibility, sizing, density, pageSize: pageSizeNow });
  }, [id, visibility, sizing, density, prefsLoaded, pageSizeNow]);

  // Selection survives paging and sorting, and is cleared by a new search.
  const [selection, setSelection] = useState<RowSelectionState>({});
  const seen = useRef(new Map<string, T>());
  data.forEach((r) => seen.current.set(getRowId(r), r));
  const lastClicked = useRef<number | null>(null);
  useEffect(() => {
    setSelection({});
    lastClicked.current = null;
  }, [state.q]);

  const selectColumn = useMemo<ColumnDef<T>>(
    () => ({
      id: SELECT_COLUMN,
      size: 44,
      minSize: 44,
      maxSize: 44,
      enableSorting: false,
      enableHiding: false,
      enableResizing: false,
      meta: { export: false, label: 'Select' },
      header: ({ table }) => (
        // The label is the touch target (44px on coarse pointers); the checkbox keeps its own name.
        <label className="flex cursor-pointer items-center gap-2 pointer-coarse:min-h-11 pointer-coarse:min-w-11 pointer-coarse:justify-center">
          <input
            type="checkbox"
            aria-label="Select all rows on this page"
            className="block size-4 cursor-pointer"
            checked={table.getIsAllPageRowsSelected()}
            ref={(el) => {
              if (el) el.indeterminate = table.getIsSomePageRowsSelected() && !table.getIsAllPageRowsSelected();
            }}
            onChange={table.getToggleAllPageRowsSelectedHandler()}
          />
          <span aria-hidden className="hidden text-xs font-semibold @max-xl/dt:inline">
            All
          </span>
        </label>
      ),
      cell: () => null
    }),
    []
  );

  const allColumns = useMemo(() => {
    const wrapped = columns.map((c): ColumnDef<T> => ({ sortingFn: kindSortingFn<T>, sortUndefined: 'last', ...c }));
    return enableSelection ? [selectColumn, ...wrapped] : wrapped;
  }, [columns, enableSelection, selectColumn]);

  const firstColumn = columns[0] ? (columns[0].id ?? ('accessorKey' in columns[0] ? String(columns[0].accessorKey) : undefined)) : undefined;
  const pinning = useMemo<ColumnPinningState>(() => ({ left: [...(enableSelection ? [SELECT_COLUMN] : []), ...(firstColumn ? [firstColumn] : [])], right: [] }), [enableSelection, firstColumn]);

  const table: Table<T> = useReactTable<T>({
    data,
    columns: allColumns,
    getRowId: (r) => getRowId(r),
    defaultColumn: { size: DEFAULT_SIZE, minSize: 64, maxSize: 720 },
    state: {
      sorting: state.sorting,
      pagination: state.pagination,
      globalFilter: client ? state.q : undefined,
      rowSelection: selection,
      columnVisibility: visibility,
      columnSizing: sizing,
      columnPinning: pinning
    },
    onSortingChange: state.setSorting,
    onPaginationChange: state.setPagination,
    onRowSelectionChange: setSelection,
    onColumnVisibilityChange: setVisibility,
    onColumnSizingChange: setSizing,
    enableRowSelection: enableSelection,
    enableMultiSort: true,
    sortDescFirst: false,
    columnResizeMode: 'onChange',
    enableColumnResizing: true,
    getCoreRowModel: getCoreRowModel(),
    ...(client
      ? {
          getFilteredRowModel: getFilteredRowModel(),
          getSortedRowModel: getSortedRowModel(),
          getPaginationRowModel: getPaginationRowModel(),
          globalFilterFn: everyColumnFilter<T>,
          getColumnCanGlobalFilter: () => true,
          autoResetPageIndex: false
        }
      : { manualPagination: true, manualSorting: true, manualFiltering: true, rowCount: total ?? undefined })
  });

  const pageRows = table.getRowModel().rows;
  const matching = client ? table.getFilteredRowModel().rows.length : (total ?? null);
  const selectedIds = Object.keys(selection).filter((k) => selection[k]);
  const clear = useCallback(() => {
    setSelection({});
    lastClicked.current = null;
  }, []);

  // A cursor-paged table opened on a later page (from the URL) without its cursor starts over.
  const { pagination, cursor, resetPaging, setCursor } = state;
  useEffect(() => {
    if (!client && paging === 'cursor' && pagination.pageIndex > 0 && cursor === undefined) resetPaging();
  }, [client, paging, pagination.pageIndex, cursor, resetPaging]);
  // A page past the end (rows deleted, a narrower search) goes back to the last page.
  const pageCount = client ? table.getPageCount() : total != null ? Math.max(1, Math.ceil(total / pagination.pageSize)) : null;
  useEffect(() => {
    if (pageCount !== null && pageCount > 0 && pagination.pageIndex > pageCount - 1) state.setPagination((p) => ({ ...p, pageIndex: pageCount - 1 }));
  }, [pageCount, pagination.pageIndex, state]);

  const toggleRow = (row: Row<T>, index: number, e: MouseEvent<HTMLInputElement>) => {
    const value = !row.getIsSelected();
    if (e.shiftKey && lastClicked.current !== null) {
      const [from, to] = [Math.min(lastClicked.current, index), Math.max(lastClicked.current, index)];
      setSelection((s) => {
        const next = { ...s };
        pageRows.slice(from, to + 1).forEach((r) => {
          if (value) next[r.id] = true;
          else delete next[r.id];
        });
        return next;
      });
    } else {
      row.toggleSelected(value);
    }
    lastClicked.current = index;
  };

  const openRow = (row: Row<T>, e: MouseEvent<HTMLTableRowElement> | KeyboardEvent<HTMLTableRowElement>) => {
    if (!onRowClick) return;
    const target = e.target as HTMLElement;
    if (target !== e.currentTarget && target.closest(INTERACTIVE)) return;
    onRowClick(row.original);
  };

  // ── export
  const [exporting, setExporting] = useState(false);
  const download = (url: string) => {
    const a = document.createElement('a');
    a.href = url;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
  };
  const runExport = async (format: TableExportRequest['format']) => {
    if (!onExport) return;
    setExporting(true);
    try {
      const rowsForFile = selectedIds.length
        ? selectedIds.map((sid) => table.getCoreRowModel().rowsById[sid]).filter((r): r is Row<T> => r !== undefined)
        : client
          ? table.getPrePaginationRowModel().rows
          : table.getCoreRowModel().rows;
      const scope = [selectedIds.length ? `${fmt(selectedIds.length)} selected rows` : '', state.q ? `Search “${state.q}”` : '']
        .filter(Boolean)
        .join(' · ');
      const columnIds = table.getVisibleLeafColumns().filter(exportable).map((c) => c.id);
      let job = await onExport({
        format,
        columns: columnIds,
        ids: selectedIds,
        q: state.q,
        sort: state.sort,
        dataset: datasetOf(table, rowsForFile, { table: tableKey(id), title: label, scope: scope || 'All rows' })
      });
      if ((job.status === 'QUEUED' || job.status === 'RUNNING') && pollExport) {
        toast.show('Building the export… it downloads when ready.');
        for (let i = 0; i < 150 && (job.status === 'QUEUED' || job.status === 'RUNNING'); i++) {
          await new Promise((r) => setTimeout(r, 2000));
          job = await pollExport(job.id);
        }
      }
      if (job.status === 'READY' && job.downloadUrl) {
        download(job.downloadUrl);
        toast.show(`Exported ${fmt(job.rows)} ${job.rows === 1 ? 'row' : 'rows'} · ${job.fileName}`);
      } else if (job.status === 'QUEUED' || job.status === 'RUNNING') {
        toast.show(exportsHref ? 'Big export queued — it appears under Exports when ready.' : 'Big export queued.');
      } else {
        toast.show(job.error ?? 'The export could not be built. Please try again.', 'error');
      }
    } catch (e) {
      toast.show(e instanceof Error && e.message ? e.message : 'The export could not be built. Please try again.', 'error');
    } finally {
      setExporting(false);
    }
  };

  // ── header cells
  const headerCell = (h: Header<T, unknown>) => {
    const c = h.column;
    const sortable = c.getCanSort();
    const sorted = c.getIsSorted();
    const end = c.columnDef.meta?.align === 'end';
    return (
      <th
        key={h.id}
        scope="col"
        aria-sort={sortable ? (sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none') : undefined}
        style={pinStyle(c, true)}
        className={cx(
          'group relative sticky top-0 z-[2] border-b border-border bg-surface-muted px-3 py-2 text-left text-xs font-bold text-muted',
          end && 'text-right',
          c.getIsPinned() && c.getIsLastColumn('left') && 'border-r',
          // Narrow (cards): only the select-all box stays (as a chip); sorting moves to the toolbar's Sort select.
          c.id === SELECT_COLUMN
            ? '@max-xl/dt:static! @max-xl/dt:flex @max-xl/dt:items-center @max-xl/dt:rounded-pill @max-xl/dt:border @max-xl/dt:border-border-strong @max-xl/dt:bg-surface @max-xl/dt:px-3 @max-xl/dt:py-0'
            : '@max-xl/dt:hidden'
        )}
      >
        {h.isPlaceholder ? null : sortable ? (
          <button
            type="button"
            onClick={c.getToggleSortingHandler()}
            className={cx('inline-flex max-w-full items-center gap-1 text-left font-bold hover:text-text', end && 'flex-row-reverse', sorted && 'text-text')}
            title={sorted ? 'Sorted — click to change, shift-click to add a column' : 'Sort — shift-click to add a column'}
          >
            <span className="truncate">{flexRender(c.columnDef.header, h.getContext())}</span>
            {sortIcon(c)}
            {sorted && table.getState().sorting.length > 1 ? <span className="text-2xs">{c.getSortIndex() + 1}</span> : null}
          </button>
        ) : (
          flexRender(c.columnDef.header, h.getContext())
        )}
        {c.getCanResize() ? (
          // A focusable separator is the ARIA splitter widget: arrow keys resize the column.
          // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label={`Resize ${columnLabel(c)}`}
            aria-valuenow={c.getSize()}
            // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
            tabIndex={0}
            onMouseDown={h.getResizeHandler()}
            onTouchStart={h.getResizeHandler()}
            onDoubleClick={() => c.resetSize()}
            onKeyDown={(e) => {
              if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
              e.preventDefault();
              const delta = e.key === 'ArrowLeft' ? -16 : 16;
              setSizing((s) => ({ ...s, [c.id]: Math.min(720, Math.max(64, c.getSize() + delta)) }));
            }}
            className={cx(
              'absolute right-0 top-0 h-full w-1.5 cursor-col-resize touch-none select-none opacity-0 group-hover:opacity-100 focus-visible:opacity-100 @max-xl/dt:hidden',
              c.getIsResizing() ? 'bg-gold-500 opacity-100' : 'bg-border-strong'
            )}
          />
        ) : null}
      </th>
    );
  };

  const pad = density === 'compact' ? 'py-1.5' : 'py-2.5';
  const sortOptions = table
    .getAllLeafColumns()
    .filter((c) => c.getCanSort() && c.getIsVisible())
    .flatMap((c) => {
      const name = columnLabel(c);
      const kind = c.columnDef.meta?.kind;
      const [up, down] =
        kind === 'date' || kind === 'datetime'
          ? ['oldest first', 'newest first']
          : kind === 'money' || kind === 'number' || kind === 'percent' || c.columnDef.meta?.align === 'end'
            ? ['low to high', 'high to low']
            : ['A to Z', 'Z to A'];
      return [
        { value: `${c.id}:asc`, label: `${name} (${up})` },
        { value: `${c.id}:desc`, label: `${name} (${down})` }
      ];
    });
  const firstSort = state.sorting[0];
  const sortValue = firstSort ? `${firstSort.id}:${firstSort.desc ? 'desc' : 'asc'}` : '';
  const bodyCell = (cell: Cell<T, unknown>, row: Row<T>, index: number, active: boolean) => {
    const c = cell.column;
    const pinned = c.getIsPinned();
    const bg = active ? 'bg-gold-50' : row.getIsSelected() ? 'bg-green-50' : 'bg-surface group-hover/row:bg-surface-hover';
    if (c.id === SELECT_COLUMN) {
      return (
        <td
          key={cell.id}
          style={pinStyle(c)}
          className={cx('px-3 align-top', pad, pinned && bg, '@max-xl/dt:static! @max-xl/dt:col-start-2 @max-xl/dt:row-start-1 @max-xl/dt:-my-2 @max-xl/dt:-mr-2 @max-xl/dt:bg-transparent @max-xl/dt:p-0')}
          data-no-row-click
        >
          {/* The label is the touch target (44px on coarse pointers); the checkbox keeps its own name. */}
          <label className="flex cursor-pointer pointer-coarse:size-11 pointer-coarse:items-center pointer-coarse:justify-center">
            <input
              type="checkbox"
              aria-label={`Select ${rowLabel ? rowLabel(row.original) : `row ${index + 1}`}`}
              className="mt-0.5 block size-4 cursor-pointer pointer-coarse:mt-0"
              checked={row.getIsSelected()}
              onClick={(e) => toggleRow(row, index, e)}
              onChange={() => undefined}
            />
          </label>
        </td>
      );
    }
    const primary = c.id === firstColumn;
    return (
      <td
        key={cell.id}
        style={pinStyle(c)}
        data-label={columnLabel(c)}
        className={cx(
          'px-3 align-top [overflow-wrap:anywhere]',
          pad,
          c.columnDef.meta?.align === 'end' && 'text-right tabular-nums',
          pinned && bg,
          pinned && c.getIsLastColumn('left') && 'border-r border-border',
          // Narrow (cards): the first column is the card's title; every other cell is a label / value pair.
          '@max-xl/dt:static! @max-xl/dt:border-r-0 @max-xl/dt:bg-transparent @max-xl/dt:p-0 @max-xl/dt:text-left',
          primary
            ? '@max-xl/dt:col-start-1 @max-xl/dt:row-start-1 @max-xl/dt:block @max-xl/dt:text-md @max-xl/dt:font-semibold'
            : '@max-xl/dt:col-span-2 @max-xl/dt:grid @max-xl/dt:grid-cols-[minmax(6rem,38%)_minmax(0,1fr)] @max-xl/dt:gap-3 @max-xl/dt:before:text-xs @max-xl/dt:before:font-semibold @max-xl/dt:before:text-muted @max-xl/dt:before:content-[attr(data-label)] @max-xl/dt:empty:hidden'
        )}
      >
        {flexRender(c.columnDef.cell, cell.getContext())}
      </td>
    );
  };

  const visibleCount = table.getVisibleLeafColumns().length;
  const fitted = Object.keys(sizing).length === 0;
  const fixedWidth = enableSelection ? 44 : 0;
  const flexibleTotal = Math.max(1, table.getVisibleLeafColumns().reduce((n, c) => n + (c.id === SELECT_COLUMN ? 0 : c.getSize()), 0));
  // Fitted tables never squeeze a column below ~96px (text would wrap letter by letter on a tablet): past that the
  // region scrolls sideways with the checkbox and first column pinned. Phones get cards instead (container query).
  const minFitted = table.getVisibleLeafColumns().reduce((n, c) => n + (c.id === SELECT_COLUMN ? 44 : Math.max(96, Math.min(c.getSize(), 140))), 0);
  const from = pagination.pageIndex * pagination.pageSize + (pageRows.length ? 1 : 0);
  const to = pagination.pageIndex * pagination.pageSize + pageRows.length;
  const hasPrev = client ? table.getCanPreviousPage() : pagination.pageIndex > 0;
  const hasNext = client ? table.getCanNextPage() : paging === 'cursor' ? !!nextCursor : total != null && to < total;
  const goNext = () => {
    if (!client && paging === 'cursor') setCursor(pagination.pageIndex + 1, nextCursor ?? undefined);
    state.setPagination((p) => ({ ...p, pageIndex: p.pageIndex + 1 }));
  };
  const cursorPaged = !client && paging === 'cursor';
  // Cursor-paged APIs can jump only to pages already reached (and the next one); page numbers need a known count.
  const numberedCount = pageCount ?? (cursorPaged ? pagination.pageIndex + (hasNext ? 2 : 1) : pagination.pageIndex + 1);
  const reachable = (n: number) => !cursorPaged || state.cursorKnown(n) || (n === pagination.pageIndex + 1 && !!nextCursor);
  const goTo = (n: number) => {
    if (n === pagination.pageIndex + 1) return goNext();
    state.setPagination((p) => ({ ...p, pageIndex: n }));
  };
  const showing =
    matching === null
      ? `${fmt(pageRows.length)} shown${hasNext ? ' · more on the next page' : ''}`
      : pageRows.length
        ? `Showing ${fmt(from)}–${fmt(to)} of ${fmt(matching)}`
        : `0 of ${fmt(matching)}`;

  return (
    // A size container: below 36rem (phones, side sheets) the rows become stacked cards and the toolbar wraps.
    <div className="@container/dt flex flex-col gap-2" data-table-id={id}>
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={searchId} className="flex h-9 min-w-0 flex-1 basis-56 items-center gap-2 rounded-pill border border-border-strong bg-surface px-3 sm:max-w-96 pointer-coarse:h-11 @max-xl/dt:max-w-none @max-xl/dt:basis-full">
          <MagnifyingGlass size={16} aria-hidden className="shrink-0 text-muted" />
          <span className="at-visually-hidden">Search {label.toLowerCase()}</span>
          <input
            id={searchId}
            type="search"
            className="min-w-0 flex-1 bg-transparent text-sm outline-none"
            placeholder={searchPlaceholder ?? 'Search every column'}
            value={state.searchInput}
            onChange={(e) => state.setSearchInput(e.currentTarget.value)}
          />
        </label>
        <span className="flex-1 @max-xl/dt:hidden" />
        {/* Cards have no column headers: one Sort select stands in for them (the first sort key; shift-sorting is a
            desktop affordance). */}
        {sortOptions.length ? (
          <label className="hidden h-9 items-center gap-1.5 rounded-pill border border-border-strong bg-surface pr-1 pl-3 text-sm font-semibold pointer-coarse:h-11 @max-xl/dt:flex">
            <ArrowsDownUp size={16} aria-hidden />
            <span className="at-visually-hidden">Sort {label.toLowerCase()} by</span>
            <select
              className="h-full max-w-40 min-w-0 cursor-pointer bg-transparent text-sm font-semibold outline-none"
              value={sortValue}
              onChange={(e) => {
                const [col, dir] = e.currentTarget.value.split(':');
                state.setSorting(col ? [{ id: col, desc: dir === 'desc' }] : []);
              }}
            >
              <option value="">Sort</option>
              {sortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <ColumnsMenu
          table={table}
          density={density}
          onDensity={setDensity}
          onReset={() => {
            setVisibility({});
            setSizing({});
          }}
        />
        {onExport ? (
          <Menu
            align="end"
            width={260}
            label="Export"
            trigger={
              <button
                type="button"
                className="flex h-9 items-center gap-1.5 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-hover pointer-coarse:h-11"
                aria-busy={exporting || undefined}
                disabled={exporting}
              >
                <DownloadSimple size={16} aria-hidden />
                {exporting ? 'Exporting…' : selectedIds.length ? `Export ${fmt(selectedIds.length)} selected` : 'Export'}
                <CaretDown size={12} aria-hidden />
              </button>
            }
          >
            <MenuItem icon={<MicrosoftExcelLogo size={16} aria-hidden />} onSelect={() => void runExport('xlsx')}>
              Excel (.xlsx) with charts
            </MenuItem>
            <MenuItem icon={<FilePdf size={16} aria-hidden />} onSelect={() => void runExport('pdf')}>
              PDF with charts
            </MenuItem>
            <MenuItem icon={<FileCsv size={16} aria-hidden />} onSelect={() => void runExport('csv')}>
              CSV (data only)
            </MenuItem>
          </Menu>
        ) : null}
        {toolbar}
      </div>
      {selectedIds.length ? (
        <div role="region" aria-label="Selection" className="flex flex-wrap items-center gap-2 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm">
          <strong className="font-semibold" aria-live="polite">
            {fmt(selectedIds.length)} selected
          </strong>
          <button type="button" onClick={clear} className="flex items-center gap-1 text-sm font-semibold text-link hover:underline">
            <X size={14} aria-hidden /> Clear selection
          </button>
          <span className="flex-1" />
          {bulkActions ? bulkActions(selectedIds.map((sid) => seen.current.get(sid)).filter((r): r is T => r !== undefined), clear) : null}
        </div>
      ) : null}
      {partial ? <p className="m-0 text-xs text-muted">Search and sort cover the first 5,000 rows of this table; narrow the search to reach older rows.</p> : null}
      {/* A scrollable region holds focus so keyboard users can scroll it (scrollbars are hidden everywhere). */}
      <div
        className="max-h-[70dvh] overflow-auto rounded-md border border-border @max-xl/dt:max-h-none @max-xl/dt:overflow-visible @max-xl/dt:rounded-none @max-xl/dt:border-0"
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        role="region"
        aria-label={`${label} (scrollable)`}
      >
        {/* Until someone resizes a column the table fits its container (columns keep their proportions; the checkbox
            column stays 44px), so nothing hides off to the side. Resized columns keep their pixel widths and the
            region scrolls sideways with the checkbox and first column pinned. */}
        <table
          className="table-fixed border-collapse text-sm @max-xl/dt:block @max-xl/dt:w-full! @max-xl/dt:min-w-0!"
          style={{ width: fitted ? '100%' : table.getTotalSize(), minWidth: fitted ? `max(100%, ${minFitted}px)` : '100%' }}
          aria-label={label}
          aria-busy={loading || fetching || undefined}
        >
          <colgroup className="@max-xl/dt:hidden">
            {table.getVisibleLeafColumns().map((c) => (
              <col key={c.id} style={{ width: fitted && c.id !== SELECT_COLUMN ? `calc((100% - ${fixedWidth}px) * ${c.getSize() / flexibleTotal})` : c.getSize() }} />
            ))}
          </colgroup>
          <thead className="@max-xl/dt:block">
            {table.getHeaderGroups().map((hg) => (
              <tr key={hg.id} className="@max-xl/dt:flex @max-xl/dt:flex-wrap @max-xl/dt:gap-1.5 @max-xl/dt:pb-2">
                {hg.headers.map(headerCell)}
              </tr>
            ))}
          </thead>
          <tbody className={cx(fetching && !loading && 'opacity-60', '@max-xl/dt:flex @max-xl/dt:flex-col @max-xl/dt:gap-2')}>
            {error ? (
              <tr className="@max-xl/dt:block">
                <td colSpan={visibleCount} className="p-4 @max-xl/dt:block">
                  {error}
                </td>
              </tr>
            ) : loading && !data.length ? (
              Array.from({ length: 6 }, (_, i) => (
                <tr key={`sk-${i}`} className="border-t border-border @max-xl/dt:flex @max-xl/dt:flex-col @max-xl/dt:gap-2 @max-xl/dt:rounded-md @max-xl/dt:border @max-xl/dt:p-3" aria-hidden>
                  {table.getVisibleLeafColumns().map((c) => (
                    <td key={c.id} className={cx('px-3', pad, '@max-xl/dt:block @max-xl/dt:p-0', c.id === SELECT_COLUMN && '@max-xl/dt:hidden')}>
                      <Skeleton height={12} width={c.id === SELECT_COLUMN ? 16 : '80%'} />
                    </td>
                  ))}
                </tr>
              ))
            ) : pageRows.length === 0 ? (
              <tr className="@max-xl/dt:block">
                <td colSpan={visibleCount} className="px-3 py-10 text-center text-muted @max-xl/dt:block">
                  {state.q ? (noMatches ?? `Nothing matches “${state.q}”.`) : (empty ?? 'Nothing here yet.')}
                </td>
              </tr>
            ) : (
              pageRows.map((row, index) => {
                const active = activeRowId === row.id;
                return (
                  // The row opens its detail sheet by click, Enter or Space (keyboard users reach it with Tab).
                  <tr
                    key={row.id}
                    data-row-id={row.id}
                    data-testid={rowTestId}
                    data-selected={row.getIsSelected() || undefined}
                    tabIndex={onRowClick ? 0 : undefined}
                    aria-current={active || undefined}
                    onClick={onRowClick ? (e) => openRow(row, e) : undefined}
                    onKeyDown={
                      onRowClick
                        ? (e) => {
                            if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) {
                              e.preventDefault();
                              openRow(row, e);
                            }
                          }
                        : undefined
                    }
                    className={cx(
                      'group/row border-t border-border',
                      // Narrow: a card — title and checkbox on top, label / value pairs below.
                      '@max-xl/dt:grid @max-xl/dt:grid-cols-[minmax(0,1fr)_auto] @max-xl/dt:gap-x-3 @max-xl/dt:gap-y-1.5 @max-xl/dt:rounded-md @max-xl/dt:border @max-xl/dt:p-3',
                      onRowClick && 'cursor-pointer',
                      active ? 'bg-gold-50' : row.getIsSelected() ? 'bg-green-50' : 'hover:bg-surface-hover'
                    )}
                  >
                    {row.getVisibleCells().map((cell) => bodyCell(cell, row, index, active))}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted">
        <span aria-live="polite">{showing}</span>
        <span className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5">
            Rows per page
            <select
              className="h-8 rounded-sm border border-border-strong bg-surface px-1.5 text-xs text-text pointer-coarse:h-11"
              value={pagination.pageSize}
              onChange={(e) => state.setPagination({ pageIndex: 0, pageSize: Number(e.currentTarget.value) })}
            >
              {pageSizes.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </label>
          <nav aria-label={`${label} pages`} className="flex flex-wrap items-center gap-1">
            <Button variant="neutral" size="sm" icon={<CaretLeft size={14} aria-hidden />} disabled={!hasPrev || fetching} onClick={() => goTo(pagination.pageIndex - 1)}>
              Previous
            </Button>
            {pageNumbers(pagination.pageIndex, numberedCount).map((n, i) =>
              n === null ? (
                <span key={`gap-${i}`} aria-hidden className="px-1">
                  …
                </span>
              ) : (
                <button
                  key={n}
                  type="button"
                  aria-label={`Page ${n + 1}`}
                  aria-current={n === pagination.pageIndex ? 'page' : undefined}
                  disabled={fetching || !reachable(n)}
                  onClick={() => goTo(n)}
                  className={cx(
                    'h-8 min-w-8 rounded-sm border px-2 text-xs font-semibold tabular-nums disabled:cursor-default pointer-coarse:h-11 pointer-coarse:min-w-11',
                    n === pagination.pageIndex ? 'border-gold-500 bg-gold-50 text-text' : 'border-border-strong bg-surface text-text hover:bg-surface-hover disabled:opacity-45'
                  )}
                >
                  {fmt(n + 1)}
                </button>
              )
            )}
            <Button variant="neutral" size="sm" disabled={!hasNext || fetching} onClick={goNext}>
              Next <CaretRight size={14} aria-hidden />
            </Button>
          </nav>
        </span>
      </div>
      {footer}
    </div>
  );
}
