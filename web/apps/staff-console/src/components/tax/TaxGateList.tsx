'use client';

import { staffTaxQueries, type TaxLiveWithoutDecisionRow } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';

/**
 * Live listings that have a variant without a stored, resolved tax decision: product, seller and category by name, and for
 * each variant what is missing in plain words. Always empty when the tax gate holds; the catalog sweep closes anything
 * listed here within a minute, so a row means a code path skipped the gate (docs/runbooks/CatalogLiveWithoutTaxDecision.md).
 */
export function TaxGateList() {
  const t = useT();
  const api = staffApi();
  const params = useSearchParams();
  const state = useTableState({ searchParams: params, prefix: 'review-gate' });
  const query = useQuery({ ...staffTaxQueries.liveWithoutDecision(api.tax, { page: state.pagination.pageIndex, size: state.pagination.pageSize }), placeholderData: keepPreviousData });
  const data = query.data;

  const columns = useMemo<ColumnDef<TaxLiveWithoutDecisionRow>[]>(
    () => [
      { id: 'title', header: t('st.tax.gate.col.product'), size: 240, enableSorting: false, accessorFn: (r) => r.title, cell: ({ row }) => <strong className="font-semibold">{row.original.title}</strong>, meta: { label: t('st.tax.gate.col.product'), kind: 'text' } },
      { id: 'sellerName', header: t('st.tax.gate.col.seller'), size: 190, enableSorting: false, accessorFn: (r) => r.sellerName, meta: { label: t('st.tax.gate.col.seller'), kind: 'text' } },
      { id: 'categoryName', header: t('st.tax.gate.col.category'), size: 220, enableSorting: false, accessorFn: (r) => r.categoryName, meta: { label: t('st.tax.gate.col.category'), kind: 'text' } },
      {
        id: 'missing',
        header: t('st.tax.gate.col.missing'),
        size: 360,
        enableSorting: false,
        accessorFn: (r) => r.variants.map((v) => `${v.sku} ${v.statusLabel}`).join(' '),
        cell: ({ row }) => (
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {row.original.variants.map((v) => (
              <li key={v.atin}>
                <span className="font-medium">{v.sku}</span> · {v.statusLabel}
                {v.why ? <span className="block text-xs text-muted">{v.why}</span> : null}
              </li>
            ))}
          </ul>
        ),
        meta: { label: t('st.tax.gate.col.missing'), kind: 'text' }
      },
      { id: 'updatedAt', header: t('st.tax.gate.col.since'), size: 120, enableSorting: false, accessorFn: (r) => r.updatedAt, cell: ({ row }) => shortDate(row.original.updatedAt), meta: { label: t('st.tax.gate.col.since'), kind: 'text' } }
    ],
    [t]
  );

  return (
    <div className="flex flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.tax.gate.help')}</p>
      <DataTable<TaxLiveWithoutDecisionRow>
        id="staff.tax.review.gate"
        label={t('st.tax.gate.table')}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.atin}
        rowLabel={(r) => r.title}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        enableSelection={false}
        empty={t('st.tax.gate.empty')}
      />
    </div>
  );
}
