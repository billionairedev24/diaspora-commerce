'use client';

import { isApiError, orderKeys, type OrderSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, Key, Skeleton, Sparkle, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';

import pageStyles from '@/components/account/Pages.module.css';
import { PlacedConfirmation } from '@/components/preorder/PreorderConfirmation';
import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { CancelShipment } from './CancelShipment';
import styles from './Orders.module.css';
import { PickupDetails } from './PickupDetails';

export const placedDate = (tag: string, iso: string) => new Date(iso).toLocaleDateString(tag, { month: 'long', day: 'numeric', year: 'numeric' });

function OrderCard({ order }: { order: OrderSummary }) {
  const fmt = useFormatters();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const cancel = useMutation({
    mutationFn: (shipmentId: string) => browserApi().cancelShipment(order.orderNumber, shipmentId),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: orderKeys.all }); toast.show(t('ord.cancelled')); },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });
  const delivered = order.shipments.some((s) => s.deliveredAt);
  return (
    <article className={styles.order} data-testid="order" aria-label={t('ord.orderAria', { number: order.orderNumber })}>
      <div className={styles.orderHead}>
        <span>
          {t('ord.placed')}
          <strong>{placedDate(fmt.tag, order.placedAt)}</strong>
        </span>
        <span>
          {t('ord.total')}
          <strong>{fmt.money(order.totalMinor)}</strong>
        </span>
        <span>
          {t('ord.shipTo')}
          <strong>{order.shipTo}</strong>
        </span>
        <span className={styles.orderNo}>
          {t('ord.number', { number: order.orderNumber })}
          <strong>
            <Link href={`/orders/${encodeURIComponent(order.orderNumber)}`}>{t('ord.details')}</Link>
          </strong>
        </span>
      </div>
      {order.shipments.map((s) => (
        <div key={s.shipmentId} className={styles.shipment}>
          <div>
            <p className={styles.shipStatus}>{s.status}</p>
            <p className={pageStyles.muted}>{s.label}</p>
            <p className={pageStyles.muted}>{s.items}</p>
            {s.deliveryCode && (
              <span className={styles.code}>
                <Key aria-hidden /> {t('ord.deliveryCode')} <strong>{s.deliveryCode}</strong>
              </span>
            )}
          </div>
          <div className={styles.shipActions}>
            {s.status.toLowerCase() !== 'cancelled' && (
              <Link href={`/orders/${encodeURIComponent(order.orderNumber)}#${s.shipmentId}`} className={buttonClassName({ variant: 'primary', size: 'sm', block: true })}>
                {t('ord.track')}
              </Link>
            )}
            <CancelShipment shipment={s} block onCancel={() => cancel.mutate(s.shipmentId)} loading={cancel.isPending && cancel.variables === s.shipmentId} />
            {(s.returnable ?? Boolean(s.deliveredAt)) && (
              <Link href={`/account/returns/new?order=${encodeURIComponent(order.orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm', block: true })}>
                {t('ord.returnItems')}
              </Link>
            )}
            {s.problemReportOnly && !s.returnable && (
              <Link href={`/account/returns/new?order=${encodeURIComponent(order.orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm', block: true })}>
                {t('ord.reportProblem')}
              </Link>
            )}
            {s.returnable && s.returnWindowEndsAt && <span className={pageStyles.muted}>{t('ord.returnBy', { date: new Date(s.returnWindowEndsAt).toLocaleDateString(fmt.tag, { month: 'short', day: 'numeric' }) })}</span>}
          </div>
        </div>
      ))}
      {!delivered && order.shipments.length === 0 && <p className={pageStyles.muted}>{t('ord.noShipments')}</p>}
    </article>
  );
}

/** Your Orders (S148): every order with its shipments, delivery codes, track, cancel (until packed) and return. */
/** The just-placed order's pickup shipments: where and when to collect them. */
function PlacedPickups({ orderNumber }: { orderNumber: string }) {
  const order = useQuery(shopperQueries.order(browserApi(), orderNumber));
  const pickups = (order.data?.shipments ?? []).flatMap((s) => (s.pickup ? [{ id: s.shipmentId, pickup: s.pickup }] : []));
  if (!pickups.length) return null;
  return (
    <div className={styles.pickupList}>
      {pickups.map((p) => (
        <PickupDetails key={p.id} pickup={p.pickup} />
      ))}
    </div>
  );
}

export function OrdersView() {
  const t = useT();
  const api = browserApi();
  const orders = useQuery(shopperQueries.orders(api));
  const placed = useSearchParams().get('placed');
  const lola = useLolaLauncher();
  const items = orders.data?.items ?? [];
  const codes = items.flatMap((o) => o.shipments.filter((s) => s.deliveryCode && !s.deliveredAt).map((s) => ({ order: o.orderNumber, code: s.deliveryCode, label: s.label })));
  return (
    <div className={pageStyles.page}>
      {placed && (
        <PlacedConfirmation
          orderNumber={placed}
          fallback={
            <div className={styles.placed} role="status">
              <CheckCircle size={28} weight="fill" aria-hidden />
              <div>
                <strong>{t('ord.placedThanks')}</strong>
                <p className={pageStyles.muted}>{t('ord.placedSub', { number: placed })}</p>
                <PlacedPickups orderNumber={placed} />
              </div>
            </div>
          }
        />
      )}
      <h1 className={pageStyles.h1}>{t('account.orders')}</h1>
      <div className={styles.layout}>
        <div className={pageStyles.form}>
          {orders.isPending && <Skeleton height={180} />}
          {orders.isError && <p className={pageStyles.error} role="alert">{t('acct.loadError')}</p>}
          {orders.isSuccess && items.length === 0 && (
            <div className={pageStyles.empty}>
              <p className={pageStyles.muted}>{t('ord.none')}</p>
              <Link href="/" className={buttonClassName()}>
                {t('common.backToShop')}
              </Link>
            </div>
          )}
          {items.map((o) => (
            <OrderCard key={o.orderNumber} order={o} />
          ))}
        </div>
        <aside className={styles.aside}>
          <section className={pageStyles.card} aria-labelledby="codes-title">
            <strong id="codes-title">
              <Key aria-hidden /> {t('ord.yourCodes')}
            </strong>
            {codes.length === 0 ? (
              <p className={pageStyles.muted}>{t('ord.noCodes')}</p>
            ) : (
              codes.map((c) => (
                <p key={`${c.order}-${c.code}`} className={pageStyles.muted}>
                  {c.order} · <span className={pageStyles.code}>{c.code}</span>
                </p>
              ))
            )}
          </section>
          <section className={pageStyles.card}>
            <strong>{t('ord.helpTitle')}</strong>
            <p className={pageStyles.muted}>{t('ord.helpBody')}</p>
            <Button variant="neutral" icon={<Sparkle weight="fill" aria-hidden />} onClick={() => lola.open(t('ord.lolaPrompt'))}>
              {t('account.askLola')}
            </Button>
          </section>
        </aside>
      </div>
    </div>
  );
}
