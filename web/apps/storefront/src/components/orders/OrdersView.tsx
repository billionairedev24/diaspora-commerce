'use client';

import { type OrderSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { buttonClassName, EmptyState, fieldStyles, FilterBar, FilterSelect, FlatPage, Message, Pager, Section, Skeleton, TextLinkButton,textLinkClassName } from '@afrotransact/ui';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useState } from 'react';

import { AccountHeader } from '@/components/account/AccountHeader';
import { PlacedConfirmation } from '@/components/preorder/PreorderConfirmation';
import { RateItemButton } from '@/components/reviews/ReviewSheet';
import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AddressPopover, isClosed, placedDate, ProductLink, rateInColumn, ShipmentActions, ShipmentCode, ShipmentHeadline, SummaryItem, Thumb, useCancelShipment } from './OrderParts';
import styles from './Orders.module.css';
import { inRange, inTab, matchesOrder, type OrderRange, type OrderTab, rangeOptions, rangeStart } from './orderStatus';
import { PickupDetails } from './PickupDetails';

const MAX_FILTER_PAGES = 10;
const PAGE_SIZE = 10;
const RANGE_ID = 'orders-range';

/** What is in the shipment: up to three products with photo, name and quantity, then "+ N more"; sold by the seller. */
function ShipmentItems({ shipment }: { shipment: OrderSummary['shipments'][number] }) {
  const t = useT();
  const lines = shipment.lines ?? [];
  if (lines.length === 0) {
    return (
      <div className={styles.items}>
        <p>{shipment.label}</p>
        <p className={styles.muted}>{shipment.items}</p>
      </div>
    );
  }
  const shown = lines.slice(0, 3);
  const rateBeside = Boolean(shipment.deliveredAt) && !rateInColumn(shipment);
  return (
    <div className={styles.items}>
      <ul className={styles.itemList}>
        {shown.map((l, i) => (
          <li key={`${l.productAtin ?? l.title}-${i}`} className={styles.item}>
            <Thumb src={l.imageUrl} />
            <div className={styles.itemBody}>
              <ProductLink atin={l.productAtin} title={l.title} />
              {l.quantity > 1 && <span className={styles.muted}>{t('ord.qty', { count: l.quantity })}</span>}
              {rateBeside && l.productAtin && <RateItemButton atin={l.productAtin} title={l.title} imageUrl={l.imageUrl} variant="link" />}
            </div>
          </li>
        ))}
      </ul>
      {lines.length > shown.length && <p className={styles.muted}>{t('ord.moreItems', { count: lines.length - shown.length })}</p>}
      {shipment.sellerName && <p className={styles.muted}>{t('ord.soldBy', { seller: shipment.sellerName })}</p>}
    </div>
  );
}

/** One order: a thin summary line over its shipments, separated by hairlines, never a box. */
function OrderBlock({ order }: { order: OrderSummary }) {
  const fmt = useFormatters();
  const t = useT();
  const cancel = useCancelShipment(order.orderNumber);
  const detailHref = `/orders/${encodeURIComponent(order.orderNumber)}`;
  return (
    <li>
    <article className={styles.order} data-testid="order" aria-label={t('ord.orderAria', { number: order.orderNumber })}>
      <dl className={styles.summary}>
        <SummaryItem label={t('ord.placed')}>{placedDate(fmt.tag, order.placedAt)}</SummaryItem>
        <SummaryItem label={t('ord.total')}>{fmt.money(order.totalMinor)}</SummaryItem>
        <SummaryItem label={t('ord.shipTo')}>
          <AddressPopover orderNumber={order.orderNumber} shipTo={order.shipTo} />
        </SummaryItem>
        <SummaryItem label={t('ord.number', { number: order.orderNumber })} end>
          <Link href={detailHref}>{t('ord.details')}</Link>
        </SummaryItem>
      </dl>
      {order.shipments.map((s) => (
        <div key={s.shipmentId} className={styles.shipment} data-testid="order-shipment">
          <div className={styles.shipMain}>
            <ShipmentHeadline shipment={s} />
            <ShipmentItems shipment={s} />
            {s.deliveryCode && !s.deliveredAt && !isClosed(s.status) && <ShipmentCode code={s.deliveryCode} />}
          </div>
          <ShipmentActions orderNumber={order.orderNumber} shipment={s} cancel={cancel} trackHref={`${detailHref}#${s.shipmentId}`} />
        </div>
      ))}
      {order.shipments.length === 0 && <p className={styles.muted}>{t('ord.noShipments')}</p>}
    </article>
    </li>
  );
}

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

