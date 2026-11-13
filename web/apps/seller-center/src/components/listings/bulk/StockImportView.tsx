'use client';

import { isApiError, pollWhile, sellerKeys, sellerQueries,type StockImport, type StockImportRow, type StockImportRowPage } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Checkbox, cx, useToast } from '@afrotransact/ui';
import { CheckCircle, DownloadSimple, WarningCircle, XCircle } from '@phosphor-icons/react/ssr';
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

import { phaseOf } from './bulk-model';

const PAGE = 200;
const POLL_MS = 1500;

/** Every row of a stock file (optionally only those with errors), fetched page by page (at most 50,000 rows). */
async function allRows(load: (page: number) => Promise<StockImportRowPage>): Promise<StockImportRow[]> {
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

/** One stock file (S395): validation summary, the preview with before and after counts and a plain message per row, the row
    report (CSV), then the background import with progress; completed files say what changed and what was already right.
    The organisation is the signed-in one (the BFF never takes it from the browser); a count below the units reserved for
    orders is a row error. */
export function StockImportView({ importId }: { importId: string }) {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('inventory');
  const [errorsOnly, setErrorsOnly] = useState(false);
  const job = useQuery(sellerQueries.stockImportJob(api, orgId, importId, pollWhile(POLL_MS, (d) => d?.status === 'QUEUED' || d?.status === 'RUNNING')));
  const imp = job.data;
  const phase = imp ? phaseOf(imp) : 'review';
  const earlier = useQuery({ ...sellerQueries.stockImportJob(api, orgId, imp?.sameFileAs ?? ''), enabled: Boolean(imp?.sameFileAs) });
  const rowsQuery = useQuery({
    queryKey: [...sellerKeys.stockImportJob(orgId, importId), 'all-rows', errorsOnly ? 'ERROR' : 'ALL', imp?.status ?? '', imp?.processedRows ?? 0],
    queryFn: ({ signal }) => allRows((page) => api.listStockImportRows(importId, { ...(errorsOnly ? { status: 'ERROR' as const } : {}), page, size: PAGE }, signal)),
    enabled: Boolean(imp),
    placeholderData: (prev) => prev
  });
  const state = useTableState({ prefix: 'rows', defaultPageSize: 50, syncUrl: false });

  const wasRunning = useRef(false);
  useEffect(() => {
    if (!imp) return;
    if (phase === 'running') wasRunning.current = true;
    else if (wasRunning.current && phase === 'done') {
      wasRunning.current = false;
      void qc.invalidateQueries({ queryKey: sellerKeys.stockImports(orgId) });
      void qc.invalidateQueries({ queryKey: [...sellerKeys.org(orgId), 'inventory'] });
      toast.show(t('sc.bulk.stock.done', { applied: imp.appliedRows, unchanged: imp.unchangedRows }));
    }
  }, [imp, phase, qc, orgId, toast, t]);

  const start = useMutation({
    mutationFn: (validOnly: boolean) => api.startStockImport(importId, validOnly),
    onSuccess: (i: StockImport) => {
      qc.setQueryData(sellerKeys.stockImportJob(orgId, importId), i);
      void qc.invalidateQueries({ queryKey: sellerKeys.stockImports(orgId) });
    },
    onError: (e) => toast.show(isApiError(e) ? problemMessage(e) : t('sc.bulk.startFailed'), 'error')
  });
  const report = useMutation({
    mutationFn: () => api.downloadStockImportReport(importId),
    onSuccess: (f) => download(f.blob, f.fileName),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const delta = imp?.mode === 'DELTA';
  const columns = useMemo<ColumnDef<StockImportRow>[]>(() => {
    const statusLabel = (r: StockImportRow) => (phase === 'review' || r.outcome === 'PENDING' ? t(`sc.bulk.row.${r.status}`) : t(`sc.bulk.outcome.${r.outcome}`));
    const tone = (r: StockImportRow) => (r.outcome === 'APPLIED' || r.outcome === 'UNCHANGED' || (r.outcome === 'PENDING' && r.status === 'VALID') ? 'good' : r.status === 'ERROR' || r.outcome === 'FAILED' ? 'bad' : 'info');
    return [
      { id: 'rowNumber', accessorFn: (r) => r.rowNumber, header: t('sc.bulk.col.row'), size: 84, meta: { kind: 'number', label: t('sc.bulk.col.row') } },
      { id: 'status', accessorFn: statusLabel, header: t('sc.bulk.col.status'), size: 130, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={tone(row.original)}>{statusLabel(row.original)}</StatusPill> },
      { id: 'sku', accessorFn: (r) => r.sku, header: t('sc.bulk.stock.col.sku'), size: 170, meta: { kind: 'mono' } },
      { id: 'location', accessorFn: (r) => r.location, header: t('sc.bulk.stock.col.location'), size: 110, meta: { kind: 'mono' } },
      { id: 'quantity', accessorFn: (r) => r.quantity ?? '', header: delta ? t('sc.bulk.stock.col.change') : t('sc.bulk.stock.col.count'), size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'before', accessorFn: (r) => r.before ?? '', header: t('sc.bulk.stock.col.before'), size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'after', accessorFn: (r) => r.after ?? '', header: t('sc.bulk.stock.col.after'), size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'lot', accessorFn: (r) => r.lot ?? '', header: t('sc.bulk.stock.col.lot'), size: 110, meta: { kind: 'mono' } },
      { id: 'expiry', accessorFn: (r) => r.expiry ?? '', header: t('sc.bulk.stock.col.expiry'), size: 110, meta: { kind: 'mono' } },
      {
        id: 'problems', accessorFn: (r) => r.messages.map((m) => m.message).join(' '), header: t('sc.bulk.col.problems'), size: 340, enableSorting: false,
        cell: ({ row }) => row.original.messages.length || row.original.outcomeNote ? (
          <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-xs">
            {row.original.messages.map((m, i) => (
              <li key={i} className={m.severity === 'ERROR' ? 'text-danger' : 'text-warning'} data-testid="stock-row-message">
                {m.column ? <strong>{m.column}: </strong> : null}{m.message}
              </li>
            ))}
            {row.original.outcomeNote ? <li className="text-muted">{row.original.outcomeNote}</li> : null}
          </ul>
        ) : <span className="text-xs text-muted">—</span>
      }
    ];
  }, [phase, t, delta]);

  if (job.isError) return <ErrorState error={job.error} onRetry={() => void job.refetch()} />;
  if (!imp) return <Spinner label={t('sc.bulk.loading')} />;

  const okRows = imp.validRows + imp.warningRows;
  return (
    <>
      <PageHeader title={imp.fileName}
        description={t('sc.bulk.stock.meta', { reference: imp.reference, mode: t(delta ? 'sc.bulk.stockMode.DELTA' : 'sc.bulk.stockMode.ABSOLUTE'), when: dateTime(imp.createdAt) })}
        actions={<>
          <Link href="/listings/bulk/history" className="text-sm">{t('sc.bulk.historyLink')}</Link>
          <Button variant="neutral" size="sm" loading={report.isPending} onClick={() => report.mutate()} data-testid="stock-report">
            <DownloadSimple size={16} aria-hidden /> {t('sc.bulk.stock.report')}
          </Button>
        </>} />
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" data-testid="import-stats">
        <Stat label={t('sc.bulk.stat.rows')} value={imp.totalRows} />
        <Stat label={t('sc.bulk.stat.valid')} value={imp.validRows} tone="good" />
        <Stat label={t('sc.bulk.stat.warnings')} value={imp.warningRows} tone={imp.warningRows ? 'warn' : undefined} />
        <Stat label={t('sc.bulk.stat.errors')} value={imp.errorRows} tone={imp.errorRows ? 'bad' : undefined} />
      </div>
      {phase === 'review' && earlier.data ? (
        <Notice tone="info" title={t('sc.bulk.sameFile.title', { date: dateTime(earlier.data.createdAt) })}
          action={<Link href={`/listings/bulk/stock/${encodeURIComponent(earlier.data.id)}`} className="text-sm" data-testid="same-file-link">{t('sc.bulk.sameFile.show')}</Link>}>
          {t('sc.bulk.sameFile.body')}
        </Notice>
      ) : null}

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
          <Notice tone="success" title={t('sc.bulk.stock.allValid', { count: imp.totalRows })}
            action={editable ? <Button size="sm" loading={start.isPending} onClick={() => start.mutate(false)} data-testid="import-all">{t('sc.bulk.importAll', { count: okRows })}</Button> : undefined}>
            {delta ? t('sc.bulk.stock.allValidDELTA') : t('sc.bulk.stock.allValidABSOLUTE')}
          </Notice>
        )
      ) : null}

      {phase === 'running' ? (
        <div className="flex flex-col gap-1 rounded-md border border-border bg-surface p-4" role="status" aria-live="polite">
          <strong>{t('sc.bulk.running', { done: imp.processedRows, total: okRows })}</strong>
          <div className="h-2 overflow-hidden rounded-pill bg-surface-muted" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={okRows ? Math.min(100, Math.round((imp.processedRows / okRows) * 100)) : 100} aria-label={t('sc.bulk.progress')}>
            <div className="h-full bg-gold-500 transition-[width]" style={{ width: `${okRows ? Math.min(100, Math.round((imp.processedRows / okRows) * 100)) : 100}%` }} />
          </div>
          <span className="text-xs text-muted">{t('sc.bulk.runningHint')}</span>
        </div>
      ) : null}

      {phase === 'done' || phase === 'failed' ? (
        <Notice tone={phase === 'failed' || imp.failedRows ? 'warning' : 'success'} title={phase === 'failed' ? t('sc.bulk.stock.failed') : t('sc.bulk.stock.done', { applied: imp.appliedRows, unchanged: imp.unchangedRows })}
          action={<Link href="/inventory" className="text-sm">{t('sc.bulk.stock.toInventory')}</Link>}>
          <span className="inline-flex flex-wrap gap-x-3">
            <span><CheckCircle size={14} aria-hidden className="text-success" /> {t('sc.bulk.stock.doneApplied', { count: imp.appliedRows })}</span>
            {imp.unchangedRows ? <span><CheckCircle size={14} aria-hidden className="text-success" /> {t('sc.bulk.stock.doneUnchanged', { count: imp.unchangedRows })}</span> : null}
            {imp.failedRows ? <span><XCircle size={14} aria-hidden className="text-danger" /> {t('sc.bulk.doneFailed', { count: imp.failedRows })}</span> : null}
            {imp.errorRows ? <span><WarningCircle size={14} aria-hidden className="text-warning" /> {t('sc.bulk.doneSkipped', { count: imp.errorRows })}</span> : null}
          </span>
        </Notice>
      ) : null}

      <DataTable<StockImportRow>
        id="seller.stock-import-rows"
        label={t('sc.bulk.stock.rowsLabel')}
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
