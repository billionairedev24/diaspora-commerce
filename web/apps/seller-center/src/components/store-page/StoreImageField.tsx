'use client';

import { UploadSimple } from '@phosphor-icons/react/ssr';
import { useId, useState } from 'react';

import { IMAGE_TYPES } from '@/components/listings/form-model';
import { sellerApi } from '@/lib/api';
import { maxBytesOf, usePictureRules } from '@/lib/picture-rules';

interface Props {
  label: string;
  help: string;
  value: string | null;
  onChange: (url: string | null) => void;
  /** Smallest accepted size in px (width x height): small pictures turn soft when they fill a hero. */
  minWidth: number;
  minHeight: number;
  /** Where the picture stays in view when the page crops it (percent). Only the hero has one. */
  focal?: { x: number; y: number; onChange: (x: number, y: number) => void };
  /** How the crop looks on a wide screen and on a phone, to show the safe area. */
  aspect: string;
  disabled?: boolean;
  testId?: string;
}

function dimensions(file: File): Promise<{ w: number; h: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve({ w: img.naturalWidth, h: img.naturalHeight });
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('unreadable'));
    };
    img.src = url;
  });
}

/**
 * A storefront picture (banner, story photo): validated here (type, size, resolution), uploaded to AfroTransact media, and for the
 * hero a focal point the shopper's screen keeps in view. The picture itself is never cropped on the server: each screen shows its
 * own crop around the focal point, so a phone and a desktop both show what matters.
 */
export function StoreImageField({ label, help, value, onChange, minWidth, minHeight, focal, aspect, disabled, testId }: Props) {
  const pictures = usePictureRules();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const hint = useId();

  const pick = async (file: File | undefined) => {
    if (!file) return;
    if (!(IMAGE_TYPES as readonly string[]).includes(file.type)) return setError('Use a JPG, PNG, WebP or AVIF image');
    if (file.size > maxBytesOf(pictures)) return setError(`The image must be ${pictures.knobs.maxMegabytes} MB or smaller`);
    setError('');
    setBusy(true);
    try {
      const size = await dimensions(file).catch(() => null);
      if (!size) throw new Error('unreadable');
      if (size.w < minWidth || size.h < minHeight) {
        setError(`That image is ${size.w} x ${size.h}. Use at least ${minWidth} x ${minHeight} so it stays sharp.`);
        return;
      }
      const api = sellerApi();
      const ticket = await api.requestUpload({ purpose: 'storefront', fileName: file.name, contentType: file.type as (typeof IMAGE_TYPES)[number], size: file.size });
      const put = await fetch(ticket.url, { method: ticket.method, headers: ticket.headers, body: file });
      if (!put.ok) throw new Error('upload failed');
      const asset = await api.completeUpload(ticket.assetId);
      if (!asset.url) throw new Error('no url');
      onChange(asset.url);
    } catch {
      setError('We could not upload that image. Try again.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-semibold">{label}</span>
      {value ? (
        <div className="flex flex-col gap-2">
          <button
            type="button"
            className="relative block w-full max-w-[28rem] overflow-hidden rounded-md border-0 bg-surface-muted p-0"
            style={{ aspectRatio: aspect, cursor: focal && !disabled ? 'crosshair' : 'default' }}
            disabled={!focal || disabled}
            aria-label={focal ? 'Set the important part of the picture' : 'Picture'}
            onClick={(e) => {
              // A pointer click carries a position; a keyboard activation does not (the Top, Centre and Bottom buttons are the keyboard route).
              if (!focal || e.detail === 0) return;
              const r = e.currentTarget.getBoundingClientRect();
              focal.onChange(Math.round(((e.clientX - r.left) / r.width) * 100), Math.round(((e.clientY - r.top) / r.height) * 100));
            }}
            data-testid={testId ? `${testId}-frame` : undefined}
          >
            <img src={value} alt="" className="size-full object-cover" style={focal ? { objectPosition: `${focal.x}% ${focal.y}%` } : undefined} />
            {focal ? (
              <span aria-hidden className="pointer-events-none absolute size-5 -translate-x-1/2 -translate-y-1/2 rounded-pill border-2 border-white shadow-md" style={{ insetInlineStart: `${focal.x}%`, top: `${focal.y}%`, boxShadow: '0 0 0 2px rgb(0 0 0 / .45)' }} />
            ) : null}
          </button>
          {focal ? (
            <p id={hint} className="m-0 text-xs text-muted">
              Click the picture where the important part is. Every screen keeps that point in view when it crops.
            </p>
          ) : null}
          {focal && !disabled ? (
            <div className="flex flex-wrap gap-2 text-xs">
              {(
                [
                  ['Top', 50, 20],
                  ['Centre', 50, 50],
                  ['Bottom', 50, 80]
                ] as const
              ).map(([name, x, y]) => (
                <button key={name} type="button" className="min-h-9 cursor-pointer rounded-sm border border-border-strong bg-surface px-3 font-semibold" onClick={() => focal.onChange(x, y)}>
                  {name}
                </button>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
      <div className="flex items-center gap-3">
        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-sm border border-border-strong bg-surface px-3 text-sm font-semibold sm:min-h-9">
          <UploadSimple size={16} aria-hidden />
          {busy ? 'Uploading…' : value ? 'Replace picture' : 'Upload picture'}
          <input type="file" data-testid={testId} accept={IMAGE_TYPES.join(',')} className="at-visually-hidden" disabled={disabled || busy} onChange={(e) => void pick(e.target.files?.[0])} aria-describedby={focal ? hint : undefined} />
        </label>
        {value && !disabled ? (
          <button type="button" className="cursor-pointer border-0 bg-transparent text-sm text-muted underline" onClick={() => onChange(null)}>
            Remove
          </button>
        ) : null}
      </div>
      <span className="text-xs text-muted">{help}</span>
      {error ? (
        <span role="alert" className="text-xs text-danger">
          {error}
        </span>
      ) : null}
    </div>
  );
}
