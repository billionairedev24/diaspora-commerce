'use client';

import { isApiError, type OnboardingStatus, type OnboardingStripeSession, sellerKeys } from '@afrotransact/api-client';
import { Button, cx } from '@afrotransact/ui';
import { Bank, CheckCircle, CircleNotch, LockSimple, WarningCircle } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useImperativeHandle, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { sellerApi } from '@/lib/api';
import { fieldId, payoutAccountLabel } from '@/lib/onboarding-form';
import { onboardingHref } from '@/lib/onboarding-wizard';
import { useSeller } from '@/lib/session';

import { isLocalSession, LocalAccountManagement, StripeEmbedded } from '../StripeEmbedded';
import { type StepProps } from '../WizardKit';

const NOTE: Record<OnboardingStatus['form']['payouts']['status'], string> = {
  NOT_STARTED: '',
  NEEDS_BANK: '',
  FAILED: 'Stripe couldn’t use this account (a payout bounced or the bank rejected it). Change it to keep receiving payouts.',
  VERIFYING: 'Stripe has your bank account and is finishing its checks. You can continue meanwhile.',
  READY: 'Payouts go here on request from your back office, after delivery.'
};

/**
 * Step 5 "Payouts": the bank account Stripe pays out to. Seller accounts are Stripe-collected, so Stripe collects it
 * in its embedded onboarding (Stripe verification) and changes it in its account management component ("Change bank
 * account"); AfroTransact only shows the bank name, last 4 digits and status Stripe reports.
 */
export function PayoutsStep({ status, ref }: StepProps) {
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const p = status.form.payouts;
  const disabled = !status.form.editable;
  const done = status.steps.find((x) => x.key === 'PAYOUTS')?.state === 'DONE';
  const [session, setSession] = useState<OnboardingStripeSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useImperativeHandle(ref, () => ({
    save: (mode) => {
      if (mode === 'draft' || done) return Promise.resolve(true);
      setError(
        p.status === 'FAILED'
          ? 'Change your payout bank account to continue.'
          : p.status === 'VERIFYING'
            ? 'Stripe is still checking your bank account. Refresh in a moment.'
            : 'Add your payout bank account in Stripe verification to continue.'
      );
      return Promise.resolve(false);
    }
  }));

  const apply = (st: OnboardingStatus) => qc.setQueryData(sellerKeys.onboarding(orgId), st);
  const fail = (e: unknown) => setError(isApiError(e) && e.problem.detail ? e.problem.detail : 'Stripe is unavailable right now. Try again in a minute.');
  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };
  const openManagement = () => run(async () => setSession(await sellerApi().createOnboardingStripeSession()));
  const refresh = () =>
    run(async () => {
      apply(await sellerApi().refreshOnboardingStripe());
      setSession(null);
    });

  if (p.status === 'NOT_STARTED' || p.status === 'NEEDS_BANK') {
    return (
      <div className="flex flex-col gap-3">
        <Notice tone="info" title="Stripe collects your payout bank account.">
          Add it in <Link href={onboardingHref('stripe-verification')}>Stripe verification</Link>, in Stripe’s secure form. It appears here once Stripe has it.
        </Notice>
        {p.status === 'NEEDS_BANK' && !disabled ? (
          <div>
            <Button id={fieldId('payouts')} size="sm" variant="link" onClick={() => void refresh()} disabled={busy}>
              {busy ? <CircleNotch size={16} className="animate-spin" aria-hidden /> : null}I’ve added it — refresh
            </Button>
          </div>
        ) : null}
        <Footer error={error} />
      </div>
    );
  }

  const failed = p.status === 'FAILED';
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3 rounded-md border border-border px-4 py-3">
        <Bank size={28} className="flex-none" aria-hidden />
        <div className="min-w-0 flex-1">
          <strong className="text-sm">{payoutAccountLabel(p)}</strong>
          <div className="text-xs text-muted">{NOTE[p.status]}</div>
        </div>
        <span
          className={cx(
            'inline-flex items-center gap-1 text-sm font-semibold',
            failed ? 'text-danger' : p.status === 'READY' ? 'text-success' : 'text-warning'
          )}
        >
          {failed ? <WarningCircle size={18} weight="fill" aria-hidden /> : <CheckCircle size={18} weight="fill" aria-hidden />}
          {failed ? 'Needs a new account' : p.status === 'READY' ? 'Held by Stripe' : 'Stripe is checking'}
        </span>
        {!disabled && !session ? (
          <Button id={fieldId('payouts')} size="sm" variant={failed ? 'primary' : 'neutral'} onClick={() => void openManagement()} disabled={busy}>
            {busy ? <CircleNotch size={16} className="animate-spin" aria-hidden /> : null}
            Change bank account
          </Button>
        ) : null}
        {p.status === 'VERIFYING' && !session ? (
          <Button size="sm" variant="link" onClick={() => void refresh()} disabled={busy}>
            Refresh status
          </Button>
        ) : null}
      </div>
      {session && isLocalSession(session) ? <LocalAccountManagement onDone={() => void refresh()} /> : null}
      {session && !isLocalSession(session) ? (
        <StripeEmbedded
          session={session}
          component="account-management"
          fetchClientSecret={async () => (await sellerApi().createOnboardingStripeSession()).clientSecret}
          onExit={() => void refresh()}
        />
      ) : null}
      {session && !isLocalSession(session) ? (
        <div>
          <Button size="sm" variant="link" onClick={() => void refresh()} disabled={busy}>
            Done — refresh
          </Button>
        </div>
      ) : null}
      <Footer error={error} />
    </div>
  );
}

function Footer({ error }: { error: string | null }) {
  return (
    <>
      <p className="m-0 inline-flex items-center gap-1 text-xs text-muted">
        <LockSimple size={12} aria-hidden /> Stripe holds your bank details. AfroTransact only sees the bank name and the last 4 digits.
      </p>
      <p role={error ? 'alert' : undefined} className={cx('m-0 min-h-4 text-sm', error ? 'text-danger' : 'text-muted')}>
        {error}
      </p>
    </>
  );
}
