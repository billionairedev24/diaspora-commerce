'use client';

import { sellerKeys, sellerQueries, type SpreadsheetFormat, type StockImportMode } from '@afrotransact/api-client';
import { Button, buttonClassName, ChipGroup, cx, Select, useToast } from '@afrotransact/ui';
import { DownloadSimple, FileArrowUp, FileCsv, ImagesSquare, MicrosoftExcelLogo } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { type DragEvent, useMemo, useState } from 'react';

import { RequestDepartmentLink } from '@/components/listings/RequestDepartment';
import { PageHeader } from '@/components/ui/PageHeader';
import { problemMessage } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { download } from '@/lib/export/table';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { leavesOf } from '../form-model';
import { DEFAULT_LIMITS, fileProblem, limitsFor, modeLabelKey, UPLOAD_KINDS, type UploadKind } from './bulk-model';

const mb = (bytes: number) => Math.round(bytes / 1024 / 1024);

/** What each template holds, one plain line per column group (the server makes these templates; this is the summary beside the download). */
const TIPS: Record<'OFFER' | 'PRICE_QTY' | 'STOCK', MessageKey[]> = {
  OFFER: ['sc.bulk.tip.OFFER.page', 'sc.bulk.tip.OFFER.terms', 'sc.bulk.tip.OFFER.noPhotos', 'sc.bulk.tip.OFFER.held'],
  PRICE_QTY: ['sc.bulk.tip.PRICE_QTY.keys', 'sc.bulk.tip.PRICE_QTY.blank', 'sc.bulk.tip.PRICE_QTY.own'],
  STOCK: ['sc.bulk.tip.STOCK.columns', 'sc.bulk.tip.STOCK.reserved', 'sc.bulk.tip.STOCK.deltaOnce']
};

/** Bulk upload (S308, S393 to S395): choose what the file does (new products, update products, add my offers to existing
    products, update price and quantity, update stock), download its template (the server makes it: nothing per category for
    offers, the feed and stock), fill it, upload it, then review the preview. */
