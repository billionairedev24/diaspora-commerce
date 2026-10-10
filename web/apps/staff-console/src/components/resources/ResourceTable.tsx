'use client';

import { type FieldValue, isApiError, type ResourceKey, type ResourcePage, type ResourceRow, staffKeys, staffQueries } from '@afrotransact/api-client';
import { Button, cx, Dialog, Menu, MenuItem, useToast } from '@afrotransact/ui';
import { CaretDown, CaretLeft, CaretRight, DownloadSimple, FileCsv, MagnifyingGlass, MicrosoftExcelLogo, Plus } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';

import { Cell } from './cells';
import { FormFields } from './FormFields';
import { RecordSheet } from './RecordSheet';

const PAGE_SIZE = 25;

/** Pill-shaped status filter chips (server-side facets). */
export function FilterChips<V extends string>({ label, options, value, onChange }: { label: string; options: { value: V | ''; label: string }[]; value: V | ''; onChange: (v: V | '') => void }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5">
      {options.map((o) => (
        <button
          key={o.value || 'all'}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={cx('h-8 rounded-pill border px-3 text-xs font-semibold', value === o.value ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface hover:bg-surface-hover')}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** Export menu: the whole table (every page matching the filters) built as CSV or Excel on the server. */
function ExportMenu({ resource, q, status }: { resource: ResourceKey; q: string; status: string }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const exp = useMutation({
    mutationFn: (format: 'csv' | 'xlsx') => api.createExport({ resource, format, q: q || undefined, status: status || undefined }, idempotencyKey()),
    onSuccess: (job) => {
      void qc.invalidateQueries({ queryKey: staffKeys.exports() });
      if (job.status === 'READY' && job.downloadUrl) {
        window.location.assign(job.downloadUrl);
        toast.show(`${job.rows.toLocaleString('en-US')} rows exported.`);
      } else toast.show('Large export queued — it builds in the background. Find it under Exports.');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  return (
    <Menu
      align="end"
      width={230}
      label="Export"
      trigger={
        <button type="button" className="flex h-9 items-center gap-1.5 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-hover" aria-busy={exp.isPending || undefined}>
          <DownloadSimple size={16} aria-hidden />
          {exp.isPending ? 'Exporting…' : 'Export'}
          <CaretDown size={12} aria-hidden />
        </button>
      }
    >
      <MenuItem icon={<FileCsv size={16} aria-hidden />} onSelect={() => exp.mutate('csv')}>
        CSV — every matching row
      </MenuItem>
      <MenuItem icon={<MicrosoftExcelLogo size={16} aria-hidden />} onSelect={() => exp.mutate('xlsx')}>
        Excel — every matching row
      </MenuItem>
    </Menu>
  );
}

/**
 * Server-driven table of one resource: columns, facets, search, paging (page numbers or cursors) and the create form
 * come from the BFF; clicking a row opens its detail sheet (?open=<id>, so other pages can link to a record).
 */
export function ResourceTable({ resource }: { resource: ResourceKey }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const openId = params.get('open');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const q = useDebounced(search.trim(), 300);
  const [page, setPage] = useState(0);
  const [cursors, setCursors] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [createErrors, setCreateErrors] = useState<Record<string, string>>({});
  const [createError, setCreateError] = useState<string | null>(null);
  const cursor = cursors[cursors.length - 1];

  const query = useQuery({ ...staffQueries.page(api, resource, { q: q || undefined, status: status || undefined, page, size: PAGE_SIZE, cursor }), placeholderData: keepPreviousData });
  const data: ResourcePage | undefined = query.data;

  const reset = () => {
    setPage(0);
    setCursors([]);
  };
  const setOpen = (id: string | null) => {
    const next = new URLSearchParams(params.toString());
    if (id) next.set('open', id);
    else next.delete('open');
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const create = useMutation({
    mutationFn: (values: Record<string, FieldValue>) => api.createRecord(resource, values, idempotencyKey()),
    onSuccess: (r) => {
      qc.setQueryData(staffKeys.record(resource, r.id), r);
      void qc.invalidateQueries({ queryKey: staffKeys.resource(resource) });
      setCreating(false);
      toast.show(`${r.title} created.`);
      setOpen(r.id);
    },
    onError: (e) => {
      setCreateErrors(isApiError(e) ? e.fieldErrors : {});
      setCreateError(problemMessage(e));
    }
  });

  const columns = useMemo<ColumnDef<ResourceRow>[]>(
    () =>
      (data?.columns ?? []).map((c, i) => ({
        id: c.key,
        header: c.label,
        size: c.width,
        cell: ({ row }) =>
          i === 0 ? (
            <button type="button" className="text-left text-link hover:underline" onClick={() => setOpen(row.original.id)}>
              <Cell kind={c.kind} value={row.original.cells[c.key]} tone={row.original.tone} />
            </button>
          ) : (
            <Cell kind={c.kind} value={row.original.cells[c.key]} tone={row.original.tone} />
          )
      })),
    // setOpen depends on the URL only; the columns change with the page's column set.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data?.columns, params]
  );
  const table = useReactTable({
    data: data?.rows ?? [],
    columns,
    getRowId: (r) => r.id,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    manualFiltering: true,
    manualSorting: true,
    rowCount: data?.total ?? undefined
  });

  const cursorPaged = data ? data.total === null : false;
  const from = data && data.rows.length ? (cursorPaged ? 0 : page * PAGE_SIZE) + 1 : 0;
  const to = data ? (cursorPaged ? 0 : page * PAGE_SIZE) + data.rows.length : 0;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {data?.searchable ? (
          <label className="flex h-9 min-w-56 flex-1 items-center gap-2 rounded-pill border border-border-strong bg-surface px-3 sm:max-w-80">
            <MagnifyingGlass size={16} aria-hidden className="text-muted" />
            <span className="at-visually-hidden">Search {data.title.toLowerCase()}</span>
            <input
              type="search"
              className="min-w-0 flex-1 bg-transparent text-sm outline-none"
              placeholder={data.searchPlaceholder ?? 'Search'}
              value={search}
              onChange={(e) => {
                setSearch(e.currentTarget.value);
                reset();
              }}
            />
          </label>
        ) : null}
        <span className="flex-1" />
        {data?.exportable ? <ExportMenu resource={resource} q={q} status={status} /> : null}
        {data?.canCreate && data.createForm ? (
          <Button
            size="sm"
            icon={<Plus size={14} aria-hidden />}
            onClick={() => {
              setCreateErrors({});
              setCreateError(null);
              setCreating(true);
            }}
          >
            New
          </Button>
        ) : null}
      </div>
      {data?.facets.length ? (
        <FilterChips
          label="Status"
          value={status}
          onChange={(v) => {
            setStatus(v);
            reset();
          }}
          options={[{ value: '', label: 'All' }, ...data.facets]}
        />
      ) : null}
      {query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <div className="flex flex-col gap-2">
          <div className="overflow-hidden rounded-md border border-border">
            <table className="at-table text-sm" aria-label={data?.title ?? resource} aria-busy={query.isFetching || undefined}>
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
              <tbody className={cx(query.isFetching && 'opacity-60')}>
                {table.getRowModel().rows.map((row) => (
                  <tr
                    key={row.id}
                    onClick={(e) => {
                      if (!(e.target as HTMLElement).closest('button,a')) setOpen(row.id);
                    }}
                    className={cx('cursor-pointer border-t border-border', openId === row.id ? 'bg-gold-50' : 'hover:bg-surface-hover')}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-3 py-2.5">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))}
                {query.isPending ? (
                  <tr>
                    <td colSpan={Math.max(1, columns.length)} className="px-3 py-10 text-center text-muted">
                      <span role="status">Loading…</span>
                    </td>
                  </tr>
                ) : null}
                {data && data.rows.length === 0 ? (
                  <tr>
                    <td colSpan={Math.max(1, columns.length)} className="px-3 py-10 text-center text-muted">
                      {q || status ? 'Nothing matches these filters.' : 'Nothing here yet.'}
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
          {data ? (
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
              <span aria-live="polite">
                {cursorPaged ? `${data.rows.length} shown${data.hasNext ? ' · more on the next page' : ''}` : data.rows.length ? `Showing ${from}–${to} of ${data.total ?? 0}` : `0 of ${data.total ?? 0}`}
              </span>
              <span className="flex gap-2">
                <Button
                  variant="neutral"
                  size="sm"
                  icon={<CaretLeft size={14} aria-hidden />}
                  disabled={(cursorPaged ? cursors.length === 0 : page === 0) || query.isFetching}
                  onClick={() => (cursorPaged ? setCursors((c) => c.slice(0, -1)) : setPage((p) => p - 1))}
                >
                  Previous
                </Button>
                <Button
                  variant="neutral"
                  size="sm"
                  disabled={!data.hasNext || query.isFetching}
                  onClick={() => (cursorPaged && data.nextCursor ? setCursors((c) => [...c, data.nextCursor ?? '']) : setPage((p) => p + 1))}
                >
                  Next <CaretRight size={14} aria-hidden />
                </Button>
              </span>
            </div>
          ) : null}
        </div>
      )}
      <RecordSheet resource={resource} id={openId} onClose={() => setOpen(null)} />
      {data?.createForm ? (
        <Dialog open={creating} onOpenChange={setCreating} title={data.createForm.title} width={480}>
          <FormFields form={data.createForm} busy={create.isPending} serverErrors={createErrors} error={createError} onCancel={() => setCreating(false)} onSubmit={(v) => create.mutate(v)} />
        </Dialog>
      ) : null}
      {resource === 'support-cases' ? null : (
        <p className="m-0 text-xs text-subtle">
          Large tables export on the server; finished files are listed under <Link href="/exports">Exports</Link>.
        </p>
      )}
    </div>
  );
}
