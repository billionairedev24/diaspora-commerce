'use client';

import { cx } from '@afrotransact/ui';
import { UploadSimple } from '@phosphor-icons/react/ssr';
import { type DragEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { fieldId, type FormImage, IMAGE_TYPES, MAX_IMAGE_BYTES, MAX_IMAGES, MIN_IMAGE_PX } from '../form-model';
import { ImageCard } from './ImageCard';
import { ExtraPhotoMeter, MainImageRules } from './ImageGuidance';

const dims = (file: File) =>
  new Promise<{ w: number; h: number }>((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = URL.createObjectURL(file);
  });

/** What media's AI check compares a picture with, and the product type (apparel may show a model). */
export interface ImageContext {
  title?: string;
  brand?: string;
  productTypeCode?: string;
}

interface Props {
  images: FormImage[];
  update: (fn: (prev: FormImage[]) => FormImage[]) => void;
  error?: string;
  context?: ImageContext;
}

const move = (list: FormImage[], from: number, to: number) => {
  const next = [...list];
  const [it] = next.splice(from, 1);
  if (it) next.splice(Math.max(0, Math.min(to, next.length)), 0, it);
  return next;
};

/** Images: the main photo rules, then drop or browse (signed upload, judged by media's image rules), one card per image with its verdict in words,
    drag or arrow buttons to reorder, star to make main, and the extra-photo meter (a recommendation). */
export function ImagesField({ images, update, error, context }: Props) {
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
      // The first picture is the main one and is judged by the strict main photo rules; media answers with the verdict and every finding.
      const slot = count === 0 ? 'MAIN' : 'OTHER';
      count++;
      const id = `${Date.now()}-${count}-${file.name}`;
      const { w, h } = await dims(file);
      update((list) => [...list, { id, previewUrl: URL.createObjectURL(file), url: null, width: w, height: h, bytes: file.size, contentType: file.type, status: 'uploading', alt: '', slot }]);
      try {
        const ticket = await api.requestUpload({ purpose: 'product-image', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size, slot,
          ...(context?.title?.trim() ? { title: context.title.trim().slice(0, 200) } : {}), ...(context?.brand?.trim() ? { brand: context.brand.trim().slice(0, 80) } : {}),
          ...(context?.productTypeCode ? { productTypeCode: context.productTypeCode } : {}) });
        const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
        if (!put.ok) throw new Error('upload failed');
        const asset = await api.completeUpload(ticket.assetId);
        const verdict = asset.verdict ?? null;
        const width = asset.width ?? w;
        const height = asset.height ?? h;
        const rejected = verdict === 'REJECT' || asset.status === 'REJECTED';
        const held = !rejected && (verdict === 'HOLD' || asset.status === 'FLAGGED');
        // An answer without a verdict (an older media): the size rule is the one we can still read from the picture.
        const small = !verdict && slot === 'MAIN' && Math.max(width, height) < MIN_IMAGE_PX;
        // A photo we are checking has no public URL: the form keeps its asset id and submits that, and the listing waits for our answer.
        patch(id, { url: rejected || held ? null : asset.url || ticket.publicUrl || null, assetId: held ? ticket.assetId : null, width, height, bytes: asset.bytes ?? file.size,
          status: rejected ? 'rejected' : held ? 'held' : small ? 'low' : 'ready', slot, verdict, checks: asset.checks });
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

  const empty = images.length === 0;

  return (
    <div id={fieldId.images} tabIndex={-1} className="flex flex-col gap-4 outline-none">
      <MainImageRules main={images[0]} />
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
        <span className="text-xs text-muted">{t('sc.pf.images.rules', { count: images.length, max: MAX_IMAGES })}</span>
        <input type="file" data-testid="image-input" accept={IMAGE_TYPES.join(',')} multiple className="at-visually-hidden" aria-describedby={error ? 'pf-images-error' : undefined}
          onChange={(e) => { void add(e.target.files); e.target.value = ''; }} />
      </label>
      {note || error ? <span id="pf-images-error" role="alert" className="text-xs text-danger">{note || error}</span> : null}
      {images.length ? (
        <>
          <p className="m-0 text-xs text-muted">{t('sc.pf.images.reorderHint')}</p>
          <ul aria-label={t('sc.pf.images.list')} className="m-0 flex list-none flex-col p-0 border-t border-border">
            {images.map((im, i) => (
              <ImageCard
                key={im.id}
                image={im}
                index={i}
                count={images.length}
                onAlt={(alt) => patch(im.id, { alt })}
                onMakeMain={() => update((list) => move(list, i, 0))}
                onMove={(to) => update((list) => move(list, i, to))}
                onRemove={() => update((list) => list.filter((x) => x.id !== im.id))}
                dragState={{ dragging: dragging === im.id, target: target === im.id && dragging !== im.id }}
                itemProps={{
                  draggable: true,
                  'data-main': i === 0 ? 'true' : undefined,
                  onDragStart: () => setDragging(im.id),
                  onDragEnd: () => { setDragging(null); setTarget(null); },
                  onDragOver: (e) => { e.preventDefault(); if (dragging) setTarget(im.id); },
                  onDrop: (e) => {
                    e.preventDefault();
                    const from = images.findIndex((x) => x.id === dragging);
                    if (from >= 0 && from !== i) update((list) => move(list, from, i));
                    setDragging(null);
                    setTarget(null);
                  }
                }}
              />
            ))}
          </ul>
        </>
      ) : null}
      <ExtraPhotoMeter images={images} />
    </div>
  );
}
