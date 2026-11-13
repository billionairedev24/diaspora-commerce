'use client';

import { cx } from '@afrotransact/ui';
import { Plus } from '@phosphor-icons/react/ssr';
import { type DragEvent, type HTMLAttributes, type ReactNode, useRef } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

import { type FormImage, IMAGE_TYPES, MIN_IMAGE_PX } from '../form-model';
import { checkVars, type Verdict, verdictOf, visibleChecks } from './image-rules';

/** The dot on a thumbnail: the verdict as a colour and, for everyone else, as words (the dot itself is hidden from screen readers). */
const DOT: Record<Verdict, string> = { PASS: 'bg-success', WARN: 'bg-warning', HOLD: 'bg-info', REJECT: 'bg-danger' };

/** The short reason under a thumbnail, by the check that decided the verdict (the full text is in the details below the row). */
const SHORT: Record<string, MessageKey> = {
  MAIN_TOO_SMALL: 'sc.pf.photo.short.size', TOO_SMALL: 'sc.pf.photo.short.size', LOW_RES: 'sc.pf.photo.short.size',
  NOT_WHITE: 'sc.pf.photo.short.white',
  FILL_LOW: 'sc.pf.photo.short.fill', FILL_OK_NEAR: 'sc.pf.photo.short.fill',
  BORDER: 'sc.pf.photo.short.content', PLACEHOLDER: 'sc.pf.photo.short.content', MAIN_PROPS: 'sc.pf.photo.short.content',
  PROHIBITED: 'sc.pf.photo.short.rejected', REVIEW_REJECTED: 'sc.pf.photo.short.rejected',
  HELD_TEXT: 'sc.pf.photo.short.held', HELD_MISMATCH: 'sc.pf.photo.short.held', HELD_DUPLICATE: 'sc.pf.photo.short.held', HELD_PROPS: 'sc.pf.photo.short.held', HELD_REVIEW: 'sc.pf.photo.short.held',
  FORMAT: 'sc.pf.photo.short.file', FILE_TOO_BIG: 'sc.pf.photo.short.file', NOT_AN_IMAGE: 'sc.pf.photo.short.file', NOT_VERIFIED: 'sc.pf.photo.short.file', FETCH_FAILED: 'sc.pf.photo.short.file', FETCH_PRIVATE: 'sc.pf.photo.short.file',
  BLUR: 'sc.pf.photo.short.better', ASPECT: 'sc.pf.photo.short.better', LARGER_BETTER: 'sc.pf.photo.short.better'
};

/** One line saying why a photo is not (fully) accepted, or null for a photo that passed or is still uploading. */
export function useShortReason(im: FormImage): string | null {
  const t = useT();
  if (im.status === 'error') return t('sc.pf.images.failed');
  const verdict = verdictOf(im);
  if (!verdict || verdict === 'PASS') return null;
  const code = visibleChecks(im)[0]?.code;
  const key = (code && SHORT[code]) || (verdict === 'HOLD' ? 'sc.pf.photo.short.held' : verdict === 'WARN' ? 'sc.pf.photo.short.better' : 'sc.pf.photo.short.rejected');
  return t(key, { px: MIN_IMAGE_PX });
}

interface ThumbProps {
  image: FormImage;
  /** 0-based position: the first photo is the main one. */
  index: number;
  selected: boolean;
  onSelect: () => void;
  /** Drag and drop of the list item (reordering). */
  itemProps: HTMLAttributes<HTMLLIElement> & { 'data-main'?: string };
  dragState: { dragging: boolean; target: boolean };
}

/**
 * One photo as a small square: the picture, a dot for the verdict (written out for screen readers) and, only when the photo is not
 * fully accepted, one short reason under it. Pressing it opens the details row under the strip (full findings, description, order).
 */
