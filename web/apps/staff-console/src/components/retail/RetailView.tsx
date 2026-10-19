'use client';

import { type GoodsReceipt, inventoryHubQueries, type RetailItem, type Supplier, type SupplierInvoice } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Barcode, Button, Sheet, Tabs, TextField } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { toneOf, useHub, useHubAccess, useHubMutation, useParam } from '@/components/inventory/shared';
import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { dateTime, humanize, money } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { ItemForm, SupplierForm } from './MasterData';
import { PurchaseOrdersPanel } from './PurchaseOrders';
import { RetailReceiptScreen } from './ReceiptScreen';

/** One retail item: barcodes, the catalog ATIN link and "list on storefront" (points the 1P offer at this SKU). */
function ItemSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = useHub();
  const { retailEdit } = useHubAccess();
  const items = useQuery(inventoryHubQueries.items(api, { page: 0, size: 200 }));
  const item = items.data?.items.find((i) => i.id === id);
  const [atin, setAtin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const link = useHubMutation(() => api.retail.linkAtin(item?.id ?? '', atin.trim().toUpperCase(), item?.version), (i) => `${i.sku} linked to ${i.atin ?? ''}.`, () => setAtin(''));
  const listing = useHubMutation((status: 'LIVE' | 'PAUSED') => api.retail.setListing(item?.id ?? '', status, item?.version), (i) => (i.listingStatus === 'LIVE' ? `${i.sku} is live: the storefront offer now sells this SKU’s lots.` : `${i.sku} paused: off sale.`));
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={item?.name ?? 'Item'} width={560}>
      {items.isPending ? <Spinner /> : null}
      {item ? (
        <div className="flex flex-col gap-4" data-testid="item-sheet">
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={toneOf(item.listingStatus)}>Listing {humanize(item.listingStatus).toLowerCase()}</StatusPill>
            <span className="font-mono text-xs text-muted">{item.sku}</span>
          </div>
          <dl className="m-0 grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-x-3 gap-y-1 text-sm">
            {[
              ['GTIN', item.gtin],
              ['Catalog ATIN', item.atin ?? 'Not linked'],
              ['Storage · shelf life', `${humanize(item.storage)} · ${item.shelfLifeDays} days`],
              ['Case pack', String(item.casePack)],
              ['Retail price', money(item.priceMinor, item.currency)],
              ['Reorder point', String(item.reorderPoint)]
            ].map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className="m-0">{v}</dd>
              </div>
            ))}
          </dl>
          <figure className="m-0 flex flex-col gap-1">
            <figcaption className="text-xs font-bold text-muted">Retail barcode (EAN-13)</figcaption>
            <Barcode symbology="ean13" value={item.gtin} />
          </figure>
          {retailEdit ? (
            <form
              className="flex flex-wrap at-align gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (!/^AT[0-9A-Z]{8}$/.test(atin.trim().toUpperCase())) return setError('A variant ATIN: AT + 8 letters or digits');
                setError(null);
                link.mutate(undefined);
              }}
            >
              <TextField label={item.atin ? 'Change the catalog ATIN' : 'Link the catalog ATIN'} value={atin} onChange={(e) => setAtin(e.target.value)} error={error} className="min-w-[200px] flex-1" />
              <Button type="submit" size="sm" variant="neutral" loading={link.isPending}>
                Link
              </Button>
            </form>
          ) : null}
          {retailEdit ? (
            <div className="flex flex-wrap gap-2">
              {item.listingStatus !== 'LIVE' ? (
                <Button size="sm" disabled={!item.atin} loading={listing.isPending} onClick={() => listing.mutate('LIVE')}>
                  List on storefront
                </Button>
              ) : (
                <Button size="sm" variant="neutral" loading={listing.isPending} onClick={() => listing.mutate('PAUSED')}>
                  Pause listing
                </Button>
              )}
              {!item.atin ? <span className="text-xs text-muted">Link the catalog ATIN before listing.</span> : null}
            </div>
          ) : null}
          <Link className="text-sm font-semibold text-link" href={`/inventory?tab=flow&trace=${encodeURIComponent(item.sku)}`}>
            Trace this item to the storefront
          </Link>
        </div>
      ) : null}
    </Sheet>
  );
}

function ItemsPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const params = useSearchParams();
  const state = useTableState({ searchParams: params, prefix: 'it' });
  const [id, setId] = useParam('item');
  const [creating, setCreating] = useState(false);
  const { retailEdit } = useHubAccess();
  const query = useQuery(inventoryHubQueries.items(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<RetailItem>[]>(
    () => [
      { id: 'sku', accessorFn: (i) => i.sku, header: 'SKU', size: 150, meta: { kind: 'mono' } },
      { id: 'name', accessorFn: (i) => i.name, header: 'Item', size: 280, meta: { kind: 'strong' } },
      { id: 'gtin', accessorFn: (i) => i.gtin, header: 'GTIN', size: 130, meta: { kind: 'mono' } },
      { id: 'atin', accessorFn: (i) => i.atin ?? '', header: 'ATIN', size: 120, meta: { kind: 'mono' } },
      { id: 'storage', accessorFn: (i) => humanize(i.storage), header: 'Storage', size: 100 },
      { id: 'priceMinor', accessorFn: (i) => i.priceMinor, header: 'Price', size: 90, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.priceMinor, row.original.currency) },
      { id: 'listingStatus', accessorFn: (i) => humanize(i.listingStatus), header: 'Listing', size: 100, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.listingStatus)}>{humanize(row.original.listingStatus)}</StatusPill> }
    ],
    []
  );
  return (
    <>
      <DataTable<RetailItem>
        id="staff.retail.items"
        label="Retail items"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(i) => i.id}
        rowLabel={(i) => i.name}
        rowTestId="item-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(i) => setId(i.id)}
        activeRowId={id}
        toolbar={retailEdit ? <Button size="sm" onClick={() => setCreating(true)}>New item</Button> : undefined}
        empty="No retail items yet."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <Sheet open={creating} onOpenChange={setCreating} title="New AfroTransact product" width={720}>
        <ItemForm
          onDone={(i) => {
            setCreating(false);
            setId(i.id);
          }}
        />
      </Sheet>
      <ItemSheet id={creating ? null : id} onClose={() => setId(null)} />
    </>
  );
}

function ReceiptsPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'grn' });
  const [id, setId] = useParam('grn');
  const query = useQuery(inventoryHubQueries.receipts(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<GoodsReceipt>[]>(
    () => [
      { id: 'number', accessorFn: (r) => r.number, header: 'Receipt', size: 130, meta: { kind: 'mono' } },
      { id: 'poNumber', accessorFn: (r) => r.poNumber ?? '', header: 'PO', size: 140, meta: { kind: 'mono' } },
      { id: 'shipmentRef', accessorFn: (r) => r.shipmentRef ?? '', header: 'Shipment', size: 140 },
      { id: 'nodeId', accessorFn: (r) => r.nodeId, header: 'FC', size: 90 },
      { id: 'units', accessorFn: (r) => r.lines.reduce((a, l) => a + l.qtyGood + l.qtyDamaged, 0), header: 'Counted', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'lots', accessorFn: (r) => r.postedLots.length, header: 'Lots posted', size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'openedAt', accessorFn: (r) => r.openedAt ?? '', header: 'Opened', size: 140, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.openedAt) },
      { id: 'status', accessorFn: (r) => humanize(r.status), header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    []
  );
  return (
    <>
      <DataTable<GoodsReceipt>
        id="staff.retail.receipts"
        label="Goods receipts"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(r) => r.id}
        rowLabel={(r) => r.number}
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(r) => setId(r.id)}
        activeRowId={id}
        empty="No goods receipts yet: open one from a purchase order's shipment."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <Sheet open={id !== null} onOpenChange={(o) => !o && setId(null)} title="Goods receipt" width={820}>
        {id ? <RetailReceiptScreen id={id} /> : null}
      </Sheet>
    </>
  );
}

function InvoicesPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { retailApprove } = useHubAccess();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'inv' });
  const query = useQuery(inventoryHubQueries.invoices(api, { page: 0, size: 200 }));
  const act = useHubMutation(({ id, action }: { id: string; action: 'approve' | 'reject' | 'rematch' }) => api.retail.invoiceAction(id, action), (i) => `Invoice ${i.invoiceNumber}: ${humanize(i.status).toLowerCase()}.`);
  const columns = useMemo<ColumnDef<SupplierInvoice>[]>(
    () => [
      { id: 'invoiceNumber', accessorFn: (i) => i.invoiceNumber, header: 'Invoice', size: 130, meta: { kind: 'mono' } },
      { id: 'poNumber', accessorFn: (i) => i.poNumber ?? '', header: 'PO', size: 130, meta: { kind: 'mono' } },
      { id: 'invoiceDate', accessorFn: (i) => i.invoiceDate, header: 'Date', size: 110, meta: { kind: 'date' } },
      { id: 'totalMinor', accessorFn: (i) => i.totalMinor, header: 'Invoiced', size: 120, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.totalMinor, row.original.currency) },
      { id: 'expected', accessorFn: (i) => i.match?.expectedTotalMinor ?? 0, header: 'PO × received', size: 130, meta: { kind: 'money', align: 'end' }, cell: ({ row }) => money(row.original.match?.expectedTotalMinor ?? 0, row.original.currency) },
      { id: 'reasons', accessorFn: (i) => i.match?.reasons.join(' · ') ?? '', header: 'Match', size: 260 },
      { id: 'status', accessorFn: (i) => humanize(i.status), header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    []
  );
  return (
    <DataTable<SupplierInvoice>
      id="staff.retail.invoices"
      label="Supplier invoices"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(i) => i.id}
      rowLabel={(i) => i.invoiceNumber}
      state={state}
      mode="client"
      loading={query.isPending}
      fetching={query.isFetching}
      error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
      bulkActions={
        retailApprove
          ? (rows, clear) => (
              <>
                <Button size="sm" variant="neutral" onClick={() => rows.filter((r) => r.status === 'EXCEPTION').forEach((r) => act.mutate({ id: r.id, action: 'approve' }, { onSuccess: clear }))}>
                  Approve exceptions
                </Button>
                <Button size="sm" variant="neutral" onClick={() => rows.forEach((r) => act.mutate({ id: r.id, action: 'reject' }, { onSuccess: clear }))}>
                  Reject
                </Button>
              </>
            )
          : undefined
      }
      empty="No supplier invoices yet: register one from a received purchase order."
      onExport={(req) => exportDataset(qc, req)}
      exportsHref="/exports"
    />
  );
}

function SuppliersPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { retailEdit, retailApprove } = useHubAccess();
  const [creating, setCreating] = useState(false);
  const approve = useHubMutation((ids: string[]) => Promise.all(ids.map((i) => api.retail.setSupplierStatus(i, 'APPROVED', 'Vetted'))), (r) => `${r.length} suppliers approved.`);
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'sup' });
  const query = useQuery(inventoryHubQueries.suppliers(api));
  const columns = useMemo<ColumnDef<Supplier>[]>(
    () => [
      { id: 'code', accessorFn: (s) => s.code, header: 'Code', size: 110, meta: { kind: 'mono' } },
      { id: 'name', accessorFn: (s) => s.name, header: 'Supplier', size: 220, meta: { kind: 'strong' } },
      { id: 'where', accessorFn: (s) => [s.city, s.country].filter(Boolean).join(', '), header: 'Where', size: 140 },
      { id: 'currency', accessorFn: (s) => s.currency, header: 'Currency', size: 90 },
      { id: 'leadTimeDays', accessorFn: (s) => s.leadTimeDays, header: 'Lead time (d)', size: 110, meta: { kind: 'number', align: 'end' } },
      { id: 'onTime', accessorFn: (s) => s.scorecard?.onTimePercent ?? 0, header: 'On time %', size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'defect', accessorFn: (s) => s.scorecard?.defectPercent ?? 0, header: 'Defect %', size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'status', accessorFn: (s) => humanize(s.status), header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    []
  );
  return (
    <>
    <DataTable<Supplier>
      id="staff.retail.suppliers"
      label="Suppliers"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(s) => s.id}
      rowLabel={(s) => s.name}
      state={state}
      mode="client"
      loading={query.isPending}
      fetching={query.isFetching}
      error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
      toolbar={retailEdit ? <Button size="sm" onClick={() => setCreating(true)}>New supplier</Button> : undefined}
      bulkActions={retailApprove ? (rows, clear) => <Button size="sm" variant="neutral" loading={approve.isPending} onClick={() => approve.mutate(rows.filter((r) => r.status !== 'APPROVED').map((r) => r.id), { onSuccess: clear })}>Approve</Button> : undefined}
      empty="No suppliers yet."
      onExport={(req) => exportDataset(qc, req)}
      exportsHref="/exports"
    />
    <Sheet open={creating} onOpenChange={setCreating} title="New supplier" width={560}>
      <SupplierForm onDone={() => setCreating(false)} />
    </Sheet>
    </>
  );
}

const TABS = [
  ['pos', 'Purchase orders'],
  ['items', 'Items & listings'],
  ['receipts', 'Goods receipts'],
  ['invoices', 'Invoices'],
  ['suppliers', 'Suppliers']
] as const;

/** AfroTransact Retail (1P): purchase orders to suppliers through receipts, invoices and the storefront listing. */
export function RetailView() {
  const router = useRouter();
  const pathname = usePathname();
  const tab = useSearchParams().get('tab') ?? 'pos';
  const panel = (v: string) =>
    v === 'items' ? <ItemsPanel /> : v === 'receipts' ? <ReceiptsPanel /> : v === 'invoices' ? <InvoicesPanel /> : v === 'suppliers' ? <SuppliersPanel /> : <PurchaseOrdersPanel />;
  return (
    <ModulePage module="retail">
      <Tabs
        label="AfroTransact Retail"
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={TABS.map(([value, label]) => ({ value, label, content: value === tab ? panel(value) : null }))}
      />
    </ModulePage>
  );
}
