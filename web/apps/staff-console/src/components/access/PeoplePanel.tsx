'use client';

import { type AccessPerson, staffAccessQueries } from '@afrotransact/api-client';
import { CellText, DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { PersonDetail } from './PersonDetail';
import { useAccessNav } from './useAccessNav';

/** Find a person by name or email; open them to see what they can do, grant or take away a direct permission, and their changes. */
export function PeoplePanel() {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'people', defaultPageSize: 25 });
  const q = useQuery(staffAccessQueries.people(api.access, state.q, state.pagination.pageIndex, state.pagination.pageSize));
  const columns = useMemo<ColumnDef<AccessPerson>[]>(
    () => [
      { id: 'name', accessorFn: (p) => p.name, header: t('st.acc.people.col.name'), size: 220, meta: { kind: 'strong', label: t('st.acc.people.col.name') }, cell: ({ row }) => <strong className="font-semibold">{row.original.name}</strong> },
      { id: 'email', accessorFn: (p) => p.email, header: t('st.acc.people.col.email'), size: 260, cell: ({ row }) => <CellText>{row.original.email}</CellText> },
      { id: 'roles', accessorFn: (p) => p.roles.map((r) => r.name).join(', '), header: t('st.acc.people.col.roles'), size: 260, cell: ({ row }) => <CellText>{row.original.roles.map((r) => r.name).join(', ') || '—'}</CellText> },
      { id: 'direct', accessorFn: (p) => p.directGrants.length, header: t('st.acc.people.col.direct'), size: 120, meta: { kind: 'number', align: 'end', label: t('st.acc.people.col.direct') } },
      { id: 'status', accessorFn: (p) => (p.enabled ? t('st.acc.people.active') : t('st.acc.people.disabled')), header: t('st.acc.people.col.status'), size: 110 }
    ],
    [t]
  );
  if (nav.person) return <PersonDetail id={nav.person} />;
  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 text-sm text-muted">{t('st.acc.people.intro')}</p>
      <DataTable<AccessPerson>
        id="staff.access.people"
        label={t('st.acc.people.table')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(p) => p.id}
        rowLabel={(p) => p.name}
        state={state}
        mode="server"
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        enableSelection={false}
        searchPlaceholder={t('st.acc.people.search')}
        empty={t('st.acc.people.none')}
        onRowClick={(p) => nav.go({ person: p.id }, false)}
      />
    </div>
  );
}
