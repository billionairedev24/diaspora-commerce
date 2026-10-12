'use client';

import { type ImportMode, sellerKeys, sellerQueries, type SpreadsheetFormat } from '@afrotransact/api-client';
import { Button, ChipGroup, cx, Select, useToast } from '@afrotransact/ui';
import { DownloadSimple, FileArrowUp, FileCsv, MicrosoftExcelLogo } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type DragEvent, useMemo, useState } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { download } from '@/lib/export/table';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { leavesOf } from '../form-model';
import { DEFAULT_LIMITS, fileProblem } from './bulk-model';

const mb = (bytes: number) => Math.round(bytes / 1024 / 1024);

/** Bulk upload (S308): download a template (generic or for one product type, Excel or CSV), fill it, upload it in
    Create or Update mode, then review the preview. */
export function BulkUploadView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const { orgId } = useSeller();
  const tree = useQuery(sellerQueries.categories(api));
  const ctx = useQuery(sellerQueries.listingContext(api, orgId));
  const recent = useQuery(sellerQueries.imports(api, orgId, 0, 5));
  const limits = ctx.data?.importLimits ?? DEFAULT_LIMITS;
  const types = useMemo(() => {
    const seen = new Map<string, string>();
    leavesOf(tree.data ?? []).forEach((l) => {
      if (!seen.has(l.productType)) seen.set(l.productType, l.path);
    });
    return [...seen.entries()];
  }, [tree.data]);
  const [category, setCategory] = useState('');
  const [format, setFormat] = useState<SpreadsheetFormat>('xlsx');
  const [mode, setMode] = useState<ImportMode>('CREATE');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState('');
  const [over, setOver] = useState(false);
  const leaf = leavesOf(tree.data ?? []).find((l) => l.id === category);

  const template = useMutation({
    mutationFn: () => api.downloadImportTemplate({ format, ...(leaf ? { categoryId: leaf.id } : {}) }),
    onSuccess: (f) => download(f.blob, f.fileName),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const upload = useMutation({
    mutationFn: (f: File) => api.uploadImport(f, mode, leaf?.productType),
    onSuccess: (imp) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.imports(orgId) });
      router.push(`/listings/bulk/${encodeURIComponent(imp.id)}`);
    },
    onError: (e) => setError(problemMessage(e))
  });
  const choose = (f: File | null | undefined) => {
    if (!f) return;
    const p = fileProblem(f, limits);
    setFile(p ? null : f);
    setError(p ? t(p.key, p.vars) : '');
  };
  const onDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault();
    setOver(false);
    choose(e.dataTransfer.files[0]);
  };

  return (
    <>
      <PageHeader title={t('sc.bulk.title')} description={t('sc.bulk.description')}
        actions={<>
          <Link href="/listings/bulk/history" className="text-sm">{t('sc.bulk.historyLink')}</Link>
          <Link href="/listings/new" className="text-sm">{t('sc.bulk.oneLink')}</Link>
        </>} />
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <section aria-labelledby="bulk-step1" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="bulk-step1" className="m-0 text-lg font-semibold">{t('sc.bulk.step1')}</h2>
          <p className="m-0 text-sm text-muted">{t('sc.bulk.step1Hint')}</p>
          <Select label={t('sc.bulk.category')} value={category} onChange={(e) => setCategory(e.target.value)}
            options={[{ value: '', label: t('sc.bulk.generic') }, ...leavesOf(tree.data ?? []).map((l) => ({ value: l.id, label: l.path }))]} />
          <ChipGroup label={t('sc.bulk.format')} value={format} onChange={setFormat}
            options={[{ value: 'xlsx', label: t('sc.bulk.excel') }, { value: 'csv', label: t('sc.bulk.csv') }]} />
          <Button variant="neutral" loading={template.isPending} onClick={() => template.mutate()} className="self-start" data-testid="download-template">
            <DownloadSimple size={18} aria-hidden /> {t('sc.bulk.download')}
          </Button>
          <ul className="m-0 flex flex-col gap-1 pl-5 text-sm">
            <li>{t('sc.bulk.tip.required')}</li>
            <li>{t('sc.bulk.tip.variants')}</li>
            <li>{t('sc.bulk.tip.images', { max: limits.maxImagesPerProduct })}</li>
            <li>{t('sc.bulk.tip.lists', { types: types.length })}</li>
          </ul>
        </section>

        <section aria-labelledby="bulk-step2" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="bulk-step2" className="m-0 text-lg font-semibold">{t('sc.bulk.step2')}</h2>
          <div role="radiogroup" aria-label={t('sc.bulk.mode')} className="grid gap-2 sm:grid-cols-2">
            {(['CREATE', 'UPDATE'] as const).map((m) => (
              <label key={m} aria-label={m === 'CREATE' ? t('sc.bulk.mode.CREATE') : t('sc.bulk.mode.UPDATE')} className={cx('flex cursor-pointer gap-2 rounded-sm border p-3', mode === m ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
                <input type="radio" name="bulk-mode" value={m} checked={mode === m} onChange={() => setMode(m)} className="mt-1" />
                <span className="flex flex-col">
                  <strong className="text-sm">{m === 'CREATE' ? t('sc.bulk.mode.CREATE') : t('sc.bulk.mode.UPDATE')}</strong>
                  <span className="text-xs text-muted">{m === 'CREATE' ? t('sc.bulk.mode.CREATE.hint') : t('sc.bulk.mode.UPDATE.hint')}</span>
                </span>
              </label>
            ))}
          </div>
          <label
            onDragOver={(e) => { e.preventDefault(); setOver(true); }}
            onDragLeave={() => setOver(false)}
            onDrop={onDrop}
            className={cx('flex min-h-32 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border-2 border-dashed p-4 text-center text-sm',
              over ? 'border-gold-500 bg-gold-50' : error ? 'border-danger bg-surface-sunken' : 'border-border-strong bg-surface-sunken')}
          >
            {file ? (file.name.toLowerCase().endsWith('.csv') ? <FileCsv size={30} aria-hidden /> : <MicrosoftExcelLogo size={30} aria-hidden />) : <FileArrowUp size={30} aria-hidden />}
            <span>{file ? <strong>{file.name}</strong> : <><strong>{t('sc.bulk.drop')}</strong> {t('sc.bulk.browse')}</>}</span>
            <span className="text-xs text-muted">{t('sc.bulk.limits', { mb: mb(limits.maxFileBytes), rows: limits.maxRows.toLocaleString() })}</span>
            <input type="file" data-testid="bulk-file" accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv" className="at-visually-hidden"
              onChange={(e) => choose(e.target.files?.[0])} />
          </label>
          {error ? <p role="alert" className="m-0 text-sm text-danger" data-testid="bulk-error">{error}</p> : null}
          <Button disabled={!file} loading={upload.isPending} onClick={() => file && upload.mutate(file)} className="self-start">
            {t('sc.bulk.upload')}
          </Button>
          <p className="m-0 text-xs text-muted">{t('sc.bulk.uploadNote')}</p>
        </section>
      </div>
      {recent.data?.items.length ? (
        <section aria-labelledby="bulk-recent" className="flex flex-col gap-2">
          <h2 id="bulk-recent" className="m-0 text-lg font-semibold">{t('sc.bulk.recent')}</h2>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {recent.data.items.map((i) => (
              <li key={i.id}>
                <Link href={`/listings/bulk/${encodeURIComponent(i.id)}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-sm border border-border bg-surface px-3 py-2 text-sm">
                  <strong className="text-text">{i.fileName}</strong>
                  <span className="text-xs text-muted">{i.reference} · {shortDate(i.createdAt)} · {t('sc.bulk.rowsCount', { count: i.totalRows })}</span>
                  <span className="ml-auto"><StatusPill tone={toneOf(i.status === 'VALIDATED' && i.errorRows ? 'OPEN' : i.status)}>{t(`sc.bulk.status.${i.status}`)}</StatusPill></span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
