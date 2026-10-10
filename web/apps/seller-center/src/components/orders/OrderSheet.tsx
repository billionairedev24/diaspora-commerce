'use client';

import { sellerKeys, sellerQueries, type SellerStatusAction } from '@afrotransact/api-client';
import { Button, Sheet, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { dateTime, idempotencyKey, money } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { LabelForm } from './LabelForm';

export const ACTION_LABEL: Record<SellerStatusAction, string> = { CONFIRMED: 'Confirm order', PACKED: 'Mark packed', READY: 'Mark ready to ship' };

/** Order sheet: buyer, address, each of the organisation's shipments with its lines, timeline and actions. */
export function OrderSheet({ orderNumber, onClose }: { orderNumber: string | null; onClose: () => void }) {
  const api = sellerApi();
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
        </div>
      ) : null}
    </Sheet>
  );
}
