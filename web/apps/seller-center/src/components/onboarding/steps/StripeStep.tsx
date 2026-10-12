'use client';

import { isApiError, type OnboardingStatus, type OnboardingStripeSession, sellerKeys } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { ArrowDown, Bank, CheckCircle, IdentificationCard, LockSimple, Receipt } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { fieldId, payoutAccountLabel } from '@/lib/onboarding-form';
import { useSeller } from '@/lib/session';

import { isLocalSession, LocalStripeForm, StripeEmbedded, StripeLoadFailed, StripeLoading } from '../StripeEmbedded';
import { type StepProps } from '../WizardKit';

/** How long the success state shows before the wizard moves on by itself. */
export const ADVANCE_AFTER_MS = 1200;

/** What Stripe will ask for: three short items, no boxes. */
function WhatStripeAsks({ sole }: { sole: boolean }) {
  const items: { icon: ReactNode; title: string; text: string }[] = [
    {
      icon: <IdentificationCard size={24} aria-hidden />,
      title: sole ? 'About you' : 'Owners & representative',
      text: 'Name, date of birth, home address and, if needed, a photo ID'
    },
    {
      icon: <Receipt size={24} aria-hidden />,
      title: sole ? 'SSN or ITIN' : 'EIN',
      text: 'Checked against IRS records'
    },
    {
      icon: <Bank size={24} aria-hidden />,
      title: 'Payout bank account',
      text: 'Where your earnings are paid'
    }
  ];
  return (
    <ul aria-label="What Stripe will ask for" className="m-0 grid list-none grid-cols-1 gap-x-6 gap-y-3 p-0 sm:grid-cols-3">
      {items.map((i) => (
        <li key={i.title} className="flex items-start gap-3">
          <span className="grid size-10 flex-none place-items-center rounded-pill bg-gold-50">{i.icon}</span>
          <span className="flex min-w-0 flex-col">
            <strong className="text-sm">{i.title}</strong>
            <span className="text-xs text-muted">{i.text}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Stripe has what it needs: a clear success state (and, right after finishing, the move to the next step). */
function Verified({ status, moving }: { status: OnboardingStatus; moving: boolean }) {
  const pending = status.stripe.status === 'PENDING';
  const bank = status.form.payouts;
  return (
    <div role="status" className="flex flex-col items-start gap-3 rounded-lg bg-success-bg px-5 py-6 sm:px-8">
      <CheckCircle size={40} weight="fill" className="text-success" aria-hidden />
      <div className="flex flex-col gap-1">
        <strong className="text-xl">{pending ? 'Submitted to Stripe' : 'Verified by Stripe'}</strong>
        <span className="text-sm text-muted">
          {pending
            ? 'Stripe is finishing its checks. You can continue meanwhile — we’ll let you know if it needs anything else.'
            : 'Identity and tax ID checked.'}
          {bank.accountLast4 ? ` Payouts go to ${payoutAccountLabel(bank)}.` : ''}
        </span>
      </div>
      {moving ? <span className="text-sm font-semibold">Taking you to the next step…</span> : null}
    </div>
  );
}

/**
 * Step 4 "Stripe verification": one path forward. A one-line intro and what Stripe will ask, then Stripe's embedded
 * account onboarding as the step's main content (loaded as soon as the step opens, no button before it, no box around
 * it). When the seller finishes, the step shows a success state and the wizard moves on by itself; the page's
 * Continue stays as a fallback. The account is Stripe-collected: Stripe keeps the liability and the bank details.
 */
export function StripeStep({ status, ref, advance }: StepProps) {
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [session, setSession] = useState<OnboardingStripeSession | null>(null);
  const [phase, setPhase] = useState<'idle' | 'loading' | 'failed' | 'ready'>('idle');
  const [failure, setFailure] = useState<string>('');
  const [note, setNote] = useState<string | null>(null);
  const [moving, setMoving] = useState(false);
  const done = status.steps.find((x) => x.key === 'STRIPE_VERIFICATION')?.state === 'DONE';
  const editable = status.form.editable;
  const sole = status.form.business.entityType === 'SOLE_PROPRIETOR';
  const advanceRef = useRef(advance);
  advanceRef.current = advance;

  const apply = useCallback((st: OnboardingStatus) => qc.setQueryData(sellerKeys.onboarding(orgId), st), [qc, orgId]);
  const problem = (e: unknown) => (isApiError(e) && e.problem.detail ? e.problem.detail : 'Stripe is unavailable right now. Try again in a minute.');

  const open = useCallback(async () => {
    setPhase('loading');
    try {
      setSession(await sellerApi().createOnboardingStripeSession());
      setPhase('ready');
      apply(await sellerApi().getOnboarding());
    } catch (e) {
      setFailure(problem(e));
      setPhase('failed');
    }
  }, [apply]);

  // The form opens with the step: nothing to click before it.
  useEffect(() => {
    if (!done && editable && phase === 'idle') void open();
  }, [done, editable, phase, open]);

  /** Stripe says the seller finished (or closed the form): read the result and, when complete, move on. */
  const finished = async (): Promise<boolean> => {
    setNote(null);
    try {
      const st = await sellerApi().refreshOnboardingStripe();
      apply(st);
      if (st.steps.find((x) => x.key === 'STRIPE_VERIFICATION')?.state === 'DONE') {
        setMoving(true);
        window.setTimeout(() => advanceRef.current?.(), ADVANCE_AFTER_MS);
        return true;
      }
      setNote('Stripe still needs a few details. Continue in the form below.');
      return false;
    } catch (e) {
      setNote(problem(e));
      return false;
    }
  };

  useImperativeHandle(ref, () => ({
    save: async (mode) => {
      if (mode === 'draft' || done) return true;
      // The page's Continue is a fallback: check with Stripe before saying it isn't finished.
      if (await finished()) return true;
      setNote('Finish the Stripe form to continue.');
      return false;
    }
  }));

  if (done) return <Verified status={status} moving={moving} />;

  const local = session ? isLocalSession(session) : false;
  return (
    <div className="flex flex-col gap-6">
      <WhatStripeAsks sole={sole} />
      <div id={fieldId('stripe')} tabIndex={-1} className="flex flex-col gap-3 outline-none">
        <p className="m-0 flex items-center gap-2 text-md font-semibold">
          <ArrowDown size={18} aria-hidden className="flex-none" />
          Continue in the secure Stripe form below — it saves as you go.
        </p>
        {note ? (
          <p role="alert" className="m-0 rounded-md bg-warning-bg px-4 py-2.5 text-sm">
            {note}
          </p>
        ) : null}
        {!editable ? <p className="m-0 text-sm text-muted">Your application is with our team; Stripe verification can’t be changed now.</p> : null}
        {editable && (phase === 'loading' || phase === 'idle') ? <StripeLoading /> : null}
        {phase === 'failed' ? <StripeLoadFailed message={failure} onRetry={() => void open()} /> : null}
        {phase === 'ready' && session && local ? (
          <LocalStripeForm
            label="Local test verification"
            title="Verify your business with Stripe"
            action={
              <Button onClick={() => void finished()} disabled={moving}>
                Complete test verification
              </Button>
            }
          >
            Stripe isn’t configured on this machine. With a Stripe key, Stripe’s secure form opens right here and asks for the details above. Locally the seller
            service simulates a verified account with Stripe’s test bank, and nothing is sent to Stripe.
          </LocalStripeForm>
        ) : null}
        {phase === 'ready' && session && !local ? (
          <StripeEmbedded
            session={session}
            component="account-onboarding"
            fetchClientSecret={async () => (await sellerApi().createOnboardingStripeSession()).clientSecret}
            onExit={() => void finished()}
          />
        ) : null}
      </div>
      <p className="m-0 flex items-center gap-1.5 text-xs text-muted">
        <LockSimple size={14} aria-hidden className="flex-none" />
        Stripe runs the checks. AfroTransact never sees your ID, full tax number or bank numbers.
      </p>
    </div>
  );
}
