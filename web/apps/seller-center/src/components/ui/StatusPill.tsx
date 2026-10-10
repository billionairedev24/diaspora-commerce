import { cx } from '@afrotransact/ui';
import { type ReactNode } from 'react';

export type Tone = 'good' | 'warn' | 'bad' | 'info' | 'brand';

const TONES: Record<Tone, string> = {
  good: 'bg-success-bg text-success',
  warn: 'bg-warning-bg text-warning',
  bad: 'bg-danger-bg text-danger',
  info: 'bg-info-bg text-info',
  brand: 'bg-gold-100 text-ink-1000'
};

/** Status chip (tones from the Backoffice prototype's STATUS_TONE). */
export function StatusPill({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={cx('inline-flex items-center rounded-pill px-2 py-0.5 text-xs font-bold whitespace-nowrap', TONES[tone])}>{children}</span>;
}

const STATUS_TONE: Record<string, Tone> = {
  PLACED: 'brand', CONFIRMED: 'info', PACKED: 'info', READY: 'warn', PICKED_UP: 'info', IN_TRANSIT: 'info', OUT_FOR_DELIVERY: 'info', DELIVERED: 'good',
  DELIVERY_FAILED: 'bad', RETURNED: 'bad', CANCELLED: 'bad', PENDING_PAYMENT: 'info', LIVE: 'good', IN_REVIEW: 'brand', PENDING_CATEGORY_APPROVAL: 'warn',
  REJECTED: 'bad', PAUSED: 'info', REQUESTED: 'brand', HELD: 'bad', IN_TRANSIT_PAYOUT: 'info', PAID: 'good', FAILED: 'bad', ACTIVE: 'good', INVITED: 'info',
  RESCHEDULE_REQUESTED: 'warn', RESCHEDULE_PROPOSED: 'warn', COMPLETED: 'good', NO_SHOW: 'bad', DECLINED: 'bad', EXPIRED: 'info', CANCELLED_CUSTOMER: 'info',
  CANCELLED_PROVIDER: 'bad', APPROVED: 'good', PENDING: 'warn', DRAFT: 'info', PUBLISHED: 'good', TRIALING: 'brand', PAST_DUE: 'bad', OPEN: 'warn'
};

export const toneOf = (status: string): Tone => STATUS_TONE[status] ?? 'info';
