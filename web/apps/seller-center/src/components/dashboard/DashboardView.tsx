'use client';

import { type ModuleKey, sellerQueries } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { FilterChips } from '@/components/ui/DataTable';
import { KpiTile } from '@/components/ui/Kpi';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { moduleCopy } from '@/lib/modules';
import { hrefOf } from '@/lib/routes';
import { useSeller } from '@/lib/session';

import { ChartCard } from './ChartCard';

const TODO_DOT: Record<string, string> = { info: 'bg-info', warning: 'bg-warning', success: 'bg-success', danger: 'bg-danger' };

/** Home: KPIs, to-dos and the four dashboard charts (seller or provider) with PDF / Excel / CSV export. */
export function DashboardView() {
  const { orgId, isProvider, session, can } = useSeller();
  const [range, setRange] = useState<'7' | '30' | '90'>('30');
  const q = useQuery(sellerQueries.dashboard(sellerApi(), orgId, Number(range)));
  const copy = moduleCopy('home', { provider: isProvider, orgName: session.activeOrg?.name ?? '' });
  return (
    <>
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          <FilterChips
            label="Period"
            value={range}
            onChange={(v) => setRange(v === '' ? '30' : v)}
            options={[
              { value: '7', label: '7 days' },
              { value: '30', label: '30 days' },
              { value: '90', label: '90 days' }
            ]}
          />
        }
      />
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data ? (
        <>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
            {q.data.kpis.map((k) => (
              <KpiTile key={k.label} kpi={k} />
            ))}
          </div>
          {q.data.todo.length ? (
            <section aria-labelledby="todo-h" className="rounded-md border border-border p-4">
              <h2 id="todo-h" className="m-0 mb-2 text-md font-semibold">
                To do
              </h2>
              <ul className="m-0 flex list-none flex-col gap-1 p-0">
                {q.data.todo.map((t) => (
                  <li key={t.label} className="flex items-center gap-3 text-sm">
                    <span aria-hidden className={cx('size-2 flex-none rounded-pill', TODO_DOT[t.tone])} />
                    {can(t.module as ModuleKey, 'VIEW') ? <Link href={hrefOf(t.module)}>{t.label}</Link> : <span>{t.label}</span>}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,420px),1fr))] gap-4">
            {q.data.charts.map((c) => (
              <ChartCard key={c.id} spec={c} />
            ))}
          </div>
        </>
      ) : null}
    </>
  );
}
