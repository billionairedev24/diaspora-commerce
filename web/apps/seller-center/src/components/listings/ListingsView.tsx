'use client';

import { type ListingRow, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { Badge, Button, buttonClassName, InlineEdit, RowName, Tabs, useToast } from '@afrotransact/ui';
import { Eye, FileArrowUp, ImagesSquare, Pause, PencilSimple, Play, Plus, ShieldCheck, Stack, Truck } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useMemo, useRef, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { idempotencyKey, money, shortDate } from '@/lib/format';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';
import { exportTable, pollExport, runForEach } from '@/lib/table-export';

import { DraftsTable } from './DraftsTable';
import { priceMinor } from './form-model';
import { FulfilmentSheet } from './FulfilmentSheet';
import { QuickStockSheet } from './QuickStockSheet';
import { useSavePrice } from './useSavePrice';

const MAX_STOCK = 100_000;
/** Statuses that read as news next to the name; a live listing needs no chip there (the Status column says it). */
const ATTENTION = new Set(['IN_REVIEW', 'REJECTED', 'TAKEN_DOWN', 'PENDING_CATEGORY_APPROVAL', 'PAUSED']);

/** Listings: two server-driven tables on tabs, "Listings" (search, sort, pages, selection, exports, click-to-edit price
    and stock, a "⋯" menu per row) and "Drafts" (autosaved forms waiting to be finished). */
export function ListingsView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { orgId, can, has } = useSeller();
  const editable = can('listings');
  const api = sellerApi();
  const tab = editable && params.get('tab') === 'drafts' ? 'drafts' : 'listings';
  // The tab's count comes from the API total (a page of one row is enough to read it).
  const draftCount = useQuery({ ...sellerQueries.productDraftPage(api, orgId, { kind: 'new', page: 0, size: 1 }), enabled: editable });
  return (
    <ModulePage
      module="listings"
      actions={editable ? (
        <>
          <Link href="/listings/approvals" data-tour="listings-approvals" className={buttonClassName({ variant: 'neutral' })}>
            <ShieldCheck size={18} aria-hidden /> Selling approvals
          </Link>
          {has('imageLibrary') ? (
            <Link href="/listings/images" className={buttonClassName({ variant: 'neutral' })}>
              <ImagesSquare size={18} aria-hidden /> {t('sc.listings.images')}
            </Link>
          ) : null}
          {has('bulkUpload') ? (
            <Link href="/listings/bulk" className={buttonClassName({ variant: 'neutral' })}>
              <FileArrowUp size={18} aria-hidden /> {t('sc.listings.bulk')}
            </Link>
          ) : null}
          <Link href="/listings/new" data-tour="listings-add" className={buttonClassName({ variant: 'primary' })}>
            <Plus size={18} aria-hidden /> {t('sc.listings.add')}
          </Link>
        </>
      ) : null}
    >
      {editable ? (
        <Tabs
          label={t('sc.listings.tabs')}
          value={tab}
          onValueChange={(v) => router.replace(v === 'drafts' ? `${pathname}?tab=drafts` : pathname, { scroll: false })}
          items={[
            { value: 'listings', label: t('sc.listings.tab.listings'), content: tab === 'listings' ? <ListingsTable /> : null },
            {
              value: 'drafts',
              label: draftCount.data ? t('sc.listings.tab.drafts', { count: draftCount.data.total.toLocaleString() }) : t('sc.listings.tab.drafts', { count: '…' }),
              content: tab === 'drafts' ? <DraftsTable /> : null
            }
          ]}
        />
      ) : (
        <ListingsTable />
      )}
    </ModulePage>
  );
}

