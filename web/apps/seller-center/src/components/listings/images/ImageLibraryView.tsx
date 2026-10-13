'use client';

import { type ImageLibraryItem, type ImageLibraryQuery, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, buttonClassName, cx, Select, useToast } from '@afrotransact/ui';
import { FileArrowUp, FolderOpen, ImagesSquare, LinkSimple, Trash } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type DragEvent, useEffect, useMemo, useRef, useState } from 'react';

import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { baseName, droppedFiles, expandFiles, LIBRARY_TYPES, type LibraryType, MAX_FILES_PER_DROP, MAX_LIBRARY_BYTES, parseLibraryName, typeOf } from './library-files';

type Progress = { name: string; state: 'queued' | 'uploading' | 'done' | 'failed' | 'skipped'; note: string };

const STATUS_TONE: Record<ImageLibraryItem['status'], Tone> = { READY: 'good', FLAGGED: 'warn', REJECTED: 'bad' };
const PARALLEL = 4;

/** Product images (S310): the organisation's image library. Photos named after a SKU (SKU.MAIN.jpg, SKU.PT01.jpg …,
    SKU.SWCH.jpg) are matched to listings — the bulk upload uses them for rows without image links, and "Attach to
    listings" applies them to existing products. Files, folders and ZIPs can be dropped. */