export function PhotoThumb({ image: im, index: i, selected, onSelect, itemProps, dragState }: ThumbProps) {
  const t = useT();
  const verdict = verdictOf(im);
  const reason = useShortReason(im);
  const main = i === 0;
  const size = main ? 'size-20' : 'size-16';
  const label = im.alt || t('sc.pf.images.alt', { n: i + 1 });
  return (
    <li {...itemProps} data-verdict={verdict ?? undefined} data-image-status={im.status}
      className={cx('flex flex-col gap-1', main ? 'w-20' : 'w-16', dragState.dragging && 'opacity-50', itemProps.className)}>
      <button type="button" aria-pressed={selected} aria-label={main ? t('sc.pf.photo.thumbMain', { label }) : t('sc.pf.photo.thumb', { label })} onClick={onSelect}
        className={cx('relative block cursor-grab rounded-sm border bg-surface-muted p-0', size, selected ? 'border-text' : 'border-border', dragState.target && 'border-gold-500')}>
        {im.previewUrl ? <img src={im.previewUrl} alt="" className={cx('block rounded-sm object-cover', size, verdict === 'REJECT' && 'opacity-50')} /> : <span className={cx('block rounded-sm', size)} />}
        {main ? <span className="absolute start-1 top-1 rounded-xs bg-gold-500 px-1 text-2xs font-bold leading-4">{t('sc.pf.images.main')}</span> : null}
        {im.status === 'uploading' ? <span className="absolute inset-0 grid place-items-center rounded-sm bg-surface/70 text-2xs">{t('sc.pf.images.uploading')}</span> : null}
        {verdict ? (
          <span data-testid="image-verdict" className="absolute end-1 bottom-1 inline-flex">
            <span aria-hidden className={cx('size-2.5 rounded-full ring-2 ring-surface', DOT[verdict])} />
            <span className="sr-only">{t(`sc.img.verdict.${verdict}` as MessageKey)}</span>
          </span>
        ) : null}
      </button>
      {reason ? <span className={cx('text-2xs leading-tight', verdict === 'REJECT' || im.status === 'error' ? 'text-danger' : 'text-muted')}>{reason}</span> : null}
    </li>
  );
}

/** The full findings of a photo in plain words, with how to fix them; a rejection is an alert, the rest are polite. */
export function PhotoFindings({ image: im, index: i }: { image: FormImage; index: number }) {
  const t = useT();
  const checks = visibleChecks(im);
  const verdict = verdictOf(im);
  if (!checks.length) return null;
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0" role={verdict === 'REJECT' ? 'alert' : 'status'} aria-label={t('sc.pf.images.findings', { n: i + 1 })}>
      {checks.map((c) => (
        <li key={c.code} className={cx('text-sm', c.level === 'REJECT' && 'text-danger')} data-check={c.code}>
          {t(`sc.img.check.${c.code}` as MessageKey, checkVars(c, im))}
        </li>
      ))}
    </ul>
  );
}

interface AddProps {
  label: string;
  /** The tile is a drop target for files and opens the file picker on press. */
  onFiles: (files: File[]) => void;
  multiple?: boolean;
  /** `main` is the larger empty slot of a listing without a photo yet. */
  size: 'main' | 'extra';
  testId: string;
  describedBy?: string | undefined;
  invalid?: boolean;
  children?: ReactNode;
}

/** An empty slot: a dashed square with a plus. Press it, or drop files on it. There is no big drop zone. */
export function AddPhotoTile({ label, onFiles, multiple, size, testId, describedBy, invalid }: AddProps) {
  const input = useRef<HTMLInputElement>(null);
  const over = useRef<HTMLButtonElement>(null);
  const drop = (e: DragEvent<HTMLButtonElement>) => {
    e.preventDefault();
    over.current?.removeAttribute('data-over');
    if (e.dataTransfer.files.length) onFiles(Array.from(e.dataTransfer.files));
  };
  return (
    <li className="flex-none">
      <button ref={over} type="button" aria-label={label} title={label} aria-describedby={describedBy} onClick={() => input.current?.click()}
        onDragOver={(e) => { e.preventDefault(); over.current?.setAttribute('data-over', ''); }} onDragLeave={() => over.current?.removeAttribute('data-over')} onDrop={drop}
        className={cx('grid place-items-center rounded-sm border border-dashed bg-surface-sunken p-0 text-muted hover:text-text data-[over]:border-gold-500 data-[over]:bg-gold-50', size === 'main' ? 'size-20' : 'size-16', invalid ? 'border-danger' : 'border-border-strong')}>
        <Plus size={size === 'main' ? 24 : 20} aria-hidden />
      </button>
      <input ref={input} type="file" data-testid={testId} accept={IMAGE_TYPES.join(',')} multiple={multiple} tabIndex={-1} aria-hidden className="at-visually-hidden"
        onChange={(e) => { onFiles(Array.from(e.target.files ?? [])); e.target.value = ''; }} />
    </li>
  );
}
