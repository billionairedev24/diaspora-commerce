'use client';

import { isApiError, type OnboardingStripeSession, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { CheckCircle, CircleNotch, ShieldCheck } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { isLocalSession, LocalAccountManagement, StripeEmbedded } from './StripeEmbedded';

const BUCKET: Record<string, string> = { past_due: 'Past due', currently_due: 'Due now', eventually_due: 'Due later', pending_verification: 'Verifying' };

const WHERE: Record<string, string> = { account_management: 'In Stripe’s account settings', account_onboarding: 'In Stripe’s secure form' };

/**
 * Account health: Stripe Connect requirements mirrored by the seller service, read-only. Seller accounts are
 * Stripe-collected, so what Stripe needs (and a new payout bank account) is answered in Stripe's embedded components,
 * opened here with an Account Session (owner and finance members).
 */
export function AccountHealthView() {
  const { orgId } = useSeller();
  const qc = useQueryClient();
  const q = useQuery(sellerQueries.compliance(sellerApi(), orgId));
  const [session, setSession] = useState<OnboardingStripeSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const open = async () => {
    setBusy(true);
    setError(null);
    try {
      setSession(await sellerApi().createSellerStripeSession());
    } catch (e) {
      setError(isApiError(e) && e.problem.detail ? e.problem.detail : 'Stripe is unavailable right now. Try again in a minute.');
    } finally {
      setBusy(false);
    }
  };
  const close = () => {
    setSession(null);
    void qc.invalidateQueries({ queryKey: sellerKeys.compliance(orgId) });
  };
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
              ['Payout account', q.data.payoutBank?.last4 ? `${q.data.payoutBank.bankName ?? 'Bank'} ••${q.data.payoutBank.last4}` : 'Not added'],
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
                  <span className="text-xs text-muted">{WHERE[i.component]}</span>
                  <span className="text-xs font-semibold text-warning">{BUCKET[i.bucket] ?? i.bucket}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 flex items-center gap-2 text-sm text-success"><CheckCircle size={18} weight="fill" aria-hidden /> Nothing outstanding.</p>
          )}
          <div className="flex flex-col gap-2 rounded-md border border-border p-3">
            <div className="flex flex-wrap items-center gap-3">
              <ShieldCheck size={22} aria-hidden className="flex-none" />
              <span className="min-w-0 flex-1 text-sm">
                <strong>Update with Stripe</strong>
                <span className="block text-xs text-muted">Answer what Stripe needs or change your payout bank account, in Stripe’s secure forms.</span>
              </span>
              {!session ? (
                <Button size="sm" onClick={() => void open()} disabled={busy}>
                  {busy ? <CircleNotch size={16} className="animate-spin" aria-hidden /> : null}
                  {q.data.items.length ? 'Resolve with Stripe' : 'Change bank account'}
                </Button>
              ) : null}
            </div>
            {error ? (
              <p role="alert" className="m-0 text-sm text-danger">
                {error}
              </p>
            ) : null}
            {session && isLocalSession(session) ? <LocalAccountManagement onDone={close} /> : null}
            {session && !isLocalSession(session) ? (
              <>
                <StripeEmbedded session={session} component="notification-banner" fetchClientSecret={async () => (await sellerApi().createSellerStripeSession()).clientSecret} />
                <StripeEmbedded
                  session={session}
                  component="account-management"
                  fetchClientSecret={async () => (await sellerApi().createSellerStripeSession()).clientSecret}
                  onExit={close}
                />
                <div>
                  <Button size="sm" variant="link" onClick={close}>
                    Done
                  </Button>
                </div>
              </>
            ) : null}
          </div>
          <p className="m-0 text-xs text-muted">Stripe verifies what you send; AfroTransact never stores ID images, full tax numbers or bank numbers.</p>
        </>
      ) : null}
    </ModulePage>
  );
}
