'use client';

import { isApiError, orderKeys } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { buttonClassName, ImagePlaceholder, Key, Skeleton, Truck, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';

import pageStyles from '@/components/account/Pages.module.css';
import { RateItemButton } from '@/components/reviews/ReviewSheet';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { productHref } from '@/lib/links';

import { CancelShipment } from './CancelShipment';
import { DeliveryProofCard } from './DeliveryProofCard';
import styles from './Orders.module.css';
import { placedDate } from './OrdersView';
import { PickupDetails } from './PickupDetails';

const day = (tag: string, iso: string | null) => (iso ? new Date(iso.length === 10 ? `${iso}T12:00:00Z` : iso).toLocaleDateString(tag, { weekday: 'short', month: 'short', day: 'numeric' }) : null);

/**
 * One order, kept short: where each delivery stands, what is in it and what was paid. Cancelling is possible until it ships; once
 * delivered there is no self-service refund or return, the shopper contacts support with evidence and staff resolve it.
 */
export function OrderDetailView({ orderNumber }: { orderNumber: string }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const order = useQuery(shopperQueries.order(api, orderNumber));
  const cancel = useMutation({
    mutationFn: (shipmentId: string) => api.cancelShipment(orderNumber, shipmentId),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: orderKeys.all }); toast.show(t('ord.cancelled')); },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });
  const o = order.data;
  return (
    <div className={pageStyles.page}>
      <nav className={pageStyles.crumb} aria-label={t('acct.breadcrumb')}>
        <Link href="/orders">{t('account.orders')}</Link>
        <span aria-hidden>›</span>
        <span aria-current="page">{orderNumber}</span>
      </nav>
      {order.isPending && <Skeleton height={240} />}
      {order.isError && <p className={pageStyles.error} role="alert">{isApiError(order.error) && order.error.status === 404 ? t('ord.notFound') : t('acct.loadError')}</p>}
      {o && (
        <>
          <div className={pageStyles.head}>
            <h1 className={pageStyles.h1}>{t('ord.detailTitle', { number: o.orderNumber })}</h1>
            <span className={pageStyles.muted}>{t('ord.placedOn', { date: placedDate(fmt.tag, o.placedAt) })}</span>
          </div>
          <div className={styles.layout}>
            <div className={pageStyles.form}>
              {o.shipments.map((s) => (
                <section key={s.shipmentId} id={s.shipmentId} className={pageStyles.card} aria-label={s.label} data-testid="shipment">
                  <div className={pageStyles.cardHead}>
                    <h2 className={pageStyles.h2}>{s.statusLabel}</h2>
                    <span className={pageStyles.status}>{t(`ord.method.${s.deliveryMethod}`)}</span>
                  </div>
                  <p className={pageStyles.muted}>
                    {s.label} · {t('ord.soldBy', { seller: s.sellerName })}
                  </p>
                  {s.status.toLowerCase() !== 'cancelled' && s.promisedBy && !s.deliveredAt && <p className={pageStyles.success}>{t('ord.arriving', { date: day(fmt.tag, s.promisedBy) ?? '' })}</p>}
                  {s.pickup && <PickupDetails pickup={s.pickup} />}
                  {s.status.toLowerCase() !== 'cancelled' && s.carrier && s.trackingNumber && (
                    <p className={pageStyles.muted}>
                      <Truck aria-hidden /> {t('ord.trackingNo', { carrier: s.carrier.toUpperCase(), number: s.trackingNumber })}
                    </p>
                  )}
                  {s.status.toLowerCase() !== 'cancelled' && s.deliveryCode && (
                    <span className={styles.code}>
                      <Key aria-hidden /> {t(s.deliveryMethod === 'pickup' ? 'ord.pickupCode' : 'ord.deliveryCode')} <strong className={pageStyles.code}>{s.deliveryCode}</strong> · {t(s.deliveryMethod === 'pickup' ? 'ord.codeHintPickup' : 'ord.codeHint')}
                    </span>
                  )}
                  {s.proof && <DeliveryProofCard proof={s.proof} />}
                  <ul className={styles.lines}>
                    {s.lines.map((l) => (
                      <li key={l.lineId} className={styles.line}>
                        <ImagePlaceholder src={l.imageUrl} alt="" />
                        <Link href={productHref(l.atin)}>{l.title}</Link>
                        <span>
                          {l.quantity} × {fmt.money(l.unitPriceMinor)}
                          {(l.refundedMinor ?? 0) > 0 && <span className={pageStyles.success}> · {t('ord.lineRefunded', { amount: fmt.money(l.refundedMinor ?? 0) })}</span>}
                          {s.deliveredAt && l.productAtin && <RateItemButton atin={l.productAtin} title={l.title} imageUrl={l.imageUrl} />}
                        </span>
                      </li>
                    ))}
                  </ul>
                  <div className={pageStyles.actions}>
                    <CancelShipment shipment={s} onCancel={() => cancel.mutate(s.shipmentId)} loading={cancel.isPending} />
                    {s.deliveredAt && (
                      <Link href={`/account/help/new?order=${encodeURIComponent(o.orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm' })} data-testid="order-get-help">
                        {t('ord.getHelp')}
                      </Link>
                    )}
                  </div>
                </section>
              ))}
            </div>
            <aside className={styles.aside}>
              <section className={pageStyles.card} aria-labelledby="sum-title">
                <strong id="sum-title">{t('ord.summary')}</strong>
                <dl className={pageStyles.dl}>
                  <dt>{t('ord.items')}</dt>
                  <dd>{fmt.money(o.itemsMinor)}</dd>
                  <dt>{t('ord.shipping')}</dt>
                  <dd>{fmt.money(o.shippingMinor)}</dd>
                  {o.discountMinor > 0 && (
                    <>
                      <dt>{t('ord.discount')}</dt>
                      <dd>−{fmt.money(o.discountMinor)}</dd>
                    </>
                  )}
                  <dt>{t('ord.tax')}</dt>
                  <dd>{fmt.money(o.taxMinor)}</dd>
                  {(o.payment?.shippingTaxMinor ?? 0) > 0 && (
                    <>
                      <dt className={pageStyles.muted}>{t('ord.shippingTax')}</dt>
                      <dd className={pageStyles.muted}>{fmt.money(o.payment?.shippingTaxMinor ?? 0)}</dd>
                    </>
                  )}
                  <dt>
                    <strong>{t('ord.total')}</strong>
                  </dt>
                  <dd>
                    <strong>{fmt.money(o.totalMinor)}</strong>
                  </dd>
                  {o.payment && o.payment.creditMinor > 0 && (
                    <>
                      <dt>{t('ord.paidWithCredit')}</dt>
                      <dd data-testid="order-credit">{fmt.money(o.payment.creditMinor)}</dd>
                    </>
                  )}
                  {o.payment && o.payment.refundedMinor > 0 && (
                    <>
                      <dt>{t('ord.refunded')}</dt>
                      <dd className={pageStyles.success} data-testid="order-refunded">
                        −{fmt.money(o.payment.refundedMinor)}
                      </dd>
                    </>
                  )}
                </dl>
              </section>
              {o.shippingAddress && (
                <section className={pageStyles.card}>
                  <strong>{t('ord.shipTo')}</strong>
                  <p className={pageStyles.muted}>
                    {o.shippingAddress.name}
                    <br />
                    {o.shippingAddress.line1} {o.shippingAddress.line2}
                    <br />
                    {o.shippingAddress.city}, {o.shippingAddress.state} {o.shippingAddress.zip}
                  </p>
                </section>
              )}
            </aside>
          </div>
        </>
      )}
    </div>
  );
}
