'use client';

import { isApiError, orderKeys, type OrderSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, ChipGroup, Key, Select, Skeleton, Sparkle, TextField, useToast } from '@afrotransact/ui';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import pageStyles from '@/components/account/Pages.module.css';
import { PlacedConfirmation } from '@/components/preorder/PreorderConfirmation';
import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { CancelShipment } from './CancelShipment';
import styles from './Orders.module.css';
import { inRange, matchesOrder, type OrderGroup, orderGroup, type OrderRange, rangeOptions, shipmentLook } from './orderStatus';
import { PickupDetails } from './PickupDetails';

export const placedDate = (tag: string, iso: string) => new Date(iso).toLocaleDateString(tag, { month: 'long', day: 'numeric', year: 'numeric' });

/** "On the way" with a four-step track (ordered, packed, on the way, delivered); closed shipments just say what happened. */
function ShipmentStatus({ status, deliveredAt }: { status: string; deliveredAt?: string | null }) {
  const t = useT();
  const look = shipmentLook(status, deliveredAt);
  const steps = ['ordered', 'packed', 'onTheWay', 'delivered'] as const;
  return (
    <div className={styles.statusBlock}>
      <p className={`${styles.shipStatus} ${styles[look.tone]}`}>{t(`ord.s.${look.key}`)}</p>
      {look.step >= 0 && (
        <ol className={styles.track} aria-label={t('ord.progress')}>
          {steps.map((k, i) => (
            <li key={k} className={i < look.step ? styles.done : i === look.step - 1 ? styles.current : undefined} aria-current={i === look.step - 1 ? 'step' : undefined}>
              {t(`ord.step.${k}`)}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

/** The delivery code stays hidden (it is a secret until the handover) behind a link on the shipment; clicking shows it. */
function DeliveryCode({ code }: { code: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <div className={styles.codeBlock}>
      <Button variant="link" icon={<Key aria-hidden />} aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {open ? t('ord.hideCode') : t('ord.showCode')}
      </Button>
      {open && (
        <div className={styles.codeCallout} role="status">
          <div>
            <span>{t('ord.deliveryCode')}</span>
            <strong className={styles.codeDigits}>{code}</strong>
            <p className={pageStyles.muted}>{t('ord.codeHelp')}</p>
          </div>
        </div>
      )}
    </div>
  );
}

const isClosed = (status: string) => ['cancelled', 'canceled', 'returned'].includes(status.toLowerCase());

/** What is in the shipment: up to three products with photo, name and quantity, then "+ N more"; sold by the seller. */
function ShipmentItems({ shipment }: { shipment: OrderSummary['shipments'][number] }) {
  const t = useT();
  const lines = shipment.lines ?? [];
  if (lines.length === 0) {
    return (
      <>
        <p className={pageStyles.muted}>{shipment.label}</p>
        <p className={pageStyles.muted}>{shipment.items}</p>
      </>
    );
  }
  const shown = lines.slice(0, 3);
  return (
    <div className={styles.items}>
      <ul className={styles.itemList}>
        {shown.map((l, i) => (
          <li key={`${l.productAtin ?? l.title}-${i}`} className={styles.item}>
            {l.imageUrl ? <img src={l.imageUrl} alt="" loading="lazy" className={styles.thumb} /> : <span className={styles.thumb} aria-hidden />}
            <span className={styles.itemTitle}>{l.title}</span>
            {l.quantity > 1 && <span className={pageStyles.muted}>{t('ord.qty', { count: l.quantity })}</span>}
          </li>
        ))}
      </ul>
      {lines.length > shown.length && <p className={pageStyles.muted}>{t('ord.moreItems', { count: lines.length - shown.length })}</p>}
      {shipment.sellerName && <p className={pageStyles.muted}>{t('ord.soldBy', { seller: shipment.sellerName })}</p>}
    </div>
  );
}

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
          <div className={styles.shipMain}>
            <ShipmentStatus status={s.status} deliveredAt={s.deliveredAt} />
            <ShipmentItems shipment={s} />
            {s.deliveryCode && !s.deliveredAt && !isClosed(s.status) && <DeliveryCode code={s.deliveryCode} />}
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
  const orders = useInfiniteQuery(shopperQueries.infiniteOrders(api));
  const placed = useSearchParams().get('placed');
  const lola = useLolaLauncher();
  const [group, setGroup] = useState<'ALL' | OrderGroup>('ALL');
  const [text, setText] = useState('');
  const [range, setRange] = useState<OrderRange>('all');
  const items = useMemo(() => orders.data?.pages.flatMap((p) => p.items) ?? [], [orders.data]);
  const count = (g: OrderGroup) => items.filter((o) => orderGroup(o.shipments) === g).length;
  const now = useMemo(() => new Date(), []);
  const shown = items.filter((o) => (group === 'ALL' || orderGroup(o.shipments) === group) && inRange(o.placedAt, range, now) && matchesOrder(o, text));
  const filtering = group !== 'ALL' || range !== 'all' || text.trim() !== '';
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
          {orders.isError && (
            <p className={pageStyles.error} role="alert">
              {t('acct.loadError')} <Button variant="link" onClick={() => void orders.refetch()}>{t('ord.retry')}</Button>
            </p>
          )}
          {items.length > 0 && (
            <div className={styles.toolbar}>
              <ChipGroup
                label={t('ord.filters')}
                value={group}
                onChange={setGroup}
                options={[
                  { value: 'ALL', label: `${t('ord.f.all')} (${items.length}${orders.hasNextPage ? '+' : ''})` },
                  { value: 'IN_PROGRESS', label: `${t('ord.f.inProgress')} (${count('IN_PROGRESS')})` },
                  { value: 'DELIVERED', label: `${t('ord.f.delivered')} (${count('DELIVERED')})` },
                  { value: 'CLOSED', label: `${t('ord.f.closed')} (${count('CLOSED')})` }
                ]}
              />
              <Select label={t('ord.range')} value={range} onChange={(e) => setRange(e.currentTarget.value as OrderRange)} options={rangeOptions(now).map((o) => ({ value: o.value, label: t(o.labelKey, o.vars) }))} />
              <TextField label={t('ord.search')} placeholder={t('ord.searchPlaceholder')} type="search" value={text} onChange={(e) => setText(e.currentTarget.value)} />
            </div>
          )}
          {orders.isSuccess && items.length === 0 && (
            <div className={pageStyles.empty}>
              <p className={pageStyles.muted}>{t('ord.none')}</p>
              <Link href="/" className={buttonClassName()}>
                {t('common.backToShop')}
              </Link>
            </div>
          )}
          {items.length > 0 && shown.length === 0 && (
            <div className={pageStyles.empty}>
              <p className={pageStyles.muted}>{t('ord.noMatch')}</p>
              {orders.hasNextPage && <p className={pageStyles.muted}>{t('ord.noMatchMore')}</p>}
            </div>
          )}
          {shown.map((o) => (
            <OrderCard key={o.orderNumber} order={o} />
          ))}
          {items.length > 0 && (
            <div className={styles.more}>
              <p className={pageStyles.muted} role="status">{t(filtering ? 'ord.showingFiltered' : 'ord.showing', { shown: shown.length, count: items.length })}</p>
              {orders.hasNextPage && (
                <Button variant="neutral" loading={orders.isFetchingNextPage} onClick={() => void orders.fetchNextPage()}>
                  {t('ord.loadMore')}
                </Button>
              )}
            </div>
          )}
        </div>
        <aside className={styles.aside}>
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
