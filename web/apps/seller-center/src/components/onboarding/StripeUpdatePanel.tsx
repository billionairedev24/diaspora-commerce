'use client';

import { isApiError, type OnboardingStripeSession, sellerKeys } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { CircleNotch, ShieldCheck } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { isLocalSession, LocalAccountManagement, StripeEmbedded } from './StripeEmbedded';

/**
 * "Update with Stripe": opens Stripe's secure account management (what Stripe still needs, or the payout bank account) with
 * an Account Session. Used by Settings > Payouts, Settings > Documents and Account health, so Stripe is the one place
 * these are changed. Owner and finance members only (the BFF refuses others).
 */
export function StripeUpdatePanel({ actionLabel, tour, disabled = false }: { actionLabel: string; tour?: string; disabled?: boolean }) {
  const { orgId } = useSeller();
  const qc = useQueryClient();
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
    void qc.invalidateQueries({ queryKey: sellerKeys.onboarding(orgId) });
  };
  return (
    <div className="flex flex-col gap-2 rounded-md border border-border p-3" data-tour={tour}>
      <div className="flex flex-wrap items-center gap-3">
        <ShieldCheck size={22} aria-hidden className="flex-none" />
        <span className="min-w-0 flex-1 text-sm">
          <strong>Update with Stripe</strong>
          <span className="block text-xs text-muted">Answer what Stripe needs or change your payout bank account, in Stripe’s secure forms.</span>
        </span>
        {!session ? (
          <Button size="sm" onClick={() => void open()} disabled={busy || disabled}>
            {busy ? <CircleNotch size={16} className="animate-spin" aria-hidden /> : null}
            {actionLabel}
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
          <StripeEmbedded session={session} component="account-management" fetchClientSecret={async () => (await sellerApi().createSellerStripeSession()).clientSecret} onExit={close} />
          <div>
            <Button size="sm" variant="link" onClick={close}>
              Done
            </Button>
          </div>
        </>
      ) : null}
    </div>
  );
}
