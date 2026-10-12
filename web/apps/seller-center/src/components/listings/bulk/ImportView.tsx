'use client';

import { isApiError, type ProductImport, type ProductImportRow, type ProductImportRowPage, sellerKeys, sellerQueries, type SpreadsheetFormat } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Checkbox, cx, Menu, MenuItem, useToast } from '@afrotransact/ui';
import { CaretDown, CheckCircle, DownloadSimple, FileCsv, MicrosoftExcelLogo, WarningCircle, XCircle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useEffect, useMemo, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { download } from '@/lib/export/table';
import { dateTime } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { cellProblems, importable, phaseOf, previewColumns, progress } from './bulk-model';

const PAGE = 200;
const POLL_MS = 1500;

/** Every row of an upload (optionally only those with errors), fetched page by page (at most 5,000 rows). */
async function allRows(load: (page: number) => Promise<ProductImportRowPage>): Promise<ProductImportRow[]> {
  const first = await load(0);
  const rows = [...first.items];
  for (let p = 1; p * PAGE < first.total; p++) rows.push(...(await load(p)).items);
  return rows;
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'good' | 'warn' | 'bad' }) {
  return (
    <div className="flex flex-col rounded-sm border border-border bg-surface px-3 py-2">
      <span className="text-xs text-muted">{label}</span>
      <strong className={cx('text-2xl', tone === 'good' && 'text-success', tone === 'warn' && 'text-warning', tone === 'bad' && 'text-danger')}>{value.toLocaleString()}</strong>
    </div>
  );
}

/** One upload (S308): validation summary, the preview table with per-cell errors (errors-only filter), the error report,
    then the background import with progress; completed imports show what was created, updated or skipped. */
