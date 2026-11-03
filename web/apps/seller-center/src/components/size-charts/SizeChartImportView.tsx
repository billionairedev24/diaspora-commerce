'use client';

import { sellerKeys, sellerQueries, type SizeChartImportReport, type SpreadsheetFormat } from '@afrotransact/api-client';
import { Badge, Button, buttonClassName, Checkbox, ChipGroup, cx, Select, useToast } from '@afrotransact/ui';
import { CheckCircle, DownloadSimple, FileArrowUp, FileCsv, MicrosoftExcelLogo } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type DragEvent, useMemo, useState } from 'react';

import { DEFAULT_LIMITS, fileProblem } from '@/components/listings/bulk/bulk-model';
import { leavesOf } from '@/components/listings/form-model';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { download } from '@/lib/export/table';
import { useSeller } from '@/lib/session';

import { actionKey, actionTone, columnName, failedCharts, rowsToShow, stageOf, summaryOf } from './import-model';

const mb = (bytes: number) => Math.round(bytes / 1024 / 1024);
const SHOWN_ROWS = 200;

/** Bulk size chart upload: download the template, fill one row per size, check the file (a dry run that saves nothing), read what it would do row by row,
    then import. A chart is saved whole or not at all; the same name updates the chart, so uploading the same file again changes nothing. */
