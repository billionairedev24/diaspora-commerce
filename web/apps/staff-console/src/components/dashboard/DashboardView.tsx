'use client';

import { staffQueries } from '@afrotransact/api-client';
import { ChipGroup } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { KpiTile } from '@/components/ui/Kpi';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { ChartCard } from './ChartCard';

/** Platform dashboard: KPIs and four charts from one dashboard feed, each exportable as PDF, Excel or CSV. */
export function DashboardView() {
  const t = useT();
  const [range, setRange] = useState<'7' | '30' | '90'>('30');
  const q = useQuery(staffQueries.dashboard(staffApi(), Number(range)));
  return (
    <ModulePage
      module="dash"
      actions={
        <ChipGroup
          label={t('st.dashboardView.period')}
          value={range}
          onChange={setRange}
          options={[
            { value: '7', label: '7 days' },
            { value: '30', label: '30 days' },
            { value: '90', label: '90 days' }
          ]}
        />
      }
    >
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
            {q.data.kpis.map((k) => (
              <KpiTile key={k.label} kpi={k} />
            ))}
          </div>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,420px),1fr))] gap-4">
            {q.data.charts.map((c) => (
              <ChartCard key={c.id} spec={c} />
            ))}
          </div>
          <p className="m-0 text-xs text-subtle">Figures from one dashboard feed · generated {dateTime(q.data.generatedAt)}</p>
        </>
      ) : null}
    </ModulePage>
  );
}
