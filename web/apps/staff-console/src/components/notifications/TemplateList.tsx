'use client';

import { type NotificationTemplateSummary, staffQueries } from '@afrotransact/api-client';
import { MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from '@tanstack/react-table';
import Link from 'next/link';
import { useMemo, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useDebounced } from '@/lib/use-debounced';

import { CATEGORY_LABEL, CATEGORY_TONE, CHANNEL_LABEL } from './labels';

export const templateHref = (key: string) => `/notifications?key=${encodeURIComponent(key)}`;

/** Every template key: channels, locales, latest version, who changed it and when. The search runs on the BFF. */
export function TemplateList() {
  const t = useT();
  const [text, setText] = useState('');
  const q = useDebounced(text.trim(), 250);
  const query = useQuery(staffQueries.templates(staffApi(), q));
  const columns = useMemo<ColumnDef<NotificationTemplateSummary>[]>(
    () => [
      {
        id: 'key',
        header: t('st.ntf.list.key'),
        size: 34,
        cell: ({ row }) => (
          <Link href={templateHref(row.original.key)} className="font-mono text-sm font-semibold break-all">
            {row.original.key}
          </Link>
        )
      },
      {
        id: 'category',
        header: t('st.ntf.list.category'),
        size: 14,
        cell: ({ row }) => <StatusPill tone={CATEGORY_TONE[row.original.category]}>{t(CATEGORY_LABEL[row.original.category])}</StatusPill>
      },
      { id: 'channels', header: t('st.ntf.list.channels'), size: 18, cell: ({ row }) => row.original.channels.map((c) => t(CHANNEL_LABEL[c])).join(', ') },
      { id: 'locales', header: t('st.ntf.list.locales'), size: 8, cell: ({ row }) => row.original.locales.join(', ') },
      { id: 'version', header: t('st.ntf.list.version'), size: 8, cell: ({ row }) => `v${row.original.latestVersion}` },
      {
        id: 'updated',
        header: t('st.ntf.list.updated'),
        size: 18,
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
  const table = useReactTable({ data: query.data?.items ?? [], columns, getCoreRowModel: getCoreRowModel(), manualFiltering: true });
  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.ntf.list.intro')}</p>
      <label className="flex h-10 max-w-xl items-center gap-2 rounded-pill border border-border-strong bg-surface px-3">
        <MagnifyingGlass size={16} aria-hidden className="text-muted" />
        <span className="at-visually-hidden">{t('st.ntf.list.search')}</span>
        <input
          type="search"
          className="min-w-0 flex-1 bg-transparent text-sm outline-none"
          placeholder={t('st.ntf.list.searchPlaceholder')}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
        />
      </label>
      {query.isPending ? <Spinner /> : null}
      {query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.data ? (
        <>
          <p className="m-0 text-sm text-muted" aria-live="polite">
            {t('st.ntf.list.count', { n: query.data.total })}
          </p>
          <div className="overflow-hidden rounded-md border border-border">
            <table className="at-table text-sm" aria-label={t('st.ntf.list.table')} aria-busy={query.isFetching || undefined}>
              <colgroup>
                {table.getAllLeafColumns().map((c) => (
                  <col key={c.id} style={{ width: `${c.getSize()}%` }} />
                ))}
              </colgroup>
              <thead className="bg-surface-muted text-left">
                {table.getHeaderGroups().map((hg) => (
                  <tr key={hg.id}>
                    {hg.headers.map((h) => (
                      <th key={h.id} scope="col" className="px-3 py-2 text-xs font-bold text-muted">
                        {flexRender(h.column.columnDef.header, h.getContext())}
                      </th>
                    ))}
                  </tr>
                ))}
              </thead>
              <tbody>
                {table.getRowModel().rows.map((row) => (
                  <tr key={row.id} className="border-t border-border" data-testid="template-row">
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-3 py-2.5">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                ))}
                {query.data.items.length === 0 ? (
                  <tr>
                    <td colSpan={columns.length} className="px-3 py-10 text-center text-muted">
                      {t('st.ntf.list.none')}
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
        </>
      ) : null}
    </div>
  );
}
