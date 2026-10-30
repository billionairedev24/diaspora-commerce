'use client';

import { type OrderPickup } from '@afrotransact/api-client';
import { Clock, MapPin, Storefront } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import styles from './Orders.module.css';

/** Where a pickup shipment is collected: name, address, opening hours and when it is ready, as plain lines. */
export function PickupDetails({ pickup }: { pickup: OrderPickup }) {
  const t = useT();
  return (
    <div className={styles.pickup} data-testid="order-pickup">
      <strong>
        <Storefront aria-hidden /> {t('order.pickup.title')} {pickup.name}
      </strong>
      <span>
        <MapPin aria-hidden /> {pickup.address}
      </span>
      <span>
        <Clock aria-hidden /> {t('order.pickup.hours', { hours: pickup.hours })} · {pickup.ready}
      </span>
    </div>
  );
}
