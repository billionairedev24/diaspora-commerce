'use client';

import {
  CreateHelpRequestSchema,
  definitiveFailure,
  HELP_DESCRIPTION_MAX,
  HELP_DESCRIPTION_MIN,
  HELP_EVIDENCE_MAX_BYTES,
  HELP_EVIDENCE_MAX_FILES,
  HELP_EVIDENCE_TYPES,
  HELP_REQUEST_TYPES,
  type HelpRequest,
  helpRequestKeys,
  type HelpRequestType,
  isApiError,
  uploadHelpEvidence
} from '@afrotransact/api-client';
import { Button, buttonClassName, CheckCircle, Field, FlatPage, FormActions, Message, Skeleton, TextLinkButton, textLinkClassName, UploadSimple, WarningCircle, X } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type ChangeEvent, type DragEvent, type FormEvent, useEffect, useId, useRef, useState } from 'react';

import { FormErrors } from '@/components/common/FormErrors';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountHeader } from './AccountHeader';
import help from './HelpRequests.module.css';

interface Photo {
  id: string;
  name: string;
  size: number;
  state: 'uploading' | 'done' | 'error';
  progress: number;
  assetId?: string;
  preview: string | null;
}

type FormErrorKey = 'shipmentId' | 'type' | 'description' | 'photos';
const FIELD_IDS = { shipmentId: 'hr-package', type: 'hr-type', description: 'hr-description', photos: 'hr-photos' } as const;

const ALLOWED = HELP_EVIDENCE_TYPES as readonly string[];

/** The media type to upload with: phones and some desktop browsers leave HEIC photos untyped. */
function evidenceType(file: File): string | null {
  if (ALLOWED.includes(file.type)) return file.type;
  if (!file.type && /\.(heic|heif)$/i.test(file.name)) return 'image/heic';
  return null;
}

