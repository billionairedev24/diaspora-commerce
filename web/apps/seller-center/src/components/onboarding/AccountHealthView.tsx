'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { CheckCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

const BUCKET: Record<string, string> = { past_due: 'Past due', currently_due: 'Due now', eventually_due: 'Due later', pending_verification: 'Verifying' };

/** Account health: Stripe Connect requirements mirrored by the seller service. */
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
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3 text-sm">
            {[
              ['Charges', q.data.chargesEnabled ? 'Enabled' : 'Disabled'],
              ['Payouts', q.data.payoutsPaused ? 'Paused' : q.data.payoutsEnabled ? 'Enabled' : 'Disabled'],
              ['Listings', q.data.listingsHidden ? 'Hidden from shoppers' : 'Visible'],
              ['Deadline', q.data.deadline ? shortDate(q.data.deadline) : '—']
            ].map(([k, v]) => (
              <div key={k} className="flex flex-col rounded-md border border-border p-3">
                <span className="text-xs font-semibold text-muted">{k}</span>
                <strong>{v}</strong>
              </div>
            ))}
          </div>
          {q.data.items.length ? (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {q.data.items.map((i) => (
                <li key={i.requirement} className="flex flex-wrap items-center gap-3 rounded-md border border-border p-3">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <strong>{i.label}</strong>
                    <span className="font-mono text-xs text-muted">{i.requirement}</span>
                  </span>
                  <span className="text-xs font-semibold text-warning">{BUCKET[i.bucket] ?? i.bucket}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 flex items-center gap-2 text-sm text-success"><CheckCircle size={18} weight="fill" aria-hidden /> Nothing outstanding.</p>
          )}
          <p className="m-0 text-xs text-muted">Stripe verifies what you send; AfroTransact never stores ID images or full tax numbers.</p>
        </>
      ) : null}
    </ModulePage>
  );
}
