'use client';

import { type FieldValue, isApiError, type OneTimeSecret, type ResourceKey, type ResourceRow, staffKeys, staffQueries } from '@afrotransact/api-client';
import { DataTable, type TableExportRequest, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, useToast } from '@afrotransact/ui';
import { Plus } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';

import { BulkActions } from './BulkActions';
import { Cell } from './cells';
import { FormFields } from './FormFields';
import { RecordSheet } from './RecordSheet';
import { RevealDialog } from './RevealDialog';

/**
 * Server-driven table of one resource on the shared DataTable: columns, rows and the create form come from the BFF; one
 * search over every column and any sort run on the BFF (?q=, ?sort=); rows can be selected for bulk actions and
 * exports; clicking a row opens its detail sheet (?open=<id>, so other pages can link to a record). Pages that show
 * several resource tables (tabs) give each a URL prefix.
 */
export function ResourceTable({ resource, urlPrefix }: { resource: ResourceKey; urlPrefix?: string }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const openId = params.get('open');
  const state = useTableState({ searchParams: params, prefix: urlPrefix });
  const [creating, setCreating] = useState(false);
  const [createErrors, setCreateErrors] = useState<Record<string, string>>({});
  const [createError, setCreateError] = useState<string | null>(null);
  /** A secret the new record came with (a new OAuth client secret): shown once, kept out of the query cache. */
  const [reveal, setReveal] = useState<{ recordId: string; secret: OneTimeSecret } | null>(null);

  const query = useQuery({
    ...staffQueries.page(api, resource, { q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize, cursor: state.cursor }),
    placeholderData: keepPreviousData
  });
  const data = query.data;

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
      const { reveal: secret, ...sheet } = r;
      qc.setQueryData(staffKeys.record(resource, r.id), sheet);
      if (secret) setReveal({ recordId: r.id, secret });
      void qc.invalidateQueries({ queryKey: staffKeys.resource(resource) });
      setCreating(false);
      toast.show(t('st.resourceTable.created', { title: r.title }));
      setOpen(r.id);
    },
    onError: (e) => {
      setCreateErrors(isApiError(e) ? e.fieldErrors : {});
      setCreateError(problemMessage(e));
    }
  });

  const columns = useMemo<ColumnDef<ResourceRow>[]>(
    () =>
      (data?.columns ?? []).map((c) => ({
        id: c.key,
        accessorFn: (r: ResourceRow) => r.cells[c.key] ?? undefined,
        header: c.label,
        // The BFF sends widths as shares of the table; ~12px per percent fills a 1,200px table.
        size: Math.max(72, c.width * 12),
        enableSorting: c.sortable !== false,
        meta: { label: c.label, kind: c.kind, align: c.kind === 'money' || c.kind === 'number' || c.kind === 'percent' ? 'end' : 'start' },
        cell: ({ row }) => <Cell kind={c.kind} value={row.original.cells[c.key]} tone={row.original.tone} />
      })),
    [data?.columns]
  );

  const onExport = async (req: TableExportRequest) => {
    const job = await api.createExport({ resource, format: req.format, q: req.q || undefined, sort: req.sort, ids: req.ids.length ? req.ids : undefined, columns: req.columns }, idempotencyKey());
    void qc.invalidateQueries({ queryKey: staffKeys.exports() });
    return job;
  };

  return (
    <div className="flex flex-col gap-3">
      <DataTable<ResourceRow>
        id={`staff.${resource}`}
        label={data?.title ?? resource}
        columns={columns}
        data={data?.rows ?? []}
        getRowId={(r) => r.id}
        rowLabel={(r) => String(r.cells[data?.columns[0]?.key ?? ''] ?? r.id)}
        state={state}
        mode="server"
        paging={data && data.total === null ? 'cursor' : 'pages'}
        total={data?.total ?? null}
        nextCursor={data?.nextCursor ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        partial={data?.partial}
        searchPlaceholder={data?.searchPlaceholder}
        onRowClick={(r) => setOpen(r.id)}
        activeRowId={openId}
        empty={t('st.resourceTable.empty')}
        bulkActions={(rows, clear) => <BulkActions resource={resource} rows={rows} onDone={clear} />}
        onExport={data?.exportable ? onExport : undefined}
        exportsHref="/exports"
        toolbar={
          data?.canCreate && data.createForm ? (
            <Button
              size="sm"
              icon={<Plus size={14} aria-hidden />}
              onClick={() => {
                setCreateErrors({});
                setCreateError(null);
                setCreating(true);
              }}
            >
              {t('st.resourceTable.new')}
            </Button>
          ) : null
        }
        footer={
          data?.exportable ? (
            <p className="m-0 text-xs text-subtle">
              Exports cover the search and sort, or only the selected rows. Excel and PDF files open with a summary of charts; large exports build on the server and are
              listed under <Link href="/exports">{t('st.resourceTable.exports')}</Link>.
            </p>
          ) : null
        }
      />
      <RecordSheet resource={resource} id={openId} onClose={() => setOpen(null)} sharedOpen={urlPrefix !== undefined} />
      {/* Shown once the new record's sheet is open, so the dialog sits above the sheet and clicking in it never closes the sheet. */}
      <RevealDialog secret={reveal && reveal.recordId === openId ? reveal.secret : null} onClose={() => setReveal(null)} />
      {data?.createForm ? (
        <Dialog open={creating} onOpenChange={setCreating} title={data.createForm.title} width={480}>
          <FormFields form={data.createForm} busy={create.isPending} serverErrors={createErrors} error={createError} onCancel={() => setCreating(false)} onSubmit={(v) => create.mutate(v)} />
        </Dialog>
      ) : null}
    </div>
  );
}
