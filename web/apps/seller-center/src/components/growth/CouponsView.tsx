'use client';

import { sellerKeys, sellerQueries, type CreateSellerCoupon, type SellerCoupon } from '@afrotransact/api-client';
import { Button, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

function valueLabel(c: SellerCoupon): string {
  if (c.type === 'FREE_SHIPPING') return 'Free shipping';
  if (c.type === 'PERCENT') return `${c.value}% off`;
  return `$${(c.value / 100).toFixed(2)} off`;
}

/** Seller-funded coupons (create / pause). Platform coupons are managed by AfroTransact staff. */
export function CouponsView() {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId, can } = useSeller();
  const api = sellerApi();
  const editable = can('coupons', 'EDIT');
  const q = useQuery(sellerQueries.coupons(api, orgId));
  const [draft, setDraft] = useState<CreateSellerCoupon>({ code: '', type: 'PERCENT', value: 10, scope: 'SELLER', description: '' });

  const create = useMutation({
    mutationFn: () => api.createCoupon({ ...draft, code: draft.code.trim().toUpperCase() }),
    onSuccess: () => {
      toast.show(t('sc.coupons.created'));
      setDraft({ code: '', type: 'PERCENT', value: 10, scope: 'SELLER', description: '' });
      void qc.invalidateQueries({ queryKey: sellerKeys.coupons(orgId) });
    }
  });

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: 'ACTIVE' | 'PAUSED' }) => api.setCouponStatus(id, status),
    onSuccess: () => void qc.invalidateQueries({ queryKey: sellerKeys.coupons(orgId) })
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate();
  };

  if (q.isError) return <ErrorState message={problemMessage(q.error)} onRetry={() => void q.refetch()} />;
  const items = q.data ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="m-0 text-2xl font-bold">{t('sc.coupons.title')}</h1>
        <p className="mt-2 max-w-2xl text-sm text-muted">{t('sc.coupons.lead')}</p>
      </div>

      <Notice tone="info" icon="info">
        {t('sc.coupons.platformNote')}
      </Notice>

      {editable ? (
        <Card title={t('sc.coupons.createTitle')}>
          <form className="flex flex-col gap-4" onSubmit={onSubmit}>
            <TextField label={t('sc.coupons.code')} value={draft.code} onChange={(e) => setDraft((d) => ({ ...d, code: e.target.value }))} required autoComplete="off" />
            <TextField label={t('sc.coupons.description')} value={draft.description ?? ''} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-semibold">{t('sc.coupons.type')}</span>
              <select className="rounded-md border border-border px-3 py-2" value={draft.type} onChange={(e) => setDraft((d) => ({ ...d, type: e.target.value as CreateSellerCoupon['type'] }))}>
                <option value="PERCENT">{t('sc.coupons.typePercent')}</option>
                <option value="FIXED">{t('sc.coupons.typeFixed')}</option>
                <option value="FREE_SHIPPING">{t('sc.coupons.typeFreeShip')}</option>
              </select>
            </label>
            {draft.type !== 'FREE_SHIPPING' ? (
              <TextField
                label={draft.type === 'PERCENT' ? t('sc.coupons.percent') : t('sc.coupons.amountCents')}
                type="number"
                value={String(draft.value)}
                onChange={(e) => setDraft((d) => ({ ...d, value: Number(e.target.value) }))}
                required
              />
            ) : null}
            <Button type="submit" loading={create.isPending}>
              {t('sc.coupons.create')}
            </Button>
            {create.isError ? <p className="m-0 text-sm text-danger">{problemMessage(create.error)}</p> : null}
          </form>
        </Card>
      ) : null}

      <Card title={t('sc.coupons.yours')}>
        {q.isPending ? <p className="text-sm text-muted">{t('common.loading')}</p> : null}
        {!q.isPending && items.length === 0 ? <p className="text-sm text-muted">{t('sc.coupons.empty')}</p> : null}
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {items.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border px-4 py-3">
              <div>
                <strong className="font-mono">{c.code}</strong>
                <span className="ml-2 text-sm text-muted">{valueLabel(c)} · {c.status}</span>
                {c.description ? <p className="m-0 mt-1 text-sm text-muted">{c.description}</p> : null}
              </div>
              {c.editable && editable ? (
                <div className="flex gap-2">
                  {c.status === 'ACTIVE' ? (
                    <Button size="sm" variant="neutral" loading={setStatus.isPending} onClick={() => setStatus.mutate({ id: c.id, status: 'PAUSED' })}>
                      {t('sc.coupons.pause')}
                    </Button>
                  ) : c.status === 'PAUSED' ? (
                    <Button size="sm" variant="secondary" loading={setStatus.isPending} onClick={() => setStatus.mutate({ id: c.id, status: 'ACTIVE' })}>
                      {t('sc.coupons.activate')}
                    </Button>
                  ) : null}
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
