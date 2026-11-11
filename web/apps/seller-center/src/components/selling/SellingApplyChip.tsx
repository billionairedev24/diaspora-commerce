'use client';

import { buttonClassName } from '@afrotransact/ui';
import { CheckCircle, Prohibit, ShieldCheck } from '@phosphor-icons/react/ssr';
import Link from 'next/link';

import { type SellingStatusResult } from '@/lib/selling-status';

/**
 * The product form's chip for the three selling states: "Sell this product", "Apply to sell" with a button that opens the
 * application for the first scope in the way, and "Not available" with the reason (and the date they may apply again).
 * Pass the result of `useSellingStatus`.
 */
export function SellingApplyChip({ selling }: { selling: SellingStatusResult }) {
  if (selling.loading) return null;
  if (selling.status === 'SELL') {
    return (
      <span className="inline-flex w-fit items-center gap-1.5 rounded-pill bg-success-bg px-2.5 py-1 text-sm font-semibold text-success" data-selling="SELL">
        <CheckCircle size={16} weight="fill" aria-hidden /> Sell this product
      </span>
    );
  }
  if (selling.status === 'APPLY') {
    return (
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2" data-selling="APPLY">
        <span className="inline-flex items-center gap-1.5 rounded-pill bg-warning-bg px-2.5 py-1 text-sm font-semibold text-warning">
          <ShieldCheck size={16} weight="fill" aria-hidden /> Apply to sell
        </span>
        {selling.reason ? <span className="min-w-0 flex-1 basis-72 text-sm text-muted">{selling.reason}</span> : null}
        {selling.applyHref ? (
          <Link href={selling.applyHref} className={buttonClassName({ variant: 'primary' })}>
            Apply to sell
          </Link>
        ) : null}
      </div>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2" data-selling="NOT_AVAILABLE">
      <span className="inline-flex items-center gap-1.5 rounded-pill bg-danger-bg px-2.5 py-1 text-sm font-semibold text-danger">
        <Prohibit size={16} weight="fill" aria-hidden /> Not available
      </span>
      {selling.reason ? <span className="min-w-0 flex-1 basis-72 text-sm text-muted">{selling.reason}</span> : null}
    </div>
  );
}
