'use client';

import { cx } from '@afrotransact/ui';

import { type MessageKey, useT } from '@/i18n/t';
import { type SellingStatus } from '@/lib/selling-status';

const TONE: Record<SellingStatus, string> = {
  SELL: 'border-success text-success',
  APPLY: 'border-warning text-warning',
  NOT_AVAILABLE: 'border-danger text-danger'
};

/** The approval state of the chosen category and brand: Sell this product, Apply to sell, or Not available (with its reason). */
export function SellingStatusChip({ status, reason }: { status: SellingStatus; reason?: string | null }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-0.5" data-testid="selling-status" data-status={status}>
      <span role="status" aria-label={t('sc.pf.sell.aria', { status: t(`sc.pf.sell.${status}` as MessageKey) })}
        className={cx('inline-flex w-fit items-center rounded-pill border px-3 py-0.5 text-xs font-semibold', TONE[status])}>
        {t(`sc.pf.sell.${status}` as MessageKey)}
      </span>
      <span className="text-xs text-muted">{reason ?? t(`sc.pf.sell.hint.${status}` as MessageKey)}</span>
    </div>
  );
}
