'use client';

import { type GrantType, type StaffBillingGrant, type StaffGrantQuery, staffPlanQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Select } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { dateTime, humanize } from '@/lib/format';

import { plansApi, STATUS_TONE, type T } from './kit';

const TYPES: GrantType[] = ['FREE_DAYS', 'FREE_MONTHS', 'CREDIT', 'PERCENT_OFF', 'WAIVE_INVOICE', 'TRIAL_END', 'CHARGE_GATE'];

export const grantColumns = (t: T, withOrg = true): ColumnDef<StaffBillingGrant>[] => [
  { id: 'createdAt', accessorFn: (g) => g.createdAt, header: t('st.plans.col.date'), size: 160, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
  ...(withOrg ? [{ id: 'orgId', accessorFn: (g: StaffBillingGrant) => g.orgId, header: t('st.plans.col.org'), size: 180, meta: { kind: 'mono' as const } }] : []),
  { id: 'summary', accessorFn: (g) => g.summary, header: t('st.plans.col.grant'), size: 180, meta: { kind: 'strong' } },
  { id: 'source', accessorFn: (g) => g.source, header: t('st.plans.col.source'), size: 110, cell: ({ row }) => t(`st.plans.source.${row.original.source}`) },
  { id: 'reason', accessorFn: (g) => g.reasonCode ?? g.metric ?? '', header: t('st.plans.col.why'), size: 220,
    cell: ({ row }) => [row.original.reasonCode ? humanize(row.original.reasonCode) : row.original.metric ? humanize(row.original.metric) : '', row.original.note].filter(Boolean).join(' · ') },
  { id: 'actorId', accessorFn: (g) => g.actorId, header: t('st.plans.col.by'), size: 140 },
  { id: 'status', accessorFn: (g) => g.status, header: t('st.plans.col.status'), size: 110, meta: { kind: 'status' },
    cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status] ?? 'info'}>{humanize(row.original.status)}</StatusPill> },
  { id: 'effect', accessorFn: (g) => g.reference ?? g.invoiceId ?? g.newTrialEnd ?? '', header: t('st.plans.col.effect'), size: 200, meta: { kind: 'mono' },
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.lastError ?? row.original.reference ?? row.original.invoiceId ?? (row.original.newTrialEnd ? t('st.plans.chargesFrom', { date: dateTime(row.original.newTrialEnd) }) : '')}</span> }
];

/** Grants log: every reward and adjustment, filtered and paged by the seller service. */
export function GrantsPanel() {
  const t = useT();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'grants', defaultSort: [{ id: 'createdAt', desc: true }] });
  const [source, setSource] = useState<'' | 'RULE' | 'MANUAL'>('');
  const [type, setType] = useState<'' | GrantType>('');
  const query: StaffGrantQuery = { q: state.q || undefined, source: source || undefined, type: type || undefined, page: state.pagination.pageIndex, size: state.pagination.pageSize };
  const grants = useQuery(staffPlanQueries.grants(plansApi(), query));
  const columns = useMemo(() => grantColumns(t), [t]);
  return (
    <DataTable<StaffBillingGrant> id="staff.plans.grants" label={t('st.plans.tab.grants')} columns={columns} data={grants.data?.items ?? []} getRowId={(g) => g.id} rowLabel={(g) => g.summary}
      state={state} mode="server" paging="pages" total={grants.data?.total ?? null} loading={grants.isPending} fetching={grants.isFetching}
      error={grants.isError ? <ErrorState error={grants.error} onRetry={() => void grants.refetch()} /> : undefined} searchPlaceholder={t('st.plans.searchGrants')} empty={t('st.plans.noGrants')}
      toolbar={
        <div className="flex flex-wrap gap-2">
          <Select id="grants-source" label={t('st.plans.col.source')} value={source} onChange={(e) => { setSource(e.currentTarget.value as '' | 'RULE' | 'MANUAL'); state.resetPaging(); }}
            options={[{ value: '', label: t('st.plans.all') }, { value: 'RULE', label: t('st.plans.source.RULE') }, { value: 'MANUAL', label: t('st.plans.source.MANUAL') }]} />
          <Select id="grants-type" label={t('st.plans.col.grant')} value={type} onChange={(e) => { setType(e.currentTarget.value as '' | GrantType); state.resetPaging(); }}
            options={[{ value: '', label: t('st.plans.all') }, ...TYPES.map((x) => ({ value: x, label: t(`st.plans.grantType.${x}`) }))]} />
        </div>
      } />
  );
}
