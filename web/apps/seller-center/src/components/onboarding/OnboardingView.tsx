'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { CheckCircle, CircleDashed, HourglassMedium, WarningCircle, XCircle } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';

const STATE = {
  DONE: { Icon: CheckCircle, cls: 'text-success', text: 'Complete' },
  TODO: { Icon: CircleDashed, cls: 'text-subtle', text: 'To do' },
  ACTION: { Icon: WarningCircle, cls: 'text-warning', text: 'Action needed' },
  WAITING: { Icon: HourglassMedium, cls: 'text-info', text: 'In review · SLA 2 business days' },
  BLOCKED: { Icon: XCircle, cls: 'text-danger', text: 'Not approved' }
} as const;

/** Onboarding: the only page before approval — application checklist, requested items and resume link. */
export function OnboardingView() {
  const storefrontUrl = useStorefrontUrl();
  const { orgId, isProvider } = useSeller();
  const q = useQuery(sellerQueries.onboarding(sellerApi(), orgId));
  return (
    <ModulePage module="onboarding" readOnlyNote={false}>
      <Notice tone="warning" title={`${isProvider ? 'Provider Hub' : 'Seller Center'} is locked.`} icon="shield">
        Finish onboarding and wait for approval. Only onboarding is open to your account.
      </Notice>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <section className="flex flex-col gap-4 rounded-md border border-border p-6">
          <div className="flex flex-wrap items-center gap-3">
            <strong className="flex-1 text-lg">Application {q.data.reference ?? ''}</strong>
            {q.data.canResume ? (
              <a className="rounded-pill bg-primary px-5 py-2 text-sm font-bold text-on-primary no-underline hover:bg-primary-hover hover:text-on-primary hover:no-underline" href={`${storefrontUrl}/sell?resume=1`}>
                {q.data.status === 'DRAFT' ? 'Continue application' : 'Upload requested document'}
              </a>
            ) : null}
          </div>
          <ol className="m-0 flex list-none flex-col gap-2 p-0">
            {q.data.steps.map((s) => {
              const st = STATE[s.state];
              return (
                <li key={s.key} className="flex items-center gap-3">
                  <st.Icon size={22} weight="fill" aria-hidden className={st.cls} />
                  <span className="flex-1">{s.label}</span>
                  <span className={cx('text-xs', st.cls)}>{st.text}</span>
                </li>
              );
            })}
          </ol>
          {q.data.requestedItems.length ? (
            <Notice tone="warning" title="We need:">{q.data.requestedItems.join(', ')}</Notice>
          ) : null}
        </section>
      ) : null}
    </ModulePage>
  );
}
