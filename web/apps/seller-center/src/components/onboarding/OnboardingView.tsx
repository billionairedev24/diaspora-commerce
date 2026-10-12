'use client';

import { type OnboardingStatus, type OnboardingStepSlug, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { ArrowLeft, UserCircleCheck, X } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { type ComponentType, useEffect, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { signOutEverywhere } from '@/lib/auth';
import { prefillNote } from '@/lib/onboarding-form';
import { isEditable, nextSlug, onboardingHref, prevSlug, progressOf, redirectFor } from '@/lib/onboarding-wizard';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';

import { Stepper } from './Stepper';
import { BusinessStep } from './steps/BusinessStep';
import { ComplianceStep } from './steps/ComplianceStep';
import { DetailsStep } from './steps/DetailsStep';
import { OwnersStep } from './steps/OwnersStep';
import { PayoutsStep } from './steps/PayoutsStep';
import { PlanStep } from './steps/PlanStep';
import { ReviewStep } from './steps/ReviewStep';
import { StripeStep } from './steps/StripeStep';
import { SubmittedView } from './SubmittedView';
import { type StepHandle, type StepProps } from './WizardKit';

const STEP_COMPONENTS: Record<OnboardingStepSlug, ComponentType<StepProps>> = {
  business: BusinessStep,
  'business-details': DetailsStep,
  owners: OwnersStep,
  'stripe-verification': StripeStep,
  payouts: PayoutsStep,
  plan: PlanStep,
  'catalog-compliance': ComplianceStep,
  review: ReviewStep
};

const SUBTITLES: Partial<Record<OnboardingStepSlug, string>> = {
  business: 'Tell us what you’ll sell and how your business is set up. The rest of the form adapts to your answers.',
  owners: 'Each person will verify their identity in the next step.',
  'stripe-verification': 'Stripe, our payments partner, verifies your business and keeps your payout bank account safe. It takes about three minutes.',
  payouts: 'Stripe holds the bank account your payouts go to. It must belong to the business or owner; change it here any time.',
  plan: 'Every paid plan starts with a free trial. Change plans any time.',
  'catalog-compliance': 'Some categories need permits. We only ask for what applies to you.',
  review: 'Our trust team reviews every application, usually within 2 business days. You can’t sell until it’s approved.'
};

function subtitleOf(st: OnboardingStatus, slug: OnboardingStepSlug): string {
  if (slug === 'business-details') {
    return st.form.business.entityType === 'SOLE_PROPRIETOR'
      ? 'Must match what the IRS has for your SSN or ITIN.'
      : 'Must match your IRS records for this EIN. We run an IRS TIN match before approval.';
  }
  if (slug === 'catalog-compliance' && st.form.business.offering === 'SERVICES') return 'Regulated services need a valid licence before you can take bookings.';
  return SUBTITLES[slug] ?? '';
}

/** A quiet text action next to the primary one. */
const QUIET =
  'cursor-pointer border-0 bg-transparent p-1 text-sm font-semibold text-muted underline-offset-4 hover:text-text hover:underline disabled:cursor-not-allowed';

function useOnboarding() {
  const { orgId } = useSeller();
  return useQuery(sellerQueries.onboarding(sellerApi(), orgId));
}

function ApplicationHeading({ status }: { status: OnboardingStatus }) {
  const provider = status.kind === 'PROVIDER';
  return (
    <div className="flex flex-col gap-0.5">
      <h1 className="m-0 text-xl font-bold">
        {provider ? 'Your provider application' : 'Your seller application'}
        {status.reference ? <span className="font-normal text-muted"> · {status.reference}</span> : null}
      </h1>
      <p className="m-0 text-sm text-muted">
        {provider ? 'Provider Hub' : 'Seller Center'} opens once AfroTransact approves your application. Progress saves to your account.
      </p>
    </div>
  );
}

/** The design's prefill banner ("Using your existing AfroTransact account."), dismissable. */
function PrefillBanner({ status }: { status: OnboardingStatus }) {
  const [note, setNote] = useState<string | null>(() => prefillNote(status.form));
  if (!note) return null;
  return (
    <div role="status" className="flex items-start gap-3 rounded-sm bg-gold-50 px-4 py-2.5 text-sm">
      <UserCircleCheck size={20} aria-hidden className="flex-none" />
      <span className="flex-1">
        <strong>Using your existing AfroTransact account.</strong> {note}
      </span>
      <button type="button" aria-label="Dismiss" className="cursor-pointer border-0 bg-transparent p-0 text-text" onClick={() => setNote(null)}>
        <X size={18} aria-hidden />
      </button>
    </div>
  );
}

/**
 * /onboarding: resumes the wizard at the first incomplete step, or (once submitted) shows what happens next and where
 * the application is.
 */
export function OnboardingView() {
  const q = useOnboarding();
  const router = useRouter();
  const target = q.data ? redirectFor(q.data, null) : null;
  useEffect(() => {
    if (target) router.replace(target);
  }, [target, router]);
  if (q.isPending || target) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return (
    <div className="flex flex-col gap-6">
      <ApplicationHeading status={q.data} />
      <div className="w-full max-w-[880px]">
        <SubmittedView status={q.data} onRefresh={() => void q.refetch()} refreshing={q.isFetching} />
      </div>
    </div>
  );
}

/**
 * /onboarding/<step>: the application wizard. Progress at the top, Back beside the step heading, the step's fields,
 * then one primary action at the end of the content ("Save and continue": validates, saves, then opens the next step)
 * with a quiet "Save & sign out" next to it. No boxes around the step and no footer bar: whitespace and type instead.
 */
export function OnboardingWizard({ slug }: { slug: string }) {
  const q = useOnboarding();
  const router = useRouter();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const storefrontUrl = useStorefrontUrl();
  const step = useRef<StepHandle>(null);
  const [busy, setBusy] = useState<'continue' | 'exit' | null>(null);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const target = q.data ? redirectFor(q.data, slug) : null;
  useEffect(() => {
    if (target) router.replace(target);
  }, [target, router]);

  if (q.isPending || target) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const status = q.data;
  const current = status.steps.find((s) => s.slug === slug);
  if (!current) return <Spinner />;
  const Step = STEP_COMPONENTS[current.slug];
  const editable = isEditable(status);
  const p = progressOf(status, slug);
  const back = prevSlug(status, slug);
  const isReview = current.slug === 'review';

  const latest = (): OnboardingStatus => qc.getQueryData<OnboardingStatus>(sellerKeys.onboarding(orgId)) ?? status;
  /** Opens the next step (or the status page once the application left the applicant's hands). */
  const goNext = () => {
    const st = latest();
    if (isReview || !isEditable(st)) {
      router.push('/onboarding');
      return;
    }
    router.push(onboardingHref(nextSlug(st, slug) ?? st.resumeStep));
    window.scrollTo({ top: 0 });
  };
  const saveAndContinue = async () => {
    if (!step.current) return;
    setBusy('continue');
    try {
      const ok = await step.current.save('continue');
      if (!ok) return;
      setSavedAt(new Date());
      goNext();
    } finally {
      setBusy(null);
    }
  };
  const saveAndSignOut = async () => {
    if (!step.current) return;
    setBusy('exit');
    const ok = await step.current.save('draft');
    if (ok) await signOutEverywhere(storefrontUrl);
    setBusy(null);
  };

  const stripePending = current.slug === 'stripe-verification' && current.state !== 'DONE';
  const primaryLabel =
    busy === 'continue'
      ? isReview
        ? 'Submitting…'
        : 'Saving…'
      : isReview
        ? status.status === 'INFO_REQUESTED'
          ? 'Resubmit for review'
          : 'Submit for review'
        : stripePending
          ? 'I’ve finished — continue'
          : 'Save and continue';

  return (
    <div className="flex flex-col gap-6">
      <ApplicationHeading status={status} />
      {status.status === 'INFO_REQUESTED' && status.requestedItems.length ? (
        <Notice tone="warning" title="We need a little more information:" role="alert">
          {status.requestedItems.join('; ')}. Update the highlighted steps and submit again.
        </Notice>
      ) : null}
      <Stepper status={status} current={slug} />
      <section aria-labelledby="ob-step-title" className="flex w-full max-w-[880px] flex-col gap-6">
        <header className="flex flex-col gap-1">
          {back ? (
            <button
              type="button"
              onClick={() => router.push(onboardingHref(back))}
              disabled={busy !== null}
              className="mb-2 inline-flex w-fit cursor-pointer items-center gap-1.5 rounded-pill border-0 bg-transparent px-0 py-1 text-sm font-semibold text-muted hover:text-text disabled:cursor-not-allowed"
            >
              <ArrowLeft size={16} aria-hidden /> Back
            </button>
          ) : null}
          <span className="text-xs font-semibold tracking-wide text-muted uppercase max-md:hidden">
            Step {p.num} of {p.total}
          </span>
          <h2 id="ob-step-title" className="m-0 text-2xl font-bold">
            {current.label}
          </h2>
          <p className="m-0 max-w-[70ch] text-md text-muted">{subtitleOf(status, current.slug)}</p>
        </header>
        {current.slug === 'business' || current.slug === 'business-details' ? <PrefillBanner status={status} /> : null}
        <Step key={`${slug}-${status.orgId}`} status={status} ref={step} advance={() => void goNext()} />
        <div className="flex flex-wrap items-center gap-x-5 gap-y-3 pt-2 max-sm:flex-col max-sm:items-stretch">
          {editable ? (
            <>
              {stripePending ? (
                // Stripe's form holds the step's one primary action; this is only a quiet fallback.
                <button type="button" onClick={() => void saveAndContinue()} disabled={busy !== null} className={QUIET}>
                  {primaryLabel}
                </button>
              ) : (
                <Button onClick={() => void saveAndContinue()} disabled={busy !== null} className="sm:min-w-56">
                  {primaryLabel}
                </Button>
              )}
              <button type="button" onClick={() => void saveAndSignOut()} disabled={busy !== null} className={QUIET}>
                {busy === 'exit' ? 'Saving…' : 'Save & sign out'}
              </button>
              <span className="text-xs text-muted max-sm:text-center sm:ml-auto" aria-live="polite">
                {savedAt ? 'Saved' : 'Progress saves to your account'}
              </span>
            </>
          ) : (
            <>
              <Button variant="neutral" onClick={() => router.push('/onboarding')}>
                Application status
              </Button>
              <span className="text-xs text-muted">Submitted — answers can’t be changed now</span>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
