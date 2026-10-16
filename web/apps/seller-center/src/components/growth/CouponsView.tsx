'use client';

import { type CreateSellerCoupon, type SellerCoupon, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

function valueLabel(c: SellerCoupon): string {
  if (c.type === 'FREE_SHIPPING') return 'Free shipping';
  if (c.type === 'PERCENT') return `${c.value}% off`;
  return `$${(c.value / 100).toFixed(2)} off`;
}

/**
 * Seller Coupons: TanStack DataTable powered component.
 * Allows sellers to review codes, view discounts and pause/activate in an intuitive table workflow.
 * Modal dialog for clean creation workflow.
 */
export function CouponsView() {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId, can, session } = useSeller();
  const api = sellerApi();
  const role = session.activeOrg?.roleKey ?? '';
  const editable = can('coupons', 'EDIT') || can('storefront', 'EDIT') || role === 'seller.owner' || role === 'seller.admin' || role === 'seller.ads';
  const state = useTableState({ searchParams: useSearchParams(), defaultPageSize: 20 });
  const [createOpen, setCreateOpen] = useState(false);
  const [draft, setDraft] = useState<CreateSellerCoupon>({
    code: '',
    type: 'PERCENT',
    value: 10,
    scope: 'SELLER',
    scopeRef: orgId,
    description: ''
  });

  const q = useQuery(sellerQueries.coupons(api, orgId));

  const create = useMutation({
    mutationFn: () => api.createCoupon({
      ...draft,
      code: draft.code.trim().toUpperCase(),
      scopeRef: draft.scopeRef || orgId
    }),
    onSuccess: () => {
      toast.show(t('sc.coupons.created'));
      setDraft({ code: '', type: 'PERCENT', value: 10, scope: 'SELLER', scopeRef: orgId, description: '' });
      setCreateOpen(false);
      void qc.invalidateQueries({ queryKey: sellerKeys.coupons(orgId) });
    }
  });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'ACTIVE' | 'PAUSED' }) => api.setCouponStatus(id, status),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sellerKeys.coupons(orgId) });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const [toDelete, setToDelete] = useState<SellerCoupon | null>(null);

  const deleteCoupon = useMutation({
    mutationFn: (id: string) => api.deleteCoupon(id),
    onSuccess: () => {
      toast.show(t('sc.coupons.deleted'));
      setToDelete(null);
      void qc.invalidateQueries({ queryKey: sellerKeys.coupons(orgId) });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };

  const columns = useMemo<ColumnDef<SellerCoupon>[]>(
    () => [
      {
        id: 'code',
        accessorFn: (c) => c.code,
        header: t('sc.coupons.code'),
        size: 180,
        meta: { kind: 'mono' },
        cell: ({ row }) => (
          <span className="flex flex-col">
            <strong className="font-mono text-sm tracking-wide text-text">{row.original.code}</strong>
            <span className="text-xs text-muted">{row.original.scope === 'SELLER' ? 'Store coupon' : 'Item coupon'}</span>
          </span>
        )
      },
      {
        id: 'discount',
        accessorFn: (c) => valueLabel(c),
        header: t('sc.coupons.type'),
        size: 160,
        meta: { kind: 'strong' },
        cell: ({ row }) => (
          <span className="inline-flex items-center gap-1.5 font-semibold text-text">
            {valueLabel(row.original)}
          </span>
        )
      },
      {
        id: 'description',
        accessorFn: (c) => c.description ?? '',
        header: t('sc.coupons.description'),
        size: 240,
        cell: ({ row }) => (
          <span className="text-sm text-muted">
            {row.original.description ? row.original.description : '—'}
          </span>
        )
      },
      {
        id: 'status',
        accessorFn: (c) => c.status,
        header: 'Status',
        size: 120,
        meta: { kind: 'status' },
        cell: ({ row }) => (
          <StatusPill tone={toneOf(row.original.status)}>
            {row.original.status.toLowerCase()}
          </StatusPill>
        )
      },
      {
        id: 'actions',
        header: () => <span className="sr-only">Actions</span>,
        size: 180,
        enableSorting: false,
        cell: ({ row }) => {
          const c = row.original;
          if (!c.editable || !editable) return null;
          return (
            <div className="flex items-center justify-end gap-2">
              {c.status === 'ACTIVE' ? (
                <Button
                  size="sm"
                  variant="neutral"
                  loading={setStatus.isPending && setStatus.variables?.id === c.id}
                  onClick={() => setStatus.mutate({ id: c.id, status: 'PAUSED' })}
                >
                  {t('sc.coupons.pause')}
                </Button>
              ) : (
                <Button
                  size="sm"
                  variant="secondary"
                  loading={setStatus.isPending && setStatus.variables?.id === c.id}
                  onClick={() => setStatus.mutate({ id: c.id, status: 'ACTIVE' })}
                >
                  {t('sc.coupons.activate')}
                </Button>
              )}
              <Button
                size="sm"
                variant="neutral"
                icon={<Trash size={16} aria-hidden />}
                onClick={() => setToDelete(c)}
                aria-label={`Delete coupon ${c.code}`}
              >
                {t('sc.coupons.delete')}
              </Button>
            </div>
          );
        }
      }
    ],
    [editable, setStatus, t]
  );

  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const items = q.data ?? [];

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t('sc.coupons.title')}
        description={t('sc.coupons.lead')}
        actions={
          editable ? (
            <Button
              variant="primary"
              icon={<Plus size={18} aria-hidden />}
              onClick={() => setCreateOpen(true)}
            >
              {t('sc.coupons.createTitle')}
            </Button>
          ) : null
        }
      />

      <Notice tone="info" title={t('sc.coupons.platformNote')} />

      <DataTable<SellerCoupon>
        id="seller.coupons"
        label={t('sc.coupons.title')}
        columns={columns}
        data={items}
        getRowId={(c) => c.id}
        rowLabel={(c) => c.code}
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        empty={t('sc.coupons.empty')}
      />

      {editable ? (
        <Dialog
          open={createOpen}
          onOpenChange={setCreateOpen}
          title={t('sc.coupons.createTitle')}
          description="Create a promotional discount code applicable to your listings."
        >
          <form className="flex flex-col gap-4 pt-2" onSubmit={onSubmit}>
            <TextField
              label={t('sc.coupons.code')}
              placeholder="SUMMER20, AFRO10, FREESHIP"
              value={draft.code}
              onChange={(e) => setDraft((d) => ({ ...d, code: e.target.value }))}
              required
              autoComplete="off"
            />
            <TextField
              label={t('sc.coupons.description')}
              placeholder="e.g. 10% off storewide for the festive season"
              value={draft.description ?? ''}
              onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
            />
            <Select
              label={t('sc.coupons.type')}
              value={draft.type}
              onChange={(e) => setDraft((d) => ({ ...d, type: e.target.value as CreateSellerCoupon['type'] }))}
              options={[
                { value: 'PERCENT', label: t('sc.coupons.typePercent') },
                { value: 'FIXED', label: t('sc.coupons.typeFixed') },
                { value: 'FREE_SHIPPING', label: t('sc.coupons.typeFreeShip') }
              ]}
            />
            {draft.type !== 'FREE_SHIPPING' ? (
              <TextField
                label={draft.type === 'PERCENT' ? t('sc.coupons.percent') : t('sc.coupons.amountCents')}
                type="number"
                value={String(draft.value)}
                onChange={(e) => setDraft((d) => ({ ...d, value: Number(e.target.value) }))}
                required
              />
            ) : null}
            {create.isError ? <p className="m-0 text-sm text-danger">{problemMessage(create.error)}</p> : null}
            <div className="mt-3 flex justify-end gap-2 border-t border-border pt-4">
              <Button type="button" variant="neutral" onClick={() => setCreateOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending}>
                {t('sc.coupons.create')}
              </Button>
            </div>
          </form>
        </Dialog>
      ) : null}

      {toDelete ? (
        <Dialog
          open={toDelete !== null}
          onOpenChange={(open) => {
            if (!open) setToDelete(null);
          }}
          title={`${t('sc.coupons.delete')} ${toDelete.code}`}
          description={t('sc.coupons.deleteConfirm', { code: toDelete.code })}
        >
          <div className="flex justify-end gap-2 pt-4">
            <Button
              type="button"
              variant="neutral"
              onClick={() => setToDelete(null)}
              disabled={deleteCoupon.isPending}
            >
              Cancel
            </Button>
            <Button
              type="button"
              variant="dark"
              loading={deleteCoupon.isPending}
              onClick={() => deleteCoupon.mutate(toDelete.id)}
            >
              {t('sc.coupons.delete')}
            </Button>
          </div>
        </Dialog>
      ) : null}
    </div>
  );
}
