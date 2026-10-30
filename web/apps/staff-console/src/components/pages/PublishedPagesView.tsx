'use client';

import { type ContentLocale, contentPageHref, type ContentPageRow, type ContentPageStatus, type RecordTone, staffContentQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, buttonClassName, Select, Sheet } from '@afrotransact/ui';
import { ArrowSquareOut } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { recordHref } from '@/lib/modules';
import { personName } from '@/lib/names';

import { type DeviceKey } from './chrome';
import { LiveLink } from './ContentPageEditor';
import { ContentPreview, PreviewControls } from './ContentPreview';

const TONE: Record<ContentPageStatus, RecordTone> = { PUBLISHED: 'good', DRAFT_FOR_COUNSEL: 'warn', HIDDEN: 'info', DEVELOPER_ONLY: 'bad' };
type KindFilter = 'all' | 'legal' | 'help' | 'record';

/** Where a row is edited: the page editor, the table that owns the record, or a screen. */
function editHref(row: ContentPageRow): string | null {
  if (row.editor === 'page' && (row.kind === 'legal' || row.kind === 'help')) return contentPageHref(row.kind, row.key);
  if (row.consoleHref) return row.consoleHref;
  if (row.resource) return recordHref(row.resource, row.resourceId ?? '') ?? null;
  return null;
}

/** A quick look at the published page in a sheet, without leaving the list. */
function PreviewSheet({ row, onClose }: { row: ContentPageRow; onClose: () => void }) {
  const t = useT();
  const kind = row.kind === 'legal' ? 'legal' : 'help';
  const page = useQuery(staffContentQueries.page(staffApi().content, kind, row.key));
  const [locale, setLocale] = useState<ContentLocale>('en');
  const [device, setDevice] = useState<DeviceKey>('desktop');
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()} title={t('st.pages.sheet.title', { title: row.title })} description={t('st.pages.sheet.description')} closeLabel={t('st.pages.close')}>
      {page.isPending ? (
        <Spinner label={t('st.pages.loading')} />
      ) : page.isError ? (
        <ErrorState error={page.error} onRetry={() => void page.refetch()} />
      ) : (
        <div className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <LiveLink url={page.data.liveUrl} path={page.data.livePath} />
            <Link href={contentPageHref(kind, row.key)} className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
              {row.canEdit ? t('st.pages.openEditor') : t('st.pages.openPage')}
            </Link>
          </div>
          <PreviewControls locales={page.data.locales} locale={locale} onLocale={setLocale} device={device} onDevice={setDevice} translated={Object.keys(page.data.published.bodies)} needsReview={page.data.published.needsReview} />
          <ContentPreview
            kind={kind}
            doc={{ version: page.data.published.version, effectiveDate: page.data.published.effectiveDate, draftBanner: page.data.published.draftBanner, title: page.data.published.title || page.data.title, bodies: page.data.published.bodies, needsReview: page.data.published.needsReview }}
            placeholders={page.data.placeholders}
            locale={locale}
            device={device}
          />
        </div>
      )}
    </Sheet>
  );
}

/**
 * Settings → Published pages: every public page with its live link, when it was last updated and by whom, and what can
 * be done with it. Legal documents and help pages are previewed and edited here, in the console; the other surfaces
 * (contacts, banners, notification templates) open in the screen that edits them; the few pages whose text still ships
 * with the code are listed too, so nobody hunts for an editor that does not exist.
 */
