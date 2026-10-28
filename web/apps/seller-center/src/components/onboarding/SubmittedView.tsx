'use client';

import { type OnboardingStatus } from '@afrotransact/api-client';
import { Button, cx } from '@afrotransact/ui';
import { CheckCircle, CircleDashed, HourglassMedium } from '@phosphor-icons/react/ssr';
import Link from 'next/link';

import { Notice } from '@/components/ui/Notice';
import { onboardingHref } from '@/lib/onboarding-wizard';

type Phase = 'done' | 'active' | 'todo';

const date = (iso: string | null | undefined): string =>
  iso
    ? new Date(iso).toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
        hour: 'numeric',
        minute: '2-digit'
      })
    : '';

const ACTIONS: Record<string, string> = {
  SUBMITTED: 'Application submitted',
  RESUBMITTED: 'Application resubmitted',
  REVIEW_APPROVED: 'Reviewed — sent for final approval',
  FLAGGED: 'Flagged for a closer look',
  REQUEST_INFO: 'More information requested',
  FINAL_APPROVE: 'Approved',
  SEND_BACK: 'Sent back to review',
  REJECT: 'Not approved',
  STARTED: 'Application started'
};

/** The reason the applicant was given when the application was declined. */
function rejectionNote(status: OnboardingStatus): string | null {
  const last = [...status.timeline].reverse().find((t) => t.action === 'REJECT');
  return last?.note?.trim() ? last.note.trim() : null;
}

/** What happens after submitting, and where the application is now (status tracking). */
export function SubmittedView({ status, onRefresh, refreshing }: { status: OnboardingStatus; onRefresh: () => void; refreshing: boolean }) {
  const portal = status.kind === 'PROVIDER' ? 'Provider Hub' : 'Seller Center';
  const s = status.status;
  if (s === 'REJECTED') {
    return (
      <Notice tone="danger" title="Your application wasn’t approved." role="alert">
        {rejectionNote(status) ? <span className="block">{rejectionNote(status)}</span> : null}
        <span className="block">We’ve emailed you the reason. Contact AfroTransact support if you think this is a mistake.</span>
      </Notice>
    );
  }
  const finalStage = s === 'FLAGGED' || s === 'AWAITING_FINAL_APPROVAL';
  const verified = status.stripe.status === 'VERIFIED' || status.stripe.status === 'PENDING';
  const stages: { title: string; note: string; phase: Phase }[] = [
    { title: 'Submitted', note: date(status.submittedAt), phase: 'done' },
    {
      title: 'Automated checks',
      note: 'Stripe identity (KYC), IRS name & TIN match, sanctions screening',
      phase: verified ? 'done' : 'active'
    },
    {
      title: 'AfroTransact review',
      note: 'Our trust team checks your documents and permits, usually within 2 business days',
      phase: finalStage ? 'done' : 'active'
    },
    {
      title: 'Final approval',
      note: 'A second reviewer approves new sellers',
      phase: finalStage ? 'active' : 'todo'
    },
    {
      title: `${portal} unlocks`,
      note: 'We email you, and your back office opens with your plan’s free trial',
      phase: 'todo'
    }
  ];
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 className="m-0 text-2xl font-bold">Submitted — what happens next</h2>
        <p className="m-0 text-muted">
          Thanks! Application {status.reference} is with our team. You’ll get an email at each step; you can sign out and come back any time.
        </p>
      </div>
      <ol aria-label="Application status" className="m-0 flex list-none flex-col p-0">
        {stages.map((st, i) => (
          <li key={st.title} className="flex gap-3">
            <div className="flex flex-col items-center">
              {st.phase === 'done' ? (
                <CheckCircle size={26} weight="fill" className="flex-none text-success" aria-hidden />
              ) : st.phase === 'active' ? (
                <HourglassMedium size={26} weight="fill" className="flex-none text-info" aria-hidden />
              ) : (
                <CircleDashed size={26} className="flex-none text-subtle" aria-hidden />
              )}
              {i < stages.length - 1 ? <span aria-hidden className={cx('w-px flex-1', st.phase === 'done' ? 'bg-success' : 'bg-border')} /> : null}
            </div>
            <div className="flex flex-col pb-4">
              <strong className="text-sm">
                {st.title}
                <span className="sr-only">: {st.phase === 'done' ? 'complete' : st.phase === 'active' ? 'in progress' : 'to come'}</span>
              </strong>
              <span className="text-xs text-muted">{st.note}</span>
            </div>
          </li>
        ))}
      </ol>
      {status.timeline.length ? (
        <section aria-label="History" className="flex flex-col gap-1 pt-2">
          <strong className="text-sm">History</strong>
          <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
            {[...status.timeline].reverse().map((t, i) => (
              <li key={`${t.occurredAt}-${i}`} className="flex flex-wrap gap-x-3">
                <span className="text-muted">{date(t.occurredAt)}</span>
                <span>{ACTIONS[t.action] ?? t.action}</span>
                {t.note && t.action !== 'STARTED' ? <span className="text-muted">· {t.note}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="neutral" size="sm" onClick={onRefresh} disabled={refreshing}>
          {refreshing ? 'Checking…' : 'Check status'}
        </Button>
        <Link href={onboardingHref('review')} className="text-sm">
          View your answers
        </Link>
      </div>
    </div>
  );
}
