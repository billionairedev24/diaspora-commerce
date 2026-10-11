'use client';

import { sellerKeys, sellerQueries, type StockPosition, type StockReason } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useRef, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

const columns: ColumnDef<StockPosition>[] = [
  { id: 'sku', accessorFn: (p) => p.sku, header: 'SKU', size: 220, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-mono text-xs">{row.original.sku}</span> },
  { id: 'nodeId', accessorFn: (p) => p.nodeId, header: 'Warehouse', size: 150 },
  { id: 'onHand', accessorFn: (p) => p.onHand, header: 'On hand', size: 110, meta: { kind: 'number', align: 'end' } },
  { id: 'reserved', accessorFn: (p) => p.reserved, header: 'Reserved', size: 110, meta: { kind: 'number', align: 'end' } },
  {
    id: 'available', accessorFn: (p) => p.available, header: 'Available', size: 120, meta: { kind: 'number', align: 'end' },
    cell: ({ row }) => <span className={row.original.lowStock ? 'font-semibold text-danger' : undefined}>{row.original.available}</span>
  },
  { id: 'inbound', accessorFn: (p) => p.inbound, header: 'Inbound', size: 110, meta: { kind: 'number', align: 'end' } },
  // Low stock (10 or fewer available) is a column, so the one search finds it ("low") and exports chart it.
  { id: 'alert', accessorFn: (p) => (p.lowStock ? 'Low stock' : 'In stock'), header: 'Stock alert', size: 120, meta: { kind: 'status' },
    cell: ({ row }) => (row.original.lowStock ? <span className="font-semibold text-danger">Low stock</span> : <span className="text-muted">In stock</span>) }
];

const REASONS: { value: StockReason; label: string }[] = [
  { value: 'STOCK_TAKE', label: 'Stock take / count' },
  { value: 'DAMAGED', label: 'Damaged' },
  { value: 'FOUND', label: 'Found' },
  { value: 'RETURNED', label: 'Returned to stock' },
  { value: 'OTHER', label: 'Other' }
];

/** Inventory: FBA stock by warehouse (inventory) and quantity adjustments for seller-shipped SKUs (offers). */
export function InventoryView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const state = useTableState({ searchParams: useSearchParams() });
  const q = useQuery(sellerQueries.inventory(api, orgId, false));
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ sku: '', quantity: '', reason: 'STOCK_TAKE' as StockReason });
  const [error, setError] = useState<string | null>(null);
  const key = useRef(idempotencyKey());
  const adjust = useMutation({
    mutationFn: () => api.adjustStock({ sku: form.sku.trim(), quantity: Number(form.quantity), reason: form.reason }, key.current),
    onSuccess: (a) => {
      key.current = idempotencyKey();
      setOpen(false);
      void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
      toast.show(`${a.sku} set to ${a.quantity}`);
    },
    onError: (e) => setError(problemMessage(e))
  });
  const t = q.data?.totals;
  return (
    <ModulePage module="inventory" actions={can('inventory') ? <Button variant="neutral" onClick={() => { setError(null); setOpen(true); }}>Adjust stock</Button> : null}>
      {t ? (
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,160px),1fr))] gap-3">
          {[['Available', t.available], ['Reserved', t.reserved], ['Inbound', t.inbound], ['Low-stock SKUs', t.lowStockSkus]].map(([k, v]) => (
            <div key={k} className="flex flex-col rounded-md border border-border p-4"><span className="text-xs font-semibold text-muted">{k}</span><strong className="text-2xl">{v}</strong></div>
          ))}
        </div>
      ) : null}
      <DataTable<StockPosition> id="seller.inventory" label="Stock by warehouse" columns={columns} data={q.data?.positions ?? []} getRowId={(p) => `${p.sku}@${p.nodeId}`}
        rowLabel={(p) => `${p.sku} at ${p.nodeId}`} state={state} mode="client" loading={q.isPending} fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder="Search SKU, warehouse, quantities, “low stock”…"
        empty="No stock in AfroTransact fulfilment centres yet. Send an inbound shipment to earn the “Fulfilled by AfroTransact” badge."
        onExport={exportDataset} pollExport={pollExport} />
      <Dialog open={open} onOpenChange={setOpen} title="Adjust stock" description="Set the quantity you have for a seller-shipped SKU after counting it.">
        <form className="flex flex-col gap-2" onSubmit={(e) => {
          e.preventDefault();
          if (!form.sku.trim()) return setError('Enter the SKU');
          if (!/^\d+$/.test(form.quantity)) return setError('Enter the counted quantity (a whole number)');
          setError(null);
          adjust.mutate();
        }}>
          <TextField label="SKU" value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
          <TextField label="Counted quantity" inputMode="numeric" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
          <Select label="Reason" options={REASONS} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value as StockReason })} />
          {error ? <span role="alert" className="text-sm text-danger">{error}</span> : null}
          <Button type="submit" loading={adjust.isPending}>Save quantity</Button>
        </form>
      </Dialog>
    </ModulePage>
  );
}
