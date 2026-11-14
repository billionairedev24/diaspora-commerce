'use client';

import { type DeliveryRow, staffQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Select, Tabs } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';

import { DeliverySheet } from './DeliverySheet';
import { customerPlace, DELIVERY_TABS, STATUS_LABEL, STATUS_TONE, TAB_STATUSES, tabOf } from './model';

/** A name over its place: the two lines of a cell. */
function TwoLines({ primary, secondary }: { primary: string; secondary?: string }) {
  return (
    <span className="flex min-w-0 flex-col" title={[primary, secondary].filter(Boolean).join(' · ')}>
      <span className="truncate font-semibold">{primary}</span>
      {secondary ? <span className="truncate text-xs text-muted">{secondary}</span> : null}
    </span>
  );
}

const SORTABLE: Record<string, 'order' | 'seller' | 'city' | 'status' | 'updated' | 'promised'> = {
  orderNumber: 'order',
  sellerName: 'seller',
  deliverTo: 'city',
  status: 'status',
  updatedAt: 'updated',
  promisedBy: 'promised'
};

/**
 * Delivery: AfroTransact runs the orders it delivers end to end, without the driver app. A server-driven table (search, filters,
 * sort, page) of names and cities; a row opens the delivery with the steps its person may take. Seller-shipped orders and pickups
 * are never listed here.
 */
