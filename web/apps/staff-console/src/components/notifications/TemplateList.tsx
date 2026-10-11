'use client';

import { type NotificationTemplateSummary, staffQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { CATEGORY_LABEL, CATEGORY_TONE, CHANNEL_LABEL } from './labels';

export const templateHref = (key: string) => `/notifications?key=${encodeURIComponent(key)}`;

/** Every template key: channels, locales, latest version, who changed it and when. The list is small and held in full:
    one search over every column, sort, select and export run in the table. */
export function TemplateList() {
  const t = useT();
  const qc = useQueryClient();
  const router = useRouter();
  const state = useTableState({ searchParams: useSearchParams() });
  const query = useQuery(staffQueries.templates(staffApi(), ''));
  const columns = useMemo<ColumnDef<NotificationTemplateSummary>[]>(
    () => [
      {
        id: 'key',
        accessorFn: (r) => r.key,
        meta: { kind: 'mono', label: t('st.ntf.list.key') },
        header: t('st.ntf.list.key'),
        size: 300,
        cell: ({ row }) => (
          <Link href={templateHref(row.original.key)} className="font-mono text-sm font-semibold break-all">
            {row.original.key}
          </Link>
        )
      },
      {
        id: 'category',
        accessorFn: (r) => t(CATEGORY_LABEL[r.category]),
        meta: { kind: 'status', label: t('st.ntf.list.category') },
        header: t('st.ntf.list.category'),
        size: 140,
        cell: ({ row }) => <StatusPill tone={CATEGORY_TONE[row.original.category]}>{t(CATEGORY_LABEL[row.original.category])}</StatusPill>
      },
      { id: 'channels', accessorFn: (r) => r.channels.map((c) => t(CHANNEL_LABEL[c])).join(', '), meta: { label: t('st.ntf.list.channels') }, header: t('st.ntf.list.channels'), size: 180, cell: ({ row }) => row.original.channels.map((c) => t(CHANNEL_LABEL[c])).join(', ') },
      { id: 'locales', accessorFn: (r) => r.locales.join(', '), meta: { label: t('st.ntf.list.locales') }, header: t('st.ntf.list.locales'), size: 100, cell: ({ row }) => row.original.locales.join(', ') },
      { id: 'version', accessorFn: (r) => r.latestVersion, meta: { kind: 'number', label: t('st.ntf.list.version') }, header: t('st.ntf.list.version'), size: 90, cell: ({ row }) => `v${row.original.latestVersion}` },
      {
        id: 'updatedAt',
        accessorFn: (r) => r.updatedAt,
        meta: { kind: 'date', label: t('st.ntf.list.updated') },
        header: t('st.ntf.list.updated'),
        size: 170,
        cell: ({ row }) => (
          <>
            {shortDate(row.original.updatedAt)}
            <span className="block text-xs text-muted">{row.original.updatedBy ?? '—'}</span>
          </>
        )
      }
    ],
    [t]
  );
  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.ntf.list.intro')}</p>
      <DataTable<NotificationTemplateSummary>
        id="staff.notification-templates"
        label={t('st.ntf.list.table')}
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(r) => r.key}
        rowLabel={(r) => r.key}
        rowTestId="template-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder={t('st.ntf.list.searchPlaceholder')}
        onRowClick={(r) => router.push(templateHref(r.key))}
        empty={t('st.ntf.list.none')}
        noMatches={t('st.ntf.list.none')}
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
    </div>
  );
}
