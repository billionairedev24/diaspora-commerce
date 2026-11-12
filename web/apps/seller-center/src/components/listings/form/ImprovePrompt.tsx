'use client';

import { matchingQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { taskDeadline, taskReasonText, taskSummary } from './ImageTaskReasons';

/** On a live listing's photos: "Improve your image" with what is wrong in plain words and how long the grace period lasts. Nothing for a listing without a task. */
export function ImprovePrompt({ productId }: { productId: string }) {
  const t = useT();
  const { orgId } = useSeller();
  const tasks = useQuery(matchingQueries.imageTasks(sellerApi().matching, orgId));
  const task = tasks.data?.items.find((x) => x.productId === productId);
  if (!task) return null;
  const deadline = taskDeadline(t, task);
  return (
    <Notice tone={task.state === 'FAIL' ? 'warning' : 'info'} title={t('sc.pf.images.improve')}>
      <span className="block">{taskSummary(t, task)}</span>
      <ul className="m-0 mt-1 list-disc ps-5">
        {task.reasons.map((r) => (
          <li key={r.code}>{taskReasonText(t, r)}</li>
        ))}
      </ul>
      {deadline ? <span className="mt-1 block">{deadline}</span> : null}
    </Notice>
  );
}
