'use client';

import { staffDepartmentQueries,type StaffDepartmentRequestRow } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Tabs, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState, NoAccess } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { useSheetUrl } from '@/lib/use-sheet-url';
import { shortDate } from '@/lib/format';
import { useStaff } from '@/lib/session';
import { useDebounced } from '@/lib/use-debounced';

import { DepartmentRequestSheet } from './DepartmentRequestSheet';
import { STATUS_LABEL, STATUS_TONE } from './labels';

type Tab = 'pending' | 'decided';

/**
 * Department requests: sellers asking to sell in another catalog department. Pending and Decided tabs over one
 * server-driven table (filters, sort and paging run on the BFF); a row opens the request in the shared Sheet.
 */
export function DepartmentRequestsView() {
  const { can, session } = useStaff();
  if (!can('deptRequests')) return <NoAccess roles={session.roleLabel || '—'} />;
  return <Loaded />;
}

function Loaded() {
  const t = useT();
  const api = staffApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab: Tab = params.get('tab') === 'decided' ? 'decided' : 'pending';
  const sheet = useSheetUrl('open');
  const openId = sheet.value;
  const count = useQuery(staffDepartmentQueries.requests(api.departments, { status: 'PENDING', size: 1 }));
  const go = (change: (p: URLSearchParams) => void) => {
    if (window.location.pathname !== pathname) return;
    const next = new URLSearchParams(window.location.search);
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  const pending = count.data?.pending;
  return (
    <ModulePage module="deptRequests">
      <Tabs
        label={t('st.depts.tabs')}
        value={tab}
        onValueChange={(v) =>
          go((p) => {
            p.set('tab', v);
            p.delete('open');
          })
        }
        items={[
          { value: 'pending', label: pending ? t('st.depts.tab.pendingCount', { count: String(pending) }) : t('st.depts.tab.pending'), content: <RequestsTable tab="pending" openId={openId} onOpen={(id) => (id ? sheet.open(id) : sheet.close())} /> },
          { value: 'decided', label: t('st.depts.tab.decided'), content: <RequestsTable tab="decided" openId={openId} onOpen={(id) => (id ? sheet.open(id) : sheet.close())} /> }
        ]}
      />
      <DepartmentRequestSheet id={openId} onClose={sheet.close} />
    </ModulePage>
  );
}

function RequestsTable({ tab, openId, onOpen }: { tab: Tab; openId: string | null; onOpen: (id: string | null) => void }) {
  const t = useT();
  const api = staffApi();
  const decided = tab === 'decided';
  const state = useTableState({ searchParams: useSearchParams(), prefix: tab, defaultSort: [{ id: decided ? 'decidedAt' : 'requestedAt', desc: decided }] });
  const [department, setDepartment] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const dept = useDebounced(department, 300);
  // Back to the first page when a filter changes (not on the first render).
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    state.resetPaging();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resetPaging is recreated each render; only the filters matter
  }, [dept, from, to]);
  const dateProblem = from && to && from > to ? t('st.depts.dateOrder') : null;
  const query = useQuery(
    staffDepartmentQueries.requests(api.departments, {
      status: decided ? 'DECIDED' : 'PENDING',
      organisation: state.q || undefined,
      department: dept || undefined,
      from: dateProblem ? undefined : from || undefined,
      to: dateProblem ? undefined : to || undefined,
      sort: state.sort,
      page: state.pagination.pageIndex + 1,
      size: state.pagination.pageSize
    })
  );
  const data = query.data;

  const columns = useMemo<ColumnDef<StaffDepartmentRequestRow>[]>(() => {
    const col = (id: string, label: MessageKey, extra: Partial<ColumnDef<StaffDepartmentRequestRow>> = {}, size = 170): ColumnDef<StaffDepartmentRequestRow> =>
      ({ id, header: t(label), size, meta: { label: t(label), kind: 'text' }, ...extra }) as ColumnDef<StaffDepartmentRequestRow>;
    return [
      col('organisation', 'st.depts.col.organisation', { accessorFn: (r) => r.orgName, cell: ({ row }) => <strong className="font-semibold">{row.original.orgName}</strong> }, 220),
      col('department', 'st.depts.col.department', { accessorFn: (r) => r.departmentName }),
      col('requestedBy', 'st.depts.col.requestedBy', { accessorFn: (r) => r.requestedByName, enableSorting: false }),
      col('requestedAt', 'st.depts.col.requestedAt', { accessorFn: (r) => r.requestedAt, cell: ({ row }) => shortDate(row.original.requestedAt) }, 130),
      col('status', 'st.depts.col.status', { accessorFn: (r) => r.status, cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status]}>{t(STATUS_LABEL[row.original.status])}</StatusPill> }, 120),
      ...(decided
        ? [
            col('decidedBy', 'st.depts.col.decidedBy', { accessorFn: (r) => r.decidedByName ?? '', enableSorting: false, cell: ({ row }) => row.original.decidedByName ?? '—' }),
            col('decidedAt', 'st.depts.col.decidedAt', { accessorFn: (r) => r.decidedAt ?? '', cell: ({ row }) => (row.original.decidedAt ? shortDate(row.original.decidedAt) : '—') }, 130)
          ]
        : [])
    ];
  }, [t, decided]);

  return (
    <div className="flex flex-col gap-3 pt-3">
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <TextField label={t('st.depts.filter.department')} value={department} onChange={(e) => setDepartment(e.currentTarget.value)} autoComplete="off" className="min-w-52" />
        <TextField label={t('st.depts.filter.from')} type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.currentTarget.value)} error={dateProblem} />
        <TextField label={t('st.depts.filter.to')} type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.currentTarget.value)} />
      </div>
      <DataTable<StaffDepartmentRequestRow>
        id={`staff.departments.${tab}`}
        label={t(decided ? 'st.depts.tab.decided' : 'st.depts.tab.pending')}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.id}
        rowLabel={(r) => `${r.orgName} – ${r.departmentName}`}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder={t('st.depts.searchOrganisation')}
        enableSelection={false}
        onRowClick={(r) => onOpen(r.id)}
        activeRowId={openId}
        empty={t(decided ? 'st.depts.empty.decided' : 'st.depts.empty.pending')}
        footer={data?.truncated ? <p className="m-0 text-xs text-subtle">{t('st.depts.truncated')}</p> : null}
      />
    </div>
  );
}
