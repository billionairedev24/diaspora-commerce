'use client';

import { UploadSimple } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { IMAGE_TYPES } from '@/components/listings/form-model';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { maxBytesOf, usePictureRules } from '@/lib/picture-rules';

interface Props {
  /** Address of the uploaded logo, or empty. */
  value: string;
  onChange: (url: string) => void;
  disabled?: boolean;
  /** The service refused the address (or it is not one): shown here, beside the logo, with a way to carry on without it. */
  error?: string | null;
  id?: string;
}

/** Business logo: pick an image, it is signed-uploaded to public media and its URL goes on the business page. */
export function LogoField({ value, onChange, disabled, error: refused, id }: Props) {
  const t = useT();
  const pictures = usePictureRules();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const pick = async (file: File | undefined) => {
    if (!file) return;
    if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) return setError(t('sc.logo.errType'));
    if (file.size > maxBytesOf(pictures)) return setError(t('sc.logo.errSize', { mb: pictures.knobs.maxMegabytes }));
    setError('');
    setBusy(true);
    try {
      const api = sellerApi();
      const ticket = await api.requestUpload({ purpose: 'storefront', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size });
      const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
      if (!put.ok) throw new Error('upload failed');
      const asset = await api.completeUpload(ticket.assetId);
      if (!asset.url) throw new Error('no url');
      onChange(asset.url);
    } catch {
      setError(t('sc.logo.errUpload'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div id={id} tabIndex={id ? -1 : undefined} className="flex flex-col gap-1.5 outline-none">
      <span className="text-sm font-semibold">
        {t('sc.logo.label')}
        <span className="font-normal text-muted"> {t('sc.logo.optional')}</span>
      </span>
      <div className="flex items-center gap-3">
        {value ? <img src={value} alt={t('sc.logo.alt')} width={64} height={64} className="size-16 rounded-sm bg-surface object-contain" /> : null}
        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-sm border border-border-strong bg-surface px-3 text-sm font-semibold sm:min-h-9">
          <UploadSimple size={16} aria-hidden />
          {busy ? t('sc.logo.uploading') : value ? t('sc.logo.replace') : t('sc.logo.upload')}
          <input type="file" data-testid="logo-input" accept={IMAGE_TYPES.join(',')} className="at-visually-hidden" disabled={disabled || busy} onChange={(e) => void pick(e.target.files?.[0])} />
        </label>
        {value && !disabled ? (
          <button type="button" className="min-h-11 cursor-pointer border-0 bg-transparent text-sm text-muted underline sm:min-h-0" onClick={() => onChange('')}>
            {t('sc.logo.remove')}
          </button>
        ) : null}
      </div>
      <span className="text-xs text-muted">{t('sc.logo.help')}</span>
      {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
      {refused && !error ? (
        <span role="alert" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-danger">
          {t('sc.logo.errRejected')}
          {!disabled ? (
            <button type="button" className="min-h-11 cursor-pointer border-0 bg-transparent p-0 text-xs font-semibold text-text underline sm:min-h-0" onClick={() => onChange('')}>
              {t('sc.logo.continueWithout')}
            </button>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}
