'use client';

import { Button, cx } from '@afrotransact/ui';
import { CaretLeft, CaretRight } from '@phosphor-icons/react/ssr';
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

export interface ServerPage {
  /** 0-based page shown. */
  pageIndex: number;
  pageSize: number;
  /** Rows on the server matching the filters. */
  total: number;
  hasNext: boolean;
  onPage: (pageIndex: number) => void;
}

export interface DataTableProps<T> {
  label: string;
  columns: ColumnDef<T>[];
  data: T[];
  getRowId: (row: T) => string;
  page?: ServerPage;
  loading?: boolean;
  empty?: ReactNode;
  /** Highlight a row (e.g. the one open in the sheet). */
  activeRowId?: string | null;
}

/**
 * Server-driven TanStack table: paging, filtering and search happen on the BFF (manual* options); the table never
 * scrolls sideways — columns have fixed widths (column `size` is a percentage) and cells wrap.
 */
export function DataTable<T>({ label, columns, data, getRowId, page, loading, empty, activeRowId }: DataTableProps<T>) {
  const t = useT();
  const table = useReactTable({
    data,
    columns,
    getRowId: (row) => getRowId(row),
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualFiltering: true,
    manualSorting: true,
    rowCount: page?.total,
    state: page ? { pagination: { pageIndex: page.pageIndex, pageSize: page.pageSize } } : {}
  });
  const from = page ? page.pageIndex * page.pageSize + (data.length ? 1 : 0) : 0;
  const to = page ? page.pageIndex * page.pageSize + data.length : 0;
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-hidden rounded-md border border-border">
        <table className="at-table text-sm" aria-label={label} aria-busy={loading || undefined}>
          <colgroup>
            {table.getAllLeafColumns().map((c) => (
              <col key={c.id} style={{ width: `${c.getSize()}%` }} />
            ))}
          </colgroup>
          <thead className="bg-surface-muted text-left">
            {table.getHeaderGroups().map((hg) => (
              <tr key={hg.id}>
                {hg.headers.map((h) => (
                  <th key={h.id} scope="col" className="px-3 py-2 text-xs font-bold text-muted">
                    {h.isPlaceholder ? null : flexRender(h.column.columnDef.header, h.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody className={cx(loading && 'opacity-60')}>
            {table.getRowModel().rows.map((row) => (
              <tr key={row.id} className={cx('border-t border-border', activeRowId === row.id ? 'bg-gold-50' : 'hover:bg-surface-hover')}>
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id} className="px-3 py-2.5">
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </td>
                ))}
              </tr>
            ))}
            {!loading && data.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="px-3 py-10 text-center text-muted">
                  {empty ?? 'Nothing here yet.'}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      {page ? (
        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
          <span aria-live="polite">{data.length ? `Showing ${from}–${to} of ${page.total}` : `0 of ${page.total}`}</span>
          <span className="flex gap-2">
            <Button variant="neutral" size="sm" icon={<CaretLeft size={14} aria-hidden />} disabled={page.pageIndex === 0 || loading} onClick={() => page.onPage(page.pageIndex - 1)}>
              {t('sc.dataTable.previous')}
            </Button>
            <Button variant="neutral" size="sm" disabled={!page.hasNext || loading} onClick={() => page.onPage(page.pageIndex + 1)}>
              {t('sc.dataTable.next')} <CaretRight size={14} aria-hidden />
            </Button>
          </span>
        </div>
      ) : null}
    </div>
  );
}

/** Pill-shaped filter chips (status facets) used above tables. */
export function FilterChips<V extends string>({ label, options, value, onChange }: { label: string; options: { value: V | ''; label: string }[]; value: V | ''; onChange: (v: V | '') => void }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5">
      {options.map((o) => (
        <button
          key={o.value || 'all'}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={cx(
            'h-8 rounded-pill border px-3 text-xs font-semibold',
            value === o.value ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface hover:bg-surface-hover'
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
