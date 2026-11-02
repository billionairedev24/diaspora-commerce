'use client';

import { sellerKeys, sellerQueries,type SizeChart } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Badge, Button, buttonClassName, Checkbox, Dialog, useToast } from '@afrotransact/ui';
import { Archive,Copy, FileArrowUp, PencilSimple, Plus } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { leavesOf } from '@/components/listings/form-model';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { SizeChartEditor } from './SizeChartEditor';

/** My size charts: the charts the organisation writes once and links to many products. Server-driven table (search, pages), new / edit in a side sheet,
    duplicate, retire (a retired chart stays on the products that show it but can no longer be linked), and the bulk import. */
export function SizeChartsView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can, has } = useSeller();
  const editable = can('listings');
  const state = useTableState({ searchParams: useSearchParams() });
  const [withRetired, setWithRetired] = useState(false);
  const [editing, setEditing] = useState<SizeChart | 'new' | null>(null);
  const [retiring, setRetiring] = useState<SizeChart | null>(null);
  const tree = useQuery(sellerQueries.categories(api));
  const typeName = useMemo(() => {
    const leaves = leavesOf(tree.data ?? []);
    return (code: string | null) => (code ? (leaves.find((l) => l.productType === code)?.name ?? code) : t('sc.sz.anyCategory'));
  }, [tree.data, t]);

  const q = useQuery({
    ...sellerQueries.sizeCharts(api, orgId, { q: state.q || undefined, includeRetired: withRetired, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: sellerKeys.sizeCharts(orgId) });
  const duplicate = useMutation({
    mutationFn: (c: SizeChart) => api.duplicateSizeChart(c.id),
    onSuccess: (copy) => {
      refresh();
      toast.show(t('sc.sz.toast.duplicated', { name: copy.name }));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const retire = useMutation({
    mutationFn: (c: SizeChart) => api.retireSizeChart(c.id),
    onSuccess: (c) => {
      refresh();
      setRetiring(null);
      toast.show(t('sc.sz.toast.retired', { name: c.name }));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const columns = useMemo<ColumnDef<SizeChart>[]>(
    () => [
      {
        id: 'name', accessorFn: (c) => c.name, header: t('sc.sz.col.name'), size: 240, enableSorting: false, meta: { kind: 'strong' },
        cell: ({ row }) => (
          <span className="flex flex-wrap items-center gap-2">
            <strong className="font-semibold">{row.original.name}</strong>
            {row.original.retired ? <Badge tone="neutral">{t('sc.sz.retired')}</Badge> : null}
          </span>
        )
      },
      { id: 'madeFor', accessorFn: (c) => typeName(c.productTypeCode), header: t('sc.sz.col.madeFor'), size: 170, enableSorting: false },
      { id: 'sizes', accessorFn: (c) => c.rows.map((r) => r.size).join(', '), header: t('sc.sz.col.sizes'), size: 220, enableSorting: false,
        cell: ({ row }) => <span className="text-sm">{row.original.rows.map((r) => r.size).join(', ')}</span> },
      { id: 'measurements', accessorFn: (c) => c.columns.map((m) => m.label).join(', '), header: t('sc.sz.col.measurements'), size: 200, enableSorting: false },
      { id: 'unit', accessorFn: (c) => (c.unitSystem === 'CM' ? 'cm' : 'in'), header: t('sc.sz.col.unit'), size: 70, enableSorting: false },
      { id: 'fit', accessorFn: (c) => (c.fitNote ? t(`sc.pf.fit.${c.fitNote}` as MessageKey) : ''), header: t('sc.pf.field.fitNote'), size: 130, enableSorting: false },
      { id: 'productCount', accessorFn: (c) => c.productCount, header: t('sc.sz.col.products'), size: 90, enableSorting: false, meta: { kind: 'number', align: 'end' } },
      { id: 'updatedAt', accessorFn: (c) => c.updatedAt, header: t('sc.sz.col.updated'), size: 120, enableSorting: false, meta: { kind: 'datetime' }, cell: ({ row }) => shortDate(row.original.updatedAt) },
      {
        id: 'action', header: () => <span className="at-visually-hidden">{t('sc.sz.col.actions')}</span>, size: 300, enableSorting: false, meta: { export: false, label: t('sc.sz.col.actions') },
        cell: ({ row }) =>
          editable ? (
            <span className="flex flex-wrap items-center gap-2">
              {row.original.retired ? null : (
                <Button size="sm" variant="neutral" aria-label={t('sc.sz.editAria', { name: row.original.name })} onClick={() => setEditing(row.original)}>
                  <PencilSimple size={14} aria-hidden /> {t('sc.sz.edit')}
                </Button>
              )}
              <Button size="sm" variant="neutral" aria-label={t('sc.sz.duplicateAria', { name: row.original.name })} disabled={duplicate.isPending} onClick={() => duplicate.mutate(row.original)}>
                <Copy size={14} aria-hidden /> {t('sc.sz.duplicate')}
              </Button>
              {row.original.retired ? null : (
                <Button size="sm" variant="neutral" aria-label={t('sc.sz.retireAria', { name: row.original.name })} onClick={() => setRetiring(row.original)}>
                  <Archive size={14} aria-hidden /> {t('sc.sz.retire')}
                </Button>
              )}
            </span>
          ) : null
      }
    ],
    [editable, duplicate, t, typeName]
  );

  return (
    <>
      <PageHeader
        title={t('sc.sz.title')}
        description={t('sc.sz.description')}
        actions={editable ? (
          <>
            {has('bulkUpload') ? (
              <Link href="/listings/size-charts/import" className={buttonClassName({ variant: 'neutral' })}>
                <FileArrowUp size={18} aria-hidden /> {t('sc.sz.import')}
              </Link>
            ) : null}
            <Button variant="primary" onClick={() => setEditing('new')}><Plus size={18} aria-hidden /> {t('sc.sz.new')}</Button>
          </>
        ) : null}
      />
      {!editable ? <Notice tone="info" icon="eye" title={t('sc.modulePage.readOnly')}>{t('sc.sz.readOnly')}</Notice> : null}
      <DataTable<SizeChart>
        id="seller.size-charts"
        label={t('sc.sz.title')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(c) => c.id}
        rowLabel={(c) => c.name}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder={t('sc.sz.search')}
        empty={t('sc.sz.empty')}
        noMatches={t('sc.sz.noMatches')}
        enableSelection={false}
        toolbar={<Checkbox label={t('sc.sz.showRetired')} checked={withRetired} onChange={(e) => setWithRetired(e.target.checked)} />}
      />
      <SizeChartEditor open={editing !== null} chart={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => setEditing(null)} />
      <Dialog open={retiring !== null} onOpenChange={(o) => { if (!o) setRetiring(null); }} title={t('sc.sz.retireTitle', { name: retiring?.name ?? '' })} width={440}
        description={retiring && retiring.productCount > 0 ? t('sc.sz.retireUsed', { count: retiring.productCount }) : t('sc.sz.retireUnused')} closeLabel={t('sc.sz.close')}>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="neutral" onClick={() => setRetiring(null)}>{t('sc.sz.cancel')}</Button>
          <Button variant="primary" loading={retire.isPending} onClick={() => retiring && retire.mutate(retiring)}>{t('sc.sz.retireConfirm')}</Button>
        </div>
      </Dialog>
    </>
  );
}
