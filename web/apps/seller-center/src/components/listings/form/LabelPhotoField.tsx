'use client';

import { InfoTip, RequiredMark, useFormLabels } from '@afrotransact/ui';
import { Trash } from '@phosphor-icons/react/ssr';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';

import { fieldId, type FormImage, IMAGE_TYPES, MAX_IMAGE_BYTES } from '../form-model';
import { AddPhotoTile } from './PhotoSlots';

interface Props {
  image: FormImage | null;
  onChange: (image: FormImage | null) => void;
  error?: string | undefined;
  /** An optional label photo says "(optional)"; a required one carries the red asterisk like every required field. */
  optional?: boolean;
}

const dims = (file: File) =>
  new Promise<{ w: number; h: number }>((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ w: img.naturalWidth, h: img.naturalHeight });
    img.onerror = () => resolve({ w: 0, h: 0 });
    img.src = URL.createObjectURL(file);
  });

/**
 * The label photo slot: one typed image (ingredients and nutrition panel, Supplement Facts, Drug Facts) that product pictures never stand in for.
 * It is also where a seller proves a tax fact (food, supplement or drug), so the slot stays for every category that asks for it, optional or not.
 */
export function LabelPhotoField({ image, onChange, error, optional }: Props) {
  const t = useT();
  const api = sellerApi();
  const labels = useFormLabels();

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
      // A label photo we are still checking has no public URL yet: keep its asset id; the listing waits for our answer.
      const held = asset.verdict === 'HOLD' || asset.status === 'FLAGGED';
      onChange(held ? { ...local, url: null, assetId: ticket.assetId, status: 'held', verdict: 'HOLD' } : { ...local, url: asset.url, status: 'ready' });
    } catch {
      onChange({ ...local, status: 'error' });
    }
  };

  const hasImage = Boolean(image && image.status !== 'error' && image.previewUrl);
  const name = optional ? t('sc.pf.field.labelPhotoOptional') : t('sc.pf.field.labelPhoto');
  return (
    <div id={fieldId.labelPhoto} tabIndex={-1} role="group" aria-labelledby="pf-label-photo-h" className="flex flex-col gap-2 outline-none" data-testid="label-photo">
      <div className="flex items-center gap-1">
        <span id="pf-label-photo-h" className="text-sm font-semibold">{name}{optional ? null : <RequiredMark text={labels.required} />}</span>
        <InfoTip label={t('sc.pf.labelPhoto.info')}>{t('sc.pf.labelPhoto.hint')}</InfoTip>
      </div>
      <ul className="m-0 flex list-none flex-wrap items-start gap-2 p-0">
        {hasImage ? (
          <li className="flex flex-col gap-1">
            <span className="relative block size-16 rounded-sm border border-border bg-surface-muted">
              <img src={image?.previewUrl} alt={t('sc.pf.labelPhoto.alt')} className="block size-16 rounded-sm object-cover" />
              <button type="button" aria-label={t('sc.pf.labelPhoto.remove')} onClick={() => onChange(null)}
                className="absolute end-1 top-1 grid size-6 place-items-center rounded-full border-0 bg-surface p-0 text-danger"><Trash size={12} aria-hidden /></button>
            </span>
          </li>
        ) : (
          <AddPhotoTile size="extra" testId="label-photo-input" label={t('sc.pf.labelPhoto.add')} invalid={Boolean(error)} onFiles={(files) => void pick(files[0])} />
        )}
        {image?.status === 'held' ? <li role="status" className="self-center text-xs text-muted" data-testid="label-photo-checking">{t('sc.pf.images.checkingPhoto')}</li> : null}
        {image?.status === 'uploading' ? <li role="status" className="self-center text-xs text-muted">{t('sc.pf.labelPhoto.uploading')}</li> : null}
      </ul>
      {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
    </div>
  );
}