const NONE_KEY = { '30d': 'ord.none.30d', '3m': 'ord.none.3m', '6m': 'ord.none.6m' } as const;

/**
 * Your orders, as Amazon's "Your Orders": a title with breadcrumb, Orders / Not yet shipped / Cancelled orders, a search box and
 * a time range, then each order as a flat block. Orders are newest first and load a page at a time; a time range keeps
 * loading until an order older than it arrives, a search or tab looks at most MAX_FILTER_PAGES pages back, and Next loads
 * more while there are any. The pages of ten are cut client-side from what is loaded.
 */
export function OrdersView() {
  const t = useT();
  const api = browserApi();
  const orders = useInfiniteQuery(shopperQueries.infiniteOrders(api));
  const placed = useSearchParams().get('placed');
  const lola = useLolaLauncher();
  const [tab, setTab] = useState<OrderTab>('ALL');
  const [text, setText] = useState('');
  const [range, setRange] = useState<OrderRange>('3m');
  const [page, setPage] = useState(0);
  const items = useMemo(() => orders.data?.pages.flatMap((p) => p.items) ?? [], [orders.data]);
  const now = useMemo(() => new Date(), []);
  const inScope = items.filter((o) => inRange(o.placedAt, range, now));
  const shown = inScope.filter((o) => inTab(o, tab) && matchesOrder(o, text));
  const filtering = tab !== 'ALL' || range !== 'all' || text.trim() !== '';
  const pagesLoaded = orders.data?.pages.length ?? 0;
  const from = rangeStart(range, now);
  const pastRange = from !== null && items.length > 0 && new Date(items[items.length - 1]?.placedAt ?? now) < from;
  const canAutoLoad = Boolean(orders.hasNextPage) && !orders.isFetchingNextPage && !pastRange && filtering && (range !== 'all' || pagesLoaded < MAX_FILTER_PAGES);
  const { fetchNextPage } = orders;
  useEffect(() => {
    if (canAutoLoad) void fetchNextPage();
  }, [canAutoLoad, fetchNextPage, pagesLoaded]);

  const pageCount = Math.max(1, Math.ceil(shown.length / PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);
  const start = current * PAGE_SIZE;
  const visible = shown.slice(start, start + PAGE_SIZE);
  const moreToLoad = Boolean(orders.hasNextPage) && !pastRange;
  const loadingMore = orders.isFetchingNextPage || canAutoLoad;
  const reset = () => setPage(0);
  const count = (o: OrderTab) => inScope.filter((x) => inTab(x, o)).length;
  const next = async () => {
    if (current + 1 < pageCount) return setPage(current + 1);
    if (!moreToLoad) return;
    await fetchNextPage();
    setPage(current + 1);
  };
  const tabs: Array<{ value: OrderTab; label: string }> = [
    { value: 'ALL', label: t('ord.tab.all') },
    { value: 'NOT_SHIPPED', label: t('ord.tab.notShipped') },
    { value: 'CANCELLED', label: t('ord.tab.cancelled') }
  ];
  const changeRange = () => document.getElementById(RANGE_ID)?.focus();
  const empty =
    range === 'all' && items.length === 0
      ? t('ord.none')
      : tab === 'NOT_SHIPPED' && inScope.length > 0
        ? t('ord.noneTab.notShipped')
        : tab === 'CANCELLED' && inScope.length > 0
          ? t('ord.noneTab.cancelled')
          : text.trim() && inScope.length > 0
            ? t('ord.noMatch')
            : range === 'all'
              ? t('ord.noMatch')
              : range.startsWith('y')
                ? t('ord.none.year', { year: range.slice(1) })
                : t(NONE_KEY[range as keyof typeof NONE_KEY]);

  return (
    <FlatPage width="wide">
      {placed && (
        <PlacedConfirmation
          orderNumber={placed}
          fallback={
            <Section title={t('ord.placedThanks')} testId="order-placed">
              <Message tone="success">{t('ord.placedSub', { number: placed })}</Message>
              <PlacedPickups orderNumber={placed} />
            </Section>
          }
        />
      )}
      <AccountHeader title={t('account.orders')} />
      <div className={styles.controls}>
        <nav className={styles.tabs} aria-label={t('ord.tabs')}>
          {tabs.map((o) => (
            <button
              key={o.value}
              type="button"
              className={styles.tab}
              aria-current={tab === o.value ? 'page' : undefined}
              onClick={() => {
                setTab(o.value);
                reset();
              }}
              data-testid={`orders-tab-${o.value}`}
            >
              {o.label}
              {items.length > 0 && o.value !== 'ALL' && <span className={styles.tabCount}> ({count(o.value)})</span>}
            </button>
          ))}
        </nav>
        {items.length > 0 || range !== 'all' ? (
          <FilterBar label={t('ord.range')}>
            <label className={styles.search}>
              {t('ord.search')}
              <input
                type="search"
                className={fieldStyles.control}
                value={text}
                placeholder={t('ord.searchPlaceholder')}
                onChange={(e) => {
                  setText(e.currentTarget.value);
                  reset();
                }}
                data-testid="orders-search"
              />
            </label>
            <FilterSelect
              id={RANGE_ID}
              label={t('ord.range')}
              value={range}
              onChange={(v) => {
                setRange(v as OrderRange);
                reset();
              }}
              options={rangeOptions(now).map((o) => ({ value: o.value, label: t(o.labelKey, o.vars) }))}
              testId="orders-range"
            />
          </FilterBar>
        ) : null}
      </div>

      {orders.isPending && <Skeleton height={180} />}
      {orders.isError && (
        <Message tone="error">
          {t('acct.loadError')} <TextLinkButton onClick={() => void orders.refetch()}>{t('ord.retry')}</TextLinkButton>
        </Message>
      )}
      {orders.isSuccess && shown.length === 0 && loadingMore && <Skeleton height={120} />}
      {orders.isSuccess && shown.length === 0 && !loadingMore && (
        <EmptyState
          testId="orders-empty"
          title={empty}
          action={
            <span className={styles.emptyLinks}>
              {range !== 'all' && (
                <TextLinkButton onClick={changeRange} data-testid="orders-change-range">
                  {t('ord.changeRange')}
                </TextLinkButton>
              )}
              <Link href="/" className={buttonClassName({ variant: 'primary', size: 'sm' })}>
                {t('ord.keepShopping')}
              </Link>
            </span>
          }
        />
      )}
      {visible.length > 0 && (
        <ol className={styles.orderList} aria-label={t('account.orders')}>
          {visible.map((o) => (
            <OrderBlock key={o.orderNumber} order={o} />
          ))}
        </ol>
      )}
      {shown.length > 0 && (pageCount > 1 || moreToLoad) && (
        <Pager label={t('ord.pages')} info={t('ord.showingRange', { from: start + 1, to: start + visible.length, count: shown.length })}>
          <TextLinkButton disabled={current === 0} onClick={() => setPage(current - 1)}>
            {t('ord.prev')}
          </TextLinkButton>
          {Array.from({ length: pageCount }, (_, i) => (
            <TextLinkButton key={i} className={i === current ? styles.pageNow : undefined} aria-current={i === current ? 'page' : undefined} aria-label={t('ord.pageN', { n: i + 1 })} onClick={() => setPage(i)}>
              {i + 1}
            </TextLinkButton>
          ))}
          <TextLinkButton disabled={current + 1 >= pageCount && !moreToLoad} onClick={() => void next()}>
            {orders.isFetchingNextPage ? t('common.loading') : t('ord.next')}
          </TextLinkButton>
        </Pager>
      )}

      <Section title={t('ord.helpTitle')} lead={t('ord.helpBody')}>
        <div>
          <TextLinkButton onClick={() => lola.open(t('ord.lolaPrompt'))}>{t('account.askLola')}</TextLinkButton>
          {' · '}
          <Link href="/account/help" className={textLinkClassName}>
            {t('account.help')}
          </Link>
        </div>
      </Section>
    </FlatPage>
  );
}