export function ImageLibraryView() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const state = useTableState({ searchParams: useSearchParams() });
  const [status, setStatus] = useState<'' | ImageLibraryItem['status']>('');
  const [matched, setMatched] = useState<'' | 'true' | 'false'>('');
  const query: ImageLibraryQuery = {
    q: state.q || undefined,
    status: status || undefined,
    matched: matched === '' ? undefined : matched === 'true',
    page: state.pagination.pageIndex,
    size: state.pagination.pageSize
  };
  const q = useQuery({ ...sellerQueries.imageLibrary(api, orgId, query), placeholderData: keepPreviousData });
  const [progress, setProgress] = useState<Progress[]>([]);
  const [busy, setBusy] = useState(false);
  const [over, setOver] = useState(false);
  const folderInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Not in React's input attributes: lets the second picker choose a whole folder.
    folderInput.current?.setAttribute('webkitdirectory', '');
  }, []);

  const refresh = () => void qc.invalidateQueries({ queryKey: sellerKeys.imageLibrary(orgId) });

  const remove = useMutation({
    mutationFn: (assetId: string) => api.removeLibraryImage(assetId),
    onSuccess: () => {
      toast.show(t('sc.img.removed'));
      refresh();
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const attach = useMutation({
    mutationFn: () => api.attachLibraryImages(),
    onSuccess: (r) => {
      toast.show(r.productsUpdated
        ? t('sc.img.attached', { products: r.productsUpdated, images: r.imagesAttached }) + (r.skipped.length ? ` ${t('sc.img.attachedSkipped', { n: r.skipped.length })}` : '')
        : t('sc.img.attachedNone'));
      refresh();
      void qc.invalidateQueries({ queryKey: sellerKeys.org(orgId) });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const uploadOne = async (file: File, type: LibraryType) => {
    const ticket = await api.requestUpload({ purpose: 'product-image', fileName: file.name, contentType: type, size: file.size, library: true });
    const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
    if (!put.ok) throw new Error(`upload ${put.status}`);
    return api.completeUpload(ticket.assetId);
  };

  const add = async (raw: readonly File[]) => {
    if (busy || !raw.length) return;
    setBusy(true);
    let files: File[];
    try {
      files = await expandFiles(raw);
    } catch {
      toast.show(t('sc.img.zipFailed'), 'error');
      setBusy(false);
      return;
    }
    files = files.slice(0, MAX_FILES_PER_DROP);
    const list: Progress[] = files.map((f) => {
      const type = typeOf(f.name);
      if (!type || !(LIBRARY_TYPES as readonly string[]).includes(f.type || type)) return { name: baseName(f.name), state: 'skipped', note: t('sc.img.notImage') };
      if (f.size > MAX_LIBRARY_BYTES) return { name: baseName(f.name), state: 'skipped', note: t('sc.img.tooLarge') };
      const parsed = parseLibraryName(f.name);
      return { name: baseName(f.name), state: 'queued', note: parsed ? t('sc.img.willMatch', { sku: parsed.sku, slot: slotLabel(t, parsed.slot) }) : t('sc.img.noMatchName') };
    });
    setProgress(list);
    const set = (i: number, patch: Partial<Progress>) => setProgress((p) => p.map((x, j) => (j === i ? { ...x, ...patch } : x)));
    let next = 0;
    const worker = async () => {
      while (next < files.length) {
        const i = next++;
        const f = files[i];
        const type = f ? typeOf(f.name) : null;
        if (!f || !type || list[i]?.state === 'skipped') continue;
        set(i, { state: 'uploading' });
        try {
          const asset = await uploadOne(f, type);
          set(i, asset.status === 'READY' ? { state: 'done' } : { state: 'done', note: t('sc.img.held') });
        } catch {
          set(i, { state: 'failed', note: t('sc.img.uploadFailed') });
        }
      }
    };
    await Promise.all(Array.from({ length: PARALLEL }, worker));
    setBusy(false);
    refresh();
  };

  const onDrop = (e: DragEvent<HTMLElement>) => {
    e.preventDefault();
    setOver(false);
    void droppedFiles(e.dataTransfer).then(add);
  };

  const columns = useMemo<ColumnDef<ImageLibraryItem>[]>(() => [
    {
      id: 'image', accessorFn: (r) => r.fileName, header: t('sc.img.col.image'), size: 76, enableSorting: false, meta: { label: t('sc.img.col.image') },
      cell: ({ row }) => row.original.url
        ? <img src={row.original.url} alt="" loading="lazy" className="h-12 w-12 rounded-xs border border-border object-cover" />
        : <span className="flex h-12 w-12 items-center justify-center rounded-xs border border-border bg-surface-sunken text-muted"><ImagesSquare size={20} aria-hidden /></span>
    },
    { id: 'fileName', accessorFn: (r) => r.fileName, header: t('sc.img.col.file'), size: 240, meta: { kind: 'strong' } },
    {
      id: 'sku', accessorFn: (r) => r.sku ?? '', header: t('sc.img.col.match'), size: 220, enableSorting: false,
      cell: ({ row }) => row.original.sku
        ? (
          <span className="flex flex-col">
            <span className="font-mono text-sm">{row.original.sku} · {slotLabel(t, row.original.slot ?? '')}</span>
            <span className={cx('text-xs', row.original.skuKnown ? 'text-success' : 'text-warning')}>{row.original.skuKnown ? t('sc.img.skuKnown') : t('sc.img.skuUnknown')}</span>
          </span>
        )
        : <span className="text-xs text-muted">{t('sc.img.noMatchName')}</span>
    },
    {
      id: 'status', accessorFn: (r) => r.status, header: t('sc.img.col.status'), size: 150, meta: { kind: 'status' },
      cell: ({ row }) => (
        <span className="flex flex-col gap-0.5">
          <StatusPill tone={STATUS_TONE[row.original.status]}>{t(`sc.img.status.${row.original.status}`)}</StatusPill>
          {row.original.flags.length ? <span className="text-2xs text-muted">{row.original.flags.join(' · ')}</span> : null}
        </span>
      )
    },
    {
      id: 'usedBy', accessorFn: (r) => r.usedBy.join(', '), header: t('sc.img.col.usedBy'), size: 180, enableSorting: false,
      cell: ({ row }) => row.original.usedBy.length ? <span className="line-clamp-2 font-mono text-xs">{row.original.usedBy.join(', ')}</span> : <span className="text-xs text-muted">—</span>
    },
    { id: 'size', accessorFn: (r) => `${r.width}×${r.height}`, header: t('sc.img.col.size'), size: 110, enableSorting: false, meta: { kind: 'mono' } },
    { id: 'createdAt', accessorFn: (r) => r.createdAt, header: t('sc.img.col.added'), size: 160, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
    {
      id: 'actions', header: '', size: 110, enableSorting: false, meta: { label: t('sc.img.remove') },
      cell: ({ row }) => (
        <Button variant="link" size="sm" loading={remove.isPending && remove.variables === row.original.assetId}
          onClick={(e) => { e.stopPropagation(); remove.mutate(row.original.assetId); }} aria-label={t('sc.img.removeAria', { name: row.original.fileName })}>
          <Trash size={16} aria-hidden /> {t('sc.img.remove')}
        </Button>
      )
    }
  ], [t, remove]);

  const done = progress.filter((p) => p.state === 'done').length;
  const failed = progress.filter((p) => p.state === 'failed' || p.state === 'skipped').length;

  return (
    <>
      <PageHeader title={t('sc.img.title')} description={t('sc.img.description')}
        actions={<>
          <Link href="/listings/bulk" className="text-sm">{t('sc.img.bulkLink')}</Link>
          <Button variant="primary" loading={attach.isPending} onClick={() => attach.mutate()} data-testid="attach-images">
            <LinkSimple size={18} aria-hidden /> {t('sc.img.attach')}
          </Button>
        </>} />

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <section aria-labelledby="img-add" className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5">
          <h2 id="img-add" className="m-0 text-lg font-semibold">{t('sc.img.addTitle')}</h2>
          <label
            onDragOver={(e) => { e.preventDefault(); setOver(true); }}
            onDragLeave={() => setOver(false)}
            onDrop={onDrop}
            className={cx('flex min-h-32 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border-2 border-dashed p-4 text-center text-sm',
              over ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface-sunken')}
            data-testid="library-drop"
          >
            <FileArrowUp size={30} aria-hidden />
            <span><strong>{t('sc.img.drop')}</strong> {t('sc.img.browse')}</span>
            <span className="text-xs text-muted">{t('sc.img.limits')}</span>
            <input type="file" multiple accept=".jpg,.jpeg,.png,.webp,.zip,image/jpeg,image/png,image/webp,application/zip" className="at-visually-hidden"
              data-testid="library-files" disabled={busy} onChange={(e) => { void add(Array.from(e.target.files ?? [])); e.target.value = ''; }} />
          </label>
          <label className={cx(buttonClassName({ variant: 'neutral', size: 'sm' }), 'self-start cursor-pointer')}>
            <FolderOpen size={16} aria-hidden /> {t('sc.img.folder')}
            <input ref={folderInput} type="file" multiple className="at-visually-hidden" disabled={busy}
              onChange={(e) => { void add(Array.from(e.target.files ?? [])); e.target.value = ''; }} />
          </label>
          {progress.length ? (
            <div className="flex flex-col gap-2" aria-live="polite">
              <p className="m-0 text-sm">
                {busy ? t('sc.img.uploading', { done, total: progress.length }) : t('sc.img.uploaded', { done, total: progress.length })}
                {failed ? <span className="text-danger"> · {t('sc.img.failedCount', { n: failed })}</span> : null}
              </p>
              <ul className="m-0 flex max-h-56 list-none flex-col gap-1 overflow-y-auto p-0 text-xs">
                {progress.map((p, i) => (
                  <li key={`${p.name}-${i}`} className="flex items-baseline justify-between gap-3 border-b border-border py-1">
                    <span className="min-w-0 truncate font-mono">{p.name}</span>
                    <span className={cx('shrink-0', p.state === 'failed' || p.state === 'skipped' ? 'text-danger' : p.state === 'done' ? 'text-success' : 'text-muted')}>
                      {t(`sc.img.state.${p.state}`)}{p.note ? ` · ${p.note}` : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </section>

        <section aria-labelledby="img-names" className="flex flex-col gap-2 rounded-md border border-border bg-surface p-4 text-sm sm:p-5">
          <h2 id="img-names" className="m-0 text-lg font-semibold">{t('sc.img.namesTitle')}</h2>
          <p className="m-0 text-muted">{t('sc.img.namesIntro')}</p>
          <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1.5">
            <dt className="font-mono font-semibold">ANK-001.MAIN.jpg</dt><dd className="m-0">{t('sc.img.names.main')}</dd>
            <dt className="font-mono font-semibold">ANK-001.PT01.jpg</dt><dd className="m-0">{t('sc.img.names.pt')}</dd>
            <dt className="font-mono font-semibold">ANK-001-RED.MAIN.jpg</dt><dd className="m-0">{t('sc.img.names.variant')}</dd>
            <dt className="font-mono font-semibold">ANK-001.SWCH.jpg</dt><dd className="m-0">{t('sc.img.names.swatch')}</dd>
          </dl>
          <p className="m-0 text-xs text-muted">{t('sc.img.namesNote')}</p>
        </section>
      </div>

      <DataTable<ImageLibraryItem>
        id="seller.image-library"
        label={t('sc.img.title')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(r) => r.assetId}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        enableSelection={false}
        toolbar={
          <div className="flex flex-wrap gap-2">
            <Select label={t('sc.img.filter.status')} value={status} onChange={(e) => { setStatus(e.target.value as typeof status); state.resetPaging(); }}
              options={[{ value: '', label: t('sc.img.filter.any') }, ...(['READY', 'FLAGGED', 'REJECTED'] as const).map((s) => ({ value: s, label: t(`sc.img.status.${s}`) }))]} />
            <Select label={t('sc.img.filter.matched')} value={matched} onChange={(e) => { setMatched(e.target.value as typeof matched); state.resetPaging(); }}
              options={[{ value: '', label: t('sc.img.filter.any') }, { value: 'true', label: t('sc.img.filter.named') }, { value: 'false', label: t('sc.img.filter.unnamed') }]} />
          </div>
        }
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder={t('sc.img.search')}
        empty={t('sc.img.empty')}
        noMatches={t('sc.img.noMatches')}
      />
    </>
  );
}

function slotLabel(t: ReturnType<typeof useT>, slot: string): string {
  if (slot === 'MAIN') return t('sc.img.slot.main');
  if (slot === 'SWCH') return t('sc.img.slot.swatch');
  const n = Number(slot.replace('PT', ''));
  return Number.isFinite(n) && n > 0 ? t('sc.img.slot.more', { n: n + 1 }) : slot;
}
