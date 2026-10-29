'use client';

import { Button, cx } from '@afrotransact/ui';
import { CaretUp, CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode, useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';

import { type Problem, type QualityItem } from '../form-model';
import { QualityBar, QualityLists } from './ReadinessPanel';

interface Props {
  problems: [string, Problem][];
  tips: QualityItem[];
  percent: number;
  preview: ReactNode;
  /** Autosave state line ("Draft saved 10:42"). */
  saveState: ReactNode;
  primaryLabel: string;
  onPrimary: () => void;
  /** An edit that was not changed cannot be saved. */
  primaryDisabled: boolean;
  onSaveDraft: () => void;
  saveDraftDisabled: boolean;
  busy: boolean;
  savingDraft: boolean;
  onGo: (id: string) => void;
}

/** Sticky bottom bar: the draft-saved time, Save draft and the primary action. Below the wide layout it also opens the
    quality list and the preview upwards (the right-hand panel is not on screen there). */
export function ActionBar({ problems, tips, percent, preview, saveState, primaryLabel, onPrimary, primaryDisabled, onSaveDraft, saveDraftDisabled, busy, savingDraft, onGo }: Props) {
  const t = useT();
  const [open, setOpen] = useState(false);
  /** The "Ask Lola" button is fixed in the same corner: publish this bar's height so it sits above the bar and never covers Submit. */
  const bar = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = bar.current;
    if (!el) return;
    const root = document.documentElement;
    const publish = () => root.style.setProperty('--at-bottom-bar-h', `${el.offsetHeight}px`);
    publish();
    if (typeof ResizeObserver === 'undefined') return () => root.style.removeProperty('--at-bottom-bar-h');
    const ro = new ResizeObserver(publish);
    ro.observe(el);
    return () => {
      ro.disconnect();
      root.style.removeProperty('--at-bottom-bar-h');
    };
  }, []);
  const ready = problems.length === 0;
  const heading = ready ? t('sc.pf.ready.ready') : t('sc.pf.ready.missing', { count: problems.length });
  return (
    <div ref={bar} role="region" aria-label={t('sc.pf.bar.label')}
      className="sticky bottom-0 z-30 -mx-4 border-t border-border bg-surface px-4 pt-2 pb-[calc(0.5rem+env(safe-area-inset-bottom,0px))] shadow-lg sm:-mx-8 sm:px-8">
      {open ? (
        <div id="pf-quality-sheet" className="mb-2 flex max-h-[55vh] flex-col gap-3 overflow-y-auto xl:hidden">
          <QualityBar percent={percent} />
          <QualityLists problems={problems} tips={tips} onGo={(id) => { setOpen(false); onGo(id); }} />
          <div className="max-w-48">{preview}</div>
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <button type="button" aria-expanded={open} aria-controls="pf-quality-sheet" onClick={() => setOpen(!open)}
          className="flex min-h-11 min-w-0 items-center gap-1.5 border-0 bg-transparent p-0 text-left text-sm font-semibold text-text xl:hidden">
          {ready ? <CheckCircle size={20} weight="fill" aria-hidden className="flex-none text-success" /> : <WarningCircle size={20} weight="fill" aria-hidden className="flex-none text-warning" />}
          <span className="truncate" data-testid="readiness-mobile">{heading}</span>
          <span className="text-xs font-normal text-muted">{t('sc.pf.q.percent', { percent })}</span>
          <CaretUp size={14} aria-hidden className={cx('flex-none transition-transform', !open && 'rotate-180')} />
        </button>
        <p className="m-0 min-w-0 flex-1 text-xs text-muted" aria-live="polite" data-testid="save-state">{saveState}</p>
        <div className="ml-auto flex items-center gap-2">
          <Button variant="neutral" loading={savingDraft} disabled={saveDraftDisabled} onClick={onSaveDraft} aria-label={t('sc.pf.actions.saveDraft')}>
            <span className="hidden sm:inline">{t('sc.pf.actions.saveDraft')}</span><span className="sm:hidden">{t('sc.pf.actions.saveDraftShort')}</span>
          </Button>
          <Button loading={busy} disabled={primaryDisabled} onClick={onPrimary} data-testid="pf-primary">{primaryLabel}</Button>
        </div>
      </div>
    </div>
  );
}
