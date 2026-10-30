'use client';

import { cartKeys, isApiError, orderKeys } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, StatusText, type StatusTone, TextLinkButton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { RateItemButton } from '@/components/reviews/ReviewSheet';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { productHref } from '@/lib/links';

import { CancelShipment } from './CancelShipment';
import styles from './Orders.module.css';
import { canTrack, type ShipmentLook, shipmentLook } from './orderStatus';

/** "October 28, 2026". */
export const placedDate = (tag: string, iso: string) => new Date(iso).toLocaleDateString(tag, { month: 'long', day: 'numeric', year: 'numeric' });

/** "Fri, Oct 31"; a bare date (no time) is read at noon UTC so the day does not slip with the time zone. */
export const shortDay = (tag: string, iso: string | null | undefined) =>
  iso ? new Date(iso.length === 10 ? `${iso}T12:00:00Z` : iso).toLocaleDateString(tag, { weekday: 'short', month: 'short', day: 'numeric' }) : null;

export const isClosed = (status: string) => ['cancelled', 'canceled', 'returned'].includes(status.toLowerCase());

const TONE: Record<ShipmentLook['tone'], StatusTone> = { success: 'positive', info: 'info', brand: 'info', neutral: 'neutral', danger: 'negative' };

interface HeadlineShipment {
  status: string;
  deliveredAt?: string | null;
  promisedBy?: string | null;
}

/** "Delivered Oct 25" / "On the way" with "Arriving by Fri, Oct 31" under it; closed shipments just say what happened. */
export function ShipmentHeadline({ shipment }: { shipment: HeadlineShipment }) {
  const t = useT();
  const fmt = useFormatters();
  const look = shipmentLook(shipment.status, shipment.deliveredAt);
  const delivered = shipment.deliveredAt ? shortDay(fmt.tag, shipment.deliveredAt) : null;
  const text = look.key === 'delivered' && delivered ? t('ord.deliveredOn', { date: delivered }) : t(`ord.s.${look.key}`);
  const arriving = look.group === 'IN_PROGRESS' && look.step >= 0 && shipment.promisedBy ? shortDay(fmt.tag, shipment.promisedBy) : null;
  return (
    <div className={styles.headlineBlock}>
      <p className={styles.headline}>
        <StatusText tone={TONE[look.tone]}>{text}</StatusText>
      </p>
      {arriving && <p className={styles.muted}>{t('ord.arriving', { date: arriving })}</p>}
    </div>
  );
}

/** The one-time code printed plainly: a delivery code for the driver, a pickup code at the counter. */
export function ShipmentCode({ code, pickup }: { code: string; pickup?: boolean }) {
  const t = useT();
  return (
    <p className={styles.code} data-testid="shipment-code">
      <span>{t(pickup ? 'ord.pickupCode' : 'ord.deliveryCode')}</span>
      <strong className={styles.digits}>{code}</strong>
      <span className={styles.muted}>{t(pickup ? 'ord.codeHintPickup' : 'ord.codeHint')}</span>
    </p>
  );
}

export function Thumb({ src }: { src?: string | null }) {
  return src ? <img src={src} alt="" loading="lazy" className={styles.thumb} /> : <span className={styles.thumb} aria-hidden />;
}

/** The product's name, linked to its page when we know which product it is. */
export function ProductLink({ atin, title }: { atin?: string; title: string }) {
  return atin ? (
    <Link href={productHref(atin)} className={styles.itemLink}>
      {title}
    </Link>
  ) : (
    <span>{title}</span>
  );
}

interface BuyAgainLine {
  productAtin?: string;
  quantity: number;
}

/** "Buy it again": adds each product's current offer to the cart; when none can be added, opens the first product's page. */
export function useBuyAgain() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  return useMutation({
    mutationFn: async ({ lines }: { key: string; lines: BuyAgainLine[] }) => {
      const api = browserApi();
      let added = 0;
      let cart = null;
      for (const l of lines) {
        if (!l.productAtin) continue;
        try {
          const offerId = (await api.getProduct(l.productAtin)).buyBox?.offerId;
          if (!offerId) continue;
          cart = await api.addToCart({ offerId, quantity: Math.max(1, Math.min(99, l.quantity)) });
          added += 1;
        } catch {
          continue;
        }
      }
      return { added, cart };
    },
    onSuccess: ({ added, cart }, { lines }) => {
      if (cart) qc.setQueryData(cartKeys.current(), cart);
      if (added > 0) return toast.show(t('ord.buyAgainAdded', { count: added }));
      toast.show(t('ord.buyAgainFailed'));
      const first = lines.find((l) => l.productAtin)?.productAtin;
      if (first) router.push(productHref(first));
    }
  });
}

