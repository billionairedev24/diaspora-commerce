'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { Notice } from '@/components/ui/Notice';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

/**
 * Stripe compliance banner (seller service account health), shown on every page while something is outstanding.
 * Members whose role cannot open Account health still see what is wrong, without the link.
 */
export function ComplianceBanner({ onAccountHealth }: { onAccountHealth: boolean }) {
  const { orgId, can, session } = useSeller();
  const q = useQuery({ ...sellerQueries.compliance(sellerApi(), orgId), enabled: session.approved, retry: false });
  const c = q.data;
  if (!c || !c.showBanner || onAccountHealth) return null;
  const tone = c.tone === 'danger' ? 'danger' : c.tone === 'info' ? 'info' : 'warning';
  return (
    <Notice
      tone={tone}
      icon="shield"
      title={c.title}
      action={
        can('account-health', 'VIEW') ? (
          <Link href="/account-health" className="text-sm font-semibold whitespace-nowrap">
            Open Account health
          </Link>
        ) : undefined
      }
    >
      {c.message}
    </Notice>
  );
}

/** Payments compliance hold: shown on every page (to roles that see money) while payouts are paused. */
export function PayoutsPausedBanner() {
  const { orgId, can, session } = useSeller();
  const q = useQuery({ ...sellerQueries.payouts(sellerApi(), orgId), enabled: session.approved && can('payouts', 'VIEW'), retry: false });
  const b = q.data?.balance;
  if (!b?.payoutsPaused) return null;
  return (
    <Notice
      tone="danger"
      icon="shield"
      role="alert"
      title="Payouts are paused."
      action={
        can('account-health', 'VIEW') && b.payoutsPausedReason !== 'MANUAL' ? (
          <Link href="/account-health" className="text-sm font-semibold whitespace-nowrap">
            Resolve now
          </Link>
        ) : undefined
      }
    >
      {b.payoutsPausedMessage ?? 'Your balance keeps accruing and is paid out once the hold is lifted.'}
    </Notice>
  );
}
