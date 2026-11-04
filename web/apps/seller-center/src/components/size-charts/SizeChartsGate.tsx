'use client';

import { type ReactNode } from 'react';

import { RequestDepartmentLink } from '@/components/listings/RequestDepartment';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { useSellerScope } from '@/lib/seller-scope';
import { useSeller } from '@/lib/session';

/**
 * Size charts only make sense for a store that sells sized goods (clothing, shoes). When none of the organisation's
 * departments does, a direct link to the page explains why in plain words instead of showing an empty editor; the
 * server refuses size charts outside the departments in any case.
 */
export function SizeChartsGate({ children }: { children: ReactNode }) {
  const t = useT();
  const { isProvider } = useSeller();
  const scope = useSellerScope();
  if (isProvider) return children;
  if (scope.isPending) return <Spinner label={t('sc.scope.loading')} />;
  // Fails closed: when the scope cannot be read the page does not open (same rule as the sidebar and the listings button).
  if (scope.exempt || scope.hasSizing) return children;
  return (
    <>
      <PageHeader title={t('sc.sz.title')} />
      <EmptyState title={t('sc.scope.noSizingTitle')} action={<RequestDepartmentLink className="w-fit cursor-pointer border-0 bg-transparent p-0 text-sm underline" />}>
        {t('sc.scope.noSizingBody')}
      </EmptyState>
    </>
  );
}