export function SizeChartImportView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('listings');
  const tree = useQuery(sellerQueries.categories(api));
  const leaves = useMemo(() => leavesOf(tree.data ?? []), [tree.data]);
  const [category, setCategory] = useState('');
  const [format, setFormat] = useState<SpreadsheetFormat>('xlsx');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState('');
  const [over, setOver] = useState(false);
  const [report, setReport] = useState<SizeChartImportReport | null>(null);
  const [problemsOnly, setProblemsOnly] = useState(true);
  const leaf = leaves.find((l) => l.id === category);
  const scope = leaf ? { productTypeId: leaf.id } : {};

  const template = useMutation({
    mutationFn: () => api.downloadSizeChartTemplate({ format, ...scope }),
    onSuccess: (f) => download(f.blob, f.fileName),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const run = useMutation({
    mutationFn: (dryRun: boolean) => {
      if (!file) throw new Error('No file');
      return api.importSizeCharts(file, { ...scope, dryRun });
    },
    onSuccess: (r) => {
      setError('');
      setReport(r);
      setProblemsOnly(r.errorRows + r.warningRows > 0);
      if (r.applied) {
        void qc.invalidateQueries({ queryKey: sellerKeys.sizeCharts(orgId) });
        toast.show(t('sc.szi.toast.imported', { count: r.created + r.updated }));
      }
    },
    onError: (e) => setError(problemMessage(e))
  });
  const choose = (f: File | null | undefined) => {
    if (!f) return;
    const p = fileProblem(f, DEFAULT_LIMITS);
    setReport(null);
    setFile(p ? null : f);
    setError(p ? t(p.key, p.vars) : '');
  };
  const onDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault();
    setOver(false);
    choose(e.dataTransfer.files[0]);
  };
  const stage = stageOf(report);
  const sum = report ? summaryOf(report) : null;
  const shown = report ? rowsToShow(report, problemsOnly) : [];
  const failed = report ? failedCharts(report) : [];

  return (
    <>
      <PageHeader title={t('sc.szi.title')} description={t('sc.szi.description')}
        actions={<Link href="/listings/size-charts" className="text-sm">{t('sc.szi.back')}</Link>} />
      {!editable ? <Notice tone="info" icon="eye" title={t('sc.modulePage.readOnly')}>{t('sc.sz.readOnly')}</Notice> : null}
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <section aria-labelledby="szi-step1" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="szi-step1" className="m-0 text-lg font-semibold">{t('sc.szi.step1')}</h2>
          <p className="m-0 text-sm text-muted">{t('sc.szi.step1Hint')}</p>
          <Select label={t('sc.szi.category')} value={category} onChange={(e) => setCategory(e.target.value)}
            options={[{ value: '', label: t('sc.szi.generic') }, ...leaves.map((l) => ({ value: l.id, label: l.path }))]} />
          <ChipGroup label={t('sc.bulk.format')} value={format} onChange={setFormat} options={[{ value: 'xlsx', label: t('sc.bulk.excel') }, { value: 'csv', label: t('sc.bulk.csv') }]} />
          <Button variant="neutral" loading={template.isPending} disabled={!editable} onClick={() => template.mutate()} className="self-start" data-testid="download-chart-template">
            <DownloadSimple size={18} aria-hidden /> {t('sc.szi.download')}
          </Button>
          <ul className="m-0 flex flex-col gap-1 pl-5 text-sm">
            <li>{t('sc.szi.tip.rows')}</li>
            <li>{t('sc.szi.tip.measurements')}</li>
            <li>{t('sc.szi.tip.values')}</li>
            <li>{t('sc.szi.tip.update')}</li>
          </ul>
        </section>

        <section aria-labelledby="szi-step2" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="szi-step2" className="m-0 text-lg font-semibold">{t('sc.szi.step2')}</h2>
          <label onDragOver={(e) => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)} onDrop={onDrop}
            className={cx('flex min-h-32 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border-2 border-dashed p-4 text-center text-sm',
              over ? 'border-gold-500 bg-gold-50' : error ? 'border-danger bg-surface-sunken' : 'border-border-strong bg-surface-sunken')}>
            {file ? (file.name.toLowerCase().endsWith('.csv') ? <FileCsv size={30} aria-hidden /> : <MicrosoftExcelLogo size={30} aria-hidden />) : <FileArrowUp size={30} aria-hidden />}
            <span>{file ? <strong>{file.name}</strong> : <><strong>{t('sc.bulk.drop')}</strong> {t('sc.bulk.browse')}</>}</span>
            <span className="text-xs text-muted">{t('sc.szi.limits', { mb: mb(DEFAULT_LIMITS.maxFileBytes) })}</span>
            <input type="file" data-testid="chart-file" accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv" className="at-visually-hidden" disabled={!editable}
              onChange={(e) => choose(e.target.files?.[0])} />
          </label>
          {error ? <p role="alert" className="m-0 text-sm text-danger" data-testid="chart-file-error">{error}</p> : null}
          <div className="flex flex-wrap items-center gap-2">
            <Button variant={stage === 'checked' ? 'neutral' : 'primary'} disabled={!file || !editable || stage === 'imported'} loading={run.isPending && run.variables === true} onClick={() => run.mutate(true)}>
              {t('sc.szi.check')}
            </Button>
            <span className="text-xs text-muted">{t('sc.szi.checkHint')}</span>
          </div>
        </section>
      </div>

      {report && sum ? (
        <section aria-labelledby="szi-report" className="flex flex-col gap-4 rounded-md border border-border bg-surface p-4 sm:p-5" data-testid="chart-report">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 id="szi-report" className="m-0 text-lg font-semibold">{stage === 'imported' ? t('sc.szi.reportDone', { file: report.fileName }) : t('sc.szi.reportCheck', { file: report.fileName })}</h2>
            {stage === 'imported' ? (
              <Link href="/listings/size-charts" className={buttonClassName({ variant: 'primary' })}><CheckCircle size={18} aria-hidden /> {t('sc.szi.viewCharts')}</Link>
            ) : (
              <Button variant="primary" disabled={sum.importable === 0 || !editable} loading={run.isPending && run.variables === false} onClick={() => run.mutate(false)}>
                {t('sc.szi.import', { count: sum.importable })}
              </Button>
            )}
          </div>
          <dl className="m-0 grid grid-cols-2 gap-3 sm:grid-cols-5" aria-label={t('sc.szi.summary')}>
            {([['created', sum.created], ['updated', sum.updated], ['unchanged', sum.unchanged], ['failed', sum.failed], ['rowsWithErrors', sum.errorRows]] as const).map(([k, n]) => (
              <div key={k} className="flex flex-col rounded-sm border border-border px-3 py-2" data-testid={`sum-${k}`}>
                <dt className="text-xs text-muted">{t(`sc.szi.sum.${k}`)}</dt>
                <dd className={cx('m-0 text-xl font-bold', (k === 'failed' || k === 'rowsWithErrors') && n > 0 && 'text-danger')}>{n}</dd>
              </div>
            ))}
          </dl>
          {report.fileWarnings.length ? <Notice tone="warning" title={t('sc.szi.fileWarnings')}>{report.fileWarnings.join(' ')}</Notice> : null}
          {stage === 'checked' && sum.failed > 0 ? (
            <Notice tone="danger" title={t('sc.szi.failedTitle', { count: sum.failed })} role="alert">
              {sum.importable > 0 ? t('sc.szi.failedPartial', { count: sum.importable }) : t('sc.szi.failedAll')}
            </Notice>
          ) : null}
          {stage === 'checked' && sum.failed === 0 && sum.importable === 0 ? <Notice tone="info" title={t('sc.szi.nothingTitle')}>{t('sc.szi.nothingBody')}</Notice> : null}

          <div className="overflow-x-auto rounded-sm border border-border">
            <table aria-label={t('sc.szi.chartsLabel')} className="w-full border-collapse text-sm">
              <thead>
                <tr className="bg-surface-muted text-left text-xs">
                  <th scope="col" className="px-3 py-2 font-semibold">{t('sc.sz.col.name')}</th>
                  <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.result')}</th>
                  <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.rows')}</th>
                  <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.note')}</th>
                </tr>
              </thead>
              <tbody>
                {report.charts.map((c) => (
                  <tr key={c.name} className="border-t border-border align-top" data-testid="chart-result">
                    <th scope="row" className="px-3 py-1.5 text-left font-semibold">{c.name}</th>
                    <td className="px-3 py-1.5"><Badge tone={actionTone(c.action)}>{t(actionKey(c.action))}</Badge></td>
                    <td className="px-3 py-1.5 tabular-nums">{c.rows.length}</td>
                    <td className="px-3 py-1.5 text-muted">{c.message ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="m-0 text-base font-semibold">{t('sc.szi.rowsTitle')}</h3>
              <Checkbox label={t('sc.szi.problemsOnly', { count: sum.errorRows + sum.warningRows })} checked={problemsOnly} onChange={(e) => setProblemsOnly(e.target.checked)} />
            </div>
            {failed.length && problemsOnly ? <p className="m-0 text-xs text-muted">{t('sc.szi.savedWhole')}</p> : null}
            {shown.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.szi.noProblems')}</p> : (
              <div className="overflow-x-auto rounded-sm border border-border">
                <table aria-label={t('sc.szi.rowsLabel')} className="w-full border-collapse text-sm">
                  <thead>
                    <tr className="bg-surface-muted text-left text-xs">
                      <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.row')}</th>
                      <th scope="col" className="px-3 py-2 font-semibold">{t('sc.sz.col.name')}</th>
                      <th scope="col" className="px-3 py-2 font-semibold">{t('sc.pf.sizeChart.size')}</th>
                      <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.status')}</th>
                      <th scope="col" className="px-3 py-2 font-semibold">{t('sc.szi.col.messages')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shown.slice(0, SHOWN_ROWS).map((r) => (
                      <tr key={r.rowNumber} className="border-t border-border align-top" data-testid="chart-row" data-status={r.status}>
                        <td className="px-3 py-1.5 tabular-nums">{r.rowNumber}</td>
                        <td className="px-3 py-1.5">{r.chartName}</td>
                        <td className="px-3 py-1.5">{r.size}</td>
                        <td className="px-3 py-1.5"><StatusPill tone={r.status === 'ERROR' ? 'bad' : r.status === 'WARNING' ? 'warn' : 'good'}>{t(`sc.szi.status.${r.status}`)}</StatusPill></td>
                        <td className="px-3 py-1.5">
                          <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
                            {r.messages.map((m, i) => (
                              <li key={i} className={m.severity === 'ERROR' ? 'text-danger' : 'text-warning'}>
                                {columnName(m.column) ? <strong>{columnName(m.column)}: </strong> : null}{m.message}
                              </li>
                            ))}
                          </ul>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {shown.length > SHOWN_ROWS ? <p className="m-0 text-xs text-muted">{t('sc.szi.moreRows', { shown: SHOWN_ROWS, total: shown.length })}</p> : null}
          </div>
        </section>
      ) : null}
    </>
  );
}
