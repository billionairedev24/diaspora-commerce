'use client';

import {
  isApiError,
  type OwnReview,
  productKeys,
  REVIEW_PHOTO_MAX_BYTES,
  REVIEW_PHOTO_TYPES,
  REVIEW_PHOTOS_MAX,
  REVIEW_TEXT_MAX,
  REVIEW_TITLE_MAX,
  type ReviewInput,
  reviewKeys,
  reviewQueries,
  uploadReviewPhoto
} from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, SealCheck, Sheet, Skeleton, Star, TextField, useToast, X } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ChangeEvent, type FormEvent, type KeyboardEvent, useId, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Reviews.module.css';

interface PhotoDraft {
  key: string;
  url: string;
  /** Set for a photo uploaded now; a photo the review already has is sent by url. */
  assetId?: string;
}

/** One to five stars as a radio group; the arrow keys move between them. */
export function StarInput({ value, onChange, label }: { value: number; onChange: (n: number) => void; label: string }) {
  const t = useT();
  const move = (e: KeyboardEvent, n: number) => {
    const next = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? Math.min(5, n + 1) : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? Math.max(1, n - 1) : n;
    if (next !== n) {
      e.preventDefault();
      onChange(next);
    }
  };
  return (
    <div className={styles.starInput} role="radiogroup" aria-label={label}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          role="radio"
          aria-checked={n === value}
          tabIndex={n === value || (value === 0 && n === 1) ? 0 : -1}
          className={styles.starBtn}
          data-on={n <= value}
          aria-label={t('rev.stars', { n })}
          onClick={() => onChange(n)}
          onKeyDown={(e) => move(e, n)}
        >
          <Star weight={n <= value ? 'fill' : 'regular'} aria-hidden />
        </button>
      ))}
    </div>
  );
}

function problemText(e: unknown, t: ReturnType<typeof useT>): string {
  if (isApiError(e)) {
    if (e.code === 'REVIEW_NOT_VERIFIED_PURCHASE') return t('rev.notVerified');
    if (e.code === 'REVIEW_ALREADY_EXISTS') return t('rev.alreadyReviewed');
    if (e.code === 'REVIEW_EDIT_WINDOW_CLOSED') return t('rev.windowClosed');
    if (e.status === 422) return Object.values(e.fieldErrors)[0] ?? t('rev.checkReview');
  }
  return t('auth.genericError');
}

function ReviewForm({ atin, existing, onDone }: { atin: string; existing: OwnReview | null; onDone: () => void }) {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const uid = useId();
  const [rating, setRating] = useState(existing?.rating ?? 0);
  const [title, setTitle] = useState(existing?.title ?? '');
  const [text, setText] = useState(existing?.text ?? '');
  const [photos, setPhotos] = useState<PhotoDraft[]>((existing?.photos ?? []).map((p, i) => ({ key: `old-${i}`, url: p.url })));
  const [photoError, setPhotoError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    await Promise.all([qc.invalidateQueries({ queryKey: reviewKeys.all }), qc.invalidateQueries({ queryKey: reviewKeys.mine(atin) }), qc.invalidateQueries({ queryKey: productKeys.detail(atin) })]);
  };
  const save = useMutation({
    mutationFn: (body: ReviewInput) => (existing ? api.editMyProductReview(atin, body) : api.writeProductReview(atin, body)),
    onSuccess: async (saved) => {
      await refresh();
      toast.show(saved.status === 'PENDING' ? t('rev.sentToModeration') : existing ? t('rev.updated') : t('rev.thanks'));
      onDone();
    },
    onError: (e) => setError(problemText(e, t))
  });
  const remove = useMutation({
    mutationFn: () => api.deleteMyProductReview(atin),
    onSuccess: async () => {
      await refresh();
      toast.show(t('rev.deleted'));
      onDone();
    },
    onError: (e) => setError(problemText(e, t))
  });

  const addFiles = async (e: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.currentTarget.files ?? []);
    e.currentTarget.value = '';
    setPhotoError(null);
    const room = REVIEW_PHOTOS_MAX - photos.length;
    if (files.length > room) setPhotoError(t('rev.photosMax', { max: REVIEW_PHOTOS_MAX }));
    setUploading(true);
    try {
      for (const file of files.slice(0, Math.max(0, room))) {
        if (!REVIEW_PHOTO_TYPES.some((ty) => ty === file.type) || file.size > REVIEW_PHOTO_MAX_BYTES) {
          setPhotoError(t('rev.photoType'));
          continue;
        }
        try {
          const asset = await uploadReviewPhoto(api, file);
          if (asset.status !== 'READY' || !asset.url) {
            setPhotoError(t('rev.photoRefused'));
            continue;
          }
          const url = asset.url;
          setPhotos((prev) => [...prev, { key: asset.assetId, url, assetId: asset.assetId }]);
        } catch {
          setPhotoError(t('rev.photoFailed'));
        }
      }
    } finally {
      setUploading(false);
    }
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (rating < 1) {
      setError(t('rev.starsRequired'));
      return;
    }
    save.mutate({
      rating,
      title: title.trim() || undefined,
      text: text.trim() || undefined,
      photos: photos.map((p) => (p.assetId ? { assetId: p.assetId } : { url: p.url }))
    });
  };

  const moderated = existing && (existing.status === 'REJECTED' || existing.status === 'REMOVED');
  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      {existing?.status === 'PENDING' && <p className={styles.warn}>{t('rev.pendingNote')}</p>}
      {moderated && <p className={styles.warn}>{t('rev.moderatedNote', { reason: existing.moderation?.reason ?? '' })}</p>}
      {existing && !existing.editable && <p className={styles.warn}>{t('rev.windowClosed')}</p>}
      <div>
        <span className={styles.label} id={`${uid}-rating`}>{t('rev.yourRating')}</span>
        <StarInput value={rating} onChange={setRating} label={t('rev.yourRating')} />
      </div>
      <TextField label={t('rev.titleLabel')} value={title} maxLength={REVIEW_TITLE_MAX} placeholder={t('rev.titlePlaceholder')} onChange={(e) => setTitle(e.currentTarget.value)} />
      <div>
        <label className={styles.label} htmlFor={`${uid}-text`}>{t('rev.textLabel')}</label>
        <textarea id={`${uid}-text`} className={styles.textarea} value={text} maxLength={REVIEW_TEXT_MAX} placeholder={t('rev.textPlaceholder')} onChange={(e) => setText(e.currentTarget.value)} />
        <p className={`${styles.muted} ${styles.counter}`}>{t('rev.counter', { n: text.length, max: REVIEW_TEXT_MAX })}</p>
      </div>
      <div>
        <label className={styles.label} htmlFor={`${uid}-photos`}>{t('rev.photosLabel', { max: REVIEW_PHOTOS_MAX })}</label>
        {photos.length > 0 && (
          <ul className={styles.thumbs}>
            {photos.map((p) => (
              <li key={p.key} className={styles.thumb}>
                <img src={p.url} alt="" className={styles.photo} />
                <button type="button" className={styles.remove} aria-label={t('rev.removePhoto')} onClick={() => setPhotos((prev) => prev.filter((x) => x.key !== p.key))}>
                  <X size={14} aria-hidden />
                </button>
              </li>
            ))}
          </ul>
        )}
        {photos.length < REVIEW_PHOTOS_MAX && (
          <input id={`${uid}-photos`} className={styles.file} type="file" accept={REVIEW_PHOTO_TYPES.join(',')} multiple disabled={uploading} onChange={(e) => void addFiles(e)} />
        )}
        {uploading && <p className={styles.muted} role="status">{t('rev.uploading')}</p>}
        {photoError && <p className={styles.error} role="alert">{photoError}</p>}
      </div>
      <p className={styles.notice}>{t('rev.publicNote', { name: existing?.reviewerName ?? t('rev.yourName') })}</p>
      {error && <p className={styles.error} role="alert">{error}</p>}
      <div className={styles.formActions}>
        <Button type="submit" loading={save.isPending} disabled={uploading || (existing !== null && !existing.editable)}>
          {existing ? t('rev.saveChanges') : t('rev.submit')}
        </Button>
        {existing && (
          <Button type="button" variant="neutral" loading={remove.isPending} onClick={() => { if (window.confirm(t('rev.deleteConfirm'))) remove.mutate(); }}>
            {t('rev.delete')}
          </Button>
        )}
      </div>
    </form>
  );
}

