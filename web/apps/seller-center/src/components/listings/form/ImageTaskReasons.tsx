import { type ImageTask } from '@afrotransact/api-client';

import { type MessageKey, type Translator } from '@/i18n/t';

import { MAIN_FILL_PERCENT, MIN_IMAGE_PX } from '../form-model';

/** The reasons of an Improve-your-image task in plain words: translated by code (the English message is the fallback for a code we do not know). */
export function taskReasonText(t: Translator<MessageKey>, reason: ImageTask['reasons'][number]): string {
  const key = `sc.img.check.${reason.code}` as MessageKey;
  const text = t(key, { px: MIN_IMAGE_PX, min: 500, fill: MAIN_FILL_PERCENT, w: 0, h: 0, mb: 8 });
  // The catalogue answers the key itself for a code it does not have.
  return text === key || !text ? reason.message : text;
}

/** One task's summary line: whether the photo works and could sell better, or does not meet the rules yet. */
export function taskSummary(t: Translator<MessageKey>, task: ImageTask): string {
  return t(task.state === 'FAIL' ? 'sc.img.task.summaryFail' : 'sc.img.task.summaryImprove');
}

/** The grace period, or that the listing is limited already. */
export function taskDeadline(t: Translator<MessageKey>, task: ImageTask): string | null {
  if (task.hidden) return t('sc.img.task.hidden');
  return task.state === 'FAIL' && typeof task.daysLeft === 'number' ? t('sc.img.task.daysLeft', { n: Math.max(0, task.daysLeft) }) : null;
}
