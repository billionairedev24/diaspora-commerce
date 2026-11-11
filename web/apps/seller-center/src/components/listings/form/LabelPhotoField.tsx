'use client';

import { Button } from '@afrotransact/ui';
import { UploadSimple } from '@phosphor-icons/react/ssr';
import { useRef } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { fieldId, type FormImage, IMAGE_TYPES, MAX_IMAGE_BYTES } from '../form-model';

interface Props {
  image: FormImage | null;
  onChange: (image: FormImage | null) => void;
  error?: string | undefined;
  /** Optional fields show "(optional)"; a "required or exemption" field is answered with a photo or a reason. */
  optional?: boolean;
}

const dims = (file: File) =>
  new Promise<{ w: number; h: number }>((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = URL.createObjectURL(file);
  });

/** The label photo slot: one typed image (ingredients and nutrition panel, Supplement Facts, Drug Facts) that product pictures never stand in for. */
export function LabelPhotoField({ image, onChange, error, optional }: Props) {
  const t = useT();
  const api = sellerApi();
  const input = useRef<HTMLInputElement>(null);

  const pick = async (file: File | undefined) => {
    if (!file) return;
    if (!(IMAGE_TYPES as readonly string[]).includes(file.type) || file.size > MAX_IMAGE_BYTES) {
      onChange({ id: 'img-label', previewUrl: '', url: null, width: 0, height: 0, bytes: file.size, contentType: file.type, status: 'error', alt: '' });
      return;
    }
    const { w, h } = await dims(file);
    const local: FormImage = { id: 'img-label', previewUrl: URL.createObjectURL(file), url: null, width: w, height: h, bytes: file.size, contentType: file.type, status: 'uploading', alt: '' };
    onChange(local);
    try {
      const ticket = await api.requestUpload({ purpose: 'product-image', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size });
      const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
      if (!put.ok) throw new Error('upload failed');
      const asset = await api.completeUpload(ticket.assetId);
      onChange({ ...local, url: asset.url, status: 'ready' });
    } catch {
      onChange({ ...local, status: 'error' });
    }
  };

  return (
    <div id={fieldId.labelPhoto} tabIndex={-1} className="flex flex-col gap-2 outline-none" data-testid="label-photo">
      <span className="text-sm font-semibold">{optional ? t('sc.pf.optional', { label: t('sc.pf.field.labelPhoto') }) : t('sc.pf.field.labelPhoto')}</span>
      <span className="text-xs text-muted">{t('sc.pf.labelPhoto.hint')}</span>
      <div className="flex flex-wrap items-center gap-3">
        {image && image.status !== 'error' && image.previewUrl ? (
          <img src={image.previewUrl} alt={t('sc.pf.labelPhoto.alt')} className="size-20 rounded-sm border border-border object-cover" />
        ) : null}
        <input ref={input} type="file" accept={IMAGE_TYPES.join(',')} className="sr-only" tabIndex={-1} aria-label={t('sc.pf.field.labelPhoto')} data-testid="label-photo-input"
          onChange={(e) => { void pick(e.target.files?.[0]); e.target.value = ''; }} />
        <Button size="sm" variant="neutral" onClick={() => input.current?.click()}>
          <UploadSimple size={14} aria-hidden /> {image?.url ? t('sc.pf.labelPhoto.replace') : t('sc.pf.labelPhoto.add')}
        </Button>
        {image ? <Button size="sm" variant="neutral" onClick={() => onChange(null)}>{t('sc.pf.labelPhoto.remove')}</Button> : null}
        {image?.status === 'uploading' ? <span role="status" className="text-xs text-muted">{t('sc.pf.labelPhoto.uploading')}</span> : null}
      </div>
      {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
    </div>
  );
}
