'use client';

import { matchingQueries } from '@afrotransact/api-client';
import { buttonClassName } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { taskDeadline, taskReasonText, taskSummary } from './form/ImageTaskReasons';

/** The most tasks listed before "and more": the full list is one click away on each listing. */
const SHOWN = 5;

/**
 * "Improve your image" for live listings whose main photo passes with a warning or breaks a photo rule (S392): a flat list of the listings,
 * failing ones first, each with the reasons in plain words, the end of the grace period and a link to replace the photo. Live listings stay
 * live. Nothing is shown when there are no tasks, and a failed request leaves the table as it is.
 */
export function ImageTasksBanner() {
  const t = useT();
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const tasks = useQuery(matchingQueries.imageTasks(sellerApi().matching, orgId));
  const items = tasks.data?.items ?? [];
  if (!items.length) return null;
  const failing = items.filter((x) => x.state === 'FAIL').length;
  return (
    <section aria-labelledby="image-tasks-h" className="flex flex-col gap-2" data-testid="image-tasks">
      <Notice tone={failing ? 'warning' : 'info'} title={<span id="image-tasks-h">{t('sc.img.task.title')}</span>}>
        {t('sc.img.task.home', { n: items.length })}
      </Notice>
      <ul className="m-0 flex list-none flex-col p-0">
        {items.slice(0, SHOWN).map((task) => {
          const deadline = taskDeadline(t, task);
          return (
            <li key={task.productId} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b border-border py-3" data-state={task.state}>
              <div className="flex min-w-0 flex-1 basis-72 flex-col gap-1">
                <strong className="text-sm">{task.title}</strong>
                <span className="text-sm text-muted">{taskSummary(t, task)}</span>
                <ul className="m-0 list-disc ps-5 text-sm">
                  {task.reasons.map((r) => (
                    <li key={r.code}>{taskReasonText(t, r)}</li>
                  ))}
                </ul>
                {deadline ? <span className="text-xs text-muted">{deadline}</span> : null}
              </div>
              {editable ? (
                <Link href={`/listings/${encodeURIComponent(task.productId)}/edit`} className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
                  {t('sc.img.task.action')}
                </Link>
              ) : null}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
