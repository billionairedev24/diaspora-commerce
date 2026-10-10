'use client';

import { type ListingRow, type ListingStatus, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Badge, Button, buttonClassName, TextField, useToast } from '@afrotransact/ui';
import { Plus } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useDeferredValue, useMemo, useState } from 'react';

import { DataTable, FilterChips } from '@/components/ui/DataTable';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { money, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { FulfilmentSheet } from './FulfilmentSheet';

const PAGE_SIZE = 25;
const FILTERS: { value: ListingStatus | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'LIVE', label: 'Live' },
  { value: 'IN_REVIEW', label: 'In review' },
  { value: 'PENDING_CATEGORY_APPROVAL', label: 'Waiting for category' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'TAKEN_DOWN', label: 'Taken down' },
  { value: 'REJECTED', label: 'Rejected' }
];

/** Listings: server-driven table (status, search, pages) with pause/resume and the "Add a product" builder. */
export function ListingsView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const [fulfilment, setFulfilment] = useState<ListingRow | null>(null);
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const [status, setStatus] = useState<ListingStatus | ''>('');
  const [text, setText] = useState('');
  const search = useDeferredValue(text.trim());
  const [page, setPage] = useState(0);
  const q = useQuery(sellerQueries.listings(api, orgId, { status: status || undefined, q: search || undefined, page, size: PAGE_SIZE }));
  const toggle = useMutation({
    mutationFn: (r: ListingRow) => api.setListingActive(r.productId, r.status !== 'LIVE'),
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      toast.show(r.status === 'LIVE' ? 'Listing is live again' : 'Listing paused');
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const columns = useMemo<ColumnDef<ListingRow>[]>(
    () => [
      {
        id: 'product', header: 'Product', size: 34,
        cell: ({ row }) => (
          <span className="flex flex-col">
            <strong className="font-semibold">{row.original.title}</strong>
            {row.original.moderationNote ? <span className="text-xs text-danger">{row.original.moderationNote}</span> : null}
            <span className="text-xs text-muted">Updated {shortDate(row.original.updatedAt)}</span>
          </span>
        )
      },
      { id: 'sku', header: 'SKU', size: 14, cell: ({ row }) => <span className="font-mono text-xs">{row.original.sku}{row.original.variantCount > 1 ? ` +${row.original.variantCount - 1}` : ''}</span> },
      { id: 'price', header: 'Price', size: 10, cell: ({ row }) => money(row.original.priceMinor, row.original.currency) },
      { id: 'stock', header: 'Stock', size: 8, cell: ({ row }) => <span className={row.original.stock <= 5 ? 'font-semibold text-danger' : undefined}>{row.original.stock}</span> },
      {
        id: 'ff', header: 'Fulfilment', size: 12,
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
      { id: 'status', header: 'Status', size: 12, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill> },
      {
        id: 'action', header: () => <span className="at-visually-hidden">Action</span>, size: 10,
        cell: ({ row }) =>
          editable && (row.original.status === 'LIVE' || row.original.status === 'PAUSED') ? (
            <Button size="sm" variant="neutral" disabled={toggle.isPending} onClick={() => toggle.mutate(row.original)}>
              {row.original.status === 'LIVE' ? 'Pause' : 'Resume'}
            </Button>
          ) : null
      }
    ],
    [editable, toggle, t]
  );
  return (
    <ModulePage
      module="listings"
      actions={editable ? (
        <Link href="/listings/new" className={buttonClassName({ variant: 'primary' })}>
          <Plus size={18} aria-hidden /> Add a product
        </Link>
      ) : null}
    >
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-full sm:w-72">
          <TextField label="Search listings" type="search" placeholder="Title or SKU" value={text} onChange={(e) => { setText(e.target.value); setPage(0); }} />
        </div>
        <FilterChips label="Status" options={FILTERS} value={status} onChange={(v) => { setStatus(v); setPage(0); }} />
      </div>
      {q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <DataTable
          label="Listings"
          columns={columns}
          data={q.data?.items ?? []}
          getRowId={(r) => r.productId}
          loading={q.isFetching}
          empty={search || status ? 'No listings match.' : 'No listings yet — add your first product.'}
          page={{ pageIndex: page, pageSize: PAGE_SIZE, total: q.data?.total ?? 0, hasNext: (page + 1) * PAGE_SIZE < (q.data?.total ?? 0), onPage: setPage }}
        />
      )}
      <FulfilmentSheet listing={fulfilment} onClose={() => setFulfilment(null)} />
    </ModulePage>
  );
}
