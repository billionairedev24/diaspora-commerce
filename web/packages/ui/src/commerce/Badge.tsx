import { type ReactNode } from 'react';

import { cx } from '../cx';
import { CheckCircle, Truck } from '../icons';
import styles from './commerce.module.css';

export type BadgeTone = 'fulfilled' | 'seller' | 'brand' | 'ai' | 'neutral' | 'success' | 'warning' | 'danger';

export interface BadgeProps {
  tone?: BadgeTone;
  icon?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function Badge({ tone = 'neutral', icon, children, className }: BadgeProps) {
  return (
    <span className={cx(styles.badge, styles[tone], className)}>
      {icon}
      {children}
    </span>
  );
}

export interface FulfilmentBadgeProps {
  fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY';
  /** City shown on seller-fulfilled items ("Ships from Bronx, NY") and on AfroTransact Delivery from the store. */
  shipsFrom?: string;
  fulfilledLabel?: string;
  shipsFromLabel?: (city: string) => string;
  /** Delivery from the seller's store (S255): "Delivery from Houston, TX". */
  localDeliveryLabel?: (city: string | undefined) => string;
}

/** "Fulfilled by AfroTransact" (green), "Delivery from <city>" (green, truck) or "Ships from seller" (grey). */
export function FulfilmentBadge({
  fulfilment,
  shipsFrom,
  fulfilledLabel = 'Fulfilled by AfroTransact',
  shipsFromLabel = (c) => `Ships from ${c}`,
  localDeliveryLabel = (c) => (c ? `Delivery from ${c}` : 'Delivery')
}: FulfilmentBadgeProps) {
  if (fulfilment === 'AFROTRANSACT_DELIVERY') {
    return (
      <Badge tone="fulfilled" icon={<Truck weight="fill" aria-hidden />}>
        {localDeliveryLabel(shipsFrom)}
      </Badge>
    );
  }
  if (fulfilment === 'AFROTRANSACT') {
    return (
      <Badge tone="fulfilled" icon={<CheckCircle weight="fill" aria-hidden />}>
        {fulfilledLabel}
      </Badge>
    );
  }
  return <Badge tone="seller">{shipsFrom ? shipsFromLabel(shipsFrom) : 'Ships from seller'}</Badge>;
}
