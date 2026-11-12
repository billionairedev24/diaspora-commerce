'use client';

import { TourHub } from '@afrotransact/tour/react';
import { useCallback, useMemo } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { type MessageKey, useT } from '@/i18n/t';
import { hubLabels } from '@/tours/subject';

/** The Tour hub page (Help → Tour hub): every chapter the person may take, by journey, with progress, resume, replay and skip. */
export function TourHubView() {
  const t = useT();
  const labels = useMemo(() => hubLabels(t), [t]);
  const translate = useCallback((key: string) => t(key as MessageKey), [t]);
  return (
    <>
      <PageHeader tour="page-tour-hub" title={t('st.tour.hub.pageTitle')} description={t('st.tour.hub.description')} />
      <TourHub translate={translate} labels={labels} />
    </>
  );
}
