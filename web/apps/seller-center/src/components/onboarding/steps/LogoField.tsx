'use client';

import { UploadSimple } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { IMAGE_TYPES, MAX_IMAGE_BYTES } from '@/components/listings/form-model';
import { sellerApi } from '@/lib/api';

import { Optional } from '../WizardKit';

interface Props {
  /** https URL of the uploaded logo, or empty. */
  value: string;
  onChange: (url: string) => void;
  disabled?: boolean;
}

/** Business logo: pick an image, it is signed-uploaded to public media and its URL goes on the business page. */
export function LogoField({ value, onChange, disabled }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const pick = async (file: File | undefined) => {
    if (!file) return;
    if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) return setError('Use a JPG, PNG or WebP image');
    if (file.size > MAX_IMAGE_BYTES) return setError('The logo must be 8 MB or smaller');
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
      setError('We could not upload that logo. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-sm font-semibold">
        Logo
        <Optional />
      </span>
      <div className="flex items-center gap-3">
        {value ? <img src={value} alt="Your logo" width={64} height={64} className="size-16 rounded-sm bg-surface object-contain" /> : null}
        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-sm border border-border-strong bg-surface px-3 text-sm font-semibold sm:min-h-9">
          <UploadSimple size={16} aria-hidden />
          {busy ? 'Uploading…' : value ? 'Replace logo' : 'Upload logo'}
          <input type="file" data-testid="logo-input" accept={IMAGE_TYPES.join(',')} className="at-visually-hidden" disabled={disabled || busy} onChange={(e) => void pick(e.target.files?.[0])} />
        </label>
        {value && !disabled ? (
          <button type="button" className="cursor-pointer border-0 bg-transparent text-sm text-muted underline" onClick={() => onChange('')}>
            Remove
          </button>
        ) : null}
      </div>
      <span className="text-xs text-muted">Shown on your public business page. Square, at least 256 px, JPG, PNG or WebP up to 8 MB.</span>
      {error ? <span role="alert" className="text-xs text-danger">{error}</span> : null}
    </div>
  );
}
