'use client';

import { ACCESS_PORTALS, type AccessPortal, type AccessRole, staffAccessQueries } from '@afrotransact/api-client';
import { CellText, DataTable, useTableState } from '@afrotransact/data-table';
import { Select, Toolbar } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { RoleDetail } from './RoleDetail';
import { useAccessNav } from './useAccessNav';

const PORTAL_KEY: Record<AccessPortal, MessageKey> = {
  platform: 'st.acc.portal.platform',
  seller: 'st.acc.portal.seller',
  provider: 'st.acc.portal.provider',
  account: 'st.acc.portal.account'
};

/** The roles of one portal (platform staff, seller, provider, account) with how many permissions each holds; open one for its detail. */
export function RolesPanel() {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const portal: AccessPortal = (ACCESS_PORTALS as readonly string[]).includes(nav.portal ?? '') ? (nav.portal as AccessPortal) : 'platform';
  const q = useQuery(staffAccessQueries.roles(api.access, portal));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'roles', defaultPageSize: 25 });
  const columns = useMemo<ColumnDef<AccessRole>[]>(
    () => [
      { id: 'name', accessorFn: (r) => r.name, header: t('st.acc.roles.col.role'), size: 220, meta: { kind: 'strong', label: t('st.acc.roles.col.role') }, cell: ({ row }) => <strong className="font-semibold">{row.original.name}</strong> },
      { id: 'description', accessorFn: (r) => r.description, header: t('st.acc.roles.col.description'), size: 420, cell: ({ row }) => <CellText>{row.original.description || '—'}</CellText> },
      { id: 'permissions', accessorFn: (r) => r.permissionCount, header: t('st.acc.roles.col.permissions'), size: 120, meta: { kind: 'number', align: 'end', label: t('st.acc.roles.col.permissions') } }
    ],
    [t]
  );
  const open = q.data?.items.find((r) => r.key === nav.role);
  if (nav.role && q.data && open) return <RoleDetail role={open} canAdmin={q.data.canAdmin} />;
  return (
    <div className="flex flex-col gap-3">
      <Toolbar>
        <Select
          label={t('st.acc.portal.label')}
          value={portal}
          onChange={(e) => nav.go({ portal: e.currentTarget.value === 'platform' ? null : e.currentTarget.value, role: null })}
          options={ACCESS_PORTALS.map((p) => ({ value: p, label: t(PORTAL_KEY[p]) }))}
        />
      </Toolbar>
      <p className="m-0 text-sm text-muted">{t(`st.acc.portal.${portal}.help` as MessageKey)}</p>
      {q.isPending ? (
        <Spinner />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <DataTable<AccessRole>
          id="staff.access.roles"
          label={t('st.acc.roles.table')}
          columns={columns}
          data={q.data.items}
          getRowId={(r) => r.key}
          rowLabel={(r) => r.name}
          state={state}
          mode="client"
          fetching={q.isFetching}
          enableSelection={false}
          searchPlaceholder={t('st.acc.roles.search')}
          empty={t('st.acc.roles.none')}
          onRowClick={(r) => nav.go({ role: r.key })}
        />
      )}
    </div>
  );
}
