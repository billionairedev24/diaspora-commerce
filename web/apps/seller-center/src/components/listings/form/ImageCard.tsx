'use client';

import { cx, fieldStyles } from '@afrotransact/ui';
import { ArrowLeft, ArrowRight, CheckCircle, Hourglass, Star, Trash, Warning, XCircle } from '@phosphor-icons/react/ssr';
import { type HTMLAttributes } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

import { type FormImage } from '../form-model';
import { checkVars, type Verdict, verdictOf, visibleChecks } from './image-rules';

const iconButton = 'grid size-9 place-items-center rounded-xs border-0 bg-surface-muted disabled:opacity-40 sm:size-8';

const VERDICT_ICON = { PASS: CheckCircle, WARN: Warning, HOLD: Hourglass, REJECT: XCircle } as const;
const VERDICT_TONE: Record<Verdict, string> = { PASS: 'text-success', WARN: 'text-warning', HOLD: 'text-info', REJECT: 'text-danger' };

interface Props {
  image: FormImage;
  /** 0-based position: the first image is the main one. */
  index: number;
  count: number;
  onAlt: (alt: string) => void;
  onMakeMain: () => void;
  onMove: (to: number) => void;
  onRemove: () => void;
  /** Drag and drop of the list item (reordering). */
  itemProps: HTMLAttributes<HTMLLIElement> & { 'data-main'?: string };
  dragState: { dragging: boolean; target: boolean };
}

/**
 * One image of the product form as a flat row: the picture, the verdict of the image rules in words and an icon (never colour alone),
 * every finding in plain words with how to fix it, and the controls. A rejected image says exactly why; a warning still lets the
 * listing go; one we are checking says so. Findings are announced (a rejection as an alert, the rest politely).
 */
export function ImageCard({ image: im, index: i, count, onAlt, onMakeMain, onMove, onRemove, itemProps, dragState }: Props) {
  const t = useT();
  const verdict = verdictOf(im);
  const checks = visibleChecks(im);
  const Icon = verdict ? VERDICT_ICON[verdict] : null;
  const rejected = verdict === 'REJECT';
  const status = im.status === 'uploading' ? t('sc.pf.images.uploading') : im.status === 'error' ? t('sc.pf.images.failed') : im.width && im.height ? `${im.width}×${im.height}` : '';
  return (
    <li
      {...itemProps}
      data-verdict={verdict ?? undefined}
      data-image-status={im.status}
      className={cx('flex min-w-0 flex-col gap-3 border-b border-border py-4 sm:flex-row', dragState.dragging && 'opacity-50', dragState.target && 'bg-gold-50', itemProps.className)}
    >
      <div className="relative shrink-0 self-start">
        <img src={im.previewUrl} alt={im.alt || t('sc.pf.images.alt', { n: i + 1 })} className={cx('size-24 cursor-grab rounded-sm bg-surface-muted object-cover sm:size-28', rejected && 'opacity-60')} />
        {i === 0 ? <span className="absolute start-1.5 top-1.5 rounded-xs bg-gold-500 px-1.5 py-0.5 text-2xs font-bold">{t('sc.pf.images.main')}</span> : null}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {verdict && Icon ? (
            <span className={cx('inline-flex items-center gap-1.5 text-sm font-semibold', VERDICT_TONE[verdict])} data-testid="image-verdict">
              <Icon size={18} weight="fill" aria-hidden />
              {t(`sc.img.verdict.${verdict}` as MessageKey)}
            </span>
          ) : null}
          <span className={cx('text-xs', im.status === 'error' ? 'text-danger' : 'text-muted')} role={im.status === 'error' ? 'alert' : undefined}>{status}</span>
        </div>
        {checks.length ? (
          <ul className="m-0 flex list-none flex-col gap-1 p-0" role={rejected ? 'alert' : 'status'} aria-label={t('sc.pf.images.findings', { n: i + 1 })}>
            {checks.map((c) => (
              <li key={c.code} className={cx('text-sm', c.level === 'REJECT' && 'text-danger')} data-check={c.code}>
                {t(`sc.img.check.${c.code}` as MessageKey, checkVars(c, im))}
              </li>
            ))}
          </ul>
        ) : null}
        {verdict === 'WARN' ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.warnNotice')}</p> : null}
        {verdict === 'HOLD' && i === 0 ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.holdNotice')}</p> : null}
        {i === 0 && im.slot === 'OTHER' && verdict !== 'REJECT' ? <p className="m-0 text-xs text-muted">{t('sc.pf.images.promoteNote')}</p> : null}
        <input
          type="text"
          maxLength={200}
          value={im.alt}
          placeholder={t('sc.pf.images.altPlaceholder')}
          aria-label={t('sc.pf.images.altLabel', { n: i + 1 })}
          onChange={(e) => onAlt(e.target.value)}
          className={`${fieldStyles.control} h-9 max-w-md text-sm`}
        />
        <span className="flex items-center gap-1">
          {i > 0 ? (
            <button type="button" className={iconButton} aria-label={t('sc.pf.images.makeMain', { n: i + 1 })} title={t('sc.pf.images.setMain')} onClick={onMakeMain}>
              <Star size={14} aria-hidden />
            </button>
          ) : null}
          <button type="button" disabled={i === 0} className={iconButton} aria-label={t('sc.pf.images.left', { n: i + 1 })} onClick={() => onMove(i - 1)}>
            <ArrowLeft size={14} className="rtl:-scale-x-100" aria-hidden />
          </button>
          <button type="button" disabled={i === count - 1} className={iconButton} aria-label={t('sc.pf.images.right', { n: i + 1 })} onClick={() => onMove(i + 1)}>
            <ArrowRight size={14} className="rtl:-scale-x-100" aria-hidden />
          </button>
          <button type="button" className={cx(iconButton, 'text-danger')} aria-label={t('sc.pf.images.remove', { n: i + 1 })} onClick={onRemove}>
            <Trash size={14} aria-hidden />
          </button>
        </span>
      </div>
    </li>
  );
}