export function ImportView({ importId }: { importId: string }) {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const [errorsOnly, setErrorsOnly] = useState(false);
  const job = useQuery({
    ...sellerQueries.importJob(api, orgId, importId),
    refetchInterval: (q) => {
      const d = q.state.data;
      return d && (d.status === 'QUEUED' || d.status === 'RUNNING') ? POLL_MS : false;
    }
  });
  const imp = job.data;
  const phase = imp ? phaseOf(imp) : 'review';
  const rowsQuery = useQuery({
    queryKey: [...sellerKeys.importJob(orgId, importId), 'all-rows', errorsOnly ? 'ERROR' : 'ALL', imp?.status ?? '', imp?.processedRows ?? 0],
    queryFn: ({ signal }) => allRows((page) => api.listImportRows(importId, { ...(errorsOnly ? { status: 'ERROR' as const } : {}), page, size: PAGE }, signal)),
    enabled: Boolean(imp),
    placeholderData: (prev) => prev
  });
  const state = useTableState({ prefix: 'rows', defaultPageSize: 50, syncUrl: false });

  // Tell the seller when a running import finishes (large imports also email and push).
  const wasRunning = useRef(false);
  useEffect(() => {
    if (!imp) return;
    if (phase === 'running') wasRunning.current = true;
    else if (wasRunning.current && phase === 'done') {
      wasRunning.current = false;
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.imports(orgId) });
      toast.show(t('sc.bulk.toast.done', { created: imp.createdProducts, updated: imp.updatedProducts }));
    }
  }, [imp, phase, qc, orgId, toast, t]);

  const start = useMutation({
    mutationFn: (validOnly: boolean) => api.startImport(importId, validOnly),
    onSuccess: (i: ProductImport) => {
      qc.setQueryData(sellerKeys.importJob(orgId, importId), i);
      void qc.invalidateQueries({ queryKey: sellerKeys.imports(orgId) });
    },
    onError: (e) => toast.show(isApiError(e) ? problemMessage(e) : t('sc.bulk.startFailed'), 'error')
  });
  const report = useMutation({
    mutationFn: (format: SpreadsheetFormat) => api.downloadImportReport(importId, format),
    onSuccess: (f) => download(f.blob, f.fileName),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const header = useMemo(() => imp?.header ?? [], [imp?.header]);
  const columns = useMemo<ColumnDef<ProductImportRow>[]>(() => {
    const statusLabel = (r: ProductImportRow) => (phase === 'review' || r.outcome === 'PENDING' ? t(`sc.bulk.row.${r.status}`) : t(`sc.bulk.outcome.${r.outcome}`));
    const tone = (r: ProductImportRow) => (r.outcome === 'CREATED' || r.outcome === 'UPDATED' || (r.outcome === 'PENDING' && r.status === 'VALID') ? 'good' : r.status === 'ERROR' || r.outcome === 'FAILED' ? 'bad' : r.status === 'WARNING' ? 'warn' : 'info');
    const fixed: ColumnDef<ProductImportRow>[] = [
      { id: 'rowNumber', accessorFn: (r) => r.rowNumber, header: t('sc.bulk.col.row'), size: 84, meta: { kind: 'number', label: t('sc.bulk.col.row') } },
      { id: 'status', accessorFn: statusLabel, header: t('sc.bulk.col.status'), size: 120, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={tone(row.original)}>{statusLabel(row.original)}</StatusPill> },
      {
        id: 'problems', accessorFn: (r) => r.messages.map((m) => `${m.column ?? ''} ${m.message}`).join(' '), header: t('sc.bulk.col.problems'), size: 320, enableSorting: false,
        cell: ({ row }) => row.original.messages.length || row.original.outcomeNote ? (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-xs">
            {row.original.messages.map((m, i) => (
              <li key={i} className={m.severity === 'ERROR' ? 'text-danger' : 'text-warning'}>
                {m.column ? <strong>{m.column.replace(/\s*\*$/, '')}: </strong> : null}{m.message}
              </li>
            ))}
            {row.original.outcomeNote ? <li className="text-muted">{row.original.outcomeNote}</li> : null}
          </ul>
        ) : <span className="text-xs text-muted">—</span>
      }
    ];
    const cells: ColumnDef<ProductImportRow>[] = previewColumns(header).map((c) => ({
      id: `c${c.index}`, accessorFn: (r) => r.cells[c.index] ?? '', header: c.label, size: c.label.toLowerCase() === 'title' ? 240 : 140, meta: { label: c.label },
      cell: ({ row }) => {
        const problems = cellProblems(row.original).get(c.index);
        const v = row.original.cells[c.index] ?? '';
        if (!problems) return <span className="line-clamp-2 text-sm">{v}</span>;
        const bad = problems.some((p) => p.severity === 'ERROR');
        return (
          <span className={cx('flex flex-col rounded-xs px-1', bad ? 'bg-danger-bg' : 'bg-warning-bg')} data-cell-error={bad ? 'true' : undefined} title={problems.map((p) => p.message).join(' · ')}>
            <span className="line-clamp-2 text-sm">{v || <em className="text-muted">{t('sc.bulk.blank')}</em>}</span>
            <span className={cx('text-2xs', bad ? 'text-danger' : 'text-warning')}>{problems[0]?.message}</span>
          </span>
        );
      }
    }));
    return [...fixed, ...cells];
  }, [header, phase, t]);

  if (job.isError) return <ErrorState error={job.error} onRetry={() => void job.refetch()} />;
  if (!imp) return <Spinner label={t('sc.bulk.loading')} />;

  const okRows = importable(imp);
  const reportMenu = (
    <Menu label={t('sc.bulk.report')} trigger={<Button variant="neutral" size="sm" loading={report.isPending}><DownloadSimple size={16} aria-hidden /> {t('sc.bulk.report')} <CaretDown size={12} aria-hidden /></Button>}>
      <MenuItem icon={<MicrosoftExcelLogo size={16} aria-hidden />} onSelect={() => report.mutate('xlsx')}>{t('sc.bulk.excel')}</MenuItem>
      <MenuItem icon={<FileCsv size={16} aria-hidden />} onSelect={() => report.mutate('csv')}>{t('sc.bulk.csv')}</MenuItem>
    </Menu>
  );

  return (
    <>
      <PageHeader title={imp.fileName} description={t('sc.bulk.importMeta', { reference: imp.reference, mode: imp.mode === 'UPDATE' ? t('sc.bulk.mode.UPDATE') : t('sc.bulk.mode.CREATE'), when: dateTime(imp.createdAt), who: imp.uploadedByName ?? '—' })}
        actions={<>
          <Link href="/listings/bulk/history" className="text-sm">{t('sc.bulk.historyLink')}</Link>
          {reportMenu}
        </>} />
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" data-testid="import-stats">
        <Stat label={t('sc.bulk.stat.rows')} value={imp.totalRows} />
        <Stat label={t('sc.bulk.stat.valid')} value={imp.validRows} tone="good" />
        <Stat label={t('sc.bulk.stat.warnings')} value={imp.warningRows} tone={imp.warningRows ? 'warn' : undefined} />
        <Stat label={t('sc.bulk.stat.errors')} value={imp.errorRows} tone={imp.errorRows ? 'bad' : undefined} />
      </div>
      {imp.fileWarnings.map((w) => <Notice key={w} tone="warning" title={t('sc.bulk.fileWarning')}>{w}</Notice>)}

      {phase === 'review' ? (
        imp.errorRows > 0 ? (
          <Notice tone="danger" role="alert" icon="shield" title={t('sc.bulk.hasErrors', { count: imp.errorRows })}
            action={editable ? (
              <span className="flex flex-wrap gap-2">
                <Link href="/listings/bulk" className="text-sm">{t('sc.bulk.reupload')}</Link>
                <Button size="sm" disabled={okRows === 0} loading={start.isPending} onClick={() => start.mutate(true)} data-testid="import-valid">
                  {t('sc.bulk.importValid', { count: okRows })}
                </Button>
              </span>
            ) : undefined}>
            {t('sc.bulk.hasErrorsBody')}
          </Notice>
        ) : (
          <Notice tone="success" title={t('sc.bulk.allValid', { products: imp.productCount })}
            action={editable ? <Button size="sm" loading={start.isPending} onClick={() => start.mutate(false)} data-testid="import-all">{t('sc.bulk.importAll', { count: okRows })}</Button> : undefined}>
            {imp.mode === 'UPDATE' ? t('sc.bulk.allValidUpdate') : t('sc.bulk.allValidCreate')}
          </Notice>
        )
      ) : null}

      {phase === 'running' ? (
        <div className="flex flex-col gap-1 rounded-md border border-border bg-surface p-4" role="status" aria-live="polite">
          <strong>{t('sc.bulk.running', { done: imp.processedRows, total: imp.validOnly ? okRows : imp.totalRows })}</strong>
          <div className="h-2 overflow-hidden rounded-pill bg-surface-muted" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress(imp)} aria-label={t('sc.bulk.progress')}>
            <div className="h-full bg-gold-500 transition-[width]" style={{ width: `${progress(imp)}%` }} />
          </div>
          <span className="text-xs text-muted">{t('sc.bulk.runningHint')}</span>
        </div>
      ) : null}

      {phase === 'done' || phase === 'failed' ? (
        <Notice tone={phase === 'failed' || imp.failedRows ? 'warning' : 'success'} title={phase === 'failed' ? t('sc.bulk.failed') : t('sc.bulk.done', { created: imp.createdProducts, updated: imp.updatedProducts })}
          action={<Link href="/listings" className="text-sm">{t('sc.bulk.toListings')}</Link>}>
          <span className="inline-flex flex-wrap gap-x-3">
            <span><CheckCircle size={14} aria-hidden className="text-success" /> {t('sc.bulk.doneCreated', { count: imp.createdProducts })}</span>
            {imp.updatedProducts ? <span><CheckCircle size={14} aria-hidden className="text-success" /> {t('sc.bulk.doneUpdated', { count: imp.updatedProducts })}</span> : null}
            {imp.failedRows ? <span><XCircle size={14} aria-hidden className="text-danger" /> {t('sc.bulk.doneFailed', { count: imp.failedRows })}</span> : null}
            {imp.errorRows ? <span><WarningCircle size={14} aria-hidden className="text-warning" /> {t('sc.bulk.doneSkipped', { count: imp.errorRows })}</span> : null}
          </span>
        </Notice>
      ) : null}

      <DataTable<ProductImportRow>
        id="seller.import-rows"
        label={t('sc.bulk.rowsLabel')}
        columns={columns}
        data={rowsQuery.data ?? []}
        mode="client"
        getRowId={(r) => String(r.rowNumber)}
        state={state}
        loading={rowsQuery.isPending}
        fetching={rowsQuery.isFetching}
        enableSelection={false}
        rowTestId="import-row"
        searchPlaceholder={t('sc.bulk.search')}
        error={rowsQuery.isError ? <ErrorState error={rowsQuery.error} onRetry={() => void rowsQuery.refetch()} /> : undefined}
        toolbar={<Checkbox label={t('sc.bulk.errorsOnly')} checked={errorsOnly} disabled={imp.errorRows === 0} onChange={(e) => setErrorsOnly(e.target.checked)} />}
        empty={errorsOnly ? t('sc.bulk.noErrors') : t('sc.bulk.noRows')}
        noMatches={t('sc.bulk.noMatches')}
      />
    </>
  );
}
