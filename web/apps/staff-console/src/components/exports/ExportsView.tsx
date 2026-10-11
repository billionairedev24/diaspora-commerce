'use client';

import { type ExportJob, staffQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { ErrorState } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, humanize } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

const TONE = { READY: 'good', QUEUED: 'brand', RUNNING: 'info', FAILED: 'bad', EXPIRED: 'info' } as const;

const busy = (jobs: ExportJob[] | undefined) => !!jobs?.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING');

/** My exports: background jobs poll until ready; links expire 24 hours after the file is built. */
export function ExportsView() {
  const t = useT();
  const qc = useQueryClient();
  const state = useTableState({ searchParams: useSearchParams(), defaultSort: [{ id: 'createdAt', desc: true }] });
  const q = useQuery({ ...staffQueries.exports(staffApi()), refetchInterval: (query) => (busy(query.state.data?.items) ? 3000 : false) });
  const columns = useMemo<ColumnDef<ExportJob>[]>(
    () => [
      {
        id: 'fileName',
        accessorFn: (j) => j.fileName,
        header: t('st.exportsView.file'),
        size: 280,
        meta: { kind: 'strong' },
        cell: ({ row }) => (
          <>
            <strong className="font-semibold">{row.original.fileName}</strong>
            {row.original.async ? <span className="block text-xs text-muted">background</span> : null}
          </>
        )
      },
      { id: 'title', accessorFn: (j) => j.title || humanize(j.resource), header: 'Table', size: 160 },
      { id: 'format', accessorFn: (j) => j.format.toUpperCase(), header: t('st.exportsView.format'), size: 90 },
      {
        id: 'status',
        accessorFn: (j) => j.status,
        header: t('st.exportsView.status'),
        size: 120,
        meta: { kind: 'status' },
        cell: ({ row }) => <StatusPill tone={TONE[row.original.status]}>{humanize(row.original.status)}</StatusPill>
      },
      { id: 'rows', accessorFn: (j) => j.rows, header: t('st.exportsView.rows'), size: 90, meta: { kind: 'number', align: 'end' }, cell: ({ row }) => row.original.rows.toLocaleString('en-US') },
      { id: 'createdAt', accessorFn: (j) => j.createdAt, header: 'Created', size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
      { id: 'expiresAt', accessorFn: (j) => j.expiresAt ?? undefined, header: t('st.exportsView.linkExpires'), size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.expiresAt) },
      {
        id: 'download',
        header: () => <span className="at-visually-hidden">{t('st.exportsView.download')}</span>,
        size: 170,
        enableSorting: false,
        meta: { export: false, label: t('st.exportsView.download') },
        cell: ({ row }) => {
          const j = row.original;
          if (j.status === 'READY' && j.downloadUrl)
            return (
              <a href={j.downloadUrl} download>
                {t('st.exportsView.download')}
              </a>
            );
          if (j.error) return <span className="text-xs text-danger">{j.error}</span>;
          if (j.status === 'EXPIRED') return <span className="text-xs text-muted">{t('st.exportsView.exportTheTableAgain')}</span>;
          return (
            <span className="text-xs text-muted" role="status">
              {t('st.exportsView.building')}
            </span>
          );
        }
      }
    ],
    [t]
  );
  return (
    <ModulePage module="exports">
      <DataTable<ExportJob>
        id="staff.exports"
        label={t('st.exportsView.exports')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(j) => j.id}
        rowLabel={(j) => j.fileName}
        state={state}
        mode="client"
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        empty={`${t('st.exportsView.noExportsYet')} Use Export on any table: up to 5,000 rows download right away; bigger exports build here in the background.`}
        onExport={(req) => exportDataset(qc, req)}
      />
    </ModulePage>
  );
}