function ListingsTable() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const storefront = useStorefrontUrl();
  const savePrice = useSavePrice();
  const router = useRouter();
  // The router object is not guaranteed stable; the columns must be, or open menus would close on every render.
  const routerRef = useRef(router);
  routerRef.current = router;
  const go = useCallback((href: string) => routerRef.current.push(href), []);
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const [fulfilment, setFulfilment] = useState<ListingRow | null>(null);
  const [stockOf, setStockOf] = useState<ListingRow | null>(null);
  const state = useTableState({ searchParams: useSearchParams() });
  const q = useQuery({
    ...sellerQueries.listings(api, orgId, { q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
  const toggle = useMutation({
    mutationFn: (r: ListingRow) => api.setListingActive(r.productId, r.status !== 'LIVE'),
    onSuccess: (r) => {
      refresh();
      toast.show(t(r.status === 'LIVE' ? 'sc.listings.toast.resumed' : 'sc.listings.toast.paused'));
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const saveStock = async (row: ListingRow, quantity: number) => {
    const a = await api.adjustStock({ sku: row.sku, quantity, reason: 'STOCK_TAKE' }, idempotencyKey());
    toast.show(t('sc.stock.saved', { sku: a.sku, quantity: a.quantity }));
    refresh();
    void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
  };
  const columns = useMemo<ColumnDef<ListingRow>[]>(
    () => [
      {
        id: 'title', accessorFn: (r) => r.title, header: t('sc.listings.col.product'), size: 320, meta: { kind: 'strong', label: t('sc.listings.col.product') },
        cell: ({ row }) => {
          const r = row.original;
          return (
            <RowName
              href={editable && r.status !== 'TAKEN_DOWN' ? `/listings/${encodeURIComponent(r.productId)}/edit` : undefined}
              linkAs={Link}
              badge={ATTENTION.has(r.status) ? <StatusPill tone={toneOf(r.status)}>{r.statusLabel}</StatusPill> : null}
              meta={
                <>
                  {r.atin ? <span className="font-mono">{r.atin}</span> : null}
                  {r.moderationNote ? <span className="block text-danger">{r.moderationNote}</span> : null}
                </>
              }
            >
              {r.title}
            </RowName>
          );
        }
      },
      {
        id: 'sku', accessorFn: (r) => r.sku, header: t('sc.listings.col.sku'), size: 150, meta: { kind: 'mono', label: t('sc.listings.col.sku') },
        cell: ({ row }) => <span className="font-mono text-xs">{row.original.sku}{row.original.variantCount > 1 ? ` +${row.original.variantCount - 1}` : ''}</span>
      },
      {
        id: 'priceMinor', accessorFn: (r) => r.priceMinor, header: t('sc.listings.col.price'), size: 120, meta: { kind: 'money', align: 'end', label: t('sc.listings.col.price') },
        cell: ({ row }) => {
          const r = row.original;
          const shown = r.variantCount > 1 ? t('sc.listings.pricesFrom', { price: money(r.priceMinor, r.currency) }) : money(r.priceMinor, r.currency);
          // One offer, a listing that can still be edited: click to change the price right here.
          if (!editable || r.variantCount !== 1 || r.status === 'TAKEN_DOWN') return shown;
          return (
            <InlineEdit
              value={(r.priceMinor / 100).toFixed(2)}
              label={t('sc.listings.editPriceOf', { title: r.title })}
              saveLabel={t('sc.rows.save')}
              cancelLabel={t('sc.rows.cancel')}
              inputMode="decimal"
              validate={(v) => (priceMinor(v) === null ? t('sc.listings.price.invalid') : undefined)}
              onSave={async (v) => {
                const minor = priceMinor(v) ?? 0;
                try {
                  await savePrice(r, minor);
                } catch (e) {
                  throw new Error(problemMessage(e));
                }
                toast.show(t('sc.listings.price.saved', { title: r.title, price: money(minor, r.currency) }));
              }}
            >
              {shown}
            </InlineEdit>
          );
        }
      },
      {
        id: 'stock', accessorFn: (r) => r.stock, header: t('sc.listings.col.stock'), size: 100, meta: { kind: 'number', align: 'end', label: t('sc.listings.col.stock') },
        cell: ({ row }) => {
          const r = row.original;
          const low = r.stock <= 5;
          const shown = <span className={low ? 'font-semibold text-danger' : undefined}>{r.stock}</span>;
          // One seller-held offer: click to count it. Several variants go through "Update stock by variant"; AfroTransact-held stock lives in Inventory.
          if (!editable || r.variantCount !== 1 || r.fulfilment !== 'SELLER' || !r.atin || r.status === 'TAKEN_DOWN') return shown;
          return (
            <InlineEdit
              value={String(r.stock)}
              label={t('sc.listings.editStockOf', { title: r.title })}
              saveLabel={t('sc.rows.save')}
              cancelLabel={t('sc.rows.cancel')}
              inputMode="numeric"
              width={7}
              validate={(v) => (/^\d+$/.test(v) && Number(v) <= MAX_STOCK ? undefined : t('sc.stock.invalid', { max: MAX_STOCK.toLocaleString() }))}
              onSave={async (v) => {
                try {
                  await saveStock(r, Number(v));
                } catch (e) {
                  throw new Error(problemMessage(e));
                }
              }}
            >
              {shown}
            </InlineEdit>
          );
        }
      },
      {
        id: 'fulfilment', accessorFn: (r) => t(`sc.ff.${r.fulfilment}`), header: t('sc.listings.col.fulfilment'), size: 140, meta: { label: t('sc.listings.col.fulfilment') },
        cell: ({ row }) => <Badge tone={row.original.fulfilment === 'SELLER' ? 'seller' : 'fulfilled'}>{t(`sc.ff.${row.original.fulfilment}`)}</Badge>
      },
      {
        id: 'status', accessorFn: (r) => r.statusLabel, header: t('sc.listings.col.status'), size: 140, meta: { kind: 'status', label: t('sc.listings.col.status') },
        cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{row.original.statusLabel}</StatusPill>
      },
      { id: 'updatedAt', accessorFn: (r) => r.updatedAt, header: t('sc.listings.col.updated'), size: 120, meta: { kind: 'datetime', label: t('sc.listings.col.updated') }, cell: ({ row }) => shortDate(row.original.updatedAt) },
      rowActionsColumn<ListingRow>({
        header: t('sc.rows.actionsHeader'),
        name: (r) => r.title,
        label: (r) => t('sc.rows.actionsFor', { name: r.title }),
        items: (r) => [
          editable && { label: t('sc.listings.edit'), icon: <PencilSimple size={16} aria-hidden />, onSelect: () => go(`/listings/${encodeURIComponent(r.productId)}/edit`), disabled: r.status === 'TAKEN_DOWN' ? t('sc.listings.a.takenDown') : false },
          r.status === 'LIVE' && !!r.atin && { label: t('sc.listings.a.view'), icon: <Eye size={16} aria-hidden />, href: `${storefront.replace(/\/+$/, '')}/p/${encodeURIComponent(r.atin)}`, external: true },
          editable && (r.status === 'LIVE' || r.status === 'PAUSED') && {
            label: t(r.status === 'LIVE' ? 'sc.listings.a.pause' : 'sc.listings.a.resume'),
            icon: r.status === 'LIVE' ? <Pause size={16} aria-hidden /> : <Play size={16} aria-hidden />,
            onSelect: () => toggle.mutate(r)
          },
          editable && !!r.atin && r.variantCount > 1 && r.fulfilment === 'SELLER' && { label: t('sc.listings.a.stock'), icon: <Stack size={16} aria-hidden />, onSelect: () => setStockOf(r) },
          editable && !!r.atin && { label: t('sc.listings.a.fulfilment'), icon: <Truck size={16} aria-hidden />, onSelect: () => setFulfilment(r) }
        ]
      })
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the mutation objects change every render; their callbacks read the latest values
    [editable, t, storefront, savePrice, go]
  );
  return (
    <>
      <DataTable<ListingRow>
        id="seller.listings"
        label={t('sc.listings.tab.listings')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(r) => r.productId}
        rowLabel={(r) => r.title}
        onRowClick={editable ? (r) => (r.status === 'TAKEN_DOWN' ? undefined : go(`/listings/${encodeURIComponent(r.productId)}/edit`)) : undefined}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        partial={q.data?.partial}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder={t('sc.listings.search')}
        empty={t('sc.listings.empty')}
        noMatches={t('sc.listings.noMatches')}
        onExport={(req) => exportTable('listings', req)}
        pollExport={pollExport}
        bulkActions={
          editable
            ? (rows, clear) => {
                const run = async (active: boolean) => {
                  const r = await runForEach(rows.filter((x) => x.status === (active ? 'PAUSED' : 'LIVE')), (x) => api.setListingActive(x.productId, active), active ? 'resumed' : 'paused', problemMessage);
                  refresh();
                  toast.show(r.text, r.tone);
                  clear();
                };
                return (
                  <>
                    <Button size="sm" variant="neutral" onClick={() => void run(false)} disabled={!rows.some((x) => x.status === 'LIVE')}>
                      {t('sc.listings.bulk.pause')}
                    </Button>
                    <Button size="sm" variant="neutral" onClick={() => void run(true)} disabled={!rows.some((x) => x.status === 'PAUSED')}>
                      {t('sc.listings.bulk.resume')}
                    </Button>
                  </>
                );
              }
            : undefined
        }
      />
      <FulfilmentSheet listing={fulfilment} onClose={() => setFulfilment(null)} />
      <QuickStockSheet listing={stockOf} onClose={() => setStockOf(null)} />
    </>
  );
}