/**
 * The review sheet of one product: asks the BFF whether the shopper can review it (a delivered order line), then shows
 * the form for a new review, the form to edit their own, or why they cannot review.
 */
export function ReviewSheet({ atin, title, imageUrl, open, onOpenChange }: { atin: string; title: string; imageUrl?: string | null; open: boolean; onOpenChange: (open: boolean) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const mine = useQuery({ ...reviewQueries.mine(api, atin), enabled: open });
  const review = mine.data?.review ?? null;
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t('rev.sheetTitle')} description={t('rev.sheetDescription')} closeLabel={t('rev.close')}>
      <div className={styles.form}>
        <div className={styles.item}>
          {imageUrl ? <img src={imageUrl} alt="" className={styles.itemImg} /> : <span className={styles.itemImg} aria-hidden />}
          <div>
            <strong>{title}</strong>
            <p className={styles.verified}><SealCheck weight="fill" aria-hidden /> {t('rev.verified')}</p>
          </div>
        </div>
        {mine.isPending && <Skeleton height={200} />}
        {mine.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
        {mine.data && !mine.data.eligible && !review && <p className={styles.notice}>{t('rev.notVerified')}</p>}
        {mine.data && (mine.data.eligible || review) && (
          <>
            {review && <p className={styles.muted}>{t('rev.editableUntil', { date: new Date(review.editableUntil).toLocaleDateString(fmt.tag, { month: 'long', day: 'numeric', year: 'numeric' }) })}</p>}
            <ReviewForm key={review?.id ?? 'new'} atin={atin} existing={review} onDone={() => onOpenChange(false)} />
          </>
        )}
      </div>
    </Sheet>
  );
}

/** "Rate this item" (or "Your review" once written): opens the review sheet. Offered for delivered items only. */
export function RateItemButton({ atin, title, imageUrl, size = 'sm', block = false, variant = 'neutral' }: { atin: string; title: string; imageUrl?: string | null; size?: 'sm' | 'md'; block?: boolean; variant?: 'neutral' | 'link' }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant={variant} size={size} block={block} onClick={() => setOpen(true)} aria-label={`${t('rev.rateThisItem')}: ${title}`} data-testid="rate-item">
        {t('rev.rateThisItem')}
      </Button>
      {open && <ReviewSheet atin={atin} title={title} imageUrl={imageUrl} open={open} onOpenChange={setOpen} />}
    </>
  );
}