export function BulkUploadView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const { orgId, can } = useSeller();
  const tree = useQuery(sellerQueries.categories(api, orgId));
  const ctx = useQuery(sellerQueries.listingContext(api, orgId));
  const recent = useQuery(sellerQueries.imports(api, orgId, 0, 5));
  const recentStock = useQuery({ ...sellerQueries.stockImports(api, orgId, 0, 3), enabled: can('inventory', 'VIEW') });
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
  const kinds = UPLOAD_KINDS.filter((k) => k.modules.every((m) => can(m)));
  const [chosen, setKind] = useState<UploadKind>('CREATE');
  const kind: UploadKind = kinds.some((k) => k.kind === chosen) ? chosen : (kinds[0]?.kind ?? 'CREATE');
  const [stockMode, setStockMode] = useState<StockImportMode>('ABSOLUTE');
  const productFile = kind === 'CREATE' || kind === 'UPDATE';
  const lim = limitsFor(kind, limits);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState('');
  const [over, setOver] = useState(false);
  const leaf = leavesOf(tree.data ?? []).find((l) => l.id === category);

  const template = useMutation({
    mutationFn: () => (kind === 'STOCK'
      ? api.downloadStockImportTemplate(stockMode)
      : api.downloadImportTemplate({ format, ...(kind === 'OFFER' || kind === 'PRICE_QTY' ? { mode: kind } : leaf ? { categoryId: leaf.id } : {}) })),
    onSuccess: (f) => download(f.blob, f.fileName),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const upload = useMutation({
    mutationFn: async (f: File) => (kind === 'STOCK'
      ? { stock: true, id: (await api.uploadStockImport(f, stockMode)).id }
      : { stock: false, id: (await api.uploadImport(f, kind, productFile ? leaf?.productType : undefined)).id }),
    onSuccess: ({ stock, id }) => {
      void qc.invalidateQueries({ queryKey: stock ? sellerKeys.stockImports(orgId) : sellerKeys.imports(orgId) });
      router.push(`/listings/bulk/${stock ? 'stock/' : ''}${encodeURIComponent(id)}`);
    },
    onError: (e) => setError(problemMessage(e))
  });
  const choose = (f: File | null | undefined) => {
    if (!f) return;
    const p = fileProblem(f, limits, kind);
    setFile(p ? null : f);
    setError(p ? t(p.key, p.vars) : '');
  };
  const onDrop = (e: DragEvent<HTMLLabelElement>) => {
    e.preventDefault();
    setOver(false);
    choose(e.dataTransfer.files[0]);
  };

  const pick = (k: UploadKind) => {
    setKind(k);
    setFile(null);
    setError('');
  };

  return (
    <>
      <PageHeader tour="page-bulk" title={t('sc.bulk.title')} description={t('sc.bulk.description')}
        actions={<>
          <Link href="/listings/images" className="text-sm">{t('sc.listings.images')}</Link>
          <Link href="/listings/bulk/history" className="text-sm">{t('sc.bulk.historyLink')}</Link>
          <Link href="/listings/new" className="text-sm">{t('sc.bulk.oneLink')}</Link>
        </>} />
      <section aria-labelledby="bulk-kind" className="flex flex-col gap-2 rounded-md border border-border bg-surface p-4 sm:p-5" data-tour="bulk-upload-types">
        <h2 id="bulk-kind" className="m-0 text-lg font-semibold">{t('sc.bulk.mode')}</h2>
        <div role="radiogroup" aria-labelledby="bulk-kind" className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {kinds.map(({ kind: k }) => (
            <label key={k} aria-label={t(modeLabelKey(k))} className={cx('flex cursor-pointer gap-2 rounded-sm border p-3', kind === k ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface')}>
              <input type="radio" name="bulk-mode" value={k} checked={kind === k} onChange={() => pick(k)} className="mt-1" data-testid={`bulk-kind-${k}`} />
              <span className="flex flex-col">
                <strong className="text-sm">{t(modeLabelKey(k))}</strong>
                <span className="text-xs text-muted">{t(`${modeLabelKey(k)}.hint` as MessageKey)}</span>
              </span>
            </label>
          ))}
        </div>
      </section>
      {productFile ? (
      <section aria-labelledby="bulk-photos" className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-surface px-4 py-3 sm:px-5">
        <ImagesSquare size={28} aria-hidden className="shrink-0 text-muted" />
        <div className="flex min-w-0 flex-1 flex-col">
          <h2 id="bulk-photos" className="m-0 text-base font-semibold">{t('sc.bulk.photosTitle')}</h2>
          <p className="m-0 text-sm text-muted">{t('sc.bulk.photosHint')}</p>
        </div>
        <Link href="/listings/images" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>{t('sc.bulk.photosLink')}</Link>
      </section>
      ) : null}
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <section aria-labelledby="bulk-step1" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="bulk-step1" className="m-0 text-lg font-semibold">{t('sc.bulk.step1')}</h2>
          <p className="m-0 text-sm text-muted">{productFile ? t('sc.bulk.step1Hint') : t(`sc.bulk.step1Hint.${kind}` as MessageKey)}</p>
          {productFile ? (
            <>
              <Select label={t('sc.bulk.category')} value={category} onChange={(e) => setCategory(e.target.value)}
                options={[{ value: '', label: t('sc.bulk.generic') }, ...leavesOf(tree.data ?? []).map((l) => ({ value: l.id, label: l.path }))]} />
              <RequestDepartmentLink />
            </>
          ) : null}
          {kind === 'STOCK' ? (
            <ChipGroup label={t('sc.bulk.stockMode')} value={stockMode} onChange={setStockMode}
              options={[{ value: 'ABSOLUTE', label: t('sc.bulk.stockMode.ABSOLUTE') }, { value: 'DELTA', label: t('sc.bulk.stockMode.DELTA') }]} />
          ) : (
            <ChipGroup label={t('sc.bulk.format')} value={format} onChange={setFormat}
              options={[{ value: 'xlsx', label: t('sc.bulk.excel') }, { value: 'csv', label: t('sc.bulk.csv') }]} />
          )}
          <Button variant="neutral" loading={template.isPending} onClick={() => template.mutate()} className="self-start" data-testid="download-template">
            <DownloadSimple size={18} aria-hidden /> {t('sc.bulk.download')}
          </Button>
          {productFile ? (
            <ul className="m-0 flex flex-col gap-1 pl-5 text-sm">
              <li>{t('sc.bulk.tip.required')}</li>
              <li>{t('sc.bulk.tip.variants')}</li>
              <li>{t('sc.bulk.tip.images', { max: limits.maxImagesPerProduct })}</li>
              <li>{t('sc.bulk.tip.lists', { types: types.length })}</li>
            </ul>
          ) : (
            <ul className="m-0 flex flex-col gap-1 pl-5 text-sm" data-testid="bulk-columns">
              {TIPS[kind].map((k) => <li key={k}>{t(k)}</li>)}
            </ul>
          )}
        </section>

        <section aria-labelledby="bulk-step2" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="bulk-step2" className="m-0 text-lg font-semibold">{t('sc.bulk.step2')}</h2>
          <label
            onDragOver={(e) => { e.preventDefault(); setOver(true); }}
            onDragLeave={() => setOver(false)}
            onDrop={onDrop}
            className={cx('flex min-h-32 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border-2 border-dashed p-4 text-center text-sm',
              over ? 'border-gold-500 bg-gold-50' : error ? 'border-danger bg-surface-sunken' : 'border-border-strong bg-surface-sunken')}
          >
            {file ? (file.name.toLowerCase().endsWith('.csv') ? <FileCsv size={30} aria-hidden /> : <MicrosoftExcelLogo size={30} aria-hidden />) : <FileArrowUp size={30} aria-hidden />}
            <span>{file ? <strong>{file.name}</strong> : <><strong>{t('sc.bulk.drop')}</strong> {t('sc.bulk.browse')}</>}</span>
            <span className="text-xs text-muted">{t(kind === 'STOCK' ? 'sc.bulk.limitsCsv' : 'sc.bulk.limits', { mb: mb(lim.maxFileBytes), rows: lim.maxRows.toLocaleString() })}</span>
            <input type="file" data-testid="bulk-file" accept={kind === 'STOCK' ? '.csv,text/csv' : '.xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv'} className="at-visually-hidden"
              onChange={(e) => choose(e.target.files?.[0])} />
          </label>
          {error ? <p role="alert" className="m-0 text-sm text-danger" data-testid="bulk-error">{error}</p> : null}
          <Button disabled={!file} loading={upload.isPending} onClick={() => file && upload.mutate(file)} className="self-start">
            {t('sc.bulk.upload')}
          </Button>
          <p className="m-0 text-xs text-muted">{kind === 'STOCK' ? t('sc.bulk.uploadNoteStock') : t('sc.bulk.uploadNote')}</p>
        </section>
      </div>
      {recentStock.data?.items.length ? (
        <section aria-labelledby="bulk-recent-stock" className="flex flex-col gap-2">
          <h2 id="bulk-recent-stock" className="m-0 text-lg font-semibold">{t('sc.bulk.recentStock')}</h2>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {recentStock.data.items.map((i) => (
              <li key={i.id}>
                <Link href={`/listings/bulk/stock/${encodeURIComponent(i.id)}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-sm border border-border bg-surface px-3 py-2 text-sm">
                  <strong className="text-text">{i.fileName}</strong>
                  <span className="text-xs text-muted">{i.reference} · {shortDate(i.createdAt)} · {t('sc.bulk.rowsCount', { count: i.totalRows })}</span>
                  <span className="ml-auto"><StatusPill tone={toneOf(i.status === 'VALIDATED' && i.errorRows ? 'OPEN' : i.status)}>{t(`sc.bulk.status.${i.status}`)}</StatusPill></span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
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
