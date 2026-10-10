import { type RecordTone } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { type ReactNode } from 'react';

const TONES: Record<RecordTone, string> = {
  good: 'bg-success-bg text-success',
  warn: 'bg-warning-bg text-warning',
  bad: 'bg-danger-bg text-danger',
  info: 'bg-info-bg text-info',
  brand: 'bg-gold-100 text-ink-1000'
};

/** Status chip (tones from the Backoffice prototype's STATUS_TONE; the BFF sends the tone with each row). */
export function StatusPill({ tone, children }: { tone: RecordTone | null; children: ReactNode }) {
  return <span className={cx('inline-flex items-center rounded-pill px-2 py-0.5 text-xs font-bold whitespace-nowrap', TONES[tone ?? 'info'])}>{children}</span>;
}
