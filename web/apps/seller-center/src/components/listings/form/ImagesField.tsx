'use client';

import { cx, fieldStyles } from '@afrotransact/ui';
import { ArrowLeft, ArrowRight, Star, Trash, UploadSimple } from '@phosphor-icons/react/ssr';
import { type DragEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { fieldId, type FormImage, IMAGE_TYPES, MAX_IMAGE_BYTES, MAX_IMAGES, MIN_IMAGE_PX } from '../form-model';

const dims = (file: File) =>
  new Promise<{ w: number; h: number }>((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = URL.createObjectURL(file);
  });

interface Props {
  images: FormImage[];
  update: (fn: (prev: FormImage[]) => FormImage[]) => void;
  error?: string;
}

const iconButton = 'grid size-9 place-items-center rounded-xs border-0 bg-surface-muted disabled:opacity-40 sm:size-8';

const move = (list: FormImage[], from: number, to: number) => {
  const next = [...list];
  const [it] = next.splice(from, 1);
  if (it) next.splice(Math.max(0, Math.min(to, next.length)), 0, it);
  return next;
};

/** Images: drop or browse (signed upload, scanned and resized by media), drag or arrow buttons to reorder, star to make main. */
export function ImagesField({ images, update, error }: Props) {
  const t = useT();
  const api = sellerApi();
  const [note, setNote] = useState('');
  const [over, setOver] = useState(false);
  const [dragging, setDragging] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const patch = (id: string, p: Partial<FormImage>) => update((list) => list.map((i) => (i.id === id ? { ...i, ...p } : i)));

  const add = async (files: FileList | File[] | null) => {
    if (!files) return;
    let message = '';
    let count = images.length;
    for (const file of Array.from(files)) {
      if (count >= MAX_IMAGES) {
        message = t('sc.pf.images.max', { max: MAX_IMAGES });
        break;
      }
      if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) {
        message = t('sc.pf.images.type', { name: file.name });
        continue;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        message = t('sc.pf.images.size', { name: file.name });
        continue;
      }
      count++;
      const id = `${Date.now()}-${count}-${file.name}`;
      const { w, h } = await dims(file);
      const low = w < MIN_IMAGE_PX || h < MIN_IMAGE_PX;
      update((list) => [...list, { id, previewUrl: URL.createObjectURL(file), url: null, width: w, height: h, bytes: file.size, contentType: file.type, status: 'uploading', alt: '' }]);
      try {
        const ticket = await api.requestUpload({ purpose: 'product-image', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size });
        const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
        if (!put.ok) throw new Error('upload failed');
        const asset = await api.completeUpload(ticket.assetId);
        patch(id, { url: asset.url, status: low ? 'low' : 'ready' });
      } catch {
        patch(id, { status: 'error' });
      }
    }
    setNote(message);
  };

  const onDrop = (e: DragEvent<HTMLElement>) => {
    e.preventDefault();
    setOver(false);
    if (dragging) return;
    void add(e.dataTransfer.files);
  };

  const status = (im: FormImage, i: number) => {
    if (im.status === 'uploading') return t('sc.pf.images.uploading');
    if (im.status === 'error') return t('sc.pf.images.failed');
    if (im.status === 'low' && i === 0) return t('sc.pf.images.tooSmall', { px: MIN_IMAGE_PX });
    return `${im.width}×${im.height}`;
  };
  const empty = images.length === 0;

  return (
    <div id={fieldId.images} tabIndex={-1} className="flex flex-col gap-4 outline-none">
      <label
        onDragOver={(e) => {
          e.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={cx('flex cursor-pointer flex-col items-center justify-center gap-2 rounded-md border-2 border-dashed p-4 text-center text-sm focus-within:border-focus',
          empty ? 'min-h-48 sm:min-h-60' : 'min-h-24',
          over ? 'border-gold-500 bg-gold-50' : error ? 'border-danger bg-surface-sunken' : 'border-border-strong bg-surface-sunken')}
      >
        <UploadSimple size={empty ? 40 : 28} aria-hidden />
        <span className={empty ? 'text-base' : undefined}><strong>{empty ? t('sc.pf.images.drop') : t('sc.pf.images.addMore')}</strong> {t('sc.pf.images.browse')}</span>
        <span className="text-xs text-muted">{t('sc.pf.images.rules', { count: images.length, max: MAX_IMAGES, px: MIN_IMAGE_PX })}</span>
        <input type="file" data-testid="image-input" accept={IMAGE_TYPES.join(',')} multiple className="at-visually-hidden" aria-describedby={error ? 'pf-images-error' : undefined}
          onChange={(e) => { void add(e.target.files); e.target.value = ''; }} />
      </label>
      {note || error ? <span id="pf-images-error" role="alert" className="text-xs text-danger">{note || error}</span> : null}
      {images.length ? (
        <>
          <p className="m-0 text-xs text-muted">{t('sc.pf.images.reorderHint')}</p>
          <ul aria-label={t('sc.pf.images.list')} className="m-0 grid list-none grid-cols-2 gap-3 p-0 @lg:grid-cols-3 @3xl:grid-cols-4 @5xl:grid-cols-5">
            {images.map((im, i) => (
              <li
                key={im.id}
                draggable
                onDragStart={() => setDragging(im.id)}
                onDragEnd={() => { setDragging(null); setTarget(null); }}
                onDragOver={(e) => { e.preventDefault(); if (dragging) setTarget(im.id); }}
                onDrop={(e) => {
                  e.preventDefault();
                  const from = images.findIndex((x) => x.id === dragging);
                  if (from >= 0 && from !== i) update((list) => move(list, from, i));
                  setDragging(null);
                  setTarget(null);
                }}
                className={cx('flex min-w-0 flex-col gap-2 rounded-md border bg-surface p-2', dragging === im.id && 'opacity-50', target === im.id && dragging !== im.id ? 'border-gold-500 bg-gold-50' : 'border-border',
                  i === 0 && 'ring-2 ring-gold-500')}
                data-main={i === 0 ? 'true' : undefined}
              >
                <div className="relative">
                  <img src={im.previewUrl} alt={im.alt || t('sc.pf.images.alt', { n: i + 1 })} className="aspect-square w-full cursor-grab rounded-sm bg-surface-muted object-cover" />
                  {i === 0 ? <span className="absolute top-1.5 left-1.5 rounded-xs bg-gold-500 px-1.5 py-0.5 text-2xs font-bold">{t('sc.pf.images.main')}</span> : null}
                </div>
                <input
                  type="text"
                  maxLength={200}
                  value={im.alt}
                  placeholder={t('sc.pf.images.altPlaceholder')}
                  aria-label={t('sc.pf.images.altLabel', { n: i + 1 })}
                  onChange={(e) => patch(im.id, { alt: e.target.value })}
                  className={`${fieldStyles.control} h-9 text-sm`}
                />
                <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className={cx('text-2xs', im.status === 'error' || (im.status === 'low' && i === 0) ? 'text-danger' : 'text-muted')}>{status(im, i)}</span>
                  <span className="ml-auto flex items-center gap-1">
                    {i > 0 ? (
                      <button type="button" className={iconButton} aria-label={t('sc.pf.images.makeMain', { n: i + 1 })} title={t('sc.pf.images.setMain')} onClick={() => update((list) => move(list, i, 0))}>
                        <Star size={14} aria-hidden />
                      </button>
                    ) : null}
                    <button type="button" disabled={i === 0} className={iconButton} aria-label={t('sc.pf.images.left', { n: i + 1 })} onClick={() => update((list) => move(list, i, i - 1))}>
                      <ArrowLeft size={14} aria-hidden />
                    </button>
                    <button type="button" disabled={i === images.length - 1} className={iconButton} aria-label={t('sc.pf.images.right', { n: i + 1 })} onClick={() => update((list) => move(list, i, i + 1))}>
                      <ArrowRight size={14} aria-hidden />
                    </button>
                    <button type="button" className={cx(iconButton, 'text-danger')} aria-label={t('sc.pf.images.remove', { n: i + 1 })} onClick={() => update((list) => list.filter((x) => x.id !== im.id))}>
                      <Trash size={14} aria-hidden />
                    </button>
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}