export function DeliveryView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = tabOf(params.get('tab'));
  const [open, setOpen] = useState<string | null>(null);
  const state = useTableState({ searchParams: params, prefix: 'delivery', defaultSort: [{ id: 'promisedBy', desc: false }] });
  const [assignee, setAssignee] = useState(params.get('assignee') ?? '');
  const [mode, setMode] = useState(params.get('mode') ?? '');
  const [city, setCity] = useState('');
  const sort = state.sorting[0];
  const api = staffApi();
  const query = useMemo(
    () => ({
      status: TAB_STATUSES[tab],
      mode: mode === 'AFROTRANSACT_DELIVERY' || mode === 'FULFILLED_BY_AFROTRANSACT' ? (mode as 'AFROTRANSACT_DELIVERY' | 'FULFILLED_BY_AFROTRANSACT') : undefined,
      q: state.q || undefined,
      assignee: assignee || undefined,
      city: city.trim() || undefined,
      sort: sort ? SORTABLE[sort.id] : undefined,
      desc: sort?.desc,
      page: state.pagination.pageIndex,
      size: state.pagination.pageSize
    }),
    [tab, mode, state.q, assignee, city, sort, state.pagination.pageIndex, state.pagination.pageSize]
  );
  const list = useQuery(staffQueries.deliveries(api, query));
  const data = list.data;
  const counts = data?.counts ?? {};
  const countOf = (statuses: readonly string[]) => statuses.reduce((n, s) => n + (counts[s] ?? 0), 0);

  const columns = useMemo<ColumnDef<DeliveryRow>[]>(
    () => [
      { id: 'orderNumber', accessorFn: (r) => r.orderNumber, header: t('st.dlv.col.order'), size: 150, meta: { kind: 'mono' }, cell: ({ row }) => <span className="font-semibold">{row.original.orderNumber}</span> },
      {
        id: 'sellerName',
        accessorFn: (r) => r.sellerName,
        header: t('st.dlv.col.collectFrom'),
        size: 210,
        cell: ({ row }) => <TwoLines primary={row.original.sellerName} secondary={row.original.originCity} />
      },
      {
        id: 'deliverTo',
        accessorFn: (r) => r.customerCity,
        header: t('st.dlv.col.deliverTo'),
        size: 210,
        cell: ({ row }) => <TwoLines primary={row.original.customerName} secondary={customerPlace(row.original)} />
      },
      { id: 'promisedBy', accessorFn: (r) => r.promisedBy, header: t('st.dlv.col.promisedBy'), size: 120, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.promisedBy) },
      {
        id: 'status',
        accessorFn: (r) => r.status,
        header: t('st.dlv.col.status'),
        size: 150,
        cell: ({ row }) => <StatusPill tone={STATUS_TONE[row.original.status]}>{t(STATUS_LABEL[row.original.status])}</StatusPill>
      },
      {
        id: 'assignee',
        accessorFn: (r) => r.assigneeName ?? '',
        header: t('st.dlv.col.assignedTo'),
        size: 160,
        enableSorting: false,
        cell: ({ row }) =>
          row.original.assigneeName ? (
            <span>{row.original.assigneeMe ? `${row.original.assigneeName} (${t('st.dlv.you')})` : row.original.assigneeName}</span>
          ) : (
            <span className="text-muted">{t('st.dlv.unassigned')}</span>
          )
      },
      {
        id: 'proof',
        accessorFn: (r) => r.proofAttached,
        header: t('st.dlv.col.proof'),
        size: 90,
        enableSorting: false,
        cell: ({ row }) => (row.original.proofAttached ? <span>{t('st.dlv.proof.yes')}</span> : <span className="text-muted">—</span>)
      }
    ],
    [t]
  );

  const setTab = (v: string) => {
    state.resetPaging();
    const p = new URLSearchParams(params.toString());
    p.set('tab', v);
    p.delete('delivery.page');
    router.replace(`${pathname}?${p.toString()}`, { scroll: false });
  };

  const table = (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3" role="group" aria-label={t('st.dlv.filters')}>
        <Select
          className="w-44"
          label={t('st.dlv.filter.assignee')}
          value={assignee}
          onChange={(e) => {
            state.resetPaging();
            setAssignee(e.currentTarget.value);
          }}
          options={[
            { value: '', label: t('st.dlv.filter.everyone') },
            { value: 'me', label: t('st.dlv.filter.me') },
            { value: 'unassigned', label: t('st.dlv.unassigned') }
          ]}
        />
        <Select
          className="w-56"
          label={t('st.dlv.filter.mode')}
          value={mode}
          onChange={(e) => {
            state.resetPaging();
            setMode(e.currentTarget.value);
          }}
          options={[
            { value: '', label: t('st.dlv.filter.allModes') },
            { value: 'AFROTRANSACT_DELIVERY', label: t('st.dlv.mode.store') },
            { value: 'FULFILLED_BY_AFROTRANSACT', label: t('st.dlv.mode.fba') }
          ]}
        />
        <label className="flex flex-col gap-1 text-sm font-semibold" htmlFor="delivery-city">
          {t('st.dlv.filter.city')}
          <input
            id="delivery-city"
            className="h-10 w-44 rounded-sm border border-border bg-surface px-3 text-md font-normal"
            value={city}
            onChange={(e) => {
              state.resetPaging();
              setCity(e.currentTarget.value);
            }}
          />
        </label>
        {assignee || mode || city ? (
          <Button
            variant="link"
            onClick={() => {
              setAssignee('');
              setMode('');
              setCity('');
            }}
          >
            {t('st.dlv.filter.clear')}
          </Button>
        ) : null}
      </div>
      <DataTable<DeliveryRow>
        id="staff.delivery"
        label={t('st.dlv.table')}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.shipmentId}
        rowLabel={(r) => r.orderNumber}
        rowTestId="delivery-row"
        state={state}
        mode="server"
        paging="pages"
        total={data?.total ?? null}
        loading={list.isPending}
        fetching={list.isFetching}
        error={list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> : undefined}
        onRowClick={(r) => setOpen(r.shipmentId)}
        activeRowId={open}
        enableSelection={false}
        searchPlaceholder={t('st.dlv.search')}
        empty={t(tab === 'active' ? 'st.dlv.empty.active' : tab === 'delivered' ? 'st.dlv.empty.delivered' : 'st.dlv.empty.preparing')}
        noMatches={t('st.dlv.noMatches')}
      />
    </div>
  );

  return (
    <ModulePage module="delivery">
      <Tabs
        label={t('st.dlv.tabs')}
        value={tab}
        onValueChange={setTab}
        items={DELIVERY_TABS.map((value) => ({
          value,
          label: `${t(`st.dlv.tab.${value}`)}${data ? ` (${countOf(TAB_STATUSES[value])})` : ''}`,
          content: value === tab ? table : null
        }))}
      />
      <DeliverySheet id={open} onClose={() => setOpen(null)} />
    </ModulePage>
  );
}
