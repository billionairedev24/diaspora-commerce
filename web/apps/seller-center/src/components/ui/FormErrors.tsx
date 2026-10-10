'use client';

import { ErrorSummary } from '@afrotransact/ui';

import { useT } from '../../i18n/t';

/** Error summary at the top of a form (S194, WCAG 3.3.1): lists field errors and links each to its field. */
export function FormErrors({ errors, ids, submitCount }: { errors: Partial<Record<string, string | null | undefined>>; ids: Record<string, string>; submitCount?: number }) {
  const t = useT();
  const list = Object.keys(ids)
    .filter((k) => errors[k])
    .map((k) => ({ fieldId: ids[k] ?? k, message: errors[k] ?? '' }));
  return <ErrorSummary title={t('sc.form.errorSummary', { n: list.length })} errors={list} submitCount={submitCount} />;
}
