'use client';

import { type OnboardingStatus } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { Check, LockSimple, WarningCircle } from '@phosphor-icons/react/ssr';
import Link from 'next/link';

import { onboardingHref, progressOf, type StepView, stepView } from '@/lib/onboarding-wizard';

const SR: Record<StepView, string> = {
  done: 'complete',
  current: 'current step',
  todo: 'to do',
  blocked: 'locked until the earlier steps are complete',
  action: 'action needed'
};

function Dot({ view, n }: { view: StepView; n: number }) {
  return (
    <span
      aria-hidden
      className={cx(
        'grid size-6 flex-none place-items-center rounded-pill text-2xs font-bold',
        view === 'current' && 'bg-ink-1000 text-surface',
        view === 'done' && 'bg-success text-surface',
        view === 'action' && 'bg-warning text-surface',
        (view === 'todo' || view === 'blocked') && 'bg-surface-muted text-muted'
      )}
    >
      {view === 'done' ? (
        <Check size={13} weight="bold" />
      ) : view === 'action' ? (
        <WarningCircle size={14} weight="fill" />
      ) : view === 'blocked' ? (
        <LockSimple size={11} />
      ) : (
        n
      )}
    </span>
  );
}

/**
 * Progress at the top of the wizard: a compact horizontal stepper (name and state of every step; completed steps and
 * the step to resume at can be opened) that collapses to "Step 3 of 8 · Owners" with a progress bar on phones.
 */
export function Stepper({ status, current }: { status: OnboardingStatus; current: string }) {
  const p = progressOf(status, current);
  return (
    <nav aria-label="Application steps" className="flex flex-col gap-2">
      <ol className="m-0 hidden list-none gap-1 p-0 md:flex">
        {status.steps.map((s, i) => {
          const view = stepView(status, s, current);
          const body = (
            <>
              <Dot view={view} n={i + 1} />
              <span
                className={cx(
                  'min-w-0 text-xs leading-tight',
                  view === 'current' ? 'font-bold text-text' : view === 'blocked' ? 'text-subtle' : 'font-medium text-text'
                )}
              >
                {s.label}
              </span>
              <span className="sr-only">, {SR[view]}</span>
            </>
          );
          const cls = 'flex h-full min-w-0 items-center gap-2 rounded-sm px-2 py-1.5 no-underline hover:no-underline';
          return (
            <li key={s.slug} className="flex min-w-0 flex-1 items-center gap-1">
              {view === 'blocked' ? (
                <span className={cx(cls, 'cursor-not-allowed')} aria-disabled="true">
                  {body}
                </span>
              ) : (
                <Link
                  href={onboardingHref(s.slug)}
                  aria-current={view === 'current' ? 'step' : undefined}
                  className={cx(cls, view === 'current' ? 'bg-gold-50' : 'hover:bg-surface-hover')}
                >
                  {body}
                </Link>
              )}
              {i < status.steps.length - 1 ? <span aria-hidden className={cx('h-px min-w-2 flex-1', view === 'done' ? 'bg-success' : 'bg-border')} /> : null}
            </li>
          );
        })}
      </ol>
      <div className="flex flex-col gap-1.5 md:hidden">
        <p className="m-0 text-sm" aria-live="polite">
          <span className="font-semibold">
            Step {p.num} of {p.total}
          </span>{' '}
          <span className="text-muted">· {p.label}</span>
        </p>
      </div>
      <div
        className="h-1.5 overflow-hidden rounded-pill bg-surface-muted"
        role="progressbar"
        aria-label="Application progress"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={p.pct}
        aria-valuetext={`Step ${p.num} of ${p.total}: ${p.label}`}
      >
        <div className="h-full bg-primary" style={{ width: `${p.pct}%` }} />
      </div>
    </nav>
  );
}
