'use client';

import { isApiError, type OnboardingStatus, type OnboardingStripeSession, sellerKeys } from '@afrotransact/api-client';
import { Button, cx } from '@afrotransact/ui';
import { CheckCircle, CircleDashed, CircleNotch, Flask, ShieldCheck, WarningCircle } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useImperativeHandle, useRef, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { fieldId } from '@/lib/onboarding-form';
import { useSeller } from '@/lib/session';
import { hasStripeKey, initConnect } from '@/lib/stripe';

import { type StepProps } from '../WizardKit';

type Row = { name: string; ok: boolean; note: string };

/** Verification rows as designed: the account, identity (KYC), the IRS name / TIN match and sanctions screening. */
function rows(st: OnboardingStatus): Row[] {
  const s = st.stripe;
  const sole = st.form.business.entityType === 'SOLE_PROPRIETOR';
  const verified = s.status === 'VERIFIED';
  const account: Row = {
    name: 'Stripe Connect account',
    ok: verified,
    note:
      s.status === 'NOT_STARTED'
        ? 'Verification runs inside AfroTransact, powered by Stripe. You won’t need a Stripe login. About 3 minutes.'
        : s.status === 'IN_PROGRESS'
          ? 'Started — finish the questions below.'
          : s.status === 'REQUIREMENTS_DUE'
            ? `Stripe needs more information: ${s.currentlyDue.join(', ')}`
            : s.status === 'PENDING'
              ? 'Submitted — Stripe is checking your details. You can continue meanwhile.'
              : s.status === 'REJECTED'
                ? 'Stripe could not verify this business. Contact AfroTransact support.'
                : 'Verified · charges enabled · payouts on request from your back office'
  };
  return [
    account,
    {
      name: sole ? 'Identity (KYC)' : 'Owners & representative (KYC)',
      ok: s.identityStatus === 'verified',
      note: s.identityStatus === 'verified' ? 'Verified by Stripe' : 'Stripe checks name, date of birth, address and ID where needed'
    },
    {
      name: sole ? 'SSN / ITIN — IRS name & TIN match' : 'EIN — IRS name & TIN match',
      ok: s.taxIdStatus === 'verified',
      note: s.taxIdStatus === 'verified' ? 'Matched against IRS records' : 'Stripe compares the legal name and tax ID with the IRS database'
    },
    {
      name: 'Sanctions screening',
      ok: verified,
      note: verified ? 'No OFAC / PEP hits' : 'Run by Stripe and repeated nightly by AfroTransact'
    }
  ];
}

/**
 * Local stand-in (local stack without a Stripe key, or the mock BFF): clearly labelled, nothing leaves the machine.
 * The seller service refuses it on every other profile.
 */
function LocalVerification({ onComplete, busy }: { onComplete: () => void; busy: boolean }) {
  return (
    <div role="region" aria-label="Local test verification" className="flex flex-col gap-3 rounded-md border border-dashed border-gold-500 bg-gold-50 p-4">
      <div className="flex items-start gap-3">
        <Flask size={22} aria-hidden className="mt-0.5 flex-none" />
        <div className="flex flex-col gap-1 text-sm">
          <strong>Local test mode — Stripe isn’t configured on this machine</strong>
          <span className="text-muted">
            With a Stripe key, Stripe’s secure verification opens right here: identity, date of birth, SSN or EIN and address. Locally the seller service
            simulates a verified account instead. Nothing is sent to Stripe.
          </span>
        </div>
      </div>
      <div>
        <Button size="sm" onClick={onComplete} disabled={busy}>
          {busy ? 'Verifying…' : 'Complete test verification'}
        </Button>
      </div>
    </div>
  );
}

