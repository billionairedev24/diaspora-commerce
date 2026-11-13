'use client';

import { InfoTip, RequiredMark, useFormLabels } from '@afrotransact/ui';
import { ArrowLeft, ArrowRight, Star, Trash } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { EXTRA_PHOTOS_TARGET, fieldId, type FormImage, IMAGE_TYPES, MAX_IMAGE_BYTES, MAX_IMAGES, MIN_IMAGE_PX } from '../form-model';
import { extraPhotoCount, verdictOf } from './image-rules';
import { MainPhotoRules } from './MainPhotoRules';
import { AddPhotoTile, PhotoFindings, PhotoThumb } from './PhotoSlots';

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

const iconButton = 'grid size-9 place-items-center rounded-xs border-0 bg-surface-muted disabled:opacity-40 sm:size-8';

/**
 * Photos as one compact row of small slots: the main photo first, the extra photos after it, then an empty slot to add one (press it or
 * drop files on it; no big drop zone). Each upload is signed and judged by media's image rules; the verdict is a dot on the thumbnail and,
 * only when the photo is not fully accepted, one short reason under it. The main photo rules live in the info popover next to the label.
 * Pressing a thumbnail opens its details: every finding in full, its description, and moving, making it the main photo or removing it.
 */
export function ImagesField({ images, update, error, context }: Props) {
  const t = useT();
  const labels = useFormLabels();
  const api = sellerApi();
  const [note, setNote] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
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
        // A photo that is not fully accepted opens its details, so the whole finding is read out without a hunt for it.
        if (rejected || held || small || verdict === 'WARN') setSelected(id);
      } catch {
        patch(id, { status: 'error' });
        setSelected(id);
      }
    }
    setNote(message);
  };

  const empty = images.length === 0;
  const chosen = images.find((i) => i.id === selected);
  const at = chosen ? images.indexOf(chosen) : -1;
  const extras = extraPhotoCount(images);
  const describedBy = note || error ? 'pf-images-error' : undefined;

  return (
    <div id={fieldId.images} tabIndex={-1} role="group" aria-labelledby="pf-images-label" className="flex flex-col gap-2 outline-none" data-testid="photos">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <span id="pf-images-label" className="text-sm font-semibold">{t('sc.pf.field.photos')}<RequiredMark text={labels.required} /></span>
        <span className="text-xs text-muted">{t('sc.pf.images.mainFirst')}</span>
        <InfoTip label={t('sc.pf.images.mainRules.info')}><MainPhotoRules main={images[0]} /></InfoTip>
      </div>
      <ul aria-label={t('sc.pf.images.list')} className="m-0 flex list-none flex-wrap items-start gap-2 p-0">
        {images.map((im, i) => (
          <PhotoThumb key={im.id} image={im} index={i} selected={selected === im.id} onSelect={() => setSelected(selected === im.id ? null : im.id)}
            dragState={{ dragging: dragging === im.id, target: target === im.id && dragging !== im.id }}
            itemProps={{
              draggable: true,
              'data-main': i === 0 ? 'true' : undefined,
              onDragStart: () => setDragging(im.id),
              onDragEnd: () => { setDragging(null); setTarget(null); },
              onDragOver: (e) => { if (dragging) { e.preventDefault(); setTarget(im.id); } },
              onDrop: (e) => {
                if (!dragging) return;
                e.preventDefault();
                const from = images.findIndex((x) => x.id === dragging);
                if (from >= 0 && from !== i) update((list) => move(list, from, i));
                setDragging(null);
                setTarget(null);
              }
            }} />
        ))}
        {images.length < MAX_IMAGES ? (
          <AddPhotoTile size={empty ? 'main' : 'extra'} multiple testId="image-input" invalid={Boolean(error)} describedBy={describedBy}
            label={empty ? t('sc.pf.photo.addMain') : t('sc.pf.photo.addMore')} onFiles={(files) => void add(files)} />
        ) : null}
      </ul>
      {images.length ? <span className="text-xs text-muted">{t('sc.pf.images.count', { count: images.length, max: MAX_IMAGES, extras, target: EXTRA_PHOTOS_TARGET })}</span> : null}
      {note || error ? <span id="pf-images-error" role="alert" className="text-xs text-danger">{note || error}</span> : null}
      {chosen ? (
        <div className="flex min-w-0 flex-col gap-2 border-t border-border pt-2" data-testid="photo-details">
          <PhotoFindings image={chosen} index={at} />
          {verdictOf(chosen) === 'WARN' ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.warnNotice')}</p> : null}
          {verdictOf(chosen) === 'HOLD' && at === 0 ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.holdNotice')}</p> : null}
          {at === 0 && chosen.slot === 'OTHER' && verdictOf(chosen) !== 'REJECT' ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.promoteNote')}</p> : null}
          <div className="flex flex-wrap items-center gap-2">
            <input type="text" maxLength={200} value={chosen.alt} placeholder={t('sc.pf.images.altPlaceholder')} aria-label={t('sc.pf.images.altLabel', { n: at + 1 })}
              onChange={(e) => patch(chosen.id, { alt: e.target.value })} className="h-9 min-w-0 max-w-md flex-1 basis-56 rounded-sm border border-border-strong bg-surface px-3 text-sm" />
            <span className="flex items-center gap-1">
              {at > 0 ? (
                <button type="button" className={iconButton} aria-label={t('sc.pf.images.makeMain', { n: at + 1 })} title={t('sc.pf.images.setMain')} onClick={() => update((list) => move(list, at, 0))}>
                  <Star size={14} aria-hidden />
                </button>
              ) : null}
              <button type="button" disabled={at === 0} className={iconButton} aria-label={t('sc.pf.images.left', { n: at + 1 })} onClick={() => update((list) => move(list, at, at - 1))}>
                <ArrowLeft size={14} className="rtl:-scale-x-100" aria-hidden />
              </button>
              <button type="button" disabled={at === images.length - 1} className={iconButton} aria-label={t('sc.pf.images.right', { n: at + 1 })} onClick={() => update((list) => move(list, at, at + 1))}>
                <ArrowRight size={14} className="rtl:-scale-x-100" aria-hidden />
              </button>
              <button type="button" className={`${iconButton} text-danger`} aria-label={t('sc.pf.images.remove', { n: at + 1 })} onClick={() => { update((list) => list.filter((x) => x.id !== chosen.id)); setSelected(null); }}>
                <Trash size={14} aria-hidden />
              </button>
            </span>
          </div>
        </div>
      ) : null}
    </div>
  );
}
