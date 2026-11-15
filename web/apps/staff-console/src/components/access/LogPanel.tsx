'use client';

import { ACCESS_PORTALS, type AccessLogEntry, staffAccessQueries } from '@afrotransact/api-client';
import { CellText, DataTable, useTableState } from '@afrotransact/data-table';
import { Select, Toolbar } from '@afrotransact/ui';
import { useQueries, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { logSubject } from '@/lib/access';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { useAccessNav } from './useAccessNav';

/** The access change log: who changed what, to whom, when and why. Filter by role or by person; search finds anything on the page. */
export function LogPanel() {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const role = useSearchParams().get('logRole') ?? '';
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'log', defaultPageSize: 25 });
  const roleLists = useQueries({ queries: ACCESS_PORTALS.map((p) => staffAccessQueries.roles(api.access, p)) });
  const roleOptions = useMemo(() => roleLists.flatMap((r) => r.data?.items ?? []).map((r) => ({ value: r.key, label: r.name })), [roleLists]);
  const who = useQuery({ ...staffAccessQueries.person(api.access, nav.log ?? ''), enabled: Boolean(nav.log) });
  const q = useQuery(staffAccessQueries.log(api.access, { userId: nav.log ?? undefined, role: role || undefined, size: 100 }));
  const columns = useMemo<ColumnDef<AccessLogEntry>[]>(
    () => [
      { id: 'at', accessorFn: (e) => e.at, header: t('st.acc.log.col.when'), size: 170, meta: { kind: 'datetime', label: t('st.acc.log.col.when') }, cell: ({ row }) => dateTime(row.original.at) },
      { id: 'actor', accessorFn: (e) => e.actorName, header: t('st.acc.log.col.who'), size: 170 },
      { id: 'change', accessorFn: (e) => `${e.actionLabel} ${logSubject(e)}`, header: t('st.acc.log.col.change'), size: 280, cell: ({ row }) => <CellText>{`${row.original.actionLabel}${logSubject(row.original) ? `: ${logSubject(row.original)}` : ''}`}</CellText> },
      { id: 'person', accessorFn: (e) => e.targetName || e.roleName, header: t('st.acc.log.col.person'), size: 170, cell: ({ row }) => row.original.targetName || row.original.roleName || '—' },
      { id: 'reason', accessorFn: (e) => e.detail, header: t('st.acc.log.col.reason'), size: 280, cell: ({ row }) => <CellText>{row.original.detail || '—'}</CellText> }
    ],
    [t]
  );
  return (
    <div className="flex flex-col gap-3">
      <Toolbar>
        <Select label={t('st.acc.log.role')} value={role} onChange={(e) => nav.go({ logRole: e.currentTarget.value || null })} options={[{ value: '', label: t('st.acc.log.anyRole') }, ...roleOptions]} />
        {nav.log ? (
          <p className="m-0 flex items-center gap-2 text-sm" data-testid="log-person">
            {t('st.acc.log.forPerson', { name: who.data?.name ?? '…' })}
            <button type="button" onClick={() => nav.go({ log: null })} className="cursor-pointer border-0 bg-transparent p-0 text-link underline">
              {t('st.acc.log.clear')}
            </button>
          </p>
        ) : null}
      </Toolbar>
      <DataTable<AccessLogEntry>
        id="staff.access.log"
        label={t('st.acc.log.table')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(e) => e.id}
        rowLabel={(e) => `${e.actionLabel} ${logSubject(e)}`}
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        partial={q.data?.partial}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        enableSelection={false}
        searchPlaceholder={t('st.acc.log.search')}
        empty={t('st.acc.log.none')}
      />
    </div>
  );
}