/** Stripe's embedded account onboarding (Connect.js), mounted in the page; closing it re-reads the results. */
function EmbeddedOnboarding({ session, onExit }: { session: OnboardingStripeSession; onExit: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const exit = useRef(onExit);
  exit.current = onExit;
  useEffect(() => {
    let cancelled = false;
    let mounted: HTMLElement | null = null;
    initConnect(session.publishableKey, async () => (await sellerApi().createOnboardingStripeSession()).clientSecret)
      .then((connect) => {
        if (cancelled || !box.current) return;
        const el = connect.create('account-onboarding');
        el.setCollectionOptions?.({
          fields: 'eventually_due',
          futureRequirements: 'include'
        });
        el.setOnExit?.(() => exit.current());
        box.current.appendChild(el);
        mounted = el;
      })
      .catch(() => setFailed('Stripe verification couldn’t load. Check your connection and try again.'));
    return () => {
      cancelled = true;
      mounted?.remove();
    };
  }, [session.publishableKey, session.accountId]);
  return (
    <div className="flex flex-col gap-2">
      {failed ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {failed}
        </p>
      ) : null}
      <div ref={box} className="min-h-24 rounded-md border border-border p-2" aria-label="Stripe verification" />
    </div>
  );
}

/** Step 4 "Stripe verification": identity and tax ID checked by Stripe Connect, embedded (no Stripe-hosted page). */
export function StripeStep({ status, ref }: StepProps) {
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [session, setSession] = useState<OnboardingStripeSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = status.stripe;
  const done = status.steps.find((x) => x.key === 'STRIPE_VERIFICATION')?.state === 'DONE';
  const disabled = !status.form.editable;

  useImperativeHandle(ref, () => ({
    save: (mode) => {
      if (mode === 'draft' || done) return Promise.resolve(true);
      setError('Finish Stripe verification to continue.');
      return Promise.resolve(false);
    }
  }));

  const apply = (st: OnboardingStatus) => qc.setQueryData(sellerKeys.onboarding(orgId), st);
  const fail = (e: unknown) =>
    setError(isApiError(e) && e.problem.detail ? e.problem.detail : 'Stripe verification is unavailable right now. Try again in a minute.');

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const se = await sellerApi().createOnboardingStripeSession();
      setSession(se);
      apply(await sellerApi().getOnboarding());
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };
  const refresh = async () => {
    setBusy(true);
    setError(null);
    try {
      apply(await sellerApi().refreshOnboardingStripe());
      setSession(null);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const local = session ? session.localTest || !hasStripeKey(session.publishableKey) : false;
  return (
    <div className="flex flex-col gap-3">
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {rows(status).map((r, i) => (
          <li key={r.name} className="flex items-center gap-3 rounded-sm border border-border px-4 py-2.5">
            {r.ok ? (
              <CheckCircle size={24} weight="fill" className="flex-none text-success" aria-hidden />
            ) : i === 0 && s.status === 'REQUIREMENTS_DUE' ? (
              <WarningCircle size={24} weight="fill" className="flex-none text-warning" aria-hidden />
            ) : i === 0 ? (
              <ShieldCheck size={24} className="flex-none text-muted" aria-hidden />
            ) : (
              <CircleDashed size={24} className="flex-none text-subtle" aria-hidden />
            )}
            <div className="min-w-0 flex-1">
              <strong className="text-sm">{r.name}</strong>
              <div className="text-xs text-muted">{r.note}</div>
            </div>
            {i === 0 && !done && !disabled && !session ? (
              <Button id={fieldId('stripe')} size="sm" variant="neutral" onClick={() => void start()} disabled={busy}>
                {busy ? <CircleNotch size={16} className="animate-spin" aria-hidden /> : null}
                {s.status === 'NOT_STARTED' ? 'Start verification' : 'Continue verification'}
              </Button>
            ) : null}
            {i === 0 && s.status === 'PENDING' && !session ? (
              <Button size="sm" variant="link" onClick={() => void refresh()} disabled={busy}>
                Refresh status
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      {session && local ? <LocalVerification onComplete={() => void refresh()} busy={busy} /> : null}
      {session && !local ? <EmbeddedOnboarding session={session} onExit={() => void refresh()} /> : null}
      <p className="m-0 text-xs text-muted">
        Identity, SSN/EIN and date of birth are collected inside Stripe’s embedded Connect onboarding, and Stripe runs KYC plus the IRS name/TIN match.
        AfroTransact only receives the verification result — never ID images or full tax numbers.
      </p>
      <p role={error ? 'alert' : undefined} className={cx('m-0 min-h-4 text-sm', error ? 'text-danger' : 'text-muted')}>
        {error}
      </p>
    </div>
  );
}
