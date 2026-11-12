'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { HelpStrip, type LinkLike, ModuleSummaries, PeriodSelect, usePeriod } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { moduleCopy } from '@/lib/modules';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';

import { ChartCard } from './ChartCard';

/** Quick actions and empty-state buttons are routes of this app: they go through the router, not a page load. */
const AppLink: LinkLike = ({ href, children, ...rest }) => (
  <Link href={href} {...rest}>
    {children}
  </Link>
);

/**
 * Home: one section per module the member can open (sales, orders, listings, payouts, inventory, reviews, account health,
 * promotions; for providers bookings, revenue, availability, payouts) with a headline figure, a compact chart and
 * three quick actions, then the exportable reports (PDF / Excel / CSV) and a help strip.
 */
export function DashboardView() {
  const t = useT();
  const { orgId, isProvider, session } = useSeller();
  const storefront = useStorefrontUrl();
  const [range, setRange] = usePeriod('at.sc.home.period');
  const q = useQuery(sellerQueries.dashboard(sellerApi(), orgId, Number(range)));
  const copy = moduleCopy('home', { provider: isProvider, orgName: session.activeOrg?.name ?? '' }, t);
  return (
    <>
      <PageHeader
        tour="page-home"
        title={copy.title}
        description={copy.description}
        actions={<PeriodSelect label={t('sc.dashboardView.period')} value={range} onChange={setRange} labels={{ '7': t('sc.dashboardView.days7'), '30': t('sc.dashboardView.days30'), '90': t('sc.dashboardView.days90') }} />}
      />
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {!q.isError ? (
        <ModuleSummaries modules={q.data?.modules ?? []} loading={q.isPending} onRetry={() => void q.refetch()} linkAs={AppLink} labels={{ retry: t('sc.dashboardView.retry'), error: t('sc.dashboardView.moduleError') }} />
      ) : null}
      {q.data && q.data.charts.length ? (
        <section aria-labelledby="reports-h" className="flex flex-col gap-3">
          <h2 id="reports-h" className="m-0 text-lg font-semibold">
            {t('sc.dashboardView.reports')}
          </h2>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,420px),1fr))] gap-4">
            {q.data.charts.map((c) => (
              <ChartCard key={c.id} spec={c} />
            ))}
          </div>
        </section>
      ) : null}
      <HelpStrip
        title={t('sc.dashboardView.help.title')}
        description={t('sc.dashboardView.help.description')}
        links={[{ label: t('sc.dashboardView.help.centre'), href: `${storefront}/help` }]}
      />
    </>
  );
}
