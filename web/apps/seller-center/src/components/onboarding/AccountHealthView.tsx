'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { StatStrip } from '@afrotransact/ui';
import { CheckCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { StripeUpdatePanel } from './StripeUpdatePanel';

const BUCKET: Record<string, string> = { past_due: 'Past due', currently_due: 'Due now', eventually_due: 'Due later', pending_verification: 'Verifying' };

const WHERE: Record<string, string> = { account_management: 'In Stripe’s account settings', account_onboarding: 'In Stripe’s secure form' };

/**
 * Account health: Stripe Connect requirements mirrored by the seller service, read-only. Seller accounts are
 * Stripe-collected, so what Stripe needs (and a new payout bank account) is answered in Stripe's embedded components,
 * opened here with an Account Session (owner and finance members).
 */
export function AccountHealthView() {
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.compliance(sellerApi(), orgId));
  return (
    <ModulePage module="account-health">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          {q.data.showBanner ? (
            <Notice tone={q.data.tone === 'danger' ? 'danger' : q.data.tone === 'info' ? 'info' : 'warning'} icon="shield" title={q.data.title}>{q.data.message}</Notice>
          ) : (
            <Notice tone="success" title="Your account is in good standing.">Payments and payouts are on.</Notice>
          )}
          <StatStrip
            tour="account-health-status"
            label="Account status"
            items={[
              ['Charges', q.data.chargesEnabled ? 'Enabled' : 'Disabled'],
              ['Payouts', q.data.payoutsPaused ? 'Paused' : q.data.payoutsEnabled ? 'Enabled' : 'Disabled'],
              ['Listings', q.data.listingsHidden ? 'Hidden from shoppers' : 'Visible'],
              ['Payout account', q.data.payoutBank?.last4 ? `${q.data.payoutBank.bankName ?? 'Bank'} ••${q.data.payoutBank.last4}` : 'Not added'],
              ['Deadline', q.data.deadline ? shortDate(q.data.deadline) : '—']
            ].map(([label, value]) => ({ id: label, label, value: <span className="text-lg">{value}</span> }))}
          />
          {q.data.items.length ? (
            <ul className="m-0 flex list-none flex-col gap-2 p-0" data-tour="account-health-items">
              {q.data.items.map((i) => (
                <li key={i.requirement} className="flex flex-wrap items-center gap-3 rounded-md border border-border p-3">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <strong>{i.label}</strong>
                    <span className="font-mono text-xs text-muted">{i.requirement}</span>
                  </span>
                  <span className="text-xs text-muted">{WHERE[i.component]}</span>
                  <span className="text-xs font-semibold text-warning">{BUCKET[i.bucket] ?? i.bucket}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 flex items-center gap-2 text-sm text-success"><CheckCircle size={18} weight="fill" aria-hidden /> Nothing outstanding.</p>
          )}
          <StripeUpdatePanel actionLabel={q.data.items.length ? 'Resolve with Stripe' : 'Change bank account'} tour="account-health-stripe" />
          <p className="m-0 text-xs text-muted">Stripe verifies what you send; AfroTransact never stores ID images, full tax numbers or bank numbers.</p>
        </>
      ) : null}
    </ModulePage>
  );
}
