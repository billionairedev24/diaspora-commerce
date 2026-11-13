'use client';

import { type ProductImport, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { buttonClassName } from '@afrotransact/ui';
import { UploadSimple } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { modeLabelKey } from './bulk-model';

/** Upload history (S308): every bulk upload of the organisation, newest first, server-paged; a row opens its preview or result. */
export function ImportHistory() {
  const api = sellerApi();
  const t = useT();
  const router = useRouter();
  const { orgId } = useSeller();
  const state = useTableState({ searchParams: useSearchParams() });
  const q = useQuery({ ...sellerQueries.imports(api, orgId, state.pagination.pageIndex, state.pagination.pageSize), placeholderData: keepPreviousData });
  const columns = useMemo<ColumnDef<ProductImport>[]>(() => [
    { id: 'fileName', accessorFn: (r) => r.fileName, header: t('sc.bulk.col.file'), size: 260, meta: { kind: 'strong' },
      cell: ({ row }) => <Link href={`/listings/bulk/${encodeURIComponent(row.original.id)}`} className="font-semibold">{row.original.fileName}</Link> },
    { id: 'reference', accessorFn: (r) => r.reference, header: t('sc.bulk.col.reference'), size: 120, meta: { kind: 'mono' } },
    { id: 'createdAt', accessorFn: (r) => r.createdAt, header: t('sc.bulk.col.uploaded'), size: 170, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
    { id: 'uploadedByName', accessorFn: (r) => r.uploadedByName ?? '', header: t('sc.bulk.col.by'), size: 150 },
    { id: 'mode', accessorFn: (r) => t(modeLabelKey(r.mode)), header: t('sc.bulk.col.mode'), size: 130 },
    { id: 'totalRows', accessorFn: (r) => r.totalRows, header: t('sc.bulk.col.rows'), size: 80, meta: { kind: 'number', align: 'end' } },
    { id: 'errorRows', accessorFn: (r) => r.errorRows, header: t('sc.bulk.col.errors'), size: 80, meta: { kind: 'number', align: 'end' },
      cell: ({ row }) => <span className={row.original.errorRows ? 'font-semibold text-danger' : undefined}>{row.original.errorRows}</span> },
    { id: 'products', accessorFn: (r) => r.createdProducts + r.updatedProducts + (r.offersCreated ?? 0), header: t('sc.bulk.col.products'), size: 100, meta: { kind: 'number', align: 'end' } },
    { id: 'status', accessorFn: (r) => t(`sc.bulk.status.${r.status}`), header: t('sc.bulk.col.status'), size: 140, meta: { kind: 'status' },
      cell: ({ row }) => <StatusPill tone={toneOf(row.original.status === 'VALIDATED' && row.original.errorRows ? 'OPEN' : row.original.status)}>{t(`sc.bulk.status.${row.original.status}`)}</StatusPill> }
  ], [t]);
  return (
    <>
      <PageHeader tour="page-bulk-history" title={t('sc.bulk.historyTitle')} description={t('sc.bulk.historyDescription')}
        actions={<Link href="/listings/bulk" className={buttonClassName({ variant: 'primary' })}><UploadSimple size={18} aria-hidden /> {t('sc.bulk.newUpload')}</Link>} />
      <DataTable<ProductImport>
        id="seller.imports"
        label={t('sc.bulk.historyTitle')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(r) => r.id}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        enableSelection={false}
        onRowClick={(r) => router.push(`/listings/bulk/${encodeURIComponent(r.id)}`)}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder={t('sc.bulk.historySearch')}
        empty={t('sc.bulk.historyEmpty')}
        noMatches={t('sc.bulk.noMatches')}
      />
      <StockFiles />
    </>
  );
}

/** The organisation's latest stock files (S395): each opens its preview or result. */
function StockFiles() {
  const api = sellerApi();
  const t = useT();
  const { orgId, can } = useSeller();
  const q = useQuery({ ...sellerQueries.stockImports(api, orgId, 0, 10), enabled: can('inventory', 'VIEW') });
  if (!q.data?.items.length) return null;
  return (
    <section aria-labelledby="stock-files" className="flex flex-col gap-2" data-testid="stock-files">
      <h2 id="stock-files" className="m-0 text-lg font-semibold">{t('sc.bulk.historyStock')}</h2>
      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {q.data.items.map((i) => (
          <li key={i.id}>
            <Link href={`/listings/bulk/stock/${encodeURIComponent(i.id)}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-sm border border-border bg-surface px-3 py-2 text-sm">
              <strong className="text-text">{i.fileName}</strong>
              <span className="text-xs text-muted">{i.reference} · {shortDate(i.createdAt)} · {t('sc.bulk.rowsCount', { count: i.totalRows })} · {t(i.mode === 'DELTA' ? 'sc.bulk.stockMode.DELTA' : 'sc.bulk.stockMode.ABSOLUTE')}</span>
              <span className="ml-auto"><StatusPill tone={toneOf(i.status === 'VALIDATED' && i.errorRows ? 'OPEN' : i.status)}>{t(`sc.bulk.status.${i.status}`)}</StatusPill></span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
