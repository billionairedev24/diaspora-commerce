import { type ReactNode } from 'react';

import { cx } from '../cx';
import { CheckCircle } from '../icons';
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
  fulfilment: 'AFROTRANSACT' | 'SELLER';
  /** City shown on seller-fulfilled items ("Ships from Bronx, NY"). */
  shipsFrom?: string;
  fulfilledLabel?: string;
  shipsFromLabel?: (city: string) => string;
}

/** "Fulfilled by AfroTransact" (green) vs "Ships from seller" (grey) — one badge per item. */
export function FulfilmentBadge({ fulfilment, shipsFrom, fulfilledLabel = 'Fulfilled by AfroTransact', shipsFromLabel = (c) => `Ships from ${c}` }: FulfilmentBadgeProps) {
  if (fulfilment === 'AFROTRANSACT') {
    return (
      <Badge tone="fulfilled" icon={<CheckCircle weight="fill" aria-hidden />}>
        {fulfilledLabel}
      </Badge>
    );
  }
  return <Badge tone="seller">{shipsFrom ? shipsFromLabel(shipsFrom) : 'Ships from seller'}</Badge>;
}