/** Cancels one shipment of an order and refreshes every order view. */
export function useCancelShipment(orderNumber: string) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (shipmentId: string) => browserApi().cancelShipment(orderNumber, shipmentId),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: orderKeys.all });
      toast.show(t('ord.cancelled'));
    },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });
}

export interface ActionShipment {
  shipmentId: string;
  status: string;
  items: string;
  deliveredAt?: string | null;
  trackingNumber?: string | null;
  cancellable?: boolean;
  cancellableUntil?: string | null;
  lines?: Array<{ title: string; quantity: number; imageUrl?: string | null; productAtin?: string | null }>;
}

/** Whether "Rate this item" sits in the action column (one product) or beside each product (several). */
export const rateInColumn = (s: ActionShipment) => Boolean(s.deliveredAt) && (s.lines?.length ?? 0) === 1;

/**
 * The stacked actions of a shipment: Track package (only when it can be tracked), Get help with this order (delivered; there
 * is no self-service return or refund), Cancel items (while cancellable), Buy it again and, for a single delivered
 * product, Rate this item.
 */
export function ShipmentActions({ orderNumber, shipment, cancel, trackHref }: { orderNumber: string; shipment: ActionShipment; cancel: ReturnType<typeof useCancelShipment>; trackHref?: string }) {
  const t = useT();
  const buyAgain = useBuyAgain();
  const lines = shipment.lines ?? [];
  const single = lines.length === 1 ? lines[0] : undefined;
  return (
    <div className={styles.actions}>
      {trackHref && canTrack(shipment) && (
        <Link href={trackHref} className={buttonClassName({ variant: 'primary', size: 'sm', block: true })} data-testid="order-track">
          {t('ord.track')}
        </Link>
      )}
      {shipment.deliveredAt && (
        <Link href={`/account/help/new?order=${encodeURIComponent(orderNumber)}`} className={buttonClassName({ variant: 'neutral', size: 'sm', block: true })} data-testid="order-get-help">
          {t('ord.getHelp')}
        </Link>
      )}
      <CancelShipment shipment={shipment} block onCancel={() => cancel.mutate(shipment.shipmentId)} loading={cancel.isPending && cancel.variables === shipment.shipmentId} />
      {lines.length > 0 && (
        <Button
          variant="neutral"
          size="sm"
          block
          loading={buyAgain.isPending && buyAgain.variables?.key === shipment.shipmentId}
          onClick={() => buyAgain.mutate({ key: shipment.shipmentId, lines: lines.map((l) => ({ productAtin: l.productAtin ?? undefined, quantity: l.quantity })) })}
          data-testid="order-buy-again"
        >
          {t('ord.buyAgain')}
        </Button>
      )}
      {rateInColumn(shipment) && single?.productAtin && <RateItemButton atin={single.productAtin} title={single.title} imageUrl={single.imageUrl} block />}
    </div>
  );
}

/** "Ship to": the address summary as a link that opens the recipient's name and full address (fetched when first opened). */
export function AddressPopover({ orderNumber, shipTo }: { orderNumber: string; shipTo: string }) {
  const t = useT();
  const id = useId();
  const wrap = useRef<HTMLSpanElement | null>(null);
  const [open, setOpen] = useState(false);
  const order = useQuery({ ...shopperQueries.order(browserApi(), orderNumber), enabled: open });
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (e.target instanceof Node && !wrap.current?.contains(e.target)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOpen(false);
      wrap.current?.querySelector('button')?.focus();
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);
  const a = order.data?.shippingAddress;
  return (
    <span className={styles.popWrap} ref={wrap}>
      <TextLinkButton aria-expanded={open} aria-controls={id} onClick={() => setOpen((v) => !v)} data-testid="order-ship-to">
        {shipTo}
      </TextLinkButton>
      {open && (
        <div id={id} className={styles.popover} role="group" aria-label={t('ord.shipTo')} data-testid="order-ship-to-popover">
          {a ? (
            <address className={styles.address}>
              <strong>{a.name}</strong>
              <span>{[a.line1, a.line2].filter(Boolean).join(' ')}</span>
              <span>{`${a.city}, ${a.state} ${a.zip}`}</span>
            </address>
          ) : order.isError ? (
            <span>{shipTo}</span>
          ) : (
            <span className={styles.muted} role="status">
              {t('common.loading')}
            </span>
          )}
        </div>
      )}
    </span>
  );
}

/** A labelled value of the summary line ("Order placed", "October 28, 2026"). */
export function SummaryItem({ label, children, end }: { label: string; children: ReactNode; end?: boolean }) {
  return (
    <div className={end ? styles.sumEnd : styles.sumItem}>
      <dt className={styles.sumLabel}>{label}</dt>
      <dd className={styles.sumValue}>{children}</dd>
    </div>
  );
}