export function PublishedPagesView() {
  const t = useT();
  const list = useQuery(staffContentQueries.pages(staffApi().content));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'pages', defaultPageSize: 50 });
  const [kind, setKind] = useState<KindFilter>('all');
  const [looking, setLooking] = useState<ContentPageRow | null>(null);
  const rows = useMemo(() => (list.data?.items ?? []).filter((r) => kind === 'all' || (kind === 'record' ? r.kind === 'record' : r.kind === kind)), [list.data, kind]);
  const columns = useMemo<ColumnDef<ContentPageRow>[]>(
    () => [
      {
        id: 'title',
        accessorFn: (r) => `${r.title} ${r.group}`,
        header: t('st.pages.col.page'),
        size: 260,
        cell: ({ row }) => {
          const r = row.original;
          const href = editHref(r);
          return (
            <span className="flex min-w-0 flex-col">
              {href && r.editor === 'page' ? <Link href={href} className="font-bold">{r.title}</Link> : <strong>{r.title}</strong>}
              <span className="text-xs text-muted">{r.group}</span>
            </span>
          );
        }
      },
      {
        id: 'live',
        accessorFn: (r) => r.livePath ?? '',
        header: t('st.pages.col.live'),
        size: 200,
        cell: ({ row }) =>
          row.original.liveUrl ? (
            <a href={row.original.liveUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-mono text-xs">
              {row.original.livePath}
              <ArrowSquareOut size={12} aria-hidden />
              <span className="sr-only">{t('st.pages.live.newTab')}</span>
            </a>
          ) : (
            <span className="text-muted">—</span>
          )
      },
      {
        id: 'status',
        accessorFn: (r) => r.status,
        header: t('st.pages.col.status'),
        size: 150,
        cell: ({ row }) => <StatusPill tone={TONE[row.original.status]}>{t(`st.pages.status.${row.original.status}` as MessageKey)}</StatusPill>
      },
      { id: 'version', accessorFn: (r) => r.version, header: t('st.pages.col.version'), size: 90 },
      {
        id: 'updated',
        accessorFn: (r) => r.updatedAt ?? '',
        header: t('st.pages.col.updated'),
        size: 170,
        cell: ({ row }) => (row.original.updatedAt ? dateTime(row.original.updatedAt) : <span className="text-muted">—</span>)
      },
      {
        id: 'by',
        accessorFn: (r) => r.updatedByName ?? '',
        header: t('st.pages.col.by'),
        size: 160,
        cell: ({ row }) => (row.original.updatedByName ? personName(t, row.original.updatedByName) : <span className="text-muted">—</span>)
      },
      {
        id: 'draft',
        accessorFn: (r) => (r.hasDraft ? (r.draftUpdatedByName ?? 'draft') : ''),
        header: t('st.pages.col.draft'),
        size: 170,
        cell: ({ row }) => (row.original.hasDraft ? <StatusPill tone="info">{t('st.pages.draftBy2', { name: personName(t, row.original.draftUpdatedByName) })}</StatusPill> : <span className="text-muted">—</span>)
      },
      {
        id: 'actions',
        header: () => <span className="sr-only">{t('st.pages.col.actions')}</span>,
        enableSorting: false,
        size: 190,
        cell: ({ row }) => {
          const r = row.original;
          const href = editHref(r);
          if (r.editor === 'developer') return <span className="text-xs text-muted">{t('st.pages.developerOnly')}</span>;
          return (
            <span className="inline-flex flex-wrap gap-1">
              {r.editor === 'page' ? (
                <Button size="sm" variant="neutral" onClick={() => setLooking(r)} aria-label={t('st.pages.previewRow', { title: r.title })}>
                  {t('st.pages.preview')}
                </Button>
              ) : null}
              {href ? (
                <Link href={href} className={buttonClassName({ variant: 'neutral', size: 'sm' })} aria-label={t(r.canEdit ? 'st.pages.editRow' : 'st.pages.openRow', { title: r.title })}>
                  {r.editor === 'page' ? (r.canEdit ? t('st.pages.edit') : t('st.pages.open')) : t('st.pages.openIn')}
                </Link>
              ) : null}
            </span>
          );
        }
      }
    ],
    [t]
  );
  return (
    <div className="flex min-w-0 flex-col gap-3">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.pages.intro')}</p>
      <div className="flex flex-wrap items-end gap-3">
        <Select
          id="pages-kind"
          label={t('st.pages.filter')}
          value={kind}
          onChange={(e) => setKind(e.currentTarget.value as KindFilter)}
          options={[
            { value: 'all', label: t('st.pages.filter.all') },
            { value: 'legal', label: t('st.pages.filter.legal') },
            { value: 'help', label: t('st.pages.filter.help') },
            { value: 'record', label: t('st.pages.filter.record') }
          ]}
        />
      </div>
      <DataTable<ContentPageRow>
        id="staff.published-pages"
        label={t('st.pages.title')}
        columns={columns}
        data={rows}
        getRowId={(r) => `${r.kind}/${r.key}`}
        rowLabel={(r) => r.title}
        state={state}
        mode="client"
        enableSelection={false}
        loading={list.isPending}
        fetching={list.isFetching}
        error={list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} /> : undefined}
        empty={t('st.pages.empty')}
        searchPlaceholder={t('st.pages.search')}
        rowTestId="page-row"
      />
      {looking ? <PreviewSheet key={`${looking.kind}/${looking.key}`} row={looking} onClose={() => setLooking(null)} /> : null}
    </div>
  );
}
