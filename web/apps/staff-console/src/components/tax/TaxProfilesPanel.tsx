'use client';

import { staffTaxQueries, type TaxProfileRow } from '@afrotransact/api-client';
import { DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { Select } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef } from 'react';

import { ErrorState } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { useSheetUrl } from '@/lib/use-sheet-url';

import { GatePill, mono } from './shared';
import { TaxProfileSheet } from './TaxProfileSheet';

const PREFIX = 'profiles';

/**
 * Tax profiles: the 73 categories with their tax status gate, Stripe codes, how many scenarios the rules cover and the rule
 * version. Department and status filters, search, sort and paging run on the BFF; a row opens the profile in the Sheet.
 */
export function TaxProfilesPanel() {
  const api = staffApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const state = useTableState({ searchParams: params, prefix: PREFIX });
  const department = params.get(`${PREFIX}.department`) ?? '';
  const status = params.get(`${PREFIX}.status`) ?? '';
  const sheet = useSheetUrl('profile', ['do']);
  const openCode = sheet.value;
  const action = params.get('do');

  const go = (change: (p: URLSearchParams) => void) => {
    if (window.location.pathname !== pathname) return;
    const next = new URLSearchParams(window.location.search);
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  const setFilter = (key: 'department' | 'status', value: string) =>
    go((p) => {
      if (value) p.set(`${PREFIX}.${key}`, value);
      else p.delete(`${PREFIX}.${key}`);
      p.delete(`${PREFIX}.page`);
      p.delete('profile');
    });

  const query = useQuery({
    ...staffTaxQueries.profiles(api.tax, { department: department || undefined, status: status || undefined, q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const data = query.data;

  // Back to the first page when the search changes (not on the first render).
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    state.resetPaging();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- resetPaging is recreated each render; only the search matters
  }, [state.q]);

  const open = (code: string, doAction?: string) => sheet.open(code, { do: doAction ?? null });

  const columns = useMemo<ColumnDef<TaxProfileRow>[]>(
    () => [
      { id: 'departmentName', header: 'Department', size: 170, accessorFn: (r) => r.departmentName, meta: { label: 'Department', kind: 'text' } },
      { id: 'name', header: 'Category', size: 230, accessorFn: (r) => r.name, cell: ({ row }) => <strong className="font-semibold">{row.original.name}</strong>, meta: { label: 'Category', kind: 'text' } },
      {
        id: 'statusLabel',
        header: 'Status',
        size: 220,
        accessorFn: (r) => r.statusLabel,
        cell: ({ row }) => <GatePill status={row.original.status} label={row.original.statusLabel} />,
        meta: { label: 'Status', kind: 'status' }
      },
      {
        id: 'stripeCodeList',
        header: 'Stripe codes',
        size: 200,
        enableSorting: false,
        accessorFn: (r) => r.stripeCodeList,
        cell: ({ row }) => <span className={mono}>{row.original.stripeCodes.join(', ') || '—'}</span>,
        meta: { label: 'Stripe codes', kind: 'text' }
      },
      { id: 'scenarioCount', header: 'Scenarios', size: 100, accessorFn: (r) => r.scenarioCount, meta: { label: 'Scenarios', kind: 'number', align: 'end' } },
      { id: 'ruleVersion', header: 'Rule version', size: 190, accessorFn: (r) => r.ruleVersion, cell: ({ row }) => <span className={mono}>{row.original.ruleVersion}</span>, meta: { label: 'Rule version', kind: 'text' } },
      rowActionsColumn<TaxProfileRow>({
        header: 'Actions',
        name: (r) => r.name,
        items: (r) => {
          const lifts = ['NEEDS_APPROVAL', 'CONFLICT_OPEN', 'BLOCKED_BUNDLE'].includes(r.status);
          return [
            { label: 'Open profile', onSelect: () => open(r.categoryCode) },
            data?.canChange && lifts ? { label: 'Mark approved…', onSelect: () => open(r.categoryCode, 'approve') } : null,
            data?.canChange && r.status === 'ACTIVE' ? { label: 'Reopen for approval…', onSelect: () => open(r.categoryCode, 'reopen') } : null
          ];
        }
      })
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps -- open only reads the current URL when a menu entry is chosen
    [data?.canChange]
  );

  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        One profile per category: the questions sellers answer, the rules that turn the answers into exempt or taxable, and whether the category may go live. The rules are a draft until the Texas tax professional signs them off. Rules change through the dataset and a new rule version, not here.
      </p>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <Select
          label="Department"
          value={department}
          placeholder="All departments"
          options={(data?.departments ?? []).map((d) => ({ value: d.code, label: `${d.name} (${d.count})` }))}
          onChange={(e) => setFilter('department', e.currentTarget.value)}
          className="min-w-56"
        />
        <Select
          label="Status"
          value={status}
          placeholder="All statuses"
          options={(data?.statuses ?? []).map((s) => ({ value: s.value, label: `${s.label} (${s.count})` }))}
          onChange={(e) => setFilter('status', e.currentTarget.value)}
          className="min-w-64"
        />
      </div>
      <DataTable<TaxProfileRow>
        id="staff.tax.profiles"
        label="Tax profiles"
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.categoryCode}
        rowLabel={(r) => r.name}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder="Search categories, departments or Stripe codes"
        enableSelection={false}
        onRowClick={(r) => open(r.categoryCode)}
        activeRowId={openCode}
        empty="No category matches these filters."
      />
      <TaxProfileSheet code={openCode} action={action} onClose={sheet.close} onAction={(a) => sheet.update({ do: a })} />
    </div>
  );
}
