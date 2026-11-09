'use client';

import { staffQueries } from '@afrotransact/api-client';
import { HelpStrip, type LinkLike, ModuleSummaries, PeriodSelect, usePeriod } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ChartCard } from './ChartCard';

/** Quick actions open filtered lists of this console: they go through the router, not a page load. */
const AppLink: LinkLike = ({ href, children, ...rest }) => (
  <Link href={href} {...rest}>
    {children}
  </Link>
);

/**
 * Platform home: one section per module the staff member's permissions open (orders, applications, payments, trust and
 * safety, support, catalog, platform health) with a headline figure, a compact chart and quick actions that open the
 * filtered list, then the exportable reports (PDF, Excel, CSV) and a help strip.
 */
export function DashboardView() {
  const t = useT();
  const [range, setRange] = usePeriod('at.st.home.period');
  const q = useQuery(staffQueries.dashboard(staffApi(), Number(range)));
  return (
    <ModulePage
      module="dash"
      actions={<PeriodSelect label={t('st.dashboardView.period')} value={range} onChange={setRange} labels={{ '7': t('st.dashboardView.days7'), '30': t('st.dashboardView.days30'), '90': t('st.dashboardView.days90') }} />}
    >
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {!q.isError ? (
        <ModuleSummaries modules={q.data?.modules ?? []} loading={q.isPending} onRetry={() => void q.refetch()} linkAs={AppLink} labels={{ retry: t('st.dashboardView.retry'), error: t('st.dashboardView.moduleError') }} />
      ) : null}
      {q.data ? (
        <>
          <section aria-labelledby="reports-h" className="flex flex-col gap-3">
            <h2 id="reports-h" className="m-0 text-lg font-semibold">
              {t('st.dashboardView.reports')}
            </h2>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,420px),1fr))] gap-4">
              {q.data.charts.map((c) => (
                <ChartCard key={c.id} spec={c} />
              ))}
            </div>
          </section>
          <p className="m-0 text-xs text-subtle">Figures from one dashboard feed · generated {dateTime(q.data.generatedAt)}</p>
        </>
      ) : null}
      <HelpStrip title={t('st.dashboardView.help.title')} description={t('st.dashboardView.help.description')} />
    </ModulePage>
  );
}
