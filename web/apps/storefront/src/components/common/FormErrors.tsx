'use client';

import { ErrorSummary } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

/**
 * Error summary for a storefront form (S194, WCAG 3.3.1): lists the field errors at the top and links each one to
 * its field. `ids` maps error keys to field ids; keys not in it (form-level errors) are shown by the form itself.
 */
export function FormErrors({ errors, ids, submitCount }: { errors: Partial<Record<string, string | null | undefined>>; ids: Record<string, string>; submitCount?: number }) {
  const t = useT();
  const list = Object.keys(ids)
    .filter((k) => errors[k])
    .map((k) => ({ fieldId: ids[k] ?? k, message: errors[k] ?? '' }));
  return <ErrorSummary title={t('auth.errorSummary', { n: list.length })} errors={list} submitCount={submitCount} />;
}
