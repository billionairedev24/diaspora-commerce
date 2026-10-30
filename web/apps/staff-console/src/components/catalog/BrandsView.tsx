'use client';

import { type BrandStatus, type StaffBrand, staffTaxonomyQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button } from '@afrotransact/ui';
import { Plus } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { BRAND_STATUSES } from './brandModel';
import { BrandSheet, type BrandTarget, STATUS_LABEL, when } from './BrandSheet';

/** The URL prefix of this table's state (search, page, status), so it can sit among the other Catalog tabs. */
export const BRANDS_PREFIX = 'brands';

/**
 * Catalog → Categories → Brands: the brand registry as a server-driven table on the shared DataTable. It opens on the
 * brands waiting for review (the seller who typed each one is named, with the products that hold it, where it applies
 * and when it was asked for); chips switch to the other statuses, the search runs on the server. Rows open the shared
 * right-hand sheet where staff approve, reject, merge, edit or retire, or add one brand or a pasted list.
 */
export function BrandsView() {
  const t = useT();
  const api = staffApi().taxonomy;
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const state = useTableState({ searchParams: params, prefix: BRANDS_PREFIX });
  const [target, setTarget] = useState<BrandTarget | null>(null);

  const statusParam = `${BRANDS_PREFIX}.status`;
  const status = params.has(statusParam) ? (params.get(statusParam) ?? '') : 'PENDING';
  const setStatus = (value: string) => {
    const next = new URLSearchParams(params.toString());
    next.set(statusParam, value);
    next.delete(`${BRANDS_PREFIX}.page`);
    router.replace(`${pathname}?${next.toString()}`, { scroll: false });
  };

  const { pageIndex, pageSize } = state.pagination;
  const query = useQuery({
    ...staffTaxonomyQueries.brands(api, { status: status ? [status as BrandStatus] : undefined, q: state.q || undefined, limit: pageSize, offset: pageIndex * pageSize }),
    placeholderData: keepPreviousData
  });
  const tree = useQuery(staffTaxonomyQueries.tree(api));
  const data = query.data;
  const nodes = useMemo(() => tree.data?.nodes ?? [], [tree.data]);
  const canEdit = data?.canEdit ?? false;
  // With every status shown, the brands waiting for review come first.
  const rows = useMemo(() => (status ? (data?.brands ?? []) : [...(data?.brands ?? [])].sort((a, b) => Number(b.status === 'PENDING') - Number(a.status === 'PENDING'))), [data?.brands, status]);

  const columns = useMemo<ColumnDef<StaffBrand>[]>(
    () => [
      { id: 'name', header: t('st.brand.col.name'), accessorFn: (b) => b.name, enableSorting: false, size: 220, cell: ({ row }) => <strong>{row.original.name}</strong> },
      { id: 'status', header: t('st.brand.col.status'), accessorFn: (b) => t(STATUS_LABEL[b.status]), enableSorting: false, size: 150 },
      { id: 'seller', header: t('st.brand.col.seller'), accessorFn: (b) => b.createdByName ?? t('st.brand.byStaff'), enableSorting: false, size: 180 },
      { id: 'products', header: t('st.brand.col.products'), accessorFn: (b) => t('st.brand.productsCount', { n: b.products }), enableSorting: false, size: 110, meta: { align: 'end' } },
      {
        id: 'scope',
        header: t('st.brand.col.scope'),
        enableSorting: false,
        size: 260,
        accessorFn: (b) => b.scope.map((s) => s.path).join(', '),
        cell: ({ row }) => {
          const [first, ...rest] = row.original.scope;
          return first ? (rest.length ? t('st.brand.scopeMore', { first: first.path, n: rest.length }) : first.path) : '';
        }
      },
      { id: 'requested', header: t('st.brand.col.requested'), accessorFn: (b) => when(b.createdAt), enableSorting: false, size: 170 },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        size: 120,
        cell: ({ row }) =>
          canEdit && row.original.status === 'PENDING' ? (
            <Button size="sm" variant="secondary" onClick={() => setTarget({ brand: row.original, mode: 'approve' })} aria-label={t('st.brand.approveNamed', { name: row.original.name })}>
              {t('st.brand.approve.submit')}
            </Button>
          ) : null
      }
    ],
    [t, canEdit]
  );

  return (
    <div className="flex flex-col gap-3" data-testid="brands-view">
      <DataTable<StaffBrand>
        id="staff.brands"
        label={t('st.brand.label')}
        columns={columns}
        data={rows}
        getRowId={(b) => b.id}
        rowLabel={(b) => b.name}
        state={state}
        mode="server"
        paging="pages"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder={t('st.brand.searchPlaceholder')}
        onRowClick={(b) => setTarget({ brand: b, mode: 'view' })}
        activeRowId={target?.brand?.id ?? null}
        empty={t('st.brand.empty')}
        toolbar={
          <>
            <div role="group" aria-label={t('st.brand.statusGroup')} className="flex flex-wrap gap-1">
              {[{ value: '', label: t('st.resourceTable.allStatuses'), n: null }, ...BRAND_STATUSES.map((s) => ({ value: s, label: t(STATUS_LABEL[s]), n: data?.counts[s] ?? null }))].map((f) => (
                <Button key={f.value} size="sm" variant={status === f.value ? 'secondary' : 'neutral'} aria-pressed={status === f.value} onClick={() => setStatus(f.value)}>
                  {f.n === null ? f.label : t('st.brand.statusCount', { label: f.label, n: f.n })}
                </Button>
              ))}
            </div>
            {canEdit ? (
              <>
                <Button size="sm" icon={<Plus size={14} aria-hidden />} onClick={() => setTarget({ brand: null, mode: 'add' })}>
                  {t('st.brand.add.title')}
                </Button>
                <Button size="sm" variant="neutral" onClick={() => setTarget({ brand: null, mode: 'bulk' })}>
                  {t('st.brand.addMany')}
                </Button>
              </>
            ) : null}
          </>
        }
      />
      <BrandSheet target={target} brands={data?.brands ?? []} nodes={nodes} canEdit={canEdit} onTarget={setTarget} onClose={() => setTarget(null)} />
    </div>
  );
}
