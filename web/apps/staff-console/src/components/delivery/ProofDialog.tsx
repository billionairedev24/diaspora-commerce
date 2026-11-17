'use client';

import { DELIVERY_MAX_PHOTOS, deliveryPhotoProblem, type DeliveryProofView, isApiError } from '@afrotransact/api-client';
import { Button, Dialog, DialogActions, Field, fieldStyles, TextField } from '@afrotransact/ui';
import { MapPin, Trash } from '@phosphor-icons/react/ssr';
import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { idempotencyKey } from '@/lib/format';

interface Photo {
  assetId: string;
  /** What the preview shows: the local file while it is new, the signed link for a saved photo. */
  url: string;
}

export interface ProofSubmit {
  photoAssetIds: string[];
  recipientName: string;
  note: string;
  latitude?: number;
  longitude?: number;
  reason: string;
  key: string;
}

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Delivered (a first proof) or correct (a saved proof, with a reason). */
  mode: 'deliver' | 'correct';
  orderNumber: string;
  /** The saved proof being corrected. */
  existing?: DeliveryProofView | null;
  onSubmit: (v: ProofSubmit) => Promise<unknown>;
}

/**
 * Mark delivered: at least one photo as proof (uploaded straight to private storage), who received it and a note (both
 * optional), and the location only when the device shares it. A correction of a saved proof also needs a reason.
 */
