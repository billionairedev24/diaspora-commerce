'use client';

import { isApiError, type OrderDetail } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { DataTable, DefinitionList, FlatPage, Message, Section, Skeleton, StatusText, Truck } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';

import { AccountHeader } from '@/components/account/AccountHeader';
import { benefitName } from '@/components/membership/memberText';
import { RateItemButton } from '@/components/reviews/ReviewSheet';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { DeliveryProofLink } from './DeliveryProofCard';
import { placedDate, ProductLink, rateInColumn, ShipmentActions, ShipmentCode, ShipmentHeadline, Thumb, useCancelShipment } from './OrderParts';
import styles from './Orders.module.css';
import { PickupDetails } from './PickupDetails';

type Shipment = OrderDetail['shipments'][number];

/** One delivery: where it stands, who sells it, how to follow it and the code to give, what is in it, and the actions. */
function ShipmentSection({ order, shipment: s, cancel }: { order: OrderDetail; shipment: Shipment; cancel: ReturnType<typeof useCancelShipment> }) {
  const fmt = useFormatters();
  const t = useT();
  const cancelled = s.status.toLowerCase() === 'cancelled';
  const pickup = s.deliveryMethod === 'pickup';
  const anyRefund = s.lines.some((l) => (l.refundedMinor ?? 0) > 0);
  const rateBeside = Boolean(s.deliveredAt) && !rateInColumn(s);
  const ld = s.localDelivery;
  return (
    <Section
      id={s.shipmentId}
      testId="shipment"
      title={s.statusLabel}
      action={<StatusText tone="neutral">{t(`ord.method.${s.deliveryMethod}`)}</StatusText>}
    >
      <div className={styles.shipment}>
        <div className={styles.shipMain}>
          <ShipmentHeadline shipment={s} />
          <p className={styles.muted}>
            {s.label} · {t('ord.soldBy', { seller: s.sellerName })}
          </p>
          {s.membershipWaiver && (
            <p className={styles.muted} data-testid="order-membership-waiver">
              {t('ord.mem.waived', { tier: s.membershipWaiver.tierName, benefit: benefitName(t, fmt, s.membershipWaiver.benefit), amount: fmt.money(s.membershipWaiver.waivedMinor) })}
            </p>
          )}
          {ld && (
            <p className={styles.muted} data-testid="order-local-delivery">
              {ld.roadMiles !== null && ld.durationMinutes !== null
                ? t('ld.orderInfo', { miles: fmt.number(ld.roadMiles, { maximumFractionDigits: 1 }), minutes: Math.round(ld.durationMinutes), amount: fmt.money(ld.chargeMinor) })
                : t('ld.orderInfoShort', { amount: fmt.money(ld.chargeMinor) })}
            </p>
          )}
          {s.pickup && <PickupDetails pickup={s.pickup} />}
          {!cancelled && s.carrier && s.trackingNumber && (
            <p className={styles.tracking} data-testid="order-tracking">
              <Truck aria-hidden /> {t('ord.trackingNo', { carrier: s.carrier.toUpperCase(), number: s.trackingNumber })}
            </p>
          )}
          {!cancelled && s.deliveryCode && <ShipmentCode code={s.deliveryCode} pickup={pickup} />}
          {s.proof && (
            <p>
              <DeliveryProofLink proof={s.proof} />
            </p>
          )}
          <DataTable
            caption={t('ord.itemsTable')}
            rows={s.lines}
            rowKey={(l) => l.lineId}
            rowTestId="order-line"
            columns={[
              {
                key: 'item',
                header: t('ord.col.item'),
                label: t('ord.col.item'),
                cell: (l) => (
                  <span className={styles.lineItem}>
                    <Thumb src={l.imageUrl} />
                    <span className={styles.itemBody}>
                      <ProductLink atin={l.atin} title={l.title} />
                      {rateBeside && l.productAtin && <RateItemButton atin={l.productAtin} title={l.title} imageUrl={l.imageUrl} variant="link" />}
                    </span>
                  </span>
                )
              },
              { key: 'price', header: t('ord.col.price'), label: t('ord.col.price'), numeric: true, cell: (l) => `${l.quantity} × ${fmt.money(l.unitPriceMinor)}` },
              ...(anyRefund
                ? [
                    {
                      key: 'refunded',
                      header: t('ord.refunded'),
                      label: t('ord.refunded'),
                      numeric: true,
                      cell: (l: Shipment['lines'][number]) => ((l.refundedMinor ?? 0) > 0 ? <StatusText tone="positive">{t('ord.lineRefunded', { amount: fmt.money(l.refundedMinor ?? 0) })}</StatusText> : '')
                    }
                  ]
                : [])
            ]}
          />
        </div>
        <ShipmentActions orderNumber={order.orderNumber} shipment={s} cancel={cancel} />
      </div>
    </Section>
  );
}

