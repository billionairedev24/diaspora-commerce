'use client';

import { type ListingRow, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Badge, Button, buttonClassName, useToast } from '@afrotransact/ui';
import { FileArrowUp, ImagesSquare, PencilSimple, Plus, Trash } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportTable, pollExport, runForEach } from '@/lib/table-export';

import { FulfilmentSheet } from './FulfilmentSheet';

/** Listings: server-driven table (one search over every column, sort, pages, selection, exports) with pause/resume
    (one row or many), edit, autosaved drafts of the one-step form, "Add a product" and "Bulk upload". */
export function ListingsView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const [fulfilment, setFulfilment] = useState<ListingRow | null>(null);
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const state = useTableState({ searchParams: useSearchParams() });
  const q = useQuery({
    ...sellerQueries.listings(api, orgId, { q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const toggle = useMutation({
    mutationFn: (r: ListingRow) => api.setListingActive(r.productId, r.status !== 'LIVE'),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      toast.show(r.status === 'LIVE' ? 'Listing is live again' : 'Listing paused');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const drafts = useQuery({ ...sellerQueries.productDrafts(api, orgId), enabled: editable });
  const discard = useMutation({
    mutationFn: (id: string) => api.deleteProductDraft(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sellerKeys.productDrafts(orgId) });
      toast.show(t('sc.listings.draftDiscarded'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const newDrafts = (drafts.data?.items ?? []).filter((d) => !d.productId);
  const columns = useMemo<ColumnDef<ListingRow>[]>(
    () => [
      {
        id: 'title', accessorFn: (r) => r.title, header: 'Product', size: 320, meta: { kind: 'strong' },
        cell: ({ row }) => (
          <span className="flex flex-col">
            <strong className="font-semibold">{row.original.title}</strong>
            {row.original.moderationNote ? <span className="text-xs text-danger">{row.original.moderationNote}</span> : null}
          </span>
        )
      },
      { id: 'atin', accessorFn: (r) => r.atin ?? undefined, header: 'ATIN', size: 130, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.atin ?? '—'}</span> },
      { id: 'sku', accessorFn: (r) => r.sku, header: 'SKU', size: 150, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.sku}{row.original.variantCount > 1 ? ` +${row.original.variantCount - 1}` : ''}</span> },
      { id: 'variantCount', accessorFn: (r) => r.variantCount, header: 'Variants', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'priceMinor', accessorFn: (r) => r.priceMinor, header: 'Price', size: 100, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.priceMinor, row.original.currency) },
      { id: 'stock', accessorFn: (r) => r.stock, header: 'Stock', size: 80, meta: { kind: 'number', align: 'end' }, cell: ({ row }) => <span className={row.original.stock <= 5 ? 'font-semibold text-danger' : undefined}>{row.original.stock}</span> },
      {
        id: 'fulfilment', accessorFn: (r) => t(`sc.ff.${r.fulfilment}`), header: 'Fulfilment', size: 140,
        cell: ({ row }) => (
          <span className="flex flex-col items-start gap-1">
            <Badge tone={row.original.fulfilment === 'SELLER' ? 'seller' : 'fulfilled'}>{t(`sc.ff.${row.original.fulfilment}`)}</Badge>
            {row.original.atin ? (
              <button type="button" className="link-button p-0 text-xs text-link hover:underline" onClick={() => setFulfilment(row.original)} aria-label={t('sc.ff.buttonAria', { title: row.original.title })}>
                {t('sc.ff.button')}
              </button>
            ) : null}
          </span>
        )
      },
      { id: 'status', accessorFn: (r) => r.statusLabel, header: 'Status', size: 140, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
      {
        id: 'updatedAt', accessorFn: (r) => r.updatedAt, header: 'Updated', size: 120, meta: { kind: 'datetime' }, cell: ({ row }) => shortDate(row.original.updatedAt)
      },
      {
        id: 'action', header: () => <span className="at-visually-hidden">Action</span>, size: 180, enableSorting: false, meta: { export: false, label: 'Action' },
        cell: ({ row }) =>
          editable ? (
            <span className="flex flex-wrap items-center gap-2">
              {row.original.status !== 'TAKEN_DOWN' ? (
                <Link href={`/listings/${encodeURIComponent(row.original.productId)}/edit`} className={buttonClassName({ variant: 'neutral', size: 'sm' })} aria-label={t('sc.listings.editAria', { title: row.original.title })}>
                  <PencilSimple size={14} aria-hidden /> {t('sc.listings.edit')}
                </Link>
              ) : null}
              {row.original.status === 'LIVE' || row.original.status === 'PAUSED' ? (
                <Button size="sm" variant="neutral" disabled={toggle.isPending} onClick={() => toggle.mutate(row.original)}>
                  {row.original.status === 'LIVE' ? 'Pause' : 'Resume'}
                </Button>
              ) : null}
            </span>
          ) : null
      }
    ],
    [editable, toggle, t]
  );
  return (
    <ModulePage
      module="listings"
      actions={editable ? (
        <>
          <Link href="/listings/images" className={buttonClassName({ variant: 'neutral' })}>
            <ImagesSquare size={18} aria-hidden /> {t('sc.listings.images')}
          </Link>
          <Link href="/listings/bulk" className={buttonClassName({ variant: 'neutral' })}>
            <FileArrowUp size={18} aria-hidden /> {t('sc.listings.bulk')}
          </Link>
          <Link href="/listings/new" className={buttonClassName({ variant: 'primary' })}>
            <Plus size={18} aria-hidden /> Add a product
          </Link>
        </>
      ) : null}
    >
      {newDrafts.length ? (
        <section aria-labelledby="listing-drafts" className="flex flex-col gap-2 rounded-md border border-border bg-surface p-3">
          <h2 id="listing-drafts" className="m-0 text-md font-semibold">{t('sc.listings.drafts', { count: newDrafts.length })}</h2>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {newDrafts.slice(0, 5).map((d) => (
              <li key={d.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-testid="draft-row">
                <Link href={`/listings/new?draft=${encodeURIComponent(d.id)}`} className="font-semibold">{d.title || t('sc.listings.untitled')}</Link>
                <span className="text-xs text-muted">{d.categoryPath ? `${d.categoryPath} · ` : ''}{t('sc.listings.draftSaved', { time: dateTime(d.updatedAt) })}</span>
                <button type="button" className="ml-auto inline-flex items-center gap-1 border-0 bg-transparent p-0 text-xs text-danger" disabled={discard.isPending} onClick={() => discard.mutate(d.id)}
                  aria-label={t('sc.listings.discardAria', { title: d.title || t('sc.listings.untitled') })}>
                  <Trash size={14} aria-hidden /> {t('sc.listings.discard')}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      <DataTable<ListingRow>
        id="seller.listings"
        label="Listings"
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(r) => r.productId}
        rowLabel={(r) => r.title}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        partial={q.data?.partial}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder="Search title, SKU, ATIN, price, status…"
        empty="No listings yet — add your first product."
        noMatches="No listings match."
        onExport={(req) => exportTable('listings', req)}
        pollExport={pollExport}
        bulkActions={
          editable
            ? (rows, clear) => {
                const run = async (active: boolean) => {
                  const r = await runForEach(rows.filter((x) => x.status === (active ? 'PAUSED' : 'LIVE')), (x) => api.setListingActive(x.productId, active), active ? 'resumed' : 'paused', problemMessage);
                  void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
                  toast.show(r.text, r.tone);
                  clear();
                };
                return (
                  <>
                    <Button size="sm" variant="neutral" onClick={() => void run(false)} disabled={!rows.some((x) => x.status === 'LIVE')}>
                      Pause live
                    </Button>
                    <Button size="sm" variant="neutral" onClick={() => void run(true)} disabled={!rows.some((x) => x.status === 'PAUSED')}>
                      Resume paused
                    </Button>
                  </>
                );
              }
            : undefined
        }
      />
      <FulfilmentSheet listing={fulfilment} onClose={() => setFulfilment(null)} />
    </ModulePage>
  );
}
