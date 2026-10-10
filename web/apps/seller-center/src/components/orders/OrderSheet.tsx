'use client';

import { sellerKeys, sellerQueries, type SellerStatusAction } from '@afrotransact/api-client';
import { Button, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, idempotencyKey, money } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { LabelForm } from './LabelForm';

export const ACTION_LABEL: Record<SellerStatusAction, string> = { CONFIRMED: 'Confirm order', PACKED: 'Mark packed', READY: 'Mark ready to ship' };

/** Order sheet: buyer, address, each of the organisation's shipments with its lines, timeline and actions. */
export function OrderSheet({ orderNumber, onClose }: { orderNumber: string | null; onClose: () => void }) {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('orders');
  const q = useQuery({ ...sellerQueries.order(api, orgId, orderNumber ?? ''), enabled: orderNumber !== null });
  const status = useMutation({
    mutationFn: ({ shipmentId, to }: { shipmentId: string; to: SellerStatusAction }) => api.updateShipmentStatus(orderNumber ?? '', shipmentId, to, idempotencyKey()),
    onSuccess: (o, v) => {
      qc.setQueryData(sellerKeys.order(orgId, o.orderNumber), o);
      void qc.invalidateQueries({ queryKey: sellerKeys.orders(orgId) });
      toast.show(`${ACTION_LABEL[v.to].replace('Mark ', 'Marked ').replace('Confirm order', 'Order confirmed')}`);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const o = q.data;
  return (
    <Sheet open={orderNumber !== null} onOpenChange={(open) => (open ? undefined : onClose())} title={orderNumber ? `Order ${orderNumber}` : 'Order'}>
      {q.isPending && orderNumber ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {o ? (
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1 text-sm">
            <span className="text-xs text-muted">Placed {dateTime(o.placedAt)}</span>
            <strong className="text-md">{o.buyerName}</strong>
            {o.shippingAddress ? (
              <address className="text-sm text-muted not-italic">
                {o.shippingAddress.line1}
                {o.shippingAddress.line2 ? `, ${o.shippingAddress.line2}` : ''}
                <br />
                {o.shippingAddress.city}, {o.shippingAddress.state} {o.shippingAddress.postalCode}
              </address>
            ) : null}
          </div>
          {o.shipments.map((s) => (
            <section key={s.shipmentId} aria-label={s.label} className="flex flex-col gap-3 rounded-md border border-border p-4">
              <div className="flex flex-wrap items-center gap-2">
                <strong className="flex-1 text-sm">{s.label}</strong>
                <StatusPill tone={toneOf(s.status)}>{s.statusLabel}</StatusPill>
              </div>
              <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
                {s.lines.map((l) => (
                  <li key={l.variantAtin} className="flex gap-2">
                    <span className="flex-1">
                      {l.title} <span className="text-muted">×{l.quantity}</span>
                    </span>
                    <span>{money(l.itemsMinor, o.currency)}</span>
                  </li>
                ))}
                <li className="flex gap-2 border-t border-border pt-1 text-muted">
                  <span className="flex-1">Shipping</span>
                  <span>{s.shippingMinor ? money(s.shippingMinor, o.currency) : 'Free'}</span>
                </li>
                <li className="flex gap-2 font-semibold">
                  <span className="flex-1">Total</span>
                  <span>{money(s.totalMinor, o.currency)}</span>
                </li>
              </ul>
              {s.trackingNumber ? (
                <p className="m-0 text-sm">
                  Tracking: <strong className="font-mono">{s.carrier?.toUpperCase()} {s.trackingNumber}</strong>
                </p>
              ) : null}
              {s.fulfilment === 'AFROTRANSACT' ? <p className="m-0 text-xs text-muted">Fulfilled by AfroTransact — we pick, pack and ship this one.</p> : null}
              {s.localDelivery ? (
                <div className="flex flex-col gap-1 rounded-md bg-success-bg p-3 text-sm" data-testid="local-delivery">
                  <span>{t('sc.local.shipment')}</span>
                  {s.localDelivery.roadMiles !== null ? (
                    <span className="text-xs text-muted">
                      {t('sc.local.distance', { miles: Math.round(s.localDelivery.roadMiles * 10) / 10, minutes: Math.round(s.localDelivery.durationMinutes ?? 0) })}
                    </span>
                  ) : null}
                  <span className="flex gap-2">
                    <span className="flex-1">{t('sc.local.charge')}</span>
                    <span>{money(s.localDelivery.customerChargeMinor, o.currency)}</span>
                  </span>
                  <span className="flex gap-2 font-semibold">
                    <span className="flex-1">{t('sc.local.fee')}</span>
                    <span>−{money(s.localDelivery.sellerFeeMinor, o.currency)}</span>
                  </span>
                </div>
              ) : null}
              {editable && s.nextActions.length ? (
                <div className="flex flex-wrap gap-2">
                  {s.nextActions.map((a) => (
                    <Button key={a} size="sm" loading={status.isPending} onClick={() => status.mutate({ shipmentId: s.shipmentId, to: a })}>
                      {ACTION_LABEL[a]}
                    </Button>
                  ))}
                </div>
              ) : null}
              {editable && s.canBuyLabel ? <LabelForm order={o} shipmentId={s.shipmentId} /> : null}
              <details className="text-xs text-muted">
                <summary className="cursor-pointer">Timeline</summary>
                <ol className="m-0 mt-1 flex list-none flex-col gap-0.5 p-0">
                  {s.timeline.map((t) => (
                    <li key={`${t.status}-${t.at}`}>
                      {dateTime(t.at)} · {t.status.toLowerCase().replace(/_/g, ' ')} · {t.actor}
                    </li>
                  ))}
                </ol>
              </details>
            </section>
          ))}
          <section aria-label={t('sc.earnings.title')} className="flex flex-col gap-2 rounded-md border border-border p-4" data-testid="order-earnings">
            <strong className="text-sm">{t('sc.earnings.title')}</strong>
            {o.earnings ? (
              <dl className="m-0 grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm">
                {(
                  [
                    ['sc.earnings.items', o.earnings.itemsMinor, 1],
                    ['sc.earnings.shipping', o.earnings.shippingMinor, 1],
                    ['sc.earnings.commission', o.earnings.commissionMinor, -1],
                    ['sc.earnings.fba', o.earnings.fbaFeesMinor, -1],
                    ['sc.earnings.delivery', o.earnings.deliveryFeesMinor, -1],
                    ['sc.earnings.card', o.earnings.stripeFeeMinor, -1]
                  ] as const
                )
                  .filter(([, v, sign]) => sign > 0 || v > 0)
                  .map(([k, v, sign]) => (
                    <div key={k} className="contents">
                      <dt className="text-muted">{t(k)}</dt>
                      <dd className="m-0 text-right">{sign < 0 ? `−${money(v, o.currency)}` : money(v, o.currency)}</dd>
                    </div>
                  ))}
                <dt className="border-t border-border pt-1 font-semibold">{t('sc.earnings.net')}</dt>
                <dd className="m-0 border-t border-border pt-1 text-right font-semibold">{money(o.earnings.netMinor, o.currency)}</dd>
              </dl>
            ) : (
              <p className="m-0 text-xs text-muted">{t('sc.earnings.pending')}</p>
            )}
          </section>
        </div>
      ) : null}
    </Sheet>
  );
}