/**
 * One order, in Amazon's "Order details" layout without boxes: Ship to, Payment method and Order summary side by side, then
 * each delivery as a section. Cancelling is possible until it ships; once delivered there is no self-service refund or
 * return, the shopper contacts support with evidence and staff resolve it.
 */
export function OrderDetailView({ orderNumber }: { orderNumber: string }) {
  const fmt = useFormatters();
  const t = useT();
  const order = useQuery(shopperQueries.order(browserApi(), orderNumber));
  const cancel = useCancelShipment(orderNumber);
  const o = order.data;
  const crumbTitle = t('ord.detailTitle', { number: orderNumber });
  const cardMinor = o?.payment ? Math.max(0, o.totalMinor - o.payment.creditMinor) : 0;
  return (
    <FlatPage width="wide">
      <AccountHeader
        title={t('ord.detailsTitle')}
        crumb={crumbTitle}
        via={[{ label: t('account.orders'), href: '/orders' }]}
        lead={o ? `${t('ord.placedOn', { date: placedDate(fmt.tag, o.placedAt) })} · ${t('ord.number', { number: o.orderNumber })}` : undefined}
      />
      {order.isPending && <Skeleton height={240} />}
      {order.isError && <Message tone="error">{isApiError(order.error) && order.error.status === 404 ? t('ord.notFound') : t('acct.loadError')}</Message>}
      {o && (
        <>
          <div className={styles.cols}>
            <section className={styles.col} aria-labelledby="od-ship">
              <h2 id="od-ship" className={styles.colTitle}>
                {t('ord.shipTo')}
              </h2>
              {o.shippingAddress ? (
                <address className={styles.address}>
                  <strong>{o.shippingAddress.name}</strong>
                  <span>{[o.shippingAddress.line1, o.shippingAddress.line2].filter(Boolean).join(' ')}</span>
                  <span>{`${o.shippingAddress.city}, ${o.shippingAddress.state} ${o.shippingAddress.zip}`}</span>
                </address>
              ) : (
                <p className={styles.muted}>{o.shipTo}</p>
              )}
            </section>
            <section className={styles.col} aria-labelledby="od-pay">
              <h2 id="od-pay" className={styles.colTitle}>
                {t('ord.payment')}
              </h2>
              {o.payment ? (
                <DefinitionList
                  items={[
                    ...(cardMinor > 0 ? [{ term: t('ord.cardPaid'), value: fmt.money(cardMinor) }] : []),
                    ...(o.payment.creditMinor > 0 ? [{ term: t('ord.paidWithCredit'), value: <span data-testid="order-credit">{fmt.money(o.payment.creditMinor)}</span> }] : []),
                    ...(o.payment.refundedMinor > 0
                      ? [{ term: t('ord.refunded'), value: <StatusText tone="positive" testId="order-refunded">{`−${fmt.money(o.payment.refundedMinor)}`}</StatusText> }]
                      : [])
                  ]}
                />
              ) : (
                <p className={styles.muted}>{fmt.money(o.totalMinor)}</p>
              )}
            </section>
            <section className={styles.col} aria-labelledby="od-sum">
              <h2 id="od-sum" className={styles.colTitle}>
                {t('ord.summary')}
              </h2>
              <DefinitionList
                testId="order-detail-summary"
                items={[
                  { term: t('ord.items'), value: fmt.money(o.itemsMinor) },
                  { term: t('ord.shipping'), value: fmt.money(o.shippingMinor) },
                  ...(o.discountMinor > 0 ? [{ term: t('ord.discount'), value: `−${fmt.money(o.discountMinor)}` }] : []),
                  { term: t('ord.tax'), value: fmt.money(o.taxMinor) },
                  ...((o.payment?.shippingTaxMinor ?? 0) > 0 ? [{ term: t('ord.shippingTax'), value: fmt.money(o.payment?.shippingTaxMinor ?? 0) }] : []),
                  { term: <strong>{t('ord.total')}</strong>, value: <strong>{fmt.money(o.totalMinor)}</strong> }
                ]}
              />
            </section>
          </div>
          {o.shipments.map((s) => (
            <ShipmentSection key={s.shipmentId} order={o} shipment={s} cancel={cancel} />
          ))}
          {o.shipments.length === 0 && <p className={styles.muted}>{t('ord.noShipments')}</p>}
        </>
      )}
    </FlatPage>
  );
}