export function ProofDialog({ open, onOpenChange, mode, orderNumber, existing, onSubmit }: Props) {
  const t = useT();
  const api = staffApi();
  const fileInput = useRef<HTMLInputElement>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [recipient, setRecipient] = useState('');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');
  const [position, setPosition] = useState<{ latitude: number; longitude: number } | null>(null);
  const [locating, setLocating] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  // One key per opening: a retry after a failure is the same action, never a second one.
  const key = useRef('');

  useEffect(() => {
    if (!open) return;
    key.current = idempotencyKey();
    setPhotos(existing ? existing.photoAssetIds.map((assetId, i) => ({ assetId, url: existing.photoUrls[i] ?? '' })) : []);
    setRecipient(existing?.recipientName ?? '');
    setNote(existing?.note ?? '');
    setPosition(existing?.latitude != null && existing.longitude != null ? { latitude: existing.latitude, longitude: existing.longitude } : null);
    setReason('');
    setErrors({});
  }, [open, existing]);

  const upload = useMutation({
    mutationFn: async (files: File[]) => {
      const added: Photo[] = [];
      for (const file of files) {
        const assetId = await api.uploadDeliveryPhoto(file);
        added.push({ assetId, url: URL.createObjectURL(file) });
      }
      return added;
    },
    onSuccess: (added) => {
      setPhotos((p) => [...p, ...added]);
      setErrors((e) => ({ ...e, photoAssetIds: '' }));
    },
    onError: (e) => setErrors((x) => ({ ...x, photoAssetIds: problemMessage(e) }))
  });
  const save = useMutation({
    mutationFn: (v: ProofSubmit) => onSubmit(v),
    onSuccess: () => onOpenChange(false),
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { form: problemMessage(e) })
  });

  const onFiles = (list: FileList | null) => {
    const files = Array.from(list ?? []);
    if (fileInput.current) fileInput.current.value = '';
    if (!files.length) return;
    if (photos.length + files.length > DELIVERY_MAX_PHOTOS) {
      setErrors((e) => ({ ...e, photoAssetIds: t('st.dlv.proof.tooMany', { max: DELIVERY_MAX_PHOTOS }) }));
      return;
    }
    const bad = files.map(deliveryPhotoProblem).find((p) => p !== null);
    if (bad) {
      setErrors((e) => ({ ...e, photoAssetIds: bad }));
      return;
    }
    upload.mutate(files);
  };

  const locate = () => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setErrors((e) => ({ ...e, latitude: t('st.dlv.proof.noLocation') }));
      return;
    }
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        setPosition({ latitude: p.coords.latitude, longitude: p.coords.longitude });
        setErrors((e) => ({ ...e, latitude: '' }));
        setLocating(false);
      },
      () => {
        // Location is never required: say so and carry on.
        setErrors((e) => ({ ...e, latitude: t('st.dlv.proof.noLocation') }));
        setLocating(false);
      },
      { enableHighAccuracy: true, timeout: 8000 }
    );
  };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (photos.length === 0) errs.photoAssetIds = t('st.dlv.proof.photoRequired');
    if (mode === 'correct' && reason.trim().length < 3) errs.reason = t('st.dlv.proof.reasonRequired');
    setErrors(errs);
    if (Object.keys(errs).length) return;
    save.mutate({ photoAssetIds: photos.map((p) => p.assetId), recipientName: recipient.trim(), note: note.trim(), ...(position ?? {}), reason: reason.trim(), key: key.current });
  };

  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={mode === 'deliver' ? t('st.dlv.proof.titleDeliver', { order: orderNumber }) : t('st.dlv.proof.titleCorrect', { order: orderNumber })}
      description={mode === 'deliver' ? t('st.dlv.proof.introDeliver') : t('st.dlv.proof.introCorrect')}
      closeLabel={t('st.dlv.close')}
      width={520}
    >
      <form noValidate onSubmit={submit} className="flex flex-col gap-4" aria-label={t('st.dlv.proof.form')}>
        <div className="flex flex-col gap-2">
          <span className="text-sm font-semibold">{t('st.dlv.proof.photos')}</span>
          <span className="text-xs text-muted">{t('st.dlv.proof.photosHelp', { max: DELIVERY_MAX_PHOTOS })}</span>
          {photos.length ? (
            <ul className="m-0 flex list-none flex-wrap gap-3 p-0" aria-label={t('st.dlv.proof.photos')}>
              {photos.map((p, i) => (
                <li key={p.assetId} className="flex flex-col items-start gap-1" data-testid="proof-photo">
                  {p.url ? (
                    // A local preview or a short-lived signed link, never optimised: a plain img.
                    <img src={p.url} alt={t('st.dlv.proof.photoAlt', { n: i + 1 })} className="h-24 w-32 rounded-sm object-cover" />
                  ) : (
                    <span className="flex h-24 w-32 items-center justify-center rounded-sm bg-surface-hover text-xs text-muted">{t('st.dlv.proof.photoSaved')}</span>
                  )}
                  <Button type="button" size="sm" variant="link" onClick={() => setPhotos((all) => all.filter((x) => x.assetId !== p.assetId))} aria-label={t('st.dlv.proof.removeN', { n: i + 1 })}>
                    <Trash size={14} aria-hidden /> {t('st.dlv.proof.remove')}
                  </Button>
                </li>
              ))}
            </ul>
          ) : null}
          <input ref={fileInput} id="proof-photo-input" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={(e) => onFiles(e.currentTarget.files)} className="at-visually-hidden" aria-label={t('st.dlv.proof.add')} />
          <div>
            <Button type="button" variant="neutral" loading={upload.isPending} disabled={photos.length >= DELIVERY_MAX_PHOTOS} onClick={() => fileInput.current?.click()}>
              {upload.isPending ? t('st.dlv.proof.uploading') : t('st.dlv.proof.add')}
            </Button>
          </div>
          {errors.photoAssetIds ? (
            <p className="m-0 text-sm text-danger" role="alert">
              {errors.photoAssetIds}
            </p>
          ) : null}
        </div>
        <TextField label={t('st.dlv.proof.recipient')} help={t('st.dlv.proof.recipientHelp')} maxLength={120} value={recipient} onChange={(e) => setRecipient(e.currentTarget.value)} error={errors.recipientName} />
        <Field id="proof-note" label={t('st.dlv.proof.note')} error={errors.note}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.currentTarget.value)} />}
        </Field>
        <div className="flex flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="neutral" size="sm" loading={locating} onClick={locate}>
              <MapPin size={14} aria-hidden /> {t('st.dlv.proof.useLocation')}
            </Button>
            {position ? (
              <span className="text-xs text-muted" data-testid="proof-position">
                {t('st.dlv.proof.located', { lat: position.latitude.toFixed(4), lng: position.longitude.toFixed(4) })}{' '}
                <Button type="button" size="sm" variant="link" onClick={() => setPosition(null)}>
                  {t('st.dlv.proof.removeLocation')}
                </Button>
              </span>
            ) : (
              <span className="text-xs text-muted">{t('st.dlv.proof.locationHelp')}</span>
            )}
          </div>
          {errors.latitude ? <span className="text-xs text-muted">{errors.latitude}</span> : null}
        </div>
        {mode === 'correct' ? (
          <Field id="proof-reason" label={t('st.dlv.proof.reason')} error={errors.reason}>
            {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
          </Field>
        ) : null}
        {errors.form ? (
          <Notice tone="danger" title={t('st.dlv.notSaved')} role="alert">
            {errors.form}
          </Notice>
        ) : null}
        <DialogActions>
          <Button type="button" variant="neutral" onClick={() => onOpenChange(false)}>
            {t('st.dlv.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending} disabled={upload.isPending}>
            {mode === 'deliver' ? t('st.dlv.proof.submitDeliver') : t('st.dlv.proof.submitCorrect')}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
