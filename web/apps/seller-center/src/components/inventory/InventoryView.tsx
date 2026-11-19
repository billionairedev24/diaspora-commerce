'use client';

import { sellerKeys, sellerQueries, type StockPosition, type StockReason } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, PageStack, Select, StatStrip, Tabs, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useRef, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
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

/** Inventory: the counts you keep per listing and the stock AfroTransact still holds from earlier shipments (read only; nothing new is sent, ADR 0013). */
export function InventoryView() {
  const router = useRouter();
  const pathname = usePathname();
  const tab = useSearchParams().get('tab') ?? 'stock';
  const { can } = useSeller();
  const [adjusting, setAdjusting] = useState(false);
  return (
    <ModulePage module="inventory" actions={tab === 'stock' && can('inventory') ? <Button variant="neutral" data-tour="inventory-adjust" onClick={() => setAdjusting(true)}>Adjust stock</Button> : null}>
      <Tabs
        label="Inventory"
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'stock', label: 'Stock', content: tab === 'stock' ? <StockView adjusting={adjusting} setAdjusting={setAdjusting} /> : null }
        ]}
      />
    </ModulePage>
  );
}

/** Stock AfroTransact still holds from earlier shipments (read only) and quantity adjustments for your own SKUs (offers). */
function StockView({ adjusting: open, setAdjusting: setOpen }: { adjusting: boolean; setAdjusting: (v: boolean) => void }) {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const state = useTableState({ searchParams: useSearchParams() });
  const q = useQuery(sellerQueries.inventory(api, orgId, false));
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
    <PageStack>
      {t && t.lowStockSkus > 0 ? (
        <Notice tone="warning" title={`${t.lowStockSkus} SKU${t.lowStockSkus === 1 ? ' is' : 's are'} running low on stock.`}>
          Adjust the counted quantities to avoid stockouts on the storefront.
        </Notice>
      ) : null}
      {t ? (
        <StatStrip
          tour="inventory-stats"
          label="Stock summary"
          items={[
            { id: 'available', label: 'Available', value: t.available },
            { id: 'reserved', label: 'Reserved', value: t.reserved },
            { id: 'low', label: 'Low-stock SKUs', value: t.lowStockSkus, tone: t.lowStockSkus > 0 ? 'attention' : 'neutral', onSelect: t.lowStockSkus > 0 ? () => state.setSearchInput('low stock') : undefined }
          ]}
        />
      ) : null}
      <DataTable<StockPosition> id="seller.inventory" label="Stock AfroTransact still holds from earlier shipments" columns={columns} data={q.data?.positions ?? []} getRowId={(p) => `${p.sku}@${p.nodeId}`}
        rowLabel={(p) => `${p.sku} at ${p.nodeId}`} state={state} mode="client" loading={q.isPending} fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder="Search SKU, warehouse, quantities, “low stock”…"
        empty={<>AfroTransact holds no stock for you. New stock is no longer sent to us: keep your own stock counts on each listing.</>}
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
    </PageStack>
  );
}