function newId(): string {
  return `p${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Report a problem with an order: pick the package and what went wrong, describe it and add photos (drag and drop or
 * choose; each goes straight to storage with its own progress), then send. There is no automatic return or refund:
 * staff decide, and the customer follows the outcome on the help requests page.
 */
export function HelpNewView({ orderNumber }: { orderNumber: string }) {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const order = useQuery(shopperQueries.order(api, orderNumber));
  const shipments = order.data?.shipments ?? [];

  const [pickedPackage, setPickedPackage] = useState<string | null>(null);
  const [type, setType] = useState<HelpRequestType | ''>('');
  const [description, setDescription] = useState('');
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [photoNotes, setPhotoNotes] = useState<string[]>([]);
  const [errors, setErrors] = useState<Partial<Record<FormErrorKey, string>>>({});
  const [sendError, setSendError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [done, setDone] = useState<HelpRequest | null>(null);
  const [announce, setAnnounce] = useState('');
  const [dragging, setDragging] = useState(false);
  const [submitCount, setSubmitCount] = useState(0);
  const fileInput = useRef<HTMLInputElement>(null);
  const doneHeading = useRef<HTMLHeadingElement>(null);
  const previews = useRef(new Set<string>());
  // One key per submission: a double click or a retry after a dropped connection replays the same request.
  const key = useRef(`help-${crypto.randomUUID()}`);
  const dropHint = useId();

  // A single package needs no choice; several start on "the whole order".
  const packageId = pickedPackage ?? (shipments.length === 1 ? (shipments[0]?.shipmentId ?? '') : '');

  useEffect(() => {
    const urls = previews.current;
    return () => urls.forEach((u) => URL.revokeObjectURL(u));
  }, []);
  useEffect(() => {
    if (done) doneHeading.current?.focus();
  }, [done]);

  const patch = (id: string, change: Partial<Photo>) => setPhotos((list) => list.map((p) => (p.id === id ? { ...p, ...change } : p)));

  const upload = async (id: string, file: File, contentType: string) => {
    const typed = file.type === contentType ? file : new File([file], file.name, { type: contentType });
    try {
      const asset = await uploadHelpEvidence(api, typed, { onProgress: (f) => patch(id, { progress: Math.round(f * 100) }) });
      patch(id, { state: 'done', progress: 100, assetId: asset.assetId });
      setAnnounce(t('hr.new.added', { name: file.name }));
    } catch {
      patch(id, { state: 'error' });
      setAnnounce(t('hr.new.failed', { name: file.name }));
    }
  };

  const addFiles = (incoming: File[]) => {
    const notes: string[] = [];
    let room = HELP_EVIDENCE_MAX_FILES - photos.length;
    const accepted: Array<{ photo: Photo; file: File; contentType: string }> = [];
    for (const file of incoming) {
      const contentType = evidenceType(file);
      if (!contentType) notes.push(t('hr.new.badType', { name: file.name }));
      else if (file.size > HELP_EVIDENCE_MAX_BYTES) notes.push(t('hr.new.tooBig', { name: file.name }));
      else if (room <= 0) notes.push(t('hr.new.tooMany', { max: HELP_EVIDENCE_MAX_FILES }));
      else {
        room -= 1;
        let preview: string | null = null;
        if (contentType !== 'image/heic' && typeof URL.createObjectURL === 'function') {
          preview = URL.createObjectURL(file);
          previews.current.add(preview);
        }
        accepted.push({ photo: { id: newId(), name: file.name, size: file.size, state: 'uploading', progress: 0, preview }, file, contentType });
      }
    }
    setPhotoNotes([...new Set(notes)]);
    if (!accepted.length) return;
    setErrors((e) => ({ ...e, photos: undefined }));
    setPhotos((list) => [...list, ...accepted.map((a) => a.photo)]);
    accepted.forEach((a) => void upload(a.photo.id, a.file, a.contentType));
  };

  const remove = (p: Photo) => {
    if (p.preview) {
      URL.revokeObjectURL(p.preview);
      previews.current.delete(p.preview);
    }
    setPhotos((list) => list.filter((x) => x.id !== p.id));
    setAnnounce(t('hr.new.removed', { name: p.name }));
  };

  const retry = (p: Photo) => {
    // The failed ticket is abandoned; the file is picked again to start a fresh upload.
    remove(p);
    fileInput.current?.click();
  };

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    addFiles([...(e.target.files ?? [])]);
    e.target.value = '';
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    addFiles([...e.dataTransfer.files]);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSendError(null);
    setSubmitCount((n) => n + 1);
    const found: Partial<Record<FormErrorKey, string>> = {};
    if (!type) found.type = t('hr.new.typeRequired');
    const text = description.trim();
    if (text.length < HELP_DESCRIPTION_MIN) found.description = t('hr.new.descriptionShort', { min: HELP_DESCRIPTION_MIN });
    else if (text.length > HELP_DESCRIPTION_MAX) found.description = t('hr.new.descriptionLong', { max: HELP_DESCRIPTION_MAX });
    if (photos.some((p) => p.state === 'uploading')) found.photos = t('hr.new.waitUploads');
    else if (photos.some((p) => p.state === 'error')) found.photos = t('hr.new.failed', { name: photos.find((p) => p.state === 'error')?.name ?? '' });
    setErrors(found);
    if (Object.keys(found).length) {
      const first = (['type', 'description', 'photos'] as const).find((k) => found[k]);
      if (first) document.getElementById(FIELD_IDS[first])?.focus();
      return;
    }
    const parsed = CreateHelpRequestSchema.safeParse({
      orderNumber,
      shipmentId: packageId || undefined,
      type,
      description: text,
      evidenceAssetIds: photos.flatMap((p) => (p.assetId ? [p.assetId] : []))
    });
    if (!parsed.success) {
      setSendError(t('hr.new.sendFailed'));
      return;
    }
    setSending(true);
    try {
      const created = await api.createHelpRequest(parsed.data, key.current);
      await qc.invalidateQueries({ queryKey: helpRequestKeys.all });
      setDone(created);
    } catch (err) {
      if (definitiveFailure(err)) key.current = `help-${crypto.randomUUID()}`;
      if (isApiError(err) && err.status === 404) setSendError(t('hr.new.notFound'));
      else if (isApiError(err) && Object.keys(err.fieldErrors).length) {
        setErrors({ description: err.fieldErrors.description, shipmentId: err.fieldErrors.shipmentId, photos: err.fieldErrors.evidenceAssetIds });
        setSendError(Object.values(err.fieldErrors)[0] ?? t('hr.new.sendFailed'));
      } else setSendError(isApiError(err) && err.status < 500 ? err.message : t('hr.new.sendFailed'));
    } finally {
      setSending(false);
    }
  };

  if (done) {
    return (
      <FlatPage>
        <AccountHeader title={t('hr.title')} crumb={t('hr.new.crumb')} via={[{ label: t('hr.title'), href: '/account/help' }]} />
        <section role="status" data-testid="help-request-sent" aria-labelledby="hr-done-title" className={help.form}>
          <h2 id="hr-done-title" ref={doneHeading} tabIndex={-1} className={help.doneTitle}>
            <CheckCircle weight="fill" aria-hidden /> {t('hr.done.title')}
          </h2>
          <Message tone="muted">{t('hr.done.body', { reference: done.reference })}</Message>
          <FormActions>
            <Link href="/account/help" className={buttonClassName({ size: 'md' })}>
              {t('hr.done.view')}
            </Link>
          </FormActions>
        </section>
      </FlatPage>
    );
  }

  const notFound = order.isError && isApiError(order.error) && order.error.status === 404;
  const uploading = photos.some((p) => p.state === 'uploading');
  return (
    <FlatPage>
      <AccountHeader title={t('hr.new.title', { number: orderNumber })} crumb={t('hr.new.crumb')} via={[{ label: t('hr.title'), href: '/account/help' }]} />
      <p className={help.policy}>{t('hr.policy')}</p>
      {order.isPending && <Skeleton height={160} />}
      {order.isError && <Message tone="error">{notFound ? t('hr.new.notFound') : t('acct.loadError')}</Message>}
      {order.isSuccess && (
        <form className={help.form} onSubmit={(e) => void submit(e)} noValidate aria-label={t('hr.new.title', { number: orderNumber })}>
          <FormErrors errors={errors} ids={FIELD_IDS} submitCount={submitCount} />
          {shipments.length > 1 && (
            <fieldset className={help.choices} id={FIELD_IDS.shipmentId}>
              <legend className={help.legend}>{t('hr.new.package')}</legend>
              {[...shipments.map((s) => ({ id: s.shipmentId, text: t('hr.new.packageOption', { items: s.items, seller: s.sellerName }) })), { id: '', text: t('hr.new.wholeOrder') }].map(({ id, text }) => (
                <label key={id || 'order'} className={`${help.choice} ${packageId === id ? help.choiceOn : ''}`}>
                  <input type="radio" name="package" checked={packageId === id} onChange={() => setPickedPackage(id)} />
                  <span className={help.choiceText}>{text}</span>
                </label>
              ))}
            </fieldset>
          )}
          {shipments.length === 1 && <Message tone="muted">{t('hr.new.packageOption', { items: shipments[0]?.items ?? '', seller: shipments[0]?.sellerName ?? '' })}</Message>}
          <fieldset className={help.choices} id={FIELD_IDS.type} tabIndex={-1} aria-describedby={errors.type ? 'hr-type-err' : undefined}>
            <legend className={help.legend}>{t('hr.new.type')}</legend>
            {HELP_REQUEST_TYPES.map((v) => (
              <label key={v} className={`${help.choice} ${type === v ? help.choiceOn : ''}`}>
                <input
                  type="radio"
                  name="type"
                  checked={type === v}
                  onChange={() => {
                    setType(v);
                    setErrors((x) => ({ ...x, type: undefined }));
                  }}
                />
                <span className={help.choiceText}>{t(`hr.type.${v}` as MessageKey)}</span>
              </label>
            ))}
            {errors.type && (
              <p id="hr-type-err" className={help.fieldError}>
                {errors.type}
              </p>
            )}
          </fieldset>
          <Field label={t('hr.new.describe')} id={FIELD_IDS.description} error={errors.description} help={t('hr.new.describeHelp', { min: HELP_DESCRIPTION_MIN })}>
            {(a11y) => (
              <textarea
                {...a11y}
                className={help.textarea}
                value={description}
                maxLength={HELP_DESCRIPTION_MAX + 200}
                onChange={(e) => {
                  setDescription(e.target.value);
                  setErrors((x) => ({ ...x, description: undefined }));
                }}
              />
            )}
          </Field>
          <div className={help.choices} role="group" aria-labelledby="hr-photos-label">
            <strong id="hr-photos-label" className={help.legend}>
              {t('hr.new.photos')}
            </strong>
            <p id={dropHint} className={help.fieldHelp}>
              {t('hr.new.photosHelp', { max: HELP_EVIDENCE_MAX_FILES })}
            </p>
            <div
              className={`${help.drop} ${dragging ? help.dropActive : ''}`}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={onDrop}
              data-testid="photo-drop"
            >
              <UploadSimple className={help.dropIcon} aria-hidden />
              <span>
                {dragging ? t('hr.new.dropHere') : t('hr.new.drop')}{' '}
                <button type="button" id={FIELD_IDS.photos} className={help.pick} onClick={() => fileInput.current?.click()} aria-describedby={dropHint} disabled={photos.length >= HELP_EVIDENCE_MAX_FILES}>
                  {t('hr.new.choose')}
                </button>
              </span>
              <input
                ref={fileInput}
                type="file"
                className={help.fileInput}
                accept={`${HELP_EVIDENCE_TYPES.join(',')},.heic,.heif`}
                multiple
                tabIndex={-1}
                aria-hidden="true"
                onChange={onPick}
                data-testid="photo-input"
              />
            </div>
            {errors.photos && <p className={help.fieldError}>{errors.photos}</p>}
            {photoNotes.map((n) => (
              <p key={n} className={help.fieldError} role="alert">
                {n}
              </p>
            ))}
            {photos.length > 0 && (
              <ul className={help.files} aria-label={t('hr.new.photos')}>
                {photos.map((p) => (
                  <li key={p.id} className={`${help.file} ${p.state === 'error' ? help.fileError : ''}`} data-testid="photo">
                    {p.preview ? (
                      <img src={p.preview} alt="" className={help.thumb} />
                    ) : (
                      <span className={help.thumb} aria-hidden>
                        <UploadSimple />
                      </span>
                    )}
                    <span className={help.fileBody}>
                      <span className={help.fileName}>{p.name}</span>
                      {p.state === 'uploading' && (
                        <>
                          <progress className={help.progress} max={100} value={p.progress} aria-label={t('hr.new.percent', { name: p.name, percent: p.progress })} />
                          <span className={help.fileMeta}>{t('hr.new.uploading', { name: p.name })}</span>
                        </>
                      )}
                      {p.state === 'done' && <span className={help.fileMeta}>{t('hr.new.added', { name: p.name })}</span>}
                      {p.state === 'error' && (
                        <span className={help.fileMetaError}>
                          <WarningCircle aria-hidden /> {t('hr.new.failed', { name: p.name })}
                        </span>
                      )}
                    </span>
                    <span className={help.fileActions}>
                      {p.state === 'error' && <TextLinkButton onClick={() => retry(p)}>{t('hr.new.retry')}</TextLinkButton>}
                      <button type="button" className={help.iconButton} onClick={() => remove(p)} aria-label={t('hr.new.remove', { name: p.name })}>
                        <X aria-hidden />
                      </button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <p className={help.srOnly} aria-live="polite" role="status">
              {announce}
            </p>
          </div>
          {sendError && <Message tone="error">{sendError}</Message>}
          <FormActions>
            <Button type="submit" loading={sending} disabled={uploading}>
              {sending ? t('hr.new.sending') : t('hr.new.submit')}
            </Button>
            <Link href="/account/help" className={textLinkClassName}>
              {t('common.cancel')}
            </Link>
          </FormActions>
        </form>
      )}
    </FlatPage>
  );
}
